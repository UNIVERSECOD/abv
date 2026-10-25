import { useEffect, useState } from 'react'
import AttendraBrand from '../components/AttendraBrand.tsx'
import { useAuthStore } from '../store/authStore.ts'

// This page deliberately has no privileged API calls. Its short-lived capability can only read status.
export default function BackupRestoreProgressPage() {
  const [state, setState] = useState('RUNNING')
  const [message, setMessage] = useState('Bərpa davam edir. Serverin yenidən açılması gözlənilir...')
  useEffect(() => {
    let cancelled = false
    let timer: ReturnType<typeof setTimeout>
    let job: { id: string; progressToken: string }
    try {
      job = JSON.parse(sessionStorage.getItem('attendra-backup-operation') || 'null')
      if (!job?.id || !job?.progressToken) throw new Error()
    } catch {
      setState('ERROR')
      setMessage('Aktiv bərpa sorğusu tapılmadı.')
      return
    }
    const poll = async () => {
      try {
        const response = await fetch(`/api/backup-progress/${encodeURIComponent(job.id)}`, {
          headers: { 'X-Backup-Progress': job.progressToken },
          cache: 'no-store',
        })
        if (response.ok) {
          const result = await response.json()
          if (cancelled) return
          setState(result.data.state)
          setMessage(result.data.message)
          if (['SUCCESS', 'ERROR'].includes(result.data.state)) return
        } else if (response.status === 400) {
          if (cancelled) return
          setState('ERROR')
          setMessage(
            'Status keçidi etibarlı deyil və ya vaxtı bitib. Yenidən daxil olub administratorla əməliyyatın vəziyyətini yoxlayın.',
          )
          return
        }
      } catch {
        /* Backend and JWT keys may be changing. The worker continues independently. */
      }
      if (!cancelled) timer = setTimeout(() => void poll(), 2500)
    }
    void poll()
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [])
  const login = () => {
    sessionStorage.removeItem('attendra-backup-operation')
    useAuthStore.getState().logout()
    window.location.assign('/login')
  }
  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-50 p-5">
      <section className="w-full max-w-xl space-y-5 rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
        <AttendraBrand size="header" showWordmark />
        <h1 className="text-xl font-semibold">Backupdan bərpa</h1>
        <p
          role="status"
          className={`rounded-lg p-4 text-sm ${state === 'ERROR' ? 'bg-red-50 text-red-700' : state === 'SUCCESS' ? 'bg-emerald-50 text-emerald-700' : 'bg-purple-50 text-purple-700'}`}
        >
          {message}
        </p>
        <p className="text-sm text-slate-500">
          Cari vəziyyətin tam backupu alınmadan məlumatlar dəyişdirilmir. Proses backend dayansa və ya bu səhifə
          bağlansa da serverdə davam edir.
        </p>
        {['SUCCESS', 'ERROR'].includes(state) && (
          <button onClick={login} className="rounded-lg bg-purple-600 px-4 py-2 text-sm text-white">
            Yenidən daxil ol
          </button>
        )}
      </section>
    </main>
  )
}
