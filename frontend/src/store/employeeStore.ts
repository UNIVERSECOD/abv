import { create } from 'zustand'
import { Employee, EmployeeTerminationResult, PaginatedResponse } from '../types'
import { employeeApi, EmployeeListFilters } from '../api/employeeApi.ts'

interface EmployeeState {
  employees: Employee[]
  totalElements: number
  totalPages: number
  currentPage: number
  loading: boolean
  error: string | null
  filters: EmployeeListFilters
  fetchEmployees: (page?: number, size?: number, filters?: EmployeeListFilters) => Promise<boolean>
  createEmployee: (data: Partial<Employee>) => Promise<void>
  updateEmployee: (id: number, data: Partial<Employee>) => Promise<void>
  terminateEmployee: (id: number, terminationDate: string) => Promise<EmployeeTerminationResult>
}

export const useEmployeeStore = create<EmployeeState>((set, get) => ({
  employees: [],
  totalElements: 0,
  totalPages: 0,
  currentPage: 0,
  loading: false,
  error: null,
  filters: {},
  fetchEmployees: async (page = 0, size = 20, filters = get().filters) => {
    set({ loading: true, error: null })
    try {
      const res = await employeeApi.getAll(page, size, filters)
      const data: PaginatedResponse<Employee> = res.data
      set({
        employees: data.content,
        totalElements: data.totalElements,
        totalPages: data.totalPages,
        currentPage: data.currentPage,
        filters,
        loading: false,
      })
      return true
    } catch (e: unknown) {
      set({ error: (e as Error).message, loading: false })
      return false
    }
  },
  createEmployee: async (data) => {
    await employeeApi.create(data)
    await get().fetchEmployees(get().currentPage)
  },
  updateEmployee: async (id, data) => {
    await employeeApi.update(id, data)
    await get().fetchEmployees(get().currentPage)
  },
  terminateEmployee: async (id, terminationDate) => {
    const response = await employeeApi.terminate(id, terminationDate)
    await get().fetchEmployees(get().currentPage)
    return response.data.data
  },
}))
