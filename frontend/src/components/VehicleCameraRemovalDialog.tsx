import { useEffect, useRef, useState } from 'react'
import { deviceApi } from '../api/deviceApi'
import { transportApi, type CameraRemovalPreview, type VehiclePlate } from '../api/transportApi'
import { toast } from '../store/toastStore'
import { getApiErrorMessage } from '../utils/apiError'

type CameraResult = {
  id: number
  name: string
  preview?: CameraRemovalPreview
  status: string
  failed?: boolean
}

export default function VehicleCameraRemovalDialog({ employeeId, vehicle, onClose }: {
  employeeId: number
  vehicle: VehiclePlate
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [results, setResults] = useState<CameraResult[]>()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    if (dialog.current && !dialog.current.open) dialog.current.showModal()
  }, [])

  async function checkCameras() {
    setBusy(true)
    setResults(undefined)
    setError('')
    try {
      const { data } = await deviceApi.getAll()
      const cameras = data.data.filter(camera => camera.anpr)
      setResults(await Promise.all(cameras.map(async camera => {
        const row = { id: camera.id, name: camera.deviceName || `Kamera ${camera.id}` }
        if (camera.status !== 'ACTIVE') return { ...row, status: 'Kamera deaktivdir, yoxlanılmadı', failed: true }
        try {
          const { data: response } = await transportApi.previewCameraRemoval(employeeId, vehicle.id, camera.id)
          return { ...row, preview: response.data, status: response.data.token ? 'Silmək üçün hazırdır' : 'Qeyd tapılmadı' }
        } catch (reason) {
          return { ...row, status: getApiErrorMessage(reason, 'Kamera yoxlanılmadı'), failed: true }
        }
      })))
    } catch (reason) {
      setError(getApiErrorMessage(reason, 'Kameralar yüklənmədi.'))
    } finally {
      setBusy(false)
    }
  }

  async function removeRecords() {
    if (!results) return
    setBusy(true)
    let deleted = 0
    const updated: CameraResult[] = []
    for (const row of results) {
      const preview = row.preview
      if (!preview?.token) {
        updated.push(row)
        continue
      }
      const completed = { ...preview, token: null }
      try {
        await transportApi.confirmCameraRemoval(employeeId, vehicle.id, row.id, preview.token)
        updated.push({ ...row, preview: completed, status: 'Silindi' })
        deleted++
      } catch (reason) {
        updated.push({ ...row, preview: completed, failed: true,
          status: getApiErrorMessage(reason, 'Silinmənin nəticəsi məlum deyil. Kameranı yenidən yoxlayın.') })
      }
    }
    setResults(updated)
    setBusy(false)
    if (updated.some(row => row.failed)) {
      toast.warning(deleted ? 'Kamera təmizlənməsi qismən tamamlandı' : 'Kamera təmizlənməsi tamamlanmadı')
    } else if (deleted) {
      toast.success('Nömrə təsdiqlənmiş kameralardan silindi')
    }
  }

  return <dialog ref={dialog} aria-labelledby="vehicle-camera-removal-title" onClose={onClose}
    onCancel={event => { if (busy) event.preventDefault() }}
    className="w-full max-w-lg rounded-2xl p-5 text-slate-900 shadow-xl backdrop:bg-slate-950/50">
    <h3 id="vehicle-camera-removal-title" className="text-lg font-semibold">Kamera qeydlərini sil</h3>
    <p className="mt-1 text-sm text-slate-600">{vehicle.plate} nömrəsinin Attendra təyinatı bitib.</p>
    {results && <ul className="mt-3 max-h-[45vh] divide-y overflow-y-auto text-sm" aria-live="polite">
      {!results.length && <li>ANPR kamera qeydiyyatı yoxdur.</li>}
      {results.map(row => <li key={row.id} className="py-2">
        <strong>{row.preview?.cameraName ?? row.name}</strong>
        {row.preview && <div>{row.preview.endpoint}
          {row.preview.recordId && <> · İcazə siyahısı · Qeyd ID-si {row.preview.recordId}</>}
        </div>}
        <div className={row.failed ? 'text-red-700' : 'text-slate-600'}>{row.status}</div>
      </li>)}
    </ul>}
    {error && <p role="alert" className="mt-2 text-sm text-red-700">{error}</p>}
    <div className="mt-4 flex flex-wrap justify-end gap-2 text-sm">
      <button type="button" disabled={busy} onClick={() => dialog.current?.close()}
        className="rounded-lg border border-slate-300 px-3 py-2">Bağla</button>
      <button type="button" disabled={busy} onClick={() => void checkCameras()}
        className="rounded-lg border border-slate-300 px-3 py-2">{results ? 'Yenidən yoxla' : 'Kameraları yoxla'}</button>
      {results?.some(row => row.preview?.token) &&
        <button type="button" disabled={busy} onClick={() => void removeRecords()}
          className="rounded-lg bg-red-700 px-3 py-2 text-white disabled:opacity-50">Təsdiqlə və sil</button>}
    </div>
    {busy && <p role="status" className="mt-2 text-sm text-slate-600">Kamera cavabı gözlənilir...</p>}
  </dialog>
}
