import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Branch, DeviceConfig } from '../types'
import { deviceApi } from '../api/deviceApi'
import { toast } from '../store/toastStore'
import { getApiErrorMessage } from '../utils/apiError'
import { formatAttendanceDateTime } from '../utils/dateTime'
import DeviceReconciliationModal from './DeviceReconciliationModal'
import { useAuthStore } from '../store/authStore'
import { doorRoleLabel } from '../i18n/labels'

export default function AreaDevicesPanel({ area, branches, onClose }: { area: Branch; branches: Branch[]; onClose: () => void }) {
  const [devices, setDevices] = useState<DeviceConfig[]>([])
  const [reconciliationDevice, setReconciliationDevice] = useState<DeviceConfig | null>(null)
  const canReconcile = useAuthStore(s => s.user?.userType === 'HEAD_OFFICE_HR')
  const [available, setAvailable] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState('')
  const [saving, setSaving] = useState(false)
  const [revision, setRevision] = useState(0)
  const [move, setMove] = useState<{ device: DeviceConfig; areaId: string } | null>(null)
  useEffect(() => {
    let active = true, busy = false
    const load = async () => {
      if (busy) return
      busy = true
      try {
        const { data } = await deviceApi.getHealth()
        if (active) { setDevices(data.data.devices); setAvailable(data.data.available); setError('') }
      } catch (e) { if (active) setError(getApiErrorMessage(e, 'Cihazlar yüklənmədi.')) }
      finally { busy = false; if (active) setLoading(false) }
    }
    void load()
    const timer = window.setInterval(() => { void load() }, 60000)
    return () => { active = false; window.clearInterval(timer) }
  }, [revision])
  const assigned = devices.filter(d => d.branchId === area.id)
  const assign = async (deviceId: number, areaId: number | null) => {
    setSaving(true); setError('')
    try {
      await deviceApi.assignArea(deviceId, areaId)
      setSelected(''); setMove(null); setRevision(n => n + 1)
      toast.success('Cihazın ərazisi yeniləndi', 'Terminaldakı siyahını yeniləmək üçün “Əməkdaşları uyğunlaşdır” düyməsindən istifadə edin.')
    } catch (e) { setError(getApiErrorMessage(e, 'Ərazi dəyişdirilmədi.')) }
    finally { setSaving(false) }
  }
  const role = async (device: DeviceConfig, value: string) => {
    setSaving(true); setError('')
    try {
      await deviceApi.assignDoor(device.id, { doorId: device.doorId, role: value || undefined })
      setRevision(n => n + 1); toast.success('Cihazın rolu yeniləndi')
    } catch (e) { setError(getApiErrorMessage(e, 'Rol dəyişdirilmədi.')) }
    finally { setSaving(false) }
  }
  const inputClass = 'w-full border border-gray-300 rounded-lg px-3 py-2 text-sm bg-white focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:opacity-50'
  const secondaryClass = 'px-3 py-2 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors disabled:opacity-50'
  const primaryClass = 'px-4 py-2 text-sm font-medium text-white bg-purple-500 rounded-lg hover:bg-purple-600 transition-colors disabled:opacity-50'

  return (
    <div className="fixed inset-0 z-[60] bg-black/40 flex items-center justify-center p-3 sm:p-6" role="dialog" aria-modal="true" aria-labelledby="area-devices-title">
      <section className="w-full max-w-5xl max-h-[90dvh] flex flex-col bg-white rounded-xl shadow-xl overflow-hidden">
        <header className="shrink-0 flex items-start justify-between gap-4 px-5 sm:px-6 py-5 border-b border-gray-100">
          <div>
            <h2 id="area-devices-title" className="text-xl font-bold text-gray-900">{area.name} — Cihazlar</h2>
            <p className="mt-1 text-sm text-gray-500">Əraziyə bağlı cihazlar və keçid rolları</p>
          </div>
          <button onClick={onClose} disabled={saving} aria-label="Bağla" className="p-2 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-100 disabled:opacity-50">
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 6l12 12M6 18L18 6" /></svg>
          </button>
        </header>
        <div className="overflow-y-auto min-h-0 p-4 sm:p-6 bg-slate-50 space-y-4">
          {error && <p role="alert" className="px-4 py-3 rounded-lg border border-red-200 bg-red-50 text-sm text-red-700">{error}</p>}
          {!loading && !available && <p className="px-4 py-3 rounded-lg border border-amber-200 bg-amber-50 text-sm text-amber-800">Cihaz xidməti ilə əlaqə yoxdur. Bağlantı vəziyyəti hazırda məlum deyil.</p>}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {[['Ümumi cihazlar', assigned.length], ['Giriş', assigned.filter(d => d.doorRole === 'ENTRY').length], ['Çıxış', assigned.filter(d => d.doorRole === 'EXIT').length], ['Giriş və çıxış', assigned.filter(d => d.doorRole === 'BOTH').length]].map(([label, count]) => (
              <div key={label} className="bg-white rounded-xl shadow-sm p-3 sm:p-4">
                <p className="text-xs text-gray-500">{label}</p>
                <p className="mt-1 text-lg font-bold text-gray-900">{loading ? '—' : count}</p>
              </div>
            ))}
          </div>
          <div className="space-y-3">
            {assigned.map(d => (
              <div key={d.id} className="bg-white rounded-xl shadow-sm p-4 flex flex-wrap items-center gap-4">
                <div className="w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 bg-purple-100 text-purple-500">
                  <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 17l-1 4h8l-1-4M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" /></svg>
                </div>
                <div className="flex-1 min-w-[10rem]">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="font-semibold text-sm text-gray-900 break-all">{d.deviceName || d.deviceId}</h3>
                    <span className={`inline-flex items-center gap-1.5 text-xs font-medium rounded-full px-2 py-0.5 ${!available || d.status !== 'ACTIVE' ? 'bg-gray-100 text-gray-600' : d.online ? 'bg-emerald-100 text-emerald-800' : 'bg-red-100 text-red-800'}`}>
                      <span className="w-1.5 h-1.5 rounded-full bg-current" />
                      {d.status !== 'ACTIVE' ? 'Deaktiv' : !available ? 'Məlum deyil' : d.online ? 'Onlayn' : 'Oflayn'}
                    </span>
                  </div>
                  <p className="text-xs text-gray-500 mt-1">IP: {d.deviceIp}</p>
                  <p className="text-xs text-gray-400 mt-1">Son sinxronizasiya: {d.lastSyncTime ? formatAttendanceDateTime(d.lastSyncTime) : 'Hələ olmayıb'}</p>
                </div>
                {d.anpr ? (
                  <div className="w-full sm:w-40">
                    <p className="text-xs font-medium text-gray-500">ANPR kamera rolu</p>
                    <span className="mt-1 inline-flex rounded-full bg-violet-100 px-2.5 py-1 text-xs font-semibold text-violet-800">
                      {d.doorRole === 'ENTRY' || d.doorRole === 'EXIT' ? doorRoleLabel(d.doorRole) : 'Təyin edilməyib'}
                    </span>
                  </div>
                ) : <label className="text-xs font-medium text-gray-500 w-full sm:w-40">Keçid rolu
                  <select aria-label={`${d.deviceName} keçid rolu`} className={`${inputClass} mt-1 text-gray-700`} disabled={saving || !canReconcile} value={d.doorRole || ''} onChange={e => { void role(d, e.target.value) }}>
                    <option value="">Təyin edilməyib</option><option value="ENTRY">Giriş</option><option value="EXIT">Çıxış</option><option value="BOTH">Giriş və çıxış</option>
                  </select>
                  {!d.doorRole && <span className="block mt-1 text-amber-700">Keçid rolu seçin.</span>}
                  {d.doorRole === 'BOTH' && <span className="block mt-1 text-amber-700">İlk və son keçid ərazi üzrə birləşdirilir. Sərbəst qrafik dəstəklənmir.</span>}
                </label>}
                <div className="flex flex-wrap gap-2">
                  {canReconcile && !d.anpr && <>
                    <button disabled={saving} onClick={() => setMove({ device: d, areaId: '' })} className={secondaryClass}>Ərazini dəyiş</button>
                    <button disabled={saving} onClick={() => setReconciliationDevice(d)} className="px-3 py-2 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100">Əməkdaşları uyğunlaşdır</button>
                  </>}
                  <Link hidden={!canReconcile} className="px-3 py-2 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors" to={`/devices?device=${d.id}`}>{d.anpr ? 'Kamera ayarları' : 'Cihazı idarə et'}</Link>
                </div>
              </div>
            ))}
          </div>
          {loading ? <p className="py-8 text-sm text-center text-gray-400">Cihazlar yüklənir...</p> : !assigned.length && <p className="py-8 text-sm text-center text-gray-500">Bu əraziyə cihaz bağlanmayıb.</p>}
          <p className="text-xs text-gray-500">Onlayn vəziyyət bağlantını göstərir. Son sinxronizasiya vaxtını ayrıca yoxlayın.</p>
          {assigned.some(d => !d.anpr) && <p className="text-xs text-gray-500">Giriş və çıxış rejimi hər təqvim günü ayrıca hesablanır. Bu rejimə keçid və ərazi dəyişikliyi saxlandıqdan sonrakı keçidlərə tətbiq olunur.</p>}
          {move && (
            <div className="rounded-xl bg-white shadow-sm border border-purple-200 p-4 space-y-3">
              <h3 className="text-sm font-semibold text-gray-900">{move.device.deviceName} — ərazini dəyiş</h3>
              <p className="text-sm text-gray-500">Ərazi üzrə əməkdaş təyinatları yenilənəcək, fərdi təyinatlar və əvvəlki hesabatlar saxlanılacaq. Sonra “Əməkdaşları uyğunlaşdır” düyməsi ilə terminaldakı siyahını yeniləyin.</p>
              <label className="block text-xs font-medium text-gray-500">Yeni ərazi
                <select autoFocus className={`${inputClass} mt-1`} value={move.areaId} onChange={e => setMove({ ...move, areaId: e.target.value })} disabled={saving}>
                  <option value="">Ərazidən ayır</option>{branches.filter(b => b.id !== area.id).map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
                </select>
              </label>
              <div className="flex flex-wrap justify-end gap-2">
                <button disabled={saving} onClick={() => setMove(null)} className={secondaryClass}>İmtina</button>
                <button disabled={saving} className={primaryClass} onClick={() => { void assign(move.device.id, move.areaId ? Number(move.areaId) : null) }}>Dəyişikliyi saxla</button>
              </div>
            </div>
          )}
        </div>
        <footer hidden={!canReconcile} className="shrink-0 px-5 sm:px-6 py-4 border-t border-gray-100 space-y-3">
          <div className="flex flex-wrap gap-3 items-end">
            <label className="text-xs font-medium text-gray-500 flex-1 min-w-[10rem]">Təyin edilməmiş cihaz
              <select className={`${inputClass} mt-1 text-gray-700`} value={selected} onChange={e => setSelected(e.target.value)} disabled={saving}>
                <option value="">Cihaz seçin</option>{devices.filter(d => !d.branchId).map(d => <option key={d.id} value={d.id}>{d.deviceName || d.deviceId} — {d.deviceIp}</option>)}
              </select>
            </label>
            <button disabled={!selected || saving} onClick={() => { void assign(Number(selected), area.id) }} className={primaryClass}>Əraziyə bağla</button>
          </div>
          <Link className="inline-block text-sm font-medium text-purple-600 hover:text-purple-700" to={`/devices?area=${area.id}`}>+ Yeni cihaz əlavə et</Link>
        </footer>
      </section>
      {reconciliationDevice && <DeviceReconciliationModal device={reconciliationDevice} onClose={() => setReconciliationDevice(null)} />}
    </div>
  )
}
