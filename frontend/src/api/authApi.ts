import client from './client.ts'
import { InitialSetupStatus, LoginResponse } from '../types'

export const authApi = {
  getInitialSetupStatus: () =>
    client.get<InitialSetupStatus>('/auth/initial-setup'),
  completeInitialSetup: (password: string, confirmPassword: string, setupToken: string) =>
    client.post<LoginResponse>('/auth/initial-setup', { password, confirmPassword, setupToken }),
  changePassword: (currentPassword: string, newPassword: string, confirmPassword: string) =>
    client.put('/auth/password', { currentPassword, newPassword, confirmPassword }),
  login: (username: string, password: string) =>
    client.post<LoginResponse>('/auth/login', { username, password }),
  signup: (data: {
    username: string
    email?: string
    firstName: string
    lastName: string
    password: string
    role?: string
  }) => client.post<LoginResponse>('/auth/signup', data),
  verify: () => client.get('/auth/verify'),
  me: () => client.get('/auth/me'),
}
