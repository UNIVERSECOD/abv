import { useEffect, useRef, useState } from 'react'
import { deviceApi } from '../api/deviceApi'
import { transportApi, type CameraTransferPreview, type VehiclePlate } from '../api/transportApi'
import { doorRoleLabel } from '../i18n/labels'
import { toast } from '../store/toastStore'
import type { DeviceConfig } from '../types'
import { getApiErrorMessage } from '../utils/apiError'

export default function VehicleCameraTransferDialog({ employeeId, vehicle, onClose }: {
  employeeId: number
  vehicle: VehiclePlate
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [cameras, setCameras] = useState<DeviceConfig[]>([])
  const [selected, setSelected] = useState('')
  const [preview, setPreview] = useState<CameraTransferPreview>()
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    if (dialog.current && !dialog.current.open) dialog.current.showModal()
    deviceApi.getAll().then(response => {
      if (active) setCameras(response.data.data.filter(device => device.anpr && device.status === 'ACTIVE'))
    }).catch(reason => {
      if (active) setError(getApiErrorMessage(reason, 'Kameralar yüklənmədi.'))
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  const submit = async () => {
    if (busy || !selected) return
    setBusy(true)
    setError('')
    try {
      if (!preview) {
        const response = await transportApi.previewCameraTransfer(employeeId, vehicle.id, Number(selected))
        setPreview(response.data.data)
        return
      }
      await transportApi.confirmCameraTransfer(employeeId, vehicle.id, preview.token)
      toast.success(preview.operation === 'unchanged' ? 'Nömrə kamerada artıq mövcuddur' : 'Nömrə kameraya yazıldı')
      dialog.current?.close()
    } catch (reason) {
      setPreview(undefined)
      setError(getApiErrorMessage(reason, 'Kamera əməliyyatı tamamlanmadı.'))
    } finally {
      setBusy(false)
    }
  }

  const list = preview?.listType === 'blockList' ? 'Qadağa siyahısı' : 'İcazə siyahısı'
  return <dialog ref={dialog} aria-labelledby="vehicle-camera-transfer-title" onClose={onClose}
    onCancel={event => { if (busy) event.preventDefault() }}
    className="w-full max-w-md rounded-2xl p-0 text-slate-900 shadow-xl backdrop:bg-slate-950/50">
    <form className="space-y-4 p-5" onSubmit={event => { event.preventDefault(); void submit() }}>
      <header>
        <h3 id="vehicle-camera-transfer-title" className="text-lg font-semibold">Kameraya göndər</h3>
        <p className="mt-1 text-sm text-slate-600">Nömrə Attendra-da saxlanılıb. Göndərmək üçün kameranı seçib yoxlayın.</p>
      </header>
      <label className="block text-sm font-medium">Kamera
        <select value={selected} disabled={busy || loading} onChange={event => {
          setSelected(event.target.value)
          setPreview(undefined)
          setError('')
        }} className="mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2">
          <option value="">{loading ? 'Kameralar yüklənir...' : 'Kamera seçin'}</option>
          {cameras.map(camera => <option value={camera.id} key={camera.id}>
            {camera.deviceName} · {doorRoleLabel(camera.doorRole)}
          </option>)}
        </select>
      </label>
      {!loading && !error && !cameras.length && <p className="text-sm text-slate-600">Aktiv ANPR kamera yoxdur.</p>}
      {preview && <dl className="grid grid-cols-2 gap-2 rounded-lg bg-slate-50 p-3 text-sm">
        <dt>Kamera və rol</dt><dd>{preview.cameraName} · {doorRoleLabel(preview.role)}</dd>
        <dt>IP və port</dt><dd className="break-all">{preview.endpoint}</dd>
        <dt>Əməkdaş</dt><dd>{preview.fullName}</dd>
        <dt>Avtomobil nömrəsi</dt><dd className="font-mono">{preview.plate}</dd>
        <dt>Kamera siyahısı</dt><dd>{list}</dd>
        <dt>Əməliyyat</dt><dd>{preview.operation === 'add' ? 'Yeni qeyd əlavə et'
          : preview.operation === 'modify' ? 'Mövcud qeyddə adı yenilə' : 'Eyni qeyd artıq mövcuddur'}</dd>
      </dl>}
      {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
      <div className="flex flex-wrap justify-end gap-2">
        <button type="button" disabled={busy} onClick={() => dialog.current?.close()}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm">Ləğv et</button>
        <button type="submit" disabled={busy || !selected}
          className="rounded-lg bg-violet-700 px-3 py-2 text-sm text-white disabled:opacity-50">
          {busy ? 'Gözləyin...' : preview ? 'Təsdiqlə və göndər' : 'Kameranı yoxla'}
        </button>
      </div>
    </form>
  </dialog>
}
