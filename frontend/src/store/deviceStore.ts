import { create } from 'zustand'
import { DeviceConfig } from '../types'
import { deviceApi } from '../api/deviceApi.ts'

interface DeviceState {
  devices: DeviceConfig[]
  loading: boolean
  error: string | null
  fetchDevices: () => Promise<void>
  createDevice: (data: Partial<DeviceConfig> & { password?: string }) => Promise<void>
  updateDevice: (id: number, data: Partial<DeviceConfig> & { password?: string }) => Promise<void>
  deleteDevice: (id: number) => Promise<void>
  syncDevice: (id: number) => Promise<void>
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null

const DEVICE_STATUSES = new Set(['ACTIVE', 'INACTIVE'])

const normalizeDevice = (item: Record<string, unknown>): DeviceConfig => {
  const parsedId = typeof item.id === 'number' ? item.id : Number(item.id)
  const id = Number.isFinite(parsedId) ? parsedId : 0
  const fallbackDeviceId = id > 0 ? String(id) : 'unknown'
  const deviceId = typeof item.deviceId === 'string' && item.deviceId.trim() !== '' ? item.deviceId : fallbackDeviceId
  const deviceName = typeof item.deviceName === 'string' ? item.deviceName : typeof item.name === 'string' ? item.name : undefined
  const deviceIp = typeof item.deviceIp === 'string' ? item.deviceIp : typeof item.ip === 'string' ? item.ip : ''
  const devicePort = typeof item.devicePort === 'number' ? item.devicePort : undefined
  const username = typeof item.username === 'string' ? item.username : undefined
  const branchId = typeof item.branchId === 'number' ? item.branchId : undefined
  const doorId = typeof item.doorId === 'number' ? item.doorId : undefined
  const doorRole = typeof item.doorRole === 'string' ? item.doorRole : undefined
  let status = 'INACTIVE'
  if (typeof item.status === 'string' && DEVICE_STATUSES.has(item.status)) {
    status = item.status
  } else if (typeof item.running === 'boolean') {
    status = item.running ? 'ACTIVE' : 'INACTIVE'
  } else if (typeof item.enabled === 'boolean') {
    status = item.enabled ? 'ACTIVE' : 'INACTIVE'
  }
  const online = typeof item.online === 'boolean' ? item.online : undefined
  const lastSyncTime = typeof item.lastSyncTime === 'string' ? item.lastSyncTime : undefined

  return {
    id,
    deviceId,
    deviceName,
    deviceIp,
    devicePort,
    username,
    branchId,
    doorId,
    doorRole,
    anpr: item.anpr === true,
    // Prefer explicit runtime state first, then fallback to enabled flag.
    status,
    online,
    lastSyncTime,
  }
}

const extractDevices = (payload: unknown): DeviceConfig[] => {
  // Backend may proxy device list directly as [] while older endpoints return { data: [] }.
  const list = Array.isArray(payload)
    ? payload
    : isRecord(payload)
      ? payload.data
      : undefined

  return Array.isArray(list) ? list.filter(isRecord).map(normalizeDevice) : []
}

export const useDeviceStore = create<DeviceState>((set, get) => ({
  devices: [],
  loading: false,
  error: null,
  fetchDevices: async () => {
    set({ loading: true, error: null })
    try {
      const res = await deviceApi.getAll()
      set({ devices: extractDevices(res.data), loading: false })
    } catch (e: unknown) {
      set({ error: (e as Error).message, loading: false })
    }
  },
  createDevice: async (data) => {
    await deviceApi.create(data)
    await get().fetchDevices()
  },
  updateDevice: async (id, data) => {
    await deviceApi.update(id, data)
    await get().fetchDevices()
  },
  deleteDevice: async (id) => {
    await deviceApi.delete(id)
    await get().fetchDevices()
  },
  syncDevice: async (id) => {
    try {
      await deviceApi.sync(id)
    } finally {
      await get().fetchDevices()
    }
  },
}))
