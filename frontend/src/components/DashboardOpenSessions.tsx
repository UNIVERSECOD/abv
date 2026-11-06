import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { attendanceApi } from '../api/attendanceApi.ts'
import { OpenAttendanceSession, PaginatedResponse } from '../types'
import { formatAttendanceDateTime } from '../utils/dateTime.ts'

export default function DashboardOpenSessions() {
  const [data, setData] = useState<PaginatedResponse<OpenAttendanceSession> | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    attendanceApi.getOpenSessions(0, 10)
      .then(response => setData(response.data?.data ?? null))
      .catch(() => setData(null))
      .finally(() => setLoading(false))
  }, [])

  return <section className="mb-6 rounded-xl bg-white p-5 shadow-sm">
    <div className="mb-4 flex items-center justify-between gap-3">
      <div>
        <h2 className="text-base font-semibold text-gray-800">Açıq sessiyalar</h2>
        <p className="text-xs text-gray-400">Çıxış qeydi gözlənilən ilk 10 əməkdaş</p>
      </div>
      <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-600">{data?.totalElements ?? 0}</span>
    </div>
    {loading ? <p className="text-sm text-gray-400">Yüklənir...</p>
      : !data?.content.length ? <p className="text-sm text-gray-400">Açıq sessiya yoxdur</p>
        : <div className="divide-y divide-slate-100">
          {data.content.map(session => <div key={session.attendanceLogId} className="flex items-center justify-between gap-3 py-2.5">
            <div className="min-w-0">
              <p className="truncate text-sm font-medium text-gray-800">{session.fullName}</p>
              <p className="text-xs text-gray-400">{session.employeeId} · {formatAttendanceDateTime(session.checkInTime)}</p>
            </div>
            <span className={`shrink-0 rounded-full px-2 py-1 text-xs font-medium ${session.status === 'MISSING_EXIT' ? 'bg-red-50 text-red-700' : 'bg-blue-50 text-blue-700'}`}>
              {session.status === 'MISSING_EXIT' ? 'Çıxış yoxdur' : 'Açıq'}
            </span>
          </div>)}
        </div>}
    {!!data && data.totalElements > 10 && <div className="mt-4 text-right"><Link to="/open-sessions" className="text-sm font-medium text-purple-700 hover:text-purple-900">Hamısına bax</Link></div>}
  </section>
}
