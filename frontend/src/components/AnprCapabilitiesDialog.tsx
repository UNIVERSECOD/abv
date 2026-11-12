import { useEffect, useRef, useState } from 'react'
import { deviceApi, type AnprCapabilities, type AnprCapability } from '../api/deviceApi'
import { getApiErrorMessage } from '../utils/apiError'

function Capability({ title, value }: { title: string; value: AnprCapability }) {
  const supported = value.status === 'SUPPORTED'
  const unsupported = value.status === 'UNSUPPORTED'
  return <div className="rounded-xl border border-slate-200 p-3">
    <div className="flex flex-wrap items-center justify-between gap-2">
      <h4 className="text-sm font-semibold">{title}</h4>
      <span className={`rounded-full px-2 py-1 text-xs font-medium ${supported ? 'bg-emerald-50 text-emerald-800'
        : unsupported ? 'bg-red-50 text-red-800' : 'bg-amber-50 text-amber-900'}`}>
        {supported ? 'Dəstəklənir' : unsupported ? 'Dəstəklənmir' : 'Təsdiqlənmədi'}
      </span>
    </div>
    <p className="mt-2 text-sm text-slate-600">{value.reason}</p>
  </div>
}

export default function AnprCapabilitiesDialog({ device, onClose }: {
  device: { id: number; deviceName?: string }
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [attempt, setAttempt] = useState(0)
  const [report, setReport] = useState<AnprCapabilities>()
  const [busy, setBusy] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    if (dialog.current && !dialog.current.open) dialog.current.showModal()
  }, [])

  useEffect(() => {
    let active = true
    const controller = new AbortController()
    setBusy(true)
    setError('')
    setReport(undefined)
    // Defer dispatch so React StrictMode's discarded effect does not duplicate the probe.
    const timer = window.setTimeout(() => {
      deviceApi.checkAnprCapabilities(device.id, controller.signal).then(response => {
        if (active) setReport(response.data.data)
      }).catch(reason => {
        if (active) setError(getApiErrorMessage(reason, 'İmkanları yoxlamaq alınmadı. Kamera qeydiyyatda saxlanılıb.'))
      }).finally(() => { if (active) setBusy(false) })
    }, 0)
    return () => { active = false; window.clearTimeout(timer); controller.abort() }
  }, [device.id, attempt])

  return <dialog ref={dialog} onClose={onClose} aria-labelledby="anpr-capabilities-title"
    className="w-full max-w-lg rounded-2xl p-0 text-slate-900 shadow-xl backdrop:bg-slate-950/50">
    <div className="space-y-4 p-5">
      <header>
        <h3 id="anpr-capabilities-title" className="text-lg font-semibold">ANPR imkanlarının yoxlanması</h3>
        <p className="mt-1 text-sm text-slate-600">{device.deviceName || 'ANPR kamera'}</p>
        <p className="mt-2 text-sm text-slate-600">Yalnız oxuma yoxlamasıdır. Kamerada nömrə, arxiv və ayarlar dəyişdirilmir.</p>
      </header>
      <div aria-live="polite" aria-busy={busy}>
        {busy && <p className="py-5 text-sm text-slate-600">Kameranın imkanları yoxlanılır...</p>}
        {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
        {report && <div className="space-y-3">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt>Model</dt><dd className="break-all">{report.model || 'Alınmadı'}</dd>
            <dt>Firmware</dt><dd className="break-all">{report.firmware || 'Alınmadı'}</dd>
            <dt>Yoxlama vaxtı</dt><dd>{new Date(report.checkedAt).toLocaleString('az-AZ', { timeZone: 'Asia/Baku' })}</dd>
          </dl>
          <Capability title="Nömrə əlavə etmə və redaktə" value={report.plateWrite} />
          <Capability title="Ad-soyadın yazılması" value={report.ownerName} />
          <Capability title="Arxivdən oxuma" value={report.archiveRead} />
        </div>}
      </div>
      <footer className="flex flex-wrap justify-end gap-2">
        <button type="button" onClick={() => dialog.current?.close()}
          className="rounded-lg border border-slate-300 px-3 py-2 text-sm">Bağla</button>
        <button type="button" disabled={busy} onClick={() => setAttempt(value => value + 1)}
          className="rounded-lg bg-violet-700 px-3 py-2 text-sm text-white disabled:opacity-50">Yenidən yoxla</button>
      </footer>
    </div>
  </dialog>
}
