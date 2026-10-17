#!/usr/bin/env bash
# Database restore test (D18, docs/operations.md). Restores the environment's database to its latest
# restorable time as a new, temporary instance in the same VPC, compares row counts with the live database
# through the db-tools task, and deletes the copy. It reads counts only, never row contents.
#
# Usage: infra/scripts/restore-test.sh <staging|prod>
# Needs the AWS CLI v2, jq, and credentials for the environment's account. AWS_REGION defaults to us-west-2.
set -euo pipefail

env="${1:?usage: restore-test.sh <staging|prod>}"
region="${AWS_REGION:-us-west-2}"
stack="PhotoSpace-${env}"
started=$(date +%s)

output() {
  aws cloudformation describe-stacks --region "$region" --stack-name "$stack" \
    --query "Stacks[0].Outputs[?OutputKey=='$1'].OutputValue" --output text
}
source_db=$(output DatabaseInstanceId)
cluster=$(output ClusterName)
task_definition=$(output DatabaseToolsTaskDefinition)
subnets=$(output TaskSubnetIds)
tools_security_group=$(output DatabaseToolsSecurityGroupId)
target="${source_db}-restore-$(date -u +%Y%m%d%H%M%S)"

read -r subnet_group db_security_group instance_class parameter_group restorable < <(
  aws rds describe-db-instances --region "$region" --db-instance-identifier "$source_db" --query \
    'DBInstances[0].[DBSubnetGroup.DBSubnetGroupName, VpcSecurityGroups[0].VpcSecurityGroupId, DBInstanceClass, DBParameterGroups[0].DBParameterGroupName, LatestRestorableTime]' \
    --output text)

cleanup() {
  echo "Deleting $target"
  aws rds delete-db-instance --region "$region" --db-instance-identifier "$target" \
    --skip-final-snapshot --delete-automated-backups >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Restoring $source_db as $target to its latest restorable time ($restorable)"
aws rds restore-db-instance-to-point-in-time --region "$region" \
  --source-db-instance-identifier "$source_db" --target-db-instance-identifier "$target" \
  --use-latest-restorable-time --db-subnet-group-name "$subnet_group" \
  --vpc-security-group-ids "$db_security_group" --db-parameter-group-name "$parameter_group" \
  --db-instance-class "$instance_class" --no-multi-az --no-publicly-accessible --no-deletion-protection \
  --tags Key=project,Value=photo-space Key=environment,Value="$env" Key=purpose,Value=restore-test >/dev/null
aws rds wait db-instance-available --region "$region" --db-instance-identifier "$target"
restored_host=$(aws rds describe-db-instances --region "$region" --db-instance-identifier "$target" \
  --query 'DBInstances[0].Endpoint.Address' --output text)
echo "Restored in $(( ($(date +%s) - started) / 60 )) minutes"

query="SELECT format('migration=%s workspaces=%s members=%s clients=%s sessions=%s photos=%s galleries=%s tasks=%s audit_events=%s',
  (SELECT max(version::int) FROM flyway_schema_history WHERE success),
  (SELECT count(*) FROM workspaces), (SELECT count(*) FROM workspace_members), (SELECT count(*) FROM clients),
  (SELECT count(*) FROM photo_sessions), (SELECT count(*) FROM photo_assets), (SELECT count(*) FROM galleries),
  (SELECT count(*) FROM tasks), (SELECT count(*) FROM audit_events))"

# Runs the query with psql in the db-tools task against one host and prints its single line of output.
counts() {
  local overrides task_arn exit_code
  overrides=$(jq -n --arg host "$1" --arg query "$query" \
    '{containerOverrides: [{name: "psql", command: ["-At", "-v", "ON_ERROR_STOP=1", "-c", $query],
      environment: [{name: "PGHOST", value: $host}]}]}')
  task_arn=$(aws ecs run-task --region "$region" --cluster "$cluster" --launch-type FARGATE \
    --task-definition "$task_definition" --overrides "$overrides" \
    --network-configuration "awsvpcConfiguration={subnets=[$subnets],securityGroups=[$tools_security_group],assignPublicIp=ENABLED}" \
    --query 'tasks[0].taskArn' --output text)
  aws ecs wait tasks-stopped --region "$region" --cluster "$cluster" --tasks "$task_arn"
  exit_code=$(aws ecs describe-tasks --region "$region" --cluster "$cluster" --tasks "$task_arn" \
    --query 'tasks[0].containers[0].exitCode' --output text)
  [[ "$exit_code" == "0" ]] || { echo "psql failed on $1 (exit code $exit_code); see the log group /photo-space-${env}/db-tools" >&2; return 1; }
  aws logs get-log-events --region "$region" --log-group-name "/photo-space-${env}/db-tools" \
    --log-stream-name "psql/psql/${task_arn##*/}" --query 'events[].message' --output text
}

live=$(counts "$(aws rds describe-db-instances --region "$region" --db-instance-identifier "$source_db" \
  --query 'DBInstances[0].Endpoint.Address' --output text)")
restored=$(counts "$restored_host")
echo "Live:     $live"
echo "Restored: $restored"
echo "Total time: $(( ($(date +%s) - started) / 60 )) minutes"
if [[ "$live" == "$restored" ]]; then
  echo "PASS: the restored database matches the live one"
else
  echo "DIFFERENT: rows written after the restore point ($restorable) are expected to be missing; check the counts"
  exit 1
fi
