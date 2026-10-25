import { useEffect, useState } from 'react'
import { leaveApi, LeaveTypeUsage } from '../api/leaveApi.ts'

export default function LeaveUsageTable({ employeeId, refresh = 0 }: { employeeId?: number; refresh?: number }) {
  const [rows, setRows] = useState<LeaveTypeUsage[]>([])
  const [error, setError] = useState(false)
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    let active = true
    setLoading(true)
    leaveApi.getUsage(employeeId).then(res => {
      if (active) { setRows(res.data.data); setError(false) }
    }).catch(() => { if (active) setError(true) }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [employeeId, refresh])
  return <section className="rounded-lg border border-slate-200 bg-white p-4">
    <h3 className="mb-2 font-semibold">Məzuniyyət növləri üzrə istifadə və qalıq</h3>
    <p className="mb-3 text-xs text-slate-500">Təsdiqlənmiş günlər başlanğıc balans tarixindən hesablanır. Balansdan çıxılan növlər eyni ümumi məzuniyyət qalığını istifadə edir. Balansdan çıxılmayan növlərə limit təyin edilmir.</p>
    {error ? <p role="alert" className="text-sm text-red-600">Məzuniyyət uçotu yüklənmədi.</p> : loading ? <p className="text-sm">Yüklənir...</p> :
      <div className="max-h-96 overflow-auto"><table className="w-full text-left text-sm">
        <thead className="bg-slate-50"><tr>{[...(!employeeId ? ['Əməkdaş'] : []), 'Məzuniyyət növü', 'İstifadə olunan', 'Qalan balans'].map(h => <th key={h} className="p-3">{h}</th>)}</tr></thead>
        <tbody>{rows.filter(row => employeeId || row.usedDays > 0).map(row => <tr className="border-t" key={`${row.employeeId}-${row.leaveTypeId}`}>
          {!employeeId && <td className="p-3">{row.employeeName}<div className="text-xs text-slate-500">{row.employeeCode}</div></td>}
          <td className="p-3">{row.leaveTypeName} ({row.shortName})</td><td className="p-3">{row.usedDays} gün</td>
          <td className="p-3">{!row.deductFromBalance ? 'Limitsiz' : row.remainingDays == null ? 'Qalıq daxil edilməyib' : `${row.remainingDays} gün`}</td>
        </tr>)}</tbody>
      </table>{!rows.some(row => employeeId || row.usedDays > 0) && <p className="p-3 text-sm text-slate-500">Məzuniyyət qeydi yoxdur.</p>}</div>}
  </section>
}
