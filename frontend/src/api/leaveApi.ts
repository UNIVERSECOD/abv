import client from './client.ts'
import { ApiResponse } from '../types'
import { DataImportResult } from './dataTransferApi.ts'

export type LeaveStatus = 'APPROVED' | 'PENDING' | 'REJECTED'
export interface LeaveBalance {
  employeeId: number
  employeeCode: string
  employeeName: string
  startDate: string
  openingDays: number | null
  usedDays: number
  remainingDays: number | null
}
export interface LeaveType {
  id: number
  leaveName: string
  leaveCode: string
}
export interface LeaveRequest {
  id: number
  employeeId: number
  employeeCode?: string
  employeeName?: string
  finNumber?: string
  leaveTypeId: number
  leaveTypeName?: string
  startDate: string
  endDate: string
  status: LeaveStatus
}
export type LeavePayload = Pick<LeaveRequest, 'employeeId' | 'leaveTypeId' | 'startDate' | 'endDate' | 'status'>

export const leaveApi = {
  getBalances: () => client.get<ApiResponse<LeaveBalance[]>>('/annual-leave/balances'),
  getAll: () => client.get<ApiResponse<LeaveRequest[]>>('/leaves'),
  getTypes: () => client.get<ApiResponse<LeaveType[]>>('/leaves/types'),
  create: (data: LeavePayload) => client.post<ApiResponse<LeaveRequest>>('/leaves', data),
  update: (id: number, data: LeavePayload) => client.put<ApiResponse<LeaveRequest>>(`/leaves/${id}`, data),
  setStatus: (id: number, status: LeaveStatus) => client.put(`/leaves/${id}/status`, null, { params: { status } }),
  download: (template: boolean) =>
    client.get<Blob>(`/leaves/${template ? 'template' : 'export'}`, { responseType: 'blob' }),
  import: (file: File) => {
    const data = new FormData()
    data.append('file', file)
    return client.post<ApiResponse<Omit<DataImportResult, 'entity'> & { entity: 'leaves' }>>('/leaves/import', data)
  },
}
