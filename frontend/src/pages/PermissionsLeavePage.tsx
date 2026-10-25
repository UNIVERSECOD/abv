import { useEffect, useMemo, useRef, useState } from 'react'
import Layout from '../components/Layout.tsx'
import SortableHeader from '../components/SortableHeader.tsx'
import { sortTableRows, TableSort } from '../utils/tableSort.ts'
import LeaveEmployeeSelect from '../components/LeaveEmployeeSelect.tsx'
import { allocateLeaveDebitDays, leaveMonthRange, leaveOverlapsPeriod, visibleLeaveTypes, leaveCalendarDays } from '../utils/leaveFilters.ts'
import LeaveTypesPanel from '../components/LeaveTypesPanel.tsx'
import PermissionAssignmentPage from './PermissionAssignmentPage.tsx'
import { leaveApi, LeaveBalance, LeavePayload, LeaveRequest, LeaveStatus, LeaveType } from '../api/leaveApi.ts'
import { employeeApi } from '../api/employeeApi.ts'
import { Employee } from '../types'
import { getApiErrorMessage } from '../utils/apiError.ts'
import { toast } from '../store/toastStore.ts'
import { DataImportError } from '../api/dataTransferApi.ts'

const statusLabels: Record<LeaveStatus, string> = {
  APPROVED: 'Təsdiqlənib',
  PENDING: 'Gözləmədə',
  REJECTED: 'Rədd edilib',
}
const inputClass = 'mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm'
const buttonClass =
  'rounded-md border border-slate-300 bg-white px-4 py-2 text-sm hover:bg-slate-50 disabled:opacity-50'

const leaveTypeLabel = (type: LeaveType) => type.leaveName

