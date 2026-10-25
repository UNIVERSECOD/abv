import { useCallback, useEffect, useRef, useState } from 'react'
import client from '../api/client.ts'
import { ApiResponse } from '../types'
import { getApiErrorMessage } from '../utils/apiError.ts'
import { toast } from '../store/toastStore.ts'
import { useAuthStore } from '../store/authStore.ts'

interface Operation {
  id: string
  state: string
  message: string
  preview?: { createdAt: string; files: number; bytes: number; mode: string; configurationIncluded: boolean }
  progressToken?: string
}
type Active = { id: string; restoring: boolean; progressToken?: string }
const base = '/settings/backup/operations'
const cacheKey = 'attendra-backup-operation'

export default function BackupOperationsPanel() {
  const [operation, setOperation] = useState<Operation | null>(null)
  const [active, setActive] = useState<Active | null>(() => {
    try {
      return JSON.parse(sessionStorage.getItem(cacheKey) || 'null')
    } catch {
      return null
    }
  })
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const file = useRef<HTMLInputElement>(null)
  const downloaded = useRef('')
  const inFlight = active !== null && !['READY', 'SUCCESS', 'ERROR'].includes(operation?.state || '')

  useEffect(() => {
    if (active) sessionStorage.setItem(cacheKey, JSON.stringify(active))
    else sessionStorage.removeItem(cacheKey)
  }, [active])

  useEffect(() => {
    if (!active || ['READY', 'SUCCESS', 'ERROR'].includes(operation?.state || '')) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const poll = async () => {
      try {
        let next: Operation
        if (active.restoring) {
          const response = await fetch(`/api/backup-progress/${active.id}`, {
            headers: { 'X-Backup-Progress': active.progressToken || '' },
            cache: 'no-store',
          })
          if (!response.ok) throw new Error('Status hələ əlçatan deyil')
          next = ((await response.json()) as ApiResponse<Operation>).data
        } else {
          next = (await client.get<ApiResponse<Operation>>(`${base}/${active.id}`)).data.data
        }
        if (stopped) return
        setError('')
        setOperation(next)
        if (next.state === 'SUCCESS')
          toast.success(active.restoring ? 'Bərpa tamamlandı' : 'Backup hazırdır', next.message)
        if (next.state === 'ERROR') toast.error('Backup əməliyyatı tamamlanmadı', next.message)
      } catch {
        if (!stopped)
          setError(
            'Server backup/bərpa zamanı müvəqqəti əlçatmaz ola bilər. Status avtomatik yenilənəcək; səhifəni açıq saxlayın.',
          )
      }
      if (!stopped) timer = setTimeout(() => void poll(), 2500)
    }
    void poll()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [active, operation?.state])

  const start = async (upload?: File) => {
    if (upload && (!upload.name.toLowerCase().endsWith('.zip') || upload.size > 4 * 1024 ** 3)) {
      setError('4 GB-dan kiçik Attendra ZIP backup faylı seçin')
      return
    }
    setBusy(true)
    setError('')
    setConfirmed(false)
    try {
      const response = upload
        ? await client.post<ApiResponse<Operation>>(`${base}/inspect`, upload, {
            headers: { 'Content-Type': 'application/zip' },
          })
        : await client.post<ApiResponse<Operation>>(base)
      setOperation(response.data.data)
      setActive({ id: response.data.data.id, restoring: false })
    } catch (e) {
      setError(getApiErrorMessage(e, 'Backup əməliyyatı başladılmadı'))
    } finally {
      setBusy(false)
      if (file.current) file.current.value = ''
    }
  }

  const restore = async () => {
    if (!operation || !confirmed) return
    setBusy(true)
    setError('')
    try {
      const response = await client.post<ApiResponse<Operation>>(`${base}/${operation.id}/restore`, { confirmed: true })
      const next = response.data.data
      sessionStorage.setItem(
        cacheKey,
        JSON.stringify({ id: next.id, restoring: true, progressToken: next.progressToken }),
      )
      window.location.assign('/backup-progress')
    } catch (e) {
      setError(getApiErrorMessage(e, 'Bərpa başladılmadı'))
    } finally {
      setBusy(false)
    }
  }

  const download = useCallback(async () => {
    if (!operation) return
    setBusy(true)
    try {
      const response = await client.get<Blob>(`${base}/${operation.id}/download`, { responseType: 'blob' })
      const url = URL.createObjectURL(response.data)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `attendra-backup-${operation.id}.zip`
      anchor.click()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    } catch (e) {
      setError(getApiErrorMessage(e, 'Backup endirilmədi'))
    } finally {
      setBusy(false)
    }
  }, [operation])

  useEffect(() => {
    if (operation?.state === 'SUCCESS' && !active?.restoring && downloaded.current !== operation.id) {
      downloaded.current = operation.id
      void download()
    }
  }, [operation, active?.restoring, download])

  const loginAgain = () => {
    sessionStorage.removeItem(cacheKey)
    useAuthStore.getState().logout()
    window.location.assign('/login')
  }

  return (
    <section className="mt-6 space-y-4 border-t border-gray-100 pt-6" aria-label="Backup yaratma və bərpa">
      <h3 className="font-semibold text-gray-800">Tam sistem backupu və bərpa</h3>
      <p className="text-sm text-gray-500">
        Hər iki baza, əməkdaş fotoları və konfiqurasiyalar daxildir. Uyğun snapshot üçün backend və ISAPI qısa müddətə
        dayandırılır. Arxiv məxfi məlumatlar daşıyır; onu qorunan yerdə saxlayın.
      </p>
      <div className="flex flex-wrap gap-3">
        <button
          disabled={busy || inFlight}
          onClick={() => void start()}
          className="rounded-lg bg-purple-600 px-4 py-2 text-sm text-white disabled:opacity-50"
        >
          Backup yarat və endir
        </button>
        <button
          disabled={busy || inFlight}
          onClick={() => file.current?.click()}
          className="rounded-lg border border-purple-200 px-4 py-2 text-sm text-purple-700 disabled:opacity-50"
        >
          Backupdan bərpa et
        </button>
        <input
          ref={file}
          type="file"
          accept=".zip"
          className="hidden"
          aria-label="Attendra backup ZIP faylı"
          onChange={(e) => {
            if (e.target.files?.[0]) void start(e.target.files[0])
          }}
        />
      </div>
      {busy && <p className="text-sm text-gray-500">Sorğu hazırlanır / fayl yüklənir...</p>}
      {operation && (
        <div className="rounded-lg bg-slate-50 p-4 text-sm" role="status">
          <p>{operation.message}</p>
          <p className="mt-1 text-xs text-gray-500">Əməliyyat: {operation.id}</p>
        </div>
      )}
      {operation?.state === 'READY' && operation.preview && (
        <div className="space-y-3 rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm">
          <p className="font-semibold">Bərpa önizləməsi</p>
          <p>
            Backup tarixi: {operation.preview.createdAt} · {operation.preview.files} fayl ·{' '}
            {(operation.preview.bytes / 1024 ** 2).toFixed(1)} MB
          </p>
          <p>
            Cari məlumatlar backupdakı vəziyyətlə əvəz ediləcək. Əvvəl cari bazaların, fotoların və konfiqurasiyaların
            tam backupu avtomatik saxlanılacaq. Uğursuz bərpada əvvəlki vəziyyət geri qaytarılacaq.
          </p>
          <p>
            Proqramın açılması üçün bu kompüterin portları, quraşdırma yolları, baza bağlantı şifrəsi və backup qovluğu
            saxlanılır. Backupdakı tətbiq açarları və digər tətbiq konfiqurasiyaları bərpa edilir.
          </p>
          <p>
            Bərpadan sonra backupdakı istifadəçi adı və şifrə ilə yenidən daxil olmaq lazımdır. Cihaz silmə növbəsi
            administrator yoxlamasına qədər dayandırılacaq.
          </p>
          <label className="flex items-start gap-2">
            <input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />
            <span>Bu backupa etibar edirəm və cari məlumatların əvəzlənməsini təsdiqləyirəm.</span>
          </label>
          <button
            disabled={!confirmed || busy}
            onClick={() => void restore()}
            className="rounded-lg bg-red-600 px-4 py-2 text-white disabled:opacity-50"
          >
            Cari backupu al və bərpa et
          </button>
        </div>
      )}
      {operation?.state === 'SUCCESS' && !active?.restoring && (
        <button
          disabled={busy}
          onClick={() => void download()}
          className="rounded-lg bg-emerald-600 px-4 py-2 text-sm text-white"
        >
          Hazır backupu endir
        </button>
      )}
      {['SUCCESS', 'ERROR'].includes(operation?.state || '') && active?.restoring && (
        <button onClick={loginAgain} className="rounded-lg border border-purple-200 px-4 py-2 text-sm text-purple-700">
          Yenidən daxil ol
        </button>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}
    </section>
  )
}
