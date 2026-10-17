import { Duration } from 'aws-cdk-lib'
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch'
import * as actions from 'aws-cdk-lib/aws-cloudwatch-actions'
import type * as ecs from 'aws-cdk-lib/aws-ecs'
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2'
import * as logs from 'aws-cdk-lib/aws-logs'
import type * as rds from 'aws-cdk-lib/aws-rds'
import * as sns from 'aws-cdk-lib/aws-sns'
import type * as sqs from 'aws-cdk-lib/aws-sqs'
import { Construct } from 'constructs'

export type MonitoringProps = {
  environmentName: string
  loadBalancer: elbv2.ApplicationLoadBalancer
  apiTargetGroup: elbv2.ApplicationTargetGroup
  apiService: ecs.FargateService
  workerService: ecs.FargateService
  apiLogs: logs.LogGroup
  workerLogs: logs.LogGroup
  jobQueue: sqs.Queue
  jobDeadLetterQueue: sqs.Queue
  database: rds.DatabaseInstance
}

/**
 * Log-based metrics and the alarms that matter (D18). Every alarm notifies the topic `photo-space-<env>-alarms`
 * when it fires and when it clears. Subscribe to the topic to get them (docs/operations.md).
 *
 * The backend logs one JSON object per line (the `aws` profile), so the metric filters match its fields:
 * `log.level`, `log.logger`, and `message`. They count events only and copy no log content.
 */
export class Monitoring extends Construct {
  readonly topic: sns.Topic
  readonly alarms: cloudwatch.Alarm[] = []

  constructor(scope: Construct, id: string, props: MonitoringProps) {
    super(scope, id)
    const namespace = `PhotoSpace/${props.environmentName}`
    this.topic = new sns.Topic(this, 'Alarms', {
      topicName: `photo-space-${props.environmentName}-alarms`,
      enforceSSL: true,
    })

    const logMetric = (group: logs.LogGroup, metricName: string, pattern: string) => {
      new logs.MetricFilter(this, metricName, {
        logGroup: group,
        metricNamespace: namespace,
        metricName,
        filterPattern: logs.FilterPattern.literal(pattern),
        metricValue: '1',
      })
      return new cloudwatch.Metric({ namespace, metricName, statistic: 'Sum', period: Duration.minutes(5) })
    }
    const errors = '{ $.log.level = "ERROR" }'
    const jobRunner = '$.log.logger = "com.photospace.jobs.JobRunner"'

    const alarm = (
      name: string,
      description: string,
      metric: cloudwatch.IMetric,
      threshold: number,
      options: Partial<cloudwatch.CreateAlarmOptions> = {},
    ) => {
      const created = new cloudwatch.Alarm(this, name, {
        metric,
        alarmName: `photo-space-${props.environmentName}-${name}`,
        alarmDescription: description,
        threshold,
        evaluationPeriods: 1,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        ...options,
      })
      created.addAlarmAction(new actions.SnsAction(this.topic))
      created.addOkAction(new actions.SnsAction(this.topic))
      this.alarms.push(created)
      return created
    }
    const fiveMinutes = { period: Duration.minutes(5) }

    // API failures.
    alarm(
      'api-5xx',
      'The API answered 5 or more requests with a 5xx status within 5 minutes. Check the API logs by correlation ID.',
      props.loadBalancer.metrics.httpCodeTarget(elbv2.HttpCodeTarget.TARGET_5XX_COUNT, fiveMinutes),
      5,
    )
    alarm(
      'load-balancer-5xx',
      'The load balancer returned 5 or more 502/503/504 responses within 5 minutes: no healthy API task answered.',
      props.loadBalancer.metrics.httpCodeElb(elbv2.HttpCodeElb.ELB_5XX_COUNT, fiveMinutes),
      5,
    )
    alarm(
      'api-unhealthy',
      'No healthy API task behind the load balancer for 3 minutes.',
      props.apiTargetGroup.metrics.healthyHostCount({ period: Duration.minutes(1), statistic: 'Minimum' }),
      1,
      {
        comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
        evaluationPeriods: 3,
        treatMissingData: cloudwatch.TreatMissingData.BREACHING,
      },
    )
    alarm(
      'api-errors',
      'The API logged an ERROR. Search its log group for level ERROR.',
      logMetric(props.apiLogs, 'ApiErrorLogs', errors),
      1,
    )
    alarm(
      'job-dispatch-failures',
      'The API could not send due jobs to the queue 3 or more times within 5 minutes.',
      logMetric(props.apiLogs, 'JobDispatchFailures', '{ $.log.logger = "com.photospace.jobs.JobDispatcher" && $.log.level = "WARN" }'),
      3,
    )

    // Job failures and processing issues.
    alarm(
      'worker-errors',
      'The worker logged an ERROR. Search its log group for level ERROR.',
      logMetric(props.workerLogs, 'WorkerErrorLogs', errors),
      1,
    )
    alarm(
      'jobs-failed',
      'A background job failed on its last attempt. Owners see it under Automation; recover it from its feature.',
      logMetric(props.workerLogs, 'JobsFailed', `{ ${jobRunner} && $.message = "*failed on its last attempt*" }`),
      1,
    )
    alarm(
      'job-retries',
      'Background jobs failed 10 or more attempts within 15 minutes (photo processing, AI drafts, automation).',
      logMetric(props.workerLogs, 'JobAttemptsFailed', `{ ${jobRunner} && $.message = "*failed on attempt*" }`).with({
        period: Duration.minutes(15),
      }),
      10,
    )
    alarm(
      'worker-poll-failures',
      'The worker could not read the job queue 3 or more times within 5 minutes.',
      logMetric(props.workerLogs, 'JobQueuePollFailures', '{ $.log.logger = "com.photospace.jobs.JobWorker" && $.log.level = "WARN" }'),
      3,
    )

    // Queue depth and stalled jobs.
    alarm(
      'job-queue-stalled',
      'A job message has waited over 10 minutes for 10 minutes: the worker is down or overloaded.',
      props.jobQueue.metricApproximateAgeOfOldestMessage({ ...fiveMinutes, statistic: 'Maximum' }),
      600,
      { evaluationPeriods: 2 },
    )
    alarm(
      'job-dead-letters',
      'A job message reached the dead-letter queue. See "Dead-letter queue" in docs/operations.md.',
      props.jobDeadLetterQueue.metricApproximateNumberOfMessagesVisible({ ...fiveMinutes, statistic: 'Maximum' }),
      1,
    )

    // Services that stopped running. ECS publishes CPU only while tasks run, so missing data means none ran.
    for (const [name, service] of [
      ['api', props.apiService],
      ['worker', props.workerService],
    ] as const) {
      alarm(
        `${name}-not-running`,
        `No ${name} task has reported for 10 minutes.`,
        service.metricCpuUtilization({ ...fiveMinutes, statistic: 'SampleCount' }),
        1,
        {
          comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
          evaluationPeriods: 2,
          treatMissingData: cloudwatch.TreatMissingData.BREACHING,
        },
      )
    }

    // Database.
    alarm(
      'database-cpu',
      'Database CPU stayed above 80% for 15 minutes.',
      props.database.metricCPUUtilization({ ...fiveMinutes, statistic: 'Average' }),
      80,
      { evaluationPeriods: 3 },
    )
    alarm(
      'database-storage',
      'Database free storage is below 2 GiB (storage autoscaling may have reached its maximum).',
      props.database.metricFreeStorageSpace({ ...fiveMinutes, statistic: 'Minimum' }),
      2 * 1024 ** 3,
      { comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD },
    )
  }
}

