import client from './client.ts'
import { deviceEndpoint } from '../utils/deviceEndpoint'
import { DeviceConfig, DeviceEmployeeAssignmentView, DeviceEmployeeSyncResult } from '../types'

type DeviceWritePayload = Partial<DeviceConfig> & {
  password?: string
  deviceIp?: string
  deviceName?: string
  status?: string
}

export type ReconciliationRow = { employeeId: number | null; employeeNo: string; name: string | null; action: string; reason: string }
export type ReconciliationPreview = { token: string; expiresAt: string; deviceName: string; deviceIp: string; areaId: number | null; rows: ReconciliationRow[]; operations: number }
export type ReconciliationProgress = { nextStep: number; finished: boolean; outcomes: { row: ReconciliationRow; status: string; message: string }[] }

export type AnprCapability = { status: 'SUPPORTED' | 'UNSUPPORTED' | 'UNKNOWN'; reason: string }
export type AnprCapabilities = {
  checkedAt: string
  model: string
  firmware: string
  plateWrite: AnprCapability
  ownerName: AnprCapability
  archiveRead: AnprCapability
}

const toIsapiUpsertPayload = (data: DeviceWritePayload) => ({
  ip: data.deviceIp ? deviceEndpoint(data.deviceIp, data.devicePort) : undefined,
  username: data.username,
  ...(data.password?.trim() ? { password: data.password } : {}),
  name: data.deviceName,
  enabled: data.status ? data.status === 'ACTIVE' : undefined,
  branchId: data.branchId,
  anpr: data.anpr,
  doorRole: data.anpr ? data.doorRole : undefined,
})

export const deviceApi = {
  checkAnprCapabilities: (id: number, signal?: AbortSignal) =>
    client.post<{ data: AnprCapabilities }>(`/devices/${id}/anpr-capabilities`, undefined, { signal, timeout: 120_000 }),
  previewReconciliation: (id: number) => client.post<{ data: ReconciliationPreview }>(`/devices/${id}/employees/reconciliation/preview`),
  applyReconciliation: (id: number, token: string, step: number) => client.post<{ data: ReconciliationProgress }>(`/devices/${id}/employees/reconciliation/apply`, { token, step, confirmed: true }),
  getHealth: () => client.get<{ data: { available: boolean; devices: DeviceConfig[] } }>('/devices/health-overview'),
  assignArea: (id: number, branchId: number | null) => client.put<{ data: DeviceConfig }>(`/devices/${id}/area`, { branchId }),
  getAll: () => client.get<{ data: DeviceConfig[] }>('/devices'),
  create: (data: DeviceWritePayload) => client.post<{ data: DeviceConfig }>('/devices', toIsapiUpsertPayload(data)),
  update: (id: number, data: DeviceWritePayload) => client.put<{ data: DeviceConfig }>(`/devices/${id}`, toIsapiUpsertPayload(data)),
  delete: (id: number) => client.delete(`/devices/${id}`),
  sync: (id: number) => client.post(`/devices/${id}/sync`),
  getHistory: (id: number) => client.get(`/devices/${id}/history`),
  assignDoor: (id: number, data: { doorId?: number; role?: string }) =>
    client.post<{ data: DeviceConfig }>(`/devices/${id}/assign-door`, data),
  getEmployeeAssignments: (id: number) =>
    client.get<{ data: DeviceEmployeeAssignmentView }>(`/devices/${id}/employees`),
  updateEmployeeAssignments: (id: number, manualEmployeeIds: number[]) =>
    client.put<{ data: DeviceEmployeeAssignmentView }>(`/devices/${id}/employees`, { manualEmployeeIds }),
  syncEmployees: (id: number) =>
    client.post<{ data: DeviceEmployeeSyncResult }>(`/devices/${id}/employees/sync`),
}
