import { useEffect, useState } from 'react'
import Layout from '../components/Layout.tsx'
import { attendanceApi } from '../api/attendanceApi.ts'
import { OpenAttendanceSession, PaginatedResponse } from '../types'
import { formatAttendanceDateTime } from '../utils/dateTime.ts'

const pageSize = 20
const toInput = (value: string) => value ? value.slice(0, 16) : ''

export default function OpenSessionsPage() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState<PaginatedResponse<OpenAttendanceSession> | null>(null)
  const [loading, setLoading] = useState(true)
  const [refresh, setRefresh] = useState(0)
  const [editing, setEditing] = useState<OpenAttendanceSession | null>(null)
  const [checkIn, setCheckIn] = useState('')
  const [checkOut, setCheckOut] = useState('')
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    attendanceApi.getOpenSessions(page, pageSize)
      .then(response => setData(response.data?.data ?? null))
      .catch(() => setData(null))
      .finally(() => setLoading(false))
  }, [page, refresh])

  const edit = (session: OpenAttendanceSession) => {
    setEditing(session); setCheckIn(toInput(session.checkInTime)); setCheckOut(''); setReason(''); setError(null)
  }
  const save = async () => {
    if (!editing || !checkIn || !checkOut || !reason.trim()) return
    setSaving(true); setError(null)
    try {
      await attendanceApi.correctSession(editing.attendanceLogId, { checkInTime: checkIn, checkOutTime: checkOut, reason: reason.trim() })
      setEditing(null); setRefresh(value => value + 1)
    } catch { setError('Düzəliş saxlanılmadı') }
    finally { setSaving(false) }
  }

  return <Layout><div className="min-h-screen bg-slate-50 p-4 sm:p-8">
    <div className="mb-6"><h1 className="text-2xl font-bold text-slate-900">Açıq sessiyalar</h1><p className="mt-1 text-sm text-slate-500">Çıxış qeydi olmayan sessiyaları nəzərdən keçirin və lazım olduqda düzəldin.</p></div>
    <div className="overflow-hidden rounded-xl bg-white shadow-sm">
      <div className="border-b px-5 py-4 text-sm text-slate-500">Ümumi: <strong className="text-slate-800">{data?.totalElements ?? 0}</strong></div>
      {loading ? <div className="p-8 text-center text-slate-400">Yüklənir...</div>
        : !data?.content.length ? <div className="p-8 text-center text-slate-400">Açıq sessiya yoxdur</div>
          : <div className="divide-y">{data.content.map(session => <div key={session.attendanceLogId} className="flex flex-wrap items-center gap-4 px-5 py-4">
            <div className="min-w-[220px] flex-1"><p className="font-medium text-slate-900">{session.fullName}</p><p className="text-xs text-slate-500">{session.employeeId}</p></div>
            <div className="text-sm text-slate-600">{formatAttendanceDateTime(session.checkInTime)}</div>
            <span className={`rounded-full px-2 py-1 text-xs font-medium ${session.status === 'MISSING_EXIT' ? 'bg-red-50 text-red-700' : 'bg-blue-50 text-blue-700'}`}>{session.status === 'MISSING_EXIT' ? 'Çıxış yoxdur' : 'Açıq'}</span>
            <button onClick={() => edit(session)} className="text-sm font-medium text-purple-700">Manual düzəlt</button>
          </div>)}</div>}
      <div className="flex items-center justify-between border-t px-5 py-4">
        <button disabled={page === 0} onClick={() => setPage(value => value - 1)} className="rounded-lg border px-3 py-2 text-sm disabled:opacity-40">Əvvəlki</button>
        <span className="text-sm text-slate-500">Səhifə {page + 1} / {Math.max(data?.totalPages ?? 1, 1)}</span>
        <button disabled={!data || page + 1 >= data.totalPages} onClick={() => setPage(value => value + 1)} className="rounded-lg border px-3 py-2 text-sm disabled:opacity-40">Növbəti</button>
      </div>
    </div>
    {editing && <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"><div className="w-full max-w-md rounded-xl bg-white p-5 shadow-xl">
      <div className="flex justify-between"><div><h2 className="text-lg font-semibold">Sessiyanı düzəlt</h2><p className="text-sm text-slate-500">{editing.fullName}</p></div><button onClick={() => setEditing(null)} aria-label="Bağla">×</button></div>
      <div className="mt-5 space-y-4"><label className="block text-sm">Giriş vaxtı<input type="datetime-local" value={checkIn} onChange={e => setCheckIn(e.target.value)} className="mt-1 w-full rounded-lg border px-3 py-2" /></label><label className="block text-sm">Çıxış vaxtı<input type="datetime-local" value={checkOut} onChange={e => setCheckOut(e.target.value)} className="mt-1 w-full rounded-lg border px-3 py-2" /></label><label className="block text-sm">Düzəliş səbəbi<textarea maxLength={500} rows={3} value={reason} onChange={e => setReason(e.target.value)} className="mt-1 w-full rounded-lg border px-3 py-2" /></label>{error && <p className="text-sm text-red-600">{error}</p>}</div>
      <div className="mt-5 flex justify-end gap-3"><button onClick={() => setEditing(null)} className="rounded-lg border px-4 py-2">Ləğv et</button><button disabled={saving || !checkIn || !checkOut || !reason.trim()} onClick={() => void save()} className="rounded-lg bg-purple-600 px-4 py-2 text-white disabled:opacity-50">{saving ? 'Saxlanılır...' : 'Saxla'}</button></div>
    </div></div>}
  </div></Layout>
}
