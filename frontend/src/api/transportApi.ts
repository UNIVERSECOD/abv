import client from './client.ts'

export interface VehiclePlate {
  id: number
  plate: string
}

export interface CameraTransferPreview {
  token: string
  endpoint: string
  cameraName: string
  role: 'ENTRY' | 'EXIT'
  plate: string
  fullName: string
  operation: 'add' | 'modify' | 'unchanged'
  listType: 'allowList' | 'blockList'
}

export interface CameraRemovalPreview {
  token: string | null
  endpoint: string
  cameraName: string
  plate: string
  recordId: string | null
  listType: 'allowList' | null
}

export interface TransportPassage {
  id: number
  plate?: string
  time: string
  direction: 'ENTRY' | 'EXIT'
  confidence?: number
  employeeName?: string
  area?: string
  camera?: string
  result: string
}

export const transportApi = {
  vehicles: (employeeId: number) =>
    client.get<{ data: VehiclePlate[] }>(`/employees/${employeeId}/vehicles`),
  addVehicle: (employeeId: number, plate: string, effectiveFrom?: string) =>
    client.post<{ data: VehiclePlate }>(`/employees/${employeeId}/vehicles`, { plate, effectiveFrom }),
  retireVehicle: (employeeId: number, vehicleId: number) =>
    client.delete(`/employees/${employeeId}/vehicles/${vehicleId}`),
  retiredVehicles: (employeeId: number) =>
    client.get<{ data: VehiclePlate[] }>(`/employees/${employeeId}/vehicles/retired`),
  previewCameraRemoval: (employeeId: number, vehicleId: number, cameraId: number) =>
    client.post<{ data: CameraRemovalPreview }>(
      `/employees/${employeeId}/vehicles/${vehicleId}/camera-removal/${cameraId}/preview`),
  confirmCameraRemoval: (employeeId: number, vehicleId: number, cameraId: number, token: string) =>
    client.post(`/employees/${employeeId}/vehicles/${vehicleId}/camera-removal/${cameraId}/confirm`, { token, confirmed: true }),
  previewCameraTransfer: (employeeId: number, vehicleId: number, cameraId: number) =>
    client.post<{ data: CameraTransferPreview }>(
      `/employees/${employeeId}/vehicles/${vehicleId}/camera-transfer/preview`, { cameraId }),
  confirmCameraTransfer: (employeeId: number, vehicleId: number, token: string) =>
    client.post(`/employees/${employeeId}/vehicles/${vehicleId}/camera-transfer/confirm`, { token, confirmed: true }),
  report: (params: { from: string; to: string; areaId?: number; employee?: string; plate?: string; page: number }) =>
    client.get<{ data: { rows: TransportPassage[]; hasMore: boolean } }>('/transport-report', { params }),
}
