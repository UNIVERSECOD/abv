import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { attendanceApi } from '../api/attendanceApi.ts'
import { AttendanceOverviewRow } from '../types'
import { defaultAttendanceReportFilters } from '../utils/attendanceReportFilters.ts'

export default function LeaveAttendanceWarning() {
  const [rows, setRows] = useState<AttendanceOverviewRow[]>([])
  const [total, setTotal] = useState(0)
  const [error, setError] = useState(false)
  useEffect(() => {
    let active = true
    const load = async () => {
      try {
        const response = await attendanceApi.getOverview({ ...defaultAttendanceReportFilters(),
          status: 'WORKING_ON_LEAVE', page: 0, size: 5 })
        if (!active) return
        setRows(response.data.data.content)
        setTotal(response.data.data.totalElements)
        setError(false)
      } catch { if (active) setError(true) }
    }
    void load()
    const timer = window.setInterval(() => void load(), 60000)
    return () => { active = false; window.clearInterval(timer) }
  }, [])
  if (error) return <p role="alert" className="mb-6 rounded-xl bg-amber-50 p-4 text-sm text-amber-800">Məzuniyyətdə işə gələnlər barədə məlumat yüklənmədi.</p>
  if (!total) return null
  return <section role="status" className="mb-6 rounded-xl border border-amber-200 bg-amber-50 p-5">
    <h2 className="font-semibold text-amber-900">Məzuniyyətdə olan əməkdaş işə gəlib · {total} nəfər</h2>
    <ul className="my-3 divide-y divide-amber-100">{rows.map(row =>
      <li key={row.employeePk} className="py-2 text-sm text-amber-900">{row.fullName} · {row.employeeId}</li>)}</ul>
    <Link className="text-sm font-semibold text-purple-700" to="/reports?status=WORKING_ON_LEAVE">Hamısına bax →</Link>
  </section>
}
