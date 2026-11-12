import { useEffect, useState } from 'react'
import Layout from '../components/Layout'
import { branchApi } from '../api/branchApi'
import { transportApi, type TransportPassage } from '../api/transportApi'
import { getApiErrorMessage } from '../utils/apiError'
import { todayInAppTimeZone } from '../utils/dateTime'
import type { Branch } from '../types'

const results: Record<string, string> = {
  SELECTED: 'Davamiyyətə daxil edilib',
  NOT_SELECTED: 'Yekun vaxt kimi seçilməyib',
  UNMATCHED: 'Uyğun sessiya tapılmayıb',
  BEFORE_ACTIVATION: 'Aktivləşmədən əvvəl',
  OUTSIDE_ACTIVATION: 'Aktiv dövrdən kənar',
  UNREADABLE_PLATE: 'Nömrə oxunmayıb',
  LOW_CONFIDENCE: 'Oxuma etibarı aşağıdır',
  UNASSIGNED_PLATE: 'Aktiv əməkdaşa təyin edilməyib',
}

const fieldClass = 'mt-1 block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm'

export default function TransportReportPage() {
  const [filters, setFilters] = useState(() => ({
    from: todayInAppTimeZone(), to: todayInAppTimeZone(), area: '', employee: '', plate: '',
  }))
  const [query, setQuery] = useState(() => ({ ...filters, page: 0 }))
  const [branches, setBranches] = useState<Branch[]>([])
  const [rows, setRows] = useState<TransportPassage[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [dateError, setDateError] = useState('')

  useEffect(() => {
    let active = true
    branchApi.getAll().then(response => { if (active) setBranches(response.data.data) }).catch(() => {})
    return () => { active = false }
  }, [])

  useEffect(() => {
    let active = true
    setLoading(true)
    setLoadError('')
    transportApi.report({
      from: query.from, to: query.to, areaId: query.area ? Number(query.area) : undefined,
      employee: query.employee.trim() || undefined, plate: query.plate.trim() || undefined, page: query.page,
    }).then(response => {
      if (!active) return
      setRows(response.data.data.rows)
      setHasMore(response.data.data.hasMore)
    }).catch(reason => {
      if (!active) return
      setRows([])
      setHasMore(false)
      setLoadError(getApiErrorMessage(reason, 'Nəqliyyat hesabatı yüklənmədi.'))
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [query])

  return <Layout><div className="space-y-5 p-4 md:p-6">
    <header>
      <h1 className="text-2xl font-bold text-[#1f2d6b]">Nəqliyyat hesabatı</h1>
      <p className="mt-1 text-sm text-slate-600">ANPR keçidləri avtomatik oxunur. Saatlar Bakı vaxtı ilə göstərilir.</p>
    </header>
    <form className="grid grid-cols-1 gap-3 rounded-2xl bg-white p-4 sm:grid-cols-2 xl:grid-cols-6"
      onSubmit={event => {
        event.preventDefault()
        if (!filters.from || !filters.to || filters.from > filters.to) {
          setDateError('Başlanğıc və bitiş tarixlərini yoxlayın.')
          return
        }
        setDateError('')
        setQuery({ ...filters, page: 0 })
      }}>
      <label className="text-sm">Başlanğıc tarixi
        <input className={fieldClass} type="date" value={filters.from}
          onChange={event => setFilters({ ...filters, from: event.target.value })} />
        {dateError && <span role="alert" className="text-xs text-red-700">{dateError}</span>}
      </label>
      <label className="text-sm">Bitiş tarixi
        <input className={fieldClass} type="date" value={filters.to}
          onChange={event => setFilters({ ...filters, to: event.target.value })} />
      </label>
      <label className="text-sm">Ərazi
        <select className={fieldClass} value={filters.area} onChange={event => setFilters({ ...filters, area: event.target.value })}>
          <option value="">Bütün ərazilər</option>
          {branches.map(branch => <option key={branch.id} value={branch.id}>{branch.name}</option>)}
        </select>
      </label>
      <label className="text-sm">Əməkdaş
        <input className={fieldClass} maxLength={120} placeholder="Ad, soyad və ya kod" value={filters.employee}
          onChange={event => setFilters({ ...filters, employee: event.target.value })} />
      </label>
      <label className="text-sm">Avtomobil nömrəsi
        <input className={fieldClass} maxLength={64} placeholder="10-AB-123" value={filters.plate}
          onChange={event => setFilters({ ...filters, plate: event.target.value })} />
      </label>
      <button type="submit" className="self-end rounded-lg bg-violet-700 px-4 py-2 text-sm font-medium text-white">Axtar</button>
    </form>
    <section className="overflow-x-auto rounded-2xl bg-white" aria-busy={loading}>
      {loadError && <p role="alert" className="p-4 text-sm text-red-700">{loadError}</p>}
      <table className="w-full text-left text-sm">
        <thead className="bg-slate-50 text-slate-700"><tr>
          {['Keçid vaxtı', 'İstiqamət', 'Nömrə', 'Əməkdaş', 'Ərazi', 'Kamera', 'Oxuma etibarı', 'Nəticə'].map(label =>
            <th scope="col" key={label} className="whitespace-nowrap px-4 py-3">{label}</th>)}
        </tr></thead>
        <tbody className="divide-y divide-slate-100">
          {loading ? <tr><td colSpan={8} className="p-4">Yüklənir...</td></tr>
            : rows.length === 0 ? <tr><td colSpan={8} className="p-4">Keçid tapılmadı.</td></tr>
              : rows.map(row => <tr key={row.id}>
                <td className="whitespace-nowrap px-4 py-3">{row.time.replace('T', ' ').slice(0, 19)}</td>
                <td className="px-4 py-3">{row.direction === 'ENTRY' ? 'Giriş' : 'Çıxış'}</td>
                <td className="px-4 py-3 font-mono">{row.plate || '—'}</td>
                <td className="px-4 py-3">{row.employeeName || '—'}</td>
                <td className="px-4 py-3">{row.area || '—'}</td>
                <td className="px-4 py-3">{row.camera || '—'}</td>
                <td className="px-4 py-3">{row.confidence == null ? '—' : row.confidence + '%'}</td>
                <td className={`px-4 py-3 ${row.result === 'SELECTED' ? 'text-emerald-700' : 'text-slate-700'}`}>
                  {results[row.result] ?? row.result}
                </td>
              </tr>)}
        </tbody>
      </table>
    </section>
    <nav aria-label="Hesabat səhifələri" className="flex items-center justify-end gap-3 text-sm">
      <button disabled={loading || query.page === 0} className="rounded border px-3 py-2 disabled:opacity-40"
        onClick={() => setQuery({ ...query, page: query.page - 1 })}>Əvvəlki</button>
      <span>Səhifə {query.page + 1}</span>
      <button disabled={loading || !hasMore} className="rounded border px-3 py-2 disabled:opacity-40"
        onClick={() => setQuery({ ...query, page: query.page + 1 })}>Növbəti</button>
    </nav>
  </div></Layout>
}
