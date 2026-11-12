import { useEffect, useState } from 'react'
import { transportApi, type VehiclePlate } from '../api/transportApi'
import { getApiErrorMessage } from '../utils/apiError'
import { toast } from '../store/toastStore'
import { todayInAppTimeZone } from '../utils/dateTime'
import VehicleCameraTransferDialog from './VehicleCameraTransferDialog'
import VehicleCameraRemovalDialog from './VehicleCameraRemovalDialog'

export default function EmployeeVehiclePanel({ employeeId, readOnly = false, terminated = false }: {
  employeeId: number
  readOnly?: boolean
  terminated?: boolean
}) {
  const canCleanUp = !readOnly || terminated
  const [vehicles, setVehicles] = useState<VehiclePlate[]>([])
  const [retired, setRetired] = useState<VehiclePlate[]>([])
  const [plate, setPlate] = useState('')
  const [effectiveFrom, setEffectiveFrom] = useState('')
  const [busy, setBusy] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [transfer, setTransfer] = useState<VehiclePlate>()
  const [removal, setRemoval] = useState<VehiclePlate>()

  useEffect(() => {
    let active = true
    transportApi.vehicles(employeeId)
      .then(response => { if (active) setVehicles(response.data.data) })
      .catch(reason => { if (active) setError(getApiErrorMessage(reason, 'Nömrələr yüklənmədi.')) })
      .finally(() => { if (active) setLoading(false) })
    if (canCleanUp) {
      transportApi.retiredVehicles(employeeId)
        .then(response => { if (active) setRetired(response.data.data) })
        .catch(reason => { if (active) setError(getApiErrorMessage(reason, 'Bitmiş təyinatlar yüklənmədi.')) })
    }
    return () => { active = false }
  }, [employeeId, canCleanUp])

  const save = async (vehicle?: VehiclePlate) => {
    if (!vehicle && !plate.trim()) {
      setError('Avtomobil nömrəsini daxil edin.')
      return
    }
    setBusy(true)
    setError('')
    try {
      if (vehicle) {
        await transportApi.retireVehicle(employeeId, vehicle.id)
        setVehicles(current => current.filter(item => item.id !== vehicle.id))
        setRetired(current => [vehicle, ...current.filter(item => item.plate !== vehicle.plate)])
        setRemoval(vehicle)
      } else {
        const response = await transportApi.addVehicle(employeeId, plate, effectiveFrom || undefined)
        setPlate('')
        setEffectiveFrom('')
        setRetired(current => current.filter(item => item.plate !== response.data.data.plate))
        setVehicles(current => [...current.filter(item => item.id !== response.data.data.id), response.data.data]
          .sort((left, right) => left.plate.localeCompare(right.plate)))
      }
      if (vehicle) toast.success('Attendra təyinatı bitdi', 'Kamera qeydləri ayrıca yoxlanılır və təsdiqlə silinir.')
      else toast.success('Nömrə təyin edildi')
    } catch (reason) {
      setError(getApiErrorMessage(reason, 'Nömrəni saxlamaq alınmadı.'))
    } finally {
      setBusy(false)
    }
  }

  return <section className="rounded-3xl bg-white p-5">
    <h3 className="mb-3 text-lg font-semibold text-[#1f2d6b]">{readOnly ? 'Avtomobil nömrələri' : 'Avtomobil məlumatları'}</h3>
    {!readOnly && <p className="mb-3 text-sm text-slate-600">Nömrə təyinatı yalnız Attendra-da saxlanılır.</p>}
    {loading ? <p className="text-sm text-slate-600">Yüklənir...</p> : vehicles.length === 0
      ? <p className="text-sm text-slate-600">Aktiv nömrə təyin edilməyib.</p>
      : <ul className="mb-3 divide-y divide-slate-100">
        {vehicles.map(vehicle => <li key={vehicle.id} className="flex items-center justify-between gap-2 py-2">
          <span className="font-mono">{vehicle.plate}</span>
          {canCleanUp && <div className="flex flex-wrap justify-end gap-1">
            {!readOnly && !terminated && <button type="button" disabled={busy} onClick={() => setTransfer(vehicle)}
              className="rounded-lg border border-violet-200 px-2 py-1 text-sm text-violet-800 hover:bg-violet-50 disabled:opacity-50">
              Kameraya göndər
            </button>}
            <button type="button" disabled={busy} onClick={() => void save(vehicle)}
            className="rounded-lg px-2 py-1 text-sm text-red-700 hover:bg-red-50 disabled:opacity-50">
              Təyinatı bitir
            </button>
          </div>}
        </li>)}
      </ul>}
    {canCleanUp && retired.length > 0 && <details className="mt-3 text-sm">
      <summary className="cursor-pointer font-medium">Bitmiş nömrə təyinatları</summary>
      <ul className="mt-1 divide-y divide-slate-100">
        {retired.map(vehicle => <li key={vehicle.id} className="flex items-center justify-between gap-2 py-2">
          <span className="font-mono">{vehicle.plate}</span>
          <button type="button" disabled={busy} onClick={() => setRemoval(vehicle)}
            className="rounded-lg border border-slate-300 px-2 py-1">Kamera qeydlərini yoxla</button>
        </li>)}
      </ul>
    </details>}
    {!readOnly && !terminated && <form className="mt-3 flex flex-wrap items-end gap-2"
      onSubmit={event => { event.preventDefault(); void save() }}>
      <label className="flex-1 text-sm text-slate-700">Avtomobil nömrəsi
        <input value={plate} onChange={event => setPlate(event.target.value)} maxLength={32}
          placeholder="10-AB-123" className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 uppercase" />
      </label>
      <label className="text-sm text-slate-700">Təyinatın başlanğıcı
        <input type="date" value={effectiveFrom} max={todayInAppTimeZone()}
          onChange={event => setEffectiveFrom(event.target.value)}
          className="mt-1 block rounded-lg border border-slate-300 px-3 py-2" />
      </label>
      <button type="submit" disabled={busy || loading}
        className="rounded-lg bg-violet-700 px-4 py-2 text-sm text-white disabled:opacity-50">Nömrəni saxla</button>
    </form>}
    {error && <p role="alert" className="mt-2 text-sm text-red-700">{error}</p>}
    {transfer && <VehicleCameraTransferDialog employeeId={employeeId} vehicle={transfer}
      onClose={() => setTransfer(undefined)} />}
    {removal && <VehicleCameraRemovalDialog employeeId={employeeId} vehicle={removal}
      onClose={() => setRemoval(undefined)} />}
  </section>
}
