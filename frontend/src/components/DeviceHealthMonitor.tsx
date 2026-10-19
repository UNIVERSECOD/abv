import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { deviceApi } from '../api/deviceApi'
import { branchApi } from '../api/branchApi'
import { useAuthStore } from '../store/authStore'
import { toast } from '../store/toastStore'
import { evaluateDeviceAlerts, type DeviceAlertState } from '../utils/deviceAlerts'

export default function DeviceHealthMonitor() {
  const { pathname } = useLocation()
  const restoreProgress = pathname === '/backup-progress'
  const { user, isAuthenticated } = useAuthStore()
  useEffect(() => {
    if (restoreProgress || !isAuthenticated || !user || !['HEAD_OFFICE_HR', 'OFFICE_HR', 'DEPARTMENT_HR'].includes(user.userType)) return
    const key = `attendra-device-alerts:${user.id}:${user.username}`
    let cancelled = false, running = false
    let memory: DeviceAlertState = { offline: {} }
    const read = (): DeviceAlertState => {
      try {
        const value = JSON.parse(localStorage.getItem(key) || 'null')
        if (value && value.offline && typeof value.offline === 'object') return value
      } catch { /* Storage may be unavailable. Keep in-memory reminders. */ }
      return memory
    }
    const poll = async () => {
      if (running || document.visibilityState !== 'visible') return
      running = true
      try {
        const [response, areas] = await Promise.all([deviceApi.getHealth(), branchApi.getAll().catch(() => null)])
        if (cancelled) return
        const { devices, available } = response.data.data
        const publish = () => {
          if (cancelled) return
          const result = evaluateDeviceAlerts(read(), devices, available, Date.now())
          memory = result.state
          try { localStorage.setItem(key, JSON.stringify(memory)) } catch { /* In-memory fallback. */ }
          const names = (ids: number[]) => devices.filter(d => ids.includes(d.id)).map(d => {
            const area = areas?.data.data.find(a => a.id === d.branchId)?.name
            return `${area ? `${area} — ` : ''}${d.deviceName || d.deviceId}`
          }).join(', ')
          if (result.bridgeWarning) toast.warning('Cihazların vəziyyəti yoxlanılmadı', 'Cihaz xidmətinə bağlantı yoxdur. Son vəziyyət məlum deyil.', 10000)
          if (result.warn.length) toast.warning(`${result.warn.length} cihaz oflayndır`, `${names(result.warn)}. Davamiyyət məlumatları natamam ola bilər.`, 10000)
          if (result.recovered.length) toast.success('Cihaz bağlantısı bərpa olundu', `${names(result.recovered)}. Məlumatların tamamlanmasını son sinxronizasiya vaxtından yoxlayın.`, 10000)
        }
        if (navigator.locks) await navigator.locks.request(key, publish)
        else publish()
      } catch { /* API/session errors do not imply that physical devices are offline. */ }
      finally { running = false }
    }
    void poll()
    const timer = window.setInterval(() => { void poll() }, 60000)
    const onVisible = () => { void poll() }
    document.addEventListener('visibilitychange', onVisible)
    return () => { cancelled = true; window.clearInterval(timer); document.removeEventListener('visibilitychange', onVisible) }
  }, [isAuthenticated, user, restoreProgress])
  return null
}
