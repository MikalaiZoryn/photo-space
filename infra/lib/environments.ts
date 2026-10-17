/**
 * The deployed environments and what differs between them (D18). Local development and CI need no AWS account
 * (D4, D13), so they are not listed here.
 */
export const ENVIRONMENTS = ['staging', 'prod'] as const
export type EnvironmentName = (typeof ENVIRONMENTS)[number]

export type ServiceSize = {
  /** Fargate CPU units (1024 = 1 vCPU). */
  cpu: number
  memoryMiB: number
  desiredCount: number
}

export type EnvironmentConfig = {
  name: EnvironmentName
  /** The application's region. The CloudFront firewall is always in us-east-1, as CloudFront requires. */
  region: string
  database: {
    instanceType: string
    multiAz: boolean
    allocatedStorageGiB: number
    maxAllocatedStorageGiB: number
    backupRetentionDays: number
    deletionProtection: boolean
  }
  api: ServiceSize
  /** Image processing decodes photos up to 200 megapixels (subsampled), so the worker gets more memory. */
  worker: ServiceSize
  logRetentionDays: number
}

const CONFIGS: Record<EnvironmentName, EnvironmentConfig> = {
  staging: {
    name: 'staging',
    region: 'us-west-2',
    database: {
      instanceType: 't4g.micro',
      multiAz: false,
      allocatedStorageGiB: 20,
      maxAllocatedStorageGiB: 100,
      backupRetentionDays: 7,
      deletionProtection: false,
    },
    api: { cpu: 512, memoryMiB: 1024, desiredCount: 1 },
    worker: { cpu: 512, memoryMiB: 2048, desiredCount: 1 },
    logRetentionDays: 30,
  },
  prod: {
    name: 'prod',
    region: 'us-west-2',
    database: {
      instanceType: 't4g.small',
      multiAz: true,
      allocatedStorageGiB: 50,
      maxAllocatedStorageGiB: 500,
      backupRetentionDays: 30,
      deletionProtection: true,
    },
    api: { cpu: 512, memoryMiB: 1024, desiredCount: 2 },
    worker: { cpu: 1024, memoryMiB: 2048, desiredCount: 1 },
    logRetentionDays: 90,
  },
}

/** Reads and checks the context value that selects an environment. */
export function environmentConfig(environment: unknown): EnvironmentConfig {
  if (typeof environment !== 'string' || !(ENVIRONMENTS as readonly string[]).includes(environment)) {
    throw new Error(`Set -c env=<${ENVIRONMENTS.join('|')}>`)
  }
  return CONFIGS[environment as EnvironmentName]
}