function LeaveModal({
  initial,
  employees,
  types,
  onSave,
  onClose,
}: {
  initial?: LeaveRequest
  employees: Employee[]
  types: LeaveType[]
  onSave: (payload: LeavePayload) => Promise<void>
  onClose: () => void
}) {
  const [employeeId, setEmployeeId] = useState(initial?.employeeId ?? 0)
  const choices = types.filter(t => !t.archived || t.id === initial?.leaveTypeId)
  const [leaveTypeId, setLeaveTypeId] = useState(initial?.leaveTypeId ?? 0)
  const [startDate, setStartDate] = useState(initial?.startDate ?? '')
  const [endDate, setEndDate] = useState(initial?.endDate ?? '')
  const [status, setStatus] = useState<LeaveStatus>(initial?.status ?? 'APPROVED')

  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const selectable = employees.filter(employee => employee.employmentStatus === 'ACTIVE' || employee.id === initial?.employeeId)

  const submit = async () => {
    const next: Record<string, string> = {}
    if (!employeeId) next.employee = 'Əməkdaş seçin'
    if (!leaveTypeId) next.type = 'Məzuniyyət növünü seçin'
    if (!startDate) next.startDate = 'Başlanğıc tarixini seçin'
    if (!endDate || endDate < startDate) next.endDate = 'Bitmə tarixi başlanğıcdan əvvəl ola bilməz'
    setErrors(next)
    if (Object.keys(next).length) return
    setSaving(true)
    try {
      await onSave({ employeeId, leaveTypeId, startDate, endDate, status })
      onClose()
    } catch (error) {
      setErrors({ period: getApiErrorMessage(error, 'Məzuniyyəti yadda saxlamaq mümkün olmadı') })
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="leave-title"
        className="max-h-[90vh] w-full max-w-xl overflow-y-auto rounded-lg bg-white p-6 shadow-xl"
      >
        <div className="flex items-center justify-between">
          <h2 id="leave-title" className="text-lg font-semibold">
            {initial ? 'Məzuniyyəti redaktə et' : 'Yeni məzuniyyət'}
          </h2>
          <button disabled={saving} onClick={onClose} aria-label="Bağla">
            ×
          </button>
        </div>
        <p className="mt-2 text-sm text-slate-500">
          Başlanğıc və bitmə günləri daxildir. Təsdiqlənmiş məzuniyyət Tabeldə növün qısaltması ilə göstərilir.
        </p>
        <div className="mt-4 space-y-4">
          <div>
            <LeaveEmployeeSelect employees={selectable} value={employeeId} onChange={setEmployeeId} disabled={saving} />
            {errors.employee && (
              <p role="alert" className="text-sm text-red-600">
                {errors.employee}
              </p>
            )}
          </div>
          <label className="block text-sm">
            Məzuniyyət növü
            <select className={inputClass} value={leaveTypeId} onChange={(e) => setLeaveTypeId(Number(e.target.value))}>
              <option value={0}>Seçin</option>
              {choices.map((t) => (
                <option key={t.id} value={t.id}>
                  {leaveTypeLabel(t)}
                </option>
              ))}
            </select>
            {errors.type && (
              <span role="alert" className="text-red-600">
                {errors.type}
              </span>
            )}
          </label>
          <p className="text-sm text-slate-500">
            Balansdan çıxılan növlər qalan balansdan çox təsdiqlənə bilməz. Hər növün istifadə günləri ayrıca sayılır. Tabeldə seçilmiş növün qısaltması göstərilir.
          </p>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <label className="text-sm">
              Başlanğıc tarixi
              <input
                className={inputClass}
                type="date"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
              />
              {errors.startDate && (
                <span role="alert" className="text-red-600">
                  {errors.startDate}
                </span>
              )}
            </label>
            <label className="text-sm">
              Bitmə tarixi
              <input
                className={inputClass}
                type="date"
                min={startDate}
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
              />
              {errors.endDate && (
                <span role="alert" className="text-red-600">
                  {errors.endDate}
                </span>
              )}
            </label>
          </div>
          {errors.period && (
            <p role="alert" className="text-sm text-red-600">
              {errors.period}
            </p>
          )}
          <label className="block text-sm">
            Vəziyyət
            <select className={inputClass} value={status} onChange={(e) => setStatus(e.target.value as LeaveStatus)}>
              {Object.entries(statusLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <button className={buttonClass} disabled={saving} onClick={onClose}>
            Bağla
          </button>
          <button
            disabled={saving}
            onClick={() => void submit()}
            className="rounded-md bg-purple-600 px-4 py-2 text-sm text-white disabled:opacity-50"
          >
            {saving ? 'Yadda saxlanılır...' : 'Yadda saxla'}
          </button>
        </div>
      </div>
    </div>
  )
}

function LeavesPanel() {
  const [balances, setBalances] = useState<LeaveBalance[]>([])
  const [leaves, setLeaves] = useState<LeaveRequest[]>([])
  const [employees, setEmployees] = useState<Employee[]>([])
  const [types, setTypes] = useState<LeaveType[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<TableSort | null>(null)
  const [month, setMonth] = useState('')
  const [from, setFrom] = useState('')
  const [through, setThrough] = useState('')
  const [status, setStatus] = useState('ALL')
  const [modal, setModal] = useState<{ initial?: LeaveRequest } | null>(null)
  const [importErrors, setImportErrors] = useState<DataImportError[]>([])
  const fileInput = useRef<HTMLInputElement>(null)

  const load = async () => {
    setLoading(true)
    setError('')
    try {
      const [leaveRes, typeRes, employeeRes, balanceRes] = await Promise.all([
        leaveApi.getAll(),
        leaveApi.getTypes(),
        employeeApi.getAll(0, 500),
        leaveApi.getBalances(),
      ])
      const staff = [...employeeRes.data.content]
      for (let page = 1; page < employeeRes.data.totalPages; page++) {
        const res = await employeeApi.getAll(page, 500)
        staff.push(...res.data.content)
      }
      setLeaves(leaveRes.data.data)
      setTypes(typeRes.data.data)
      setEmployees(staff)
      setBalances(balanceRes.data.data)
    } catch (e) {
      setError(getApiErrorMessage(e, 'Məzuniyyətləri yükləmək mümkün olmadı'))
    } finally {
      setLoading(false)
    }
  }
  useEffect(() => {
    void load()
  }, [])
  const filtered = useMemo(
    () =>
      leaves
        .filter(
          (l) =>
            (status === 'ALL' || l.status === status) &&
            leaveOverlapsPeriod(l.startDate, l.endDate, from, through) &&
            `${l.employeeName} ${l.employeeCode} ${l.finNumber} ${l.leaveTypeName}`
              .toLocaleLowerCase('az')
              .includes(search.toLocaleLowerCase('az')),
        ),
    [leaves, status, from, through, search],
  )

  const employeeById = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees])
  const balanceByEmployee = useMemo(() => new Map(balances.map(balance => [balance.employeeId, balance])), [balances])
  const visibleTypes = visibleLeaveTypes(types, leaves)
  const defaultRows = useMemo(() => sortTableRows(filtered, null, () => null), [filtered])
  const numberById = useMemo(() => new Map(defaultRows.map((leave, index) => [leave.id, index + 1])), [defaultRows])
  const debitByRequest = useMemo(() => allocateLeaveDebitDays(leaves,
    new Set(types.filter(type => type.deductFromBalance).map(type => type.id)),
    new Map(balances.map(balance => [balance.employeeId, balance.startDate]))), [leaves, types, balances])
  const chargedDays = (leave: LeaveRequest) => debitByRequest.get(leave.id) ?? 0
  const columns = [
    { key: 'no', label: 'No' }, { key: 'employee', label: 'Əməkdaş' }, { key: 'fin', label: 'FIN' },
    { key: 'start', label: 'Başlanğıc' }, { key: 'end', label: 'Bitmə' }, { key: 'status', label: 'Vəziyyət' },
    { key: 'balanceStart', label: 'Balansın başlanğıcı' }, { key: 'opening', label: 'Başlanğıc qalığı' },
    ...visibleTypes.map(type => ({ key: 'type:' + type.id, label: type.leaveName })),
    { key: 'charged', label: 'Balansdan çıxılan' }, { key: 'remaining', label: 'Qalan' },
  ]
  const sortedRows = sortTableRows(filtered, sort, (leave, key) => {
    const balance = balanceByEmployee.get(leave.employeeId)
    switch (key) {
      case 'no': return numberById.get(leave.id)
      case 'employee': return leave.employeeName
      case 'fin': return leave.finNumber || employeeById.get(leave.employeeId)?.finNumber
      case 'start': return leave.startDate
      case 'end': return leave.endDate
      case 'status': return statusLabels[leave.status]
      case 'balanceStart': return balance?.startDate
      case 'opening': return balance?.openingDays
      case 'charged': return chargedDays(leave)
      case 'remaining': return balance?.remainingDays
      default: return key === 'type:' + leave.leaveTypeId ? leaveCalendarDays(leave.startDate, leave.endDate) : null
    }
  })
  const changeSort = (key: string) => setSort(previous => ({ key, direction: previous?.key === key && previous.direction === 'asc' ? 'desc' : 'asc' }))
  const periodError = from && through && from > through ? 'Bitmə tarixi başlanğıcdan əvvəl ola bilməz' : ''

  const download = async (template: boolean) => {
    setBusy(true)
    try {
      const res = await leaveApi.download(template)
      const url = URL.createObjectURL(res.data)
      const a = document.createElement('a')
      a.href = url
      a.download = template ? 'mezuniyyet_sablon.xlsx' : 'mezuniyyetler.xlsx'
      a.click()
      URL.revokeObjectURL(url)
      toast.success(template ? 'Şablon endirildi' : 'Ümumi cədvəl ixrac edildi')
    } catch (e) {
      toast.error('Fayl endirilmədi', getApiErrorMessage(e, 'Excel faylını yaratmaq mümkün olmadı'))
    } finally {
      setBusy(false)
    }
  }
  const importFile = async (file: File) => {
    setBusy(true)
    setImportErrors([])
    try {
      const res = await leaveApi.import(file)
      const result = res.data.data
      setImportErrors(result.errors)
      if (result.successful) {
        setSort(null)
        toast.success('Import tamamlandı', `${result.importedRows} məzuniyyət əlavə edildi`)
        await load()
      } else toast.error('Import edilmədi', 'Sətir xətalarını düzəldin. Heç bir məlumat yazılmadı.')
    } catch (e) {
      toast.error('Import edilmədi', getApiErrorMessage(e, 'Faylı yoxlayın'))
    } finally {
      setBusy(false)
      if (fileInput.current) fileInput.current.value = ''
    }
  }
  const reject = async (leave: LeaveRequest) => {
    if (!window.confirm(`${leave.employeeName} üçün məzuniyyət ləğv edilsin?`)) return
    setBusy(true)
    try {
      await leaveApi.setStatus(leave.id, 'REJECTED')
      await load()
      toast.success('Məzuniyyət ləğv edildi')
    } catch (e) {
      toast.error('Məzuniyyət ləğv edilmədi', getApiErrorMessage(e, 'Yenidən cəhd edin'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-5 p-4 sm:p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-xl font-semibold">Məzuniyyətlər</h2>
          <p className="mt-1 text-sm text-slate-500">Təsdiqlənmiş günlər Tabeldə növün qısaltması ilə göstərilir.</p>
        </div>
        <div className="flex flex-wrap gap-2">
          <button className={buttonClass} disabled={busy || loading} onClick={() => void download(true)}>
            Şablon endir
          </button>
          <button className={buttonClass} disabled={busy || loading} onClick={() => fileInput.current?.click()}>
            Excel import
          </button>
          <button className={buttonClass} disabled={busy || loading} onClick={() => void download(false)}>
            Ümumi cədvəli ixrac et
          </button>
          <button
            className="rounded-md bg-purple-600 px-4 py-2 text-sm text-white disabled:opacity-50"
            disabled={busy || loading || !types.some(t => !t.archived)}
            onClick={() => setModal({})}
          >
            + Yeni məzuniyyət
          </button>
        </div>
        <input
          ref={fileInput}
          type="file"
          accept=".xlsx,.xls"
          className="hidden"
          aria-label="Məzuniyyət Excel faylı"
          onChange={(e) => {
            if (e.target.files?.[0]) void importFile(e.target.files[0])
          }}
        />
      </div>
      {error && (
        <p role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-700">
          {error}
        </p>
      )}
      {!loading && !types.some(t => !t.archived) && <p className="text-sm text-amber-700">Əvvəl “Məzuniyyət növləri” bölməsində növ yaradın, sonra əməkdaşa məzuniyyət təyin edin.</p>}
      {importErrors.length > 0 && (
        <div role="alert" className="max-h-64 overflow-auto rounded-md bg-red-50 p-4 text-sm text-red-700">
          <p className="mb-2 font-semibold">Heç bir məlumat yazılmadı. Faylı düzəldib yenidən import edin.</p>
          {importErrors.map((e, i) => (
            <p key={i}>
              Sətir {e.row}: {e.message}
            </p>
          ))}
        </div>
      )}
      <div className="grid gap-3 sm:grid-cols-3">
        <input
          aria-label="Məzuniyyət axtar"
          className={inputClass}
          placeholder="Əməkdaş, ID və ya FIN axtar"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select
          aria-label="Məzuniyyət vəziyyəti"
          className={inputClass}
          value={status}
          onChange={(e) => setStatus(e.target.value)}
        >
          <option value="ALL">Bütün vəziyyətlər</option>
          {Object.entries(statusLabels).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        <label className="text-sm">Ay
          <input aria-label="Məzuniyyət ayı" className={inputClass} type="month" value={month} onChange={event => {
            setMonth(event.target.value)
            const range = leaveMonthRange(event.target.value)
            setFrom(range.from); setThrough(range.through)
          }} />
        </label>
        <label className="text-sm">Başlanğıc tarixi
          <input aria-label="Filtrin başlanğıc tarixi" className={inputClass} type="date" value={from} onChange={event => { setMonth(''); setFrom(event.target.value) }} />
        </label>
        <label className="text-sm">Bitmə tarixi
          <input aria-label="Filtrin bitmə tarixi" className={inputClass} type="date" value={through} onChange={event => { setMonth(''); setThrough(event.target.value) }} />
          {periodError && <span role="alert" className="text-sm text-red-600">{periodError}</span>}
        </label>
        <button className={buttonClass + ' self-end'} onClick={() => { setMonth(''); setFrom(''); setThrough(''); setStatus('ALL'); setSearch('') }}>Filtrləri təmizlə</button>
      </div>

      {sort && <button className={buttonClass} onClick={() => setSort(null)}>Ən yenilər</button>}
      <p className="text-sm text-slate-500">{filtered.length} məzuniyyət · Hər məzuniyyət ayrıca sətirdədir. Növ sütununda həmin məzuniyyətin təqvim günləri göstərilir. Qalan balans əməkdaşın ümumi qalığıdır.</p>
      <div className="overflow-x-auto rounded-md border border-slate-200 bg-white">
        <table className="min-w-full text-left text-sm">
          <thead className="bg-slate-50 text-slate-500"><tr>
            {columns.map(column => <SortableHeader key={column.key} columnKey={column.key} label={column.label} sort={sort} onSort={changeSort} />)}
            <th className="whitespace-nowrap px-4 py-3">Əməliyyat</th>
          </tr></thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? <tr><td colSpan={columns.length + 1} className="p-8 text-center">Yüklənir...</td></tr>
              : !filtered.length ? <tr><td colSpan={columns.length + 1} className="p-8 text-center text-slate-500">Məzuniyyət tapılmadı</td></tr>
              : sortedRows.map(leave => {
                const employee = employeeById.get(leave.employeeId)
                const balance = balanceByEmployee.get(leave.employeeId)
                const days = leaveCalendarDays(leave.startDate, leave.endDate)
                const charged = chargedDays(leave)
                return <tr key={leave.id}>
                  <td className="px-4 py-3 text-slate-500">{numberById.get(leave.id)}</td>
                  <td className="whitespace-nowrap px-4 py-3"><div className="font-medium">{leave.employeeName}</div><div className="text-xs text-slate-500">{leave.employeeCode}</div></td>
                  <td className="px-4 py-3">{leave.finNumber || employee?.finNumber || '—'}</td>
                  <td className="whitespace-nowrap px-4 py-3">{leave.startDate}</td>
                  <td className="whitespace-nowrap px-4 py-3">{leave.endDate}</td>
                  <td className="whitespace-nowrap px-4 py-3"><span className={'rounded-full px-2 py-1 text-xs ' + (leave.status === 'APPROVED' ? 'bg-emerald-50 text-emerald-700' : leave.status === 'PENDING' ? 'bg-amber-50 text-amber-700' : 'bg-slate-100 text-slate-500')}>{statusLabels[leave.status]}</span></td>
                  <td className="whitespace-nowrap px-4 py-3">{balance?.startDate || '—'}</td>
                  <td className="whitespace-nowrap px-4 py-3">{balance?.openingDays == null ? 'Daxil edilməyib' : balance.openingDays + ' gün'}</td>
                  {visibleTypes.map(item => <td key={item.id} className="whitespace-nowrap px-4 py-3">{item.id === leave.leaveTypeId ? days + ' gün' : '—'}</td>)}
                  <td className="whitespace-nowrap px-4 py-3">{charged} gün</td>
                  <td className={'whitespace-nowrap px-4 py-3 ' + ((balance?.remainingDays ?? 0) < 0 ? 'font-semibold text-red-600' : '')}>{balance?.remainingDays == null ? 'Daxil edilməyib' : balance.remainingDays + ' gün'}</td>
                  <td className="whitespace-nowrap px-4 py-3"><button disabled={busy} className="mr-3 text-purple-700" onClick={() => setModal({ initial: leave })}>Redaktə et</button>
                    {leave.status !== 'REJECTED' && <button disabled={busy} className="text-red-600" onClick={() => void reject(leave)}>Ləğv et</button>}</td>
                </tr>
              })}
          </tbody>
        </table>
      </div>
      {modal && (
        <LeaveModal
          initial={modal.initial}
          employees={employees}
          types={types}
          onClose={() => setModal(null)}
          onSave={async (payload) => {
            if (modal.initial) await leaveApi.update(modal.initial.id, payload)
            else { await leaveApi.create(payload); setSort(null) }
            await load()
            toast.success('Məzuniyyət yadda saxlanıldı')
          }}
        />
      )}
    </div>
  )
}

export default function PermissionsLeavePage() {
  const [tab, setTab] = useState<'permissions' | 'leaves' | 'types'>('permissions')
  return (
    <Layout>
      <div className="border-b border-slate-200 bg-white px-4 pt-5 sm:px-6">
        <h1 className="text-2xl font-bold text-slate-900">İcazə / Məzuniyyət</h1>
        <div role="tablist" aria-label="İcazə və məzuniyyət" className="mt-4 flex gap-5">
          {(['permissions', 'leaves', 'types'] as const).map((value) => (
            <button
              role="tab"
              aria-selected={tab === value}
              key={value}
              className={`border-b-2 px-1 pb-3 text-sm font-medium ${tab === value ? 'border-purple-600 text-purple-700' : 'border-transparent text-slate-500'}`}
              onClick={() => setTab(value)}
            >
              {value === 'permissions' ? 'İcazələr' : value === 'types' ? 'Məzuniyyət növləri' : 'Məzuniyyətlər'}
            </button>
          ))}
        </div>
      </div>
      {tab === 'permissions' ? <PermissionAssignmentPage embedded /> : tab === 'types' ? <LeaveTypesPanel /> : <LeavesPanel />}
    </Layout>
  )
}
