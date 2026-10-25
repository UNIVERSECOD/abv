import { useEffect, useState } from 'react'
import Layout from '../components/Layout.tsx'
import DashboardAttendancePanel from '../components/DashboardAttendancePanel.tsx'
import LeaveAttendanceWarning from '../components/LeaveAttendanceWarning.tsx'
import client from '../api/client.ts'
import { doorRoleLabel, statusLabel } from '../i18n/labels.ts'
import { formatAttendanceDateTime } from '../utils/dateTime.ts'

interface DashboardStats {
  totalEmployees: number
  activeEmployees: number
  onLeaveEmployees: number
  activeDevices: number
  totalDevices: number
  todayAttendance: number
  pendingLeaves: number
}

interface DeviceStatus {
  totalDevices: number
  onlineDevices: number
  offlineDevices: number
}

interface AccessLog {
  id: number
  employeeId?: number
  employeeNo?: string
  checkInTime?: string
  punchTime?: string
  checkOutTime?: string
  deviceId?: string | number
  deviceName?: string
  doorRole?: string
  eventType?: string
  status?: string
  firstName?: string
  lastName?: string
}

interface CurrentTime {
  datetime: string
  formatted: string
}

export default function DashboardPage() {
  const [stats, setStats] = useState<DashboardStats | null>(null)
  const [deviceStatus, setDeviceStatus] = useState<DeviceStatus | null>(null)
  const [accessLogs, setAccessLogs] = useState<AccessLog[]>([])
  const [currentTime, setCurrentTime] = useState<CurrentTime | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    const fetchAll = async () => {
      setLoading(true)
      try {
        const [statsRes, deviceRes, logsRes] = await Promise.allSettled([
          client.get('/dashboard'),
          client.get('/dashboard/device-status'),
          client.get('/dashboard/access-logs/latest'),
        ])
        if (statsRes.status === 'fulfilled') setStats(statsRes.value.data?.data ?? null)
        if (deviceRes.status === 'fulfilled') setDeviceStatus(deviceRes.value.data?.data ?? null)
        if (logsRes.status === 'fulfilled') setAccessLogs(logsRes.value.data?.data ?? [])
      } finally {
        setLoading(false)
      }
    }
    fetchAll()

    // Update time every second
    const updateTime = () => {
      const now = new Date()
      const formatted = now.toLocaleDateString('az-AZ', {
        day: '2-digit', month: '2-digit', year: 'numeric', timeZone: 'Asia/Baku'
      }) + ' ' + now.toLocaleTimeString('az-AZ', { timeZone: 'Asia/Baku' })
      setCurrentTime({ datetime: now.toISOString(), formatted })
    }
    updateTime()
    const timer = setInterval(updateTime, 1000)
    return () => clearInterval(timer)
  }, [])

  const sortedLogs = [...accessLogs].sort((a, b) => {
    const timeA = new Date(a.checkInTime ?? a.punchTime ?? 0).getTime()
    const timeB = new Date(b.checkInTime ?? b.punchTime ?? 0).getTime()
    return timeB - timeA
  })


  return (
    <Layout>
      <div className="p-4 sm:p-8" style={{ background: '#f8fafc', minHeight: '100vh' }}>
        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-3 mb-6">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">İdarə paneli</h1>
            <p className="text-sm text-gray-500 mt-1">Canlı əməkdaş və cihaz metrikalarını bir baxışda izləyin</p>
          </div>
          <div className="text-right">
            <p className="text-xs font-semibold text-gray-400 uppercase tracking-wider">BU GÜN TARİX VƏ SAAT</p>
            <p className="text-lg font-bold text-gray-800 mt-0.5">{currentTime?.formatted ?? '...'}</p>
          </div>
        </div>

        <DashboardAttendancePanel totalEmployees={stats?.totalEmployees} />
        <LeaveAttendanceWarning />

        {/* Device Status */}
        <div className="bg-white rounded-xl shadow-sm p-6 mb-6">
          <h2 className="text-base font-semibold text-gray-800 mb-4">Cihaz vəziyyət xülasəsi</h2>
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="rounded-xl p-4 flex items-center gap-3" style={{ background: '#f3f4f6' }}>
              <div className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0" style={{ background: '#e5e7eb' }}>
                <svg className="w-5 h-5 text-gray-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                </svg>
              </div>
              <div>
                <p className="text-xs text-gray-400">Ümumi cihaz</p>
                <p className="text-xl font-bold text-gray-700">{loading ? '—' : (deviceStatus?.totalDevices ?? 0)}</p>
              </div>
            </div>
            <div className="rounded-xl p-4 flex items-center gap-3" style={{ background: '#f0fdf4' }}>
              <div className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0 bg-green-100">
                <span className="w-3 h-3 rounded-full bg-green-500 inline-block"></span>
              </div>
              <div>
                <p className="text-xs text-gray-400">Onlayn cihaz</p>
                <p className="text-xl font-bold text-green-600">{loading ? '—' : (deviceStatus?.onlineDevices ?? 0)}</p>
              </div>
            </div>
            <div className="rounded-xl p-4 flex items-center gap-3" style={{ background: '#fef2f2' }}>
              <div className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0 bg-red-100">
                <span className="w-3 h-3 rounded-full bg-red-400 inline-block"></span>
              </div>
              <div>
                <p className="text-xs text-gray-400">Oflayn cihaz</p>
                <p className="text-xl font-bold text-red-500">{loading ? '—' : (deviceStatus?.offlineDevices ?? 0)}</p>
              </div>
            </div>
          </div>
        </div>

        {/* Last 10 Access Logs */}
        <div className="bg-white rounded-xl shadow-sm p-6">
          <h2 className="text-base font-semibold text-gray-800 mb-4">Son 10 keçid</h2>
          {loading ? (
            <div className="text-center text-gray-400 py-8">
              <div className="w-7 h-7 border-2 border-purple-300 border-t-purple-600 rounded-full animate-spin mx-auto mb-2"></div>
              Yüklənir...
            </div>
          ) : sortedLogs.length === 0 ? (
            <div className="text-center text-gray-400 py-8">
              <svg className="w-10 h-10 mx-auto mb-2 text-gray-200" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
              </svg>
              Keçid qeydi tapılmadı
            </div>
          ) : (
            <div className="space-y-2">
              {sortedLogs.map((log) => {
                const eventTime = log.checkInTime ?? log.punchTime
                const role = log.doorRole?.toUpperCase()
                const roleLabel = role === 'BOTH' ? 'Keçid' : role === 'ENTRY' ? 'Giriş' : role === 'EXIT' ? 'Çıxış' : (log.status ? statusLabel(log.status) : (log.eventType || 'Keçid'))
                const deviceDisplay = log.deviceName
                  || (log.deviceId !== undefined ? `Cihaz #${log.deviceId}` : 'Bilinməyən cihaz')
                const badgeStyle = role === 'ENTRY'
                  ? { background: '#d1fae5', color: '#065f46' }
                  : role === 'EXIT'
                    ? { background: '#fee2e2', color: '#991b1b' }
                    : log.status === 'SUCCESS' || log.status === 'GRANTED'
                      ? { background: '#d1fae5', color: '#065f46' }
                      : { background: '#fef3c7', color: '#92400e' }
                return (
                  <div key={log.id} className="flex items-center gap-3 sm:gap-4 p-3 rounded-lg" style={{ background: '#f9fafb' }}>
                  <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ background: '#ede9fe' }}>
                    <svg className="w-4 h-4" style={{ color: '#a855f7' }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
                    </svg>
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-gray-800 truncate">
                      {log.firstName || log.lastName
                        ? `${log.firstName ?? ''} ${log.lastName ?? ''}`.trim()
                        : `Əməkdaş #${log.employeeNo ?? log.employeeId ?? '—'}`}
                    </p>
                    <p className="text-xs text-gray-400 truncate">
                      {deviceDisplay}
                      {log.doorRole ? ` · ${doorRoleLabel(log.doorRole)}` : ''}
                    </p>
                  </div>
                  <div className="text-right flex-shrink-0">
                    <p className="text-xs text-gray-500">
                      {eventTime ? formatAttendanceDateTime(String(eventTime)) : '—'}
                    </p>
                    <span
                      className="text-xs px-2 py-0.5 rounded-full font-medium"
                      style={badgeStyle}
                    >
                      {roleLabel}
                    </span>
                  </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </Layout>
  )
}
