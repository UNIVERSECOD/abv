import { useEffect, useMemo, useState } from 'react'
import Layout from '../components/Layout.tsx'
import { branchApi } from '../api/branchApi.ts'
import { departmentApi } from '../api/departmentApi.ts'
import { positionApi } from '../api/positionApi.ts'
import { tabelApi } from '../api/tabelApi.ts'
import { Branch, Department, Position, TabelMonthlyData } from '../types'
import { useDebounce } from '../hooks/useSearch.ts'
import { t } from '../i18n/index.ts'
import { toast } from '../store/toastStore.ts'

/** Sidebar Tailwind width classes */
const SIDEBAR_OPEN = 'w-full lg:w-64'
const SIDEBAR_CLOSED = 'w-10'

const monthOptions = [
  'Yanvar',
  'Fevral',
  'Mart',
  'Aprel',
  'May',
  'İyun',
  'İyul',
  'Avqust',
  'Sentyabr',
  'Oktyabr',
  'Noyabr',
  'Dekabr',
]

export default function TabelPage() {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const [branchId, setBranchId] = useState<number | ''>('')
  const [departmentId, setDepartmentId] = useState<number | ''>('')
  const [positionId, setPositionId] = useState<number | ''>('')
  const [search, setSearch] = useState('')
  const [branches, setBranches] = useState<Branch[]>([])
  const [departments, setDepartments] = useState<Department[]>([])
  const [positions, setPositions] = useState<Position[]>([])
  const [data, setData] = useState<TabelMonthlyData | null>(null)
  const [loading, setLoading] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [sidebarOpen, setSidebarOpen] = useState(true)
  const debouncedSearch = useDebounce(search, 300)
  const filters = useMemo(() => ({
    mode: 'STANDARD' as const,
    year,
    month,
    branchId: branchId === '' ? undefined : branchId,
    departmentId: departmentId === '' ? undefined : departmentId,
    positionId: positionId === '' ? undefined : positionId,
    search: debouncedSearch || undefined,
  }), [year, month, branchId, departmentId, positionId, debouncedSearch])
  const visibleDepartments = departments.filter((department) => branchId === '' || department.allAreas || department.branchId === branchId)
  const visiblePositions = positions.filter((position) => departmentId !== ''
    ? position.departmentId === departmentId
    : branchId === '' || visibleDepartments.some((department) => department.id === position.departmentId))

  const years = useMemo(() => {
    const currentYear = new Date().getFullYear()
    return [currentYear - 2, currentYear - 1, currentYear, currentYear + 1]
  }, [])

  useEffect(() => {
    let active = true
    const loadOptions = async () => {
      try {
        const [branchResponse, departmentResponse, positionResponse] = await Promise.all([
          branchApi.getAll(), departmentApi.getAll(), positionApi.getAll(),
        ])
        if (!active) return
        setBranches(branchResponse.data.data ?? [])
        setDepartments(departmentResponse.data.data ?? [])
        setPositions(positionResponse.data.data ?? [])
      } catch {
        if (active) toast.error('Filtr seçimləri yüklənmədi', 'Səhifəni yeniləyib təkrar yoxlayın.')
      }
    }
    void loadOptions()
    return () => { active = false }
  }, [])

  useEffect(() => {
    let active = true
    const loadTabel = async () => {
      setLoading(true)
      try {
        const res = await tabelApi.getMonthly(filters)
        if (!active) return
        if (res.data.data.calculationMode !== 'MCG') {
          setData(null)
          toast.error('MCG hesablaması aktiv deyil', 'Backend və frontend eyni MCG versiyası ilə yenilənməlidir.')
          return
        }
        setData(res.data.data)
        if (res.data.data.rows.some((row) => Object.values(row.mcgDaily ?? {}).some((day) => !day.sourceVerified))) {
          toast.warning('Bəzi günlər yalnız arxiv saatı ilə göstərilir', 'Giriş–çıxış qeydləri olmayan günlər üçün tam gün və overtime təsdiqlənmir. Ətraflı məlumat günün üzərində göstərilir.')
        }
      } catch {
        if (active) {
          setData(null)
          toast.error('Tabel yüklənmədi', 'Bir qədər sonra təkrar yoxlayın.')
        }
      } finally {
        if (active) setLoading(false)
      }
    }

    void loadTabel()
    return () => { active = false }
  }, [filters])

  const handleExport = async () => {
    setExporting(true)
    try {
      const res = await tabelApi.exportExcel(filters)
      const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
      const href = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = href
      anchor.download = `abv-tabel-${year}-${String(month).padStart(2, '0')}.xlsx`
      anchor.click()
      URL.revokeObjectURL(href)
      toast.success('ABV Tabel Excel faylı hazırlandı')
    } catch {
      toast.error('Excel faylı hazırlanmadı', 'Bir qədər sonra təkrar yoxlayın.')
    } finally {
      setExporting(false)
    }
  }

  return (
    <Layout>
      <div className="p-6 lg:p-8">
        <div className="flex flex-col gap-4 lg:flex-row">
          {/* Collapsible sidebar */}
          <div className={`flex-shrink-0 transition-all duration-200 ${sidebarOpen ? SIDEBAR_OPEN : SIDEBAR_CLOSED}`}>
            {sidebarOpen ? (
              <div className="space-y-4 rounded-2xl bg-white p-4 shadow-sm h-fit">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-slate-700">Arxiv</h2>
                  <button
                    onClick={() => setSidebarOpen(false)}
                    className="p-1 rounded hover:bg-slate-100 text-slate-500"
                    title="Gizlət"
                  >
                    <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 19l-7-7 7-7m8 14l-7-7 7-7" />
                    </svg>
                  </button>
                </div>

                <div>
                  <label className="mb-1 block text-xs text-slate-500">İl</label>
                  <select value={year} onChange={(e) => setYear(Number(e.target.value))} className="w-full rounded-lg border px-3 py-2">
                    {years.map((item) => (
                      <option key={item} value={item}>{item}</option>
                    ))}
                  </select>
                </div>

                <div>
                  <label className="mb-1 block text-xs text-slate-500">Ay</label>
                  <select value={month} onChange={(e) => setMonth(Number(e.target.value))} className="w-full rounded-lg border px-3 py-2">
                    {monthOptions.map((label, index) => (
                      <option key={label} value={index + 1}>{label}</option>
                    ))}
                  </select>
                </div>

                <div className="rounded-xl border border-indigo-100 bg-indigo-50 p-3">
                  <p className="text-sm font-semibold text-indigo-700">{monthOptions[month - 1]} {year}</p>
                  <p className="mt-1 text-xs text-indigo-600">İşçi sayı: {data?.employees ?? 0}</p>
                  <p className="text-xs text-indigo-600">Gün sayı: {data?.daysInMonth ?? 0}</p>
                </div>
              </div>
            ) : (
              <div className="rounded-2xl bg-white p-2 shadow-sm flex flex-col items-center gap-2">
                <button
                  onClick={() => setSidebarOpen(true)}
                  className="p-1 rounded hover:bg-slate-100 text-slate-500"
                  title="Arxivi aç"
                >
                  <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 5l7 7-7 7M5 5l7 7-7 7" />
                  </svg>
                </button>
                <span className="text-xs text-slate-400 [writing-mode:vertical-lr] rotate-180 select-none">
                  {monthOptions[month - 1]} {year}
                </span>
              </div>
            )}
          </div>

          {/* Main content */}
          <section className="flex-1 min-w-0 space-y-4">
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <h1 className="text-2xl font-bold text-slate-900">ABV — Standart tabel</h1>
                <p className="mt-1 text-sm text-slate-500">İşlənmiş vaxt tam saata yuvarlaqlaşdırılır: 00–30 dəqiqə aşağı, 31–59 dəqiqə yuxarı (07:20 → 7, 07:31 → 8). Aylıq cəm gündəlik yuvarlaq saatların cəmidir.</p>
              </div>
              <button onClick={handleExport} disabled={!data || exporting || loading || search !== debouncedSearch} className="rounded-lg bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50">
                {t('reports.excelExport')}
              </button>
            </div>

            <div className="grid gap-3 rounded-2xl bg-white p-4 shadow-sm md:grid-cols-2 xl:grid-cols-4">
              <input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Ad və ya FIN axtar"
                className="rounded-lg border px-3 py-2"
              />
              <select aria-label="Ərazi" value={branchId} onChange={(e) => {
                setBranchId(e.target.value ? Number(e.target.value) : '')
                setDepartmentId('')
                setPositionId('')
              }} className="rounded-lg border px-3 py-2">
                <option value="">Bütün ərazilər</option>
                {branches.map((branch) => (
                  <option key={branch.id} value={branch.id}>{branch.name}</option>
                ))}
              </select>
              <select aria-label="Departament" value={departmentId} onChange={(e) => {
                setDepartmentId(e.target.value ? Number(e.target.value) : '')
                setPositionId('')
              }} className="rounded-lg border px-3 py-2">
                <option value="">Bütün departamentlər</option>
                {visibleDepartments.map((department) => (
                  <option key={department.id} value={department.id}>{department.departmentName}</option>
                ))}
              </select>
              <select aria-label="Vəzifə" value={positionId} onChange={(e) => setPositionId(e.target.value ? Number(e.target.value) : '')} className="rounded-lg border px-3 py-2">
                <option value="">Bütün vəzifələr</option>
                {visiblePositions.map((position) => (
                  <option key={position.id} value={position.id}>{position.positionName}</option>
                ))}
              </select>
            </div>

            <div className="overflow-x-auto rounded-2xl bg-white shadow-sm">
              <table className="min-w-max border-collapse text-sm">
                <thead>
                  <tr className="bg-slate-50 text-xs uppercase text-slate-500">
                    <th className="sticky left-0 z-30 min-w-[50px] max-w-[50px] border-b bg-slate-50 px-3 py-3 text-left truncate">s/s</th>
                    <th className="sticky left-[50px] z-30 min-w-[110px] max-w-[110px] border-b bg-slate-50 px-3 py-3 text-left truncate">FIN kod</th>
                    <th className="sticky left-[160px] z-30 min-w-[200px] max-w-[200px] border-b bg-slate-50 px-3 py-3 text-left truncate">Soyadı, adı</th>
                    <th className="sticky left-[360px] z-30 min-w-[140px] max-w-[140px] border-b bg-slate-50 px-3 py-3 text-left truncate">Vəzifəsi</th>
                    {Array.from({ length: data?.daysInMonth ?? 0 }, (_, day) => (
                      <th key={day + 1} className="sticky top-0 min-w-[52px] border-b bg-slate-50 px-2 py-3 text-center">{day + 1}</th>
                    ))}
                    <th className="sticky top-0 min-w-[100px] border-b bg-slate-50 px-3 py-3 text-center">İş günlərinin sayı</th>
                    <th className="sticky top-0 min-w-[100px] border-b bg-slate-50 px-3 py-3 text-center">İş saatlarının cəmi</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr><td colSpan={(data?.daysInMonth ?? 0) + 6} className="px-4 py-8 text-center text-slate-500">Yüklənir...</td></tr>
                  ) : (data?.rows.length ?? 0) === 0 ? (
                    <tr><td colSpan={(data?.daysInMonth ?? 0) + 6} className="px-4 py-8 text-center text-slate-400">Məlumat tapılmadı</td></tr>
                  ) : data?.rows.map((row, index) => (
                    <tr key={row.employeePk} className="border-b border-slate-100 text-slate-700">
                      <td className="sticky left-0 z-20 bg-white px-3 py-2 min-w-[50px] max-w-[50px]">{index + 1}</td>
                      <td className="sticky left-[50px] z-20 bg-white px-3 py-2 min-w-[110px] max-w-[110px] truncate" title={row.fin ?? '-'}>{row.fin ?? '-'}</td>
                      <td
                        className={`sticky left-[160px] z-20 bg-white px-3 py-2 min-w-[200px] max-w-[200px] truncate ${row.employmentStatus === 'TERMINATED' ? 'font-semibold text-red-600' : ''}`}
                        title={row.fullName}
                      >
                        {row.fullName}
                      </td>
                      <td className="sticky left-[360px] z-20 bg-white px-3 py-2 min-w-[140px] max-w-[140px] truncate" title={row.position}>{row.position}</td>
                      {Array.from({ length: data.daysInMonth }, (_, day) => {
                        const value = row.daily[String(day + 1)]
                        const isCode = typeof value === 'string'
                        const isEmpty = value === null || value === undefined
                        const color = isCode && Object.prototype.hasOwnProperty.call(data.leaveLegend ?? {}, value) ? 'font-semibold text-purple-700' : 'text-black'
                        return <td key={day + 1} className={`px-2 py-2 text-center ${color}`}>{isEmpty ? '' : value}</td>
                      })}
                      <td className="px-3 py-2 text-center font-medium">{row.workingDays}</td>
                      <td className="px-3 py-2 text-center font-medium">{row.totalHours}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex flex-wrap gap-4 border-t p-4 text-sm text-slate-600">
              {Object.entries(data?.leaveLegend ?? {}).map(([code, name]) => <span key={code}><strong>{code}</strong> — {name}</span>)}
            </div>
          </section>
        </div>
      </div>
    </Layout>
  )
}
