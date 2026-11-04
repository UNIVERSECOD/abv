import client from './client.ts'
import { ApiResponse, Employee, EmployeeDeviceSyncResult, EmployeeSearchResult, EmployeeTerminationResult, PaginatedResponse } from '../types'

export const employeeApi = {
  getAll: (page = 0, size = 20, branchId?: number) =>
    client.get<PaginatedResponse<Employee>>(
      `/employees?page=${page}&size=${size}${branchId ? `&branchId=${branchId}` : ''}`
    ),
  getById: (id: number) => client.get<{ data: Employee }>(`/employees/${id}`),
  create: (data: Partial<Employee>) => client.post<{ data: Employee }>('/employees', data),
  update: (id: number, data: Partial<Employee>) => client.put<{ data: Employee }>(`/employees/${id}`, data),
  updateLeaveBalance: (id: number, days: number) =>
    client.put<{ data: Employee }>(`/employees/${id}/leave-balance`, null, { params: { days } }),
  delete: (id: number) => client.delete(`/employees/${id}`),
  terminate: (id: number, terminationDate?: string) =>
    client.post<ApiResponse<EmployeeTerminationResult>>(
      `/employees/${id}/terminate`, terminationDate ? { terminationDate } : undefined,
    ),
  getByStatus: (status: Employee['employmentStatus']) =>
    client.get<ApiResponse<Employee[]>>(`/employees/status/${status}`),
  search: (q: string, page = 0, size = 20) =>
    client.get<PaginatedResponse<Employee>>(`/employees/search?q=${q}&page=${page}&size=${size}`),
  searchEmployees: (q: string) =>
    client.get<ApiResponse<EmployeeSearchResult[]>>(`/employees/search?q=${encodeURIComponent(q)}`),
  getByBranch: (branchId: number, page = 0, size = 20) =>
    client.get<PaginatedResponse<Employee>>(`/employees/branch/${branchId}?page=${page}&size=${size}`),
  getByDepartment: (departmentId: number) =>
    client.get<{ data: Employee[] }>(`/employees/department/${departmentId}`),
  getDoors: (id: number) =>
    client.get<{ data: string[] }>(`/employees/${id}/doors`),
  getDistinctAreas: () =>
    client.get<{ data: string[] }>('/employees/areas'),
  /** Save captured/uploaded photo as the employee profile face image. */
  uploadFaceImage: (employeeId: number, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return client.post<ApiResponse<EmployeeDeviceSyncResult>>(`/faces/employee/${employeeId}/image`, formData)
  },
  getFaceImage: (employeeId: number) =>
    client.get<Blob>(`/faces/employee/${employeeId}/image`, { responseType: 'blob' }),
  deleteFaceImage: (employeeId: number) =>
    client.delete(`/faces/employee/${employeeId}/image`),
}
