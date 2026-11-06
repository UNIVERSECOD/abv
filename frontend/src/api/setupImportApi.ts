import client from './client.ts'

export interface DeviceEmployeeImportRequest {
  branchId: number
  deviceConfigIds?: number[]
  /** Default true on backend when omitted: write persons to sibling branch devices. */
  writeToOtherBranchDevices?: boolean
}

export interface DeviceScanStatus {
  deviceConfigId: number
  deviceName?: string
  deviceIp?: string
  success: boolean
  userCount: number
  faceCount?: number
  facesSynced?: number
  error?: string
}

export interface SkippedPerson {
  employeeNo: string
  name?: string
  reason: string
  deviceConfigIds?: number[]
}

export interface CreatedPerson {
  employeePk: number
  employeeNo: string
  employeeId?: string
  deviceEmployeeNo?: string
  homeBranchId?: number
  firstName: string
  lastName: string
  deviceConfigIds?: number[]
}

export interface DeviceEmployeeImportResult {
  branchId: number
  branchName?: string
  branchPrefix?: string
  devicesScanned: number
  devicesFailed: number
  totalFetched: number
  uniquePersons: number
  created: number
  skippedExisting: number
  skippedTerminated?: number
  skippedConflict: number
  crossBranchLinked: number
  accessLinked: number
  facesSynced?: number
  facesSkippedNoMatch?: number
  facesSkippedExisting?: number
  facesFailed?: number
  writtenToOtherDevices?: number
  alreadyOnOtherDevices?: number
  otherDeviceWriteFailed?: number
  facesWrittenToOtherDevices?: number
  facesOtherDeviceFailed?: number
  errors: number
  message: string
  deviceStatuses: DeviceScanStatus[]
  conflicts: SkippedPerson[]
  errorsDetail: SkippedPerson[]
  createdPersons: CreatedPerson[]
}

export const setupImportApi = {
  importEmployees: (payload: DeviceEmployeeImportRequest) =>
    client.post<{ data: DeviceEmployeeImportResult }>('/setup/import-employees', payload),
}
