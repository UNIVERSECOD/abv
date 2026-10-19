import { Fragment, useEffect, useMemo, useState } from 'react'
import Layout from '../components/Layout.tsx'
import { employeeApi } from '../api/employeeApi.ts'
import { permissionApi } from '../api/permissionApi.ts'
import { employeePermissionApi } from '../api/employeePermissionApi.ts'
import { Employee, EmployeePermission, PermissionType } from '../types'
import { statusLabel } from '../i18n/labels.ts'
import { getApiErrorMessage } from '../utils/apiError.ts'
import { toast } from '../store/toastStore.ts'

type PermissionFormValue = {
  employeeIds: number[]
  permissionTypeId: number
  startDate: string
  endDate: string
  startTime?: string
  endTime?: string
  deductFromWorkHours: boolean
  reason: string
  status: EmployeePermission['status']
}

interface PermissionModalProps {
  employees: Employee[]
  permissionTypes: PermissionType[]
  initial?: EmployeePermission
  onClose: () => void
  onSave: (payload: PermissionFormValue) => Promise<void>
}

function todayInputValue() {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

function inputTime(value?: string) {
  return value ? value.slice(0, 5) : ''
}

function PermissionModal({ employees, permissionTypes, initial, onClose, onSave }: PermissionModalProps) {
  const editing = Boolean(initial)
  const [selectedEmployeeIds, setSelectedEmployeeIds] = useState<number[]>(initial ? [initial.employeeId] : [])
  const [permissionTypeId, setPermissionTypeId] = useState(initial?.permissionTypeId ?? permissionTypes[0]?.id ?? 0)
  const [startDate, setStartDate] = useState(initial?.startDate ?? todayInputValue())
  const [endDate, setEndDate] = useState(initial?.endDate ?? todayInputValue())
  const [fullDay, setFullDay] = useState(initial ? !initial.startTime && !initial.endTime : false)
  const [startTime, setStartTime] = useState(inputTime(initial?.startTime) || '09:00')
  const [endTime, setEndTime] = useState(inputTime(initial?.endTime) || '17:00')
  const [deductFromWorkHours, setDeductFromWorkHours] = useState(initial?.deductFromWorkHours ?? false)
  const [reason, setReason] = useState(initial?.reason ?? '')
  const [status, setStatus] = useState<EmployeePermission['status']>(initial?.status ?? 'APPROVED')
  const [employeeSearch, setEmployeeSearch] = useState('')
  const [error, setError] = useState('')
  const [scheduleError, setScheduleError] = useState('')
  const [saving, setSaving] = useState(false)

  const selectableEmployees = useMemo(() => {
    const query = employeeSearch.trim().toLocaleLowerCase('az')
    if (!query) return employees
    return employees.filter(employee => {
      const text = `${employee.employeeId} ${employee.firstName} ${employee.lastName} ${employee.finNumber ?? ''} ${employee.departmentName ?? ''}`
      return text.toLocaleLowerCase('az').includes(query)
    })
  }, [employeeSearch, employees])

  const toggleEmployee = (employeeId: number, checked: boolean) => {
    setSelectedEmployeeIds(previous => checked
      ? [...new Set([...previous, employeeId])]
      : previous.filter(id => id !== employeeId))
  }

  const handleSave = async () => {
    setScheduleError('')
    if (!selectedEmployeeIds.length) {
      setError('Ən azı bir əməkdaş seçin')
      return
    }
    if (!permissionTypeId) {
      setError('İcazə növünü seçin')
      return
    }
    if (!startDate || !endDate || endDate < startDate) {
      setError('Düzgün tarix aralığı seçin')
      return
    }
    if (!fullDay && (!startTime || !endTime || endTime === startTime)) {
      setError('Başlanğıc və bitmə saatları fərqli olmalıdır')
      return
    }
    if (!reason.trim()) {
      setError('İcazənin səbəbini yazın')
      return
    }

    setSaving(true)
    setError('')
    try {
      await onSave({
        employeeIds: selectedEmployeeIds,
        permissionTypeId,
        startDate,
        endDate,
        startTime: fullDay ? undefined : startTime,
        endTime: fullDay ? undefined : endTime,
        deductFromWorkHours,
        reason: reason.trim(),
        status,
      })
      onClose()
    } catch (requestError: unknown) {
      const message = getApiErrorMessage(requestError, 'İcazəni yadda saxlamaq mümkün olmadı')
      if (/iş saat|[İi]stirahət gün|iş qrafiki/.test(message)) setScheduleError(message)
      else setError(message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div className="max-h-[90vh] w-full max-w-3xl overflow-y-auto rounded-lg bg-white p-6 shadow-xl">
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-lg font-semibold text-slate-900">{editing ? 'İcazəni redaktə et' : 'Yeni icazə'}</h2>
            <p className="mt-1 text-sm text-slate-500">Tarix, saat və iş vaxtına təsir qaydasını seçin</p>
          </div>
          <button type="button" onClick={onClose} className="text-xl text-slate-400 hover:text-slate-700" aria-label="Bağla">×</button>
        </div>

        {error && <div className="mb-4 rounded-md bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}

        <div className="grid grid-cols-2 gap-4">
          <label className="col-span-2 text-sm font-medium text-slate-700">
            İcazə növü
            <select value={permissionTypeId} onChange={event => setPermissionTypeId(Number(event.target.value))} disabled={editing} className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm disabled:bg-slate-100">
              {permissionTypes.map(type => <option key={type.id} value={type.id}>{type.name}</option>)}
            </select>
          </label>

          <label className="text-sm font-medium text-slate-700">
            Başlanğıc tarixi
            <input type="date" value={startDate} onChange={event => setStartDate(event.target.value)} className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm" />
          </label>
          <label className="text-sm font-medium text-slate-700">
            Bitmə tarixi
            <input type="date" value={endDate} onChange={event => setEndDate(event.target.value)} className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm" />
          </label>

          <label className="col-span-2 flex items-center gap-2 text-sm font-medium text-slate-700">
            <input type="checkbox" checked={fullDay} onChange={event => setFullDay(event.target.checked)} />
            Tam gün icazə
          </label>

          {!fullDay && (
            <>
              <label className="text-sm font-medium text-slate-700">
                Başlanğıc saatı
                <input type="time" value={startTime} onChange={event => setStartTime(event.target.value)} className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm" />
              </label>
              <label className="text-sm font-medium text-slate-700">
                Bitmə saatı
                <input type="time" value={endTime} onChange={event => setEndTime(event.target.value)} className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm" />
              </label>
            </>
          )}

          {scheduleError && <p role="alert" className="col-span-2 text-sm text-red-600">{scheduleError}</p>}

          <label className="col-span-2 text-sm font-medium text-slate-700">
            Səbəb
            <textarea value={reason} onChange={event => setReason(event.target.value)} rows={3} className="mt-1 w-full resize-none rounded-md border border-slate-300 px-3 py-2 text-sm" />
          </label>

          <label className="text-sm font-medium text-slate-700">
            Vəziyyət
            <select value={status} onChange={event => setStatus(event.target.value as EmployeePermission['status'])} className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm">
              <option value="APPROVED">Təsdiqlənib</option>
              <option value="PENDING">Gözləmədə</option>
              <option value="ACTIVE">Aktiv</option>
              <option value="REJECTED">Rədd edilib</option>
            </select>
          </label>

          <label className="flex items-end gap-2 pb-2 text-sm font-medium text-slate-700">
            <input type="checkbox" checked={deductFromWorkHours} onChange={event => setDeductFromWorkHours(event.target.checked)} />
            İcazə müddəti iş saatından çıxılsın
          </label>
        </div>

        <div className="mt-5">
          <div className="mb-2 flex items-center justify-between gap-3">
            <label className="text-sm font-medium text-slate-700">Əməkdaş seçimi</label>
            {!editing && <span className="text-xs text-slate-500">{selectedEmployeeIds.length} əməkdaş seçilib</span>}
          </div>
          {!editing && <input value={employeeSearch} onChange={event => setEmployeeSearch(event.target.value)} placeholder="Ad, ID, FİN və ya departament axtar" className="mb-2 w-full rounded-md border border-slate-300 px-3 py-2 text-sm" />}
          <div className="max-h-52 overflow-y-auto rounded-md border border-slate-200">
            {selectableEmployees.map(employee => (
              <label key={employee.id} className="flex items-center gap-3 border-b border-slate-100 px-3 py-2 text-sm last:border-b-0 hover:bg-slate-50">
                <input type={editing ? 'radio' : 'checkbox'} checked={selectedEmployeeIds.includes(employee.id)} onChange={event => toggleEmployee(employee.id, event.target.checked)} disabled={editing} />
                <span className="min-w-0 flex-1">
                  <span className="font-medium text-slate-900">{employee.firstName} {employee.lastName}</span>
                  <span className="ml-2 text-slate-500">{employee.employeeId} · {employee.departmentName || 'Departament yoxdur'}</span>
                </span>
              </label>
            ))}
            {selectableEmployees.length === 0 && <p className="px-3 py-8 text-center text-sm text-slate-500">Əməkdaş tapılmadı</p>}
          </div>
        </div>

        <div className="mt-6 flex justify-end gap-2">
          <button type="button" onClick={onClose} className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-50">Ləğv et</button>
          <button type="button" onClick={() => void handleSave()} disabled={saving} className="rounded-md bg-purple-600 px-4 py-2 text-sm font-medium text-white hover:bg-purple-700 disabled:opacity-60">{saving ? 'Yadda saxlanılır...' : 'Yadda saxla'}</button>
        </div>
      </div>
    </div>
  )
}

function permissionStatusStyle(status: EmployeePermission['status']) {
  switch (status) {
    case 'APPROVED':
    case 'ACTIVE':
      return 'bg-emerald-50 text-emerald-700'
    case 'PENDING':
      return 'bg-amber-50 text-amber-700'
    case 'REJECTED':
      return 'bg-red-50 text-red-700'
    default:
      return 'bg-slate-100 text-slate-600'
  }
}

export default function PermissionAssignmentPage({ embedded = false }: { embedded?: boolean }) {
  const Wrapper = embedded ? Fragment : Layout
  const [employees, setEmployees] = useState<Employee[]>([])
  const [permissionTypes, setPermissionTypes] = useState<PermissionType[]>([])
  const [permissions, setPermissions] = useState<EmployeePermission[]>([])
  const [search, setSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('ALL')
  const [statusFilter, setStatusFilter] = useState('ACTIVE')
  const [dateFilter, setDateFilter] = useState('')
  const [editingPermission, setEditingPermission] = useState<EmployeePermission | undefined>()
  const [showModal, setShowModal] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  const fetchData = async () => {
    setLoading(true)
    setError('')
    try {
      const [employeeRes, typeRes, permissionRes] = await Promise.all([
        employeeApi.getAll(0, 1000),
        permissionApi.getTypes(),
        employeePermissionApi.getAll(),
      ])
      setEmployees(employeeRes.data?.content ?? [])
      setPermissionTypes(typeRes.data?.data ?? [])
      setPermissions(permissionRes.data?.data ?? [])
    } catch (requestError: unknown) {
      setError(getApiErrorMessage(requestError, 'İcazə məlumatlarını yükləmək mümkün olmadı'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void fetchData()
  }, [])

  const employeeMap = useMemo(() => new Map(employees.map(employee => [employee.id, employee])), [employees])
  const typeMap = useMemo(() => new Map(permissionTypes.map(type => [type.id, type])), [permissionTypes])

  const filteredPermissions = useMemo(() => {
    const query = search.trim().toLocaleLowerCase('az')
    return permissions
      .filter(permission => typeFilter === 'ALL' || permission.permissionTypeId === Number(typeFilter))
      .filter(permission => {
        if (statusFilter === 'ALL') return true
        if (statusFilter === 'ACTIVE') return permission.status === 'APPROVED' || permission.status === 'ACTIVE'
        return permission.status === statusFilter
      })
      .filter(permission => !dateFilter || (permission.startDate <= dateFilter && permission.endDate >= dateFilter))
      .filter(permission => {
        if (!query) return true
        const employee = employeeMap.get(permission.employeeId)
        const text = `${employee?.employeeId ?? ''} ${employee?.firstName ?? ''} ${employee?.lastName ?? ''} ${employee?.finNumber ?? ''} ${employee?.departmentName ?? ''} ${permission.reason ?? ''}`
        return text.toLocaleLowerCase('az').includes(query)
      })
      .sort((left, right) => right.startDate.localeCompare(left.startDate) || right.id - left.id)
  }, [dateFilter, employeeMap, permissions, search, statusFilter, typeFilter])

  const savePermission = async (payload: PermissionFormValue) => {
    if (editingPermission) {
      await employeePermissionApi.update(editingPermission.id, {
        startDate: payload.startDate,
        endDate: payload.endDate,
        startTime: payload.startTime,
        endTime: payload.endTime,
        deductFromWorkHours: payload.deductFromWorkHours,
        reason: payload.reason,
        status: payload.status,
      })
    } else {
      await employeePermissionApi.bulkGrant(payload)
    }
    await fetchData()
    toast.success(editingPermission ? 'İcazə yeniləndi' : 'İcazə əlavə edildi')
  }

  const deactivatePermission = async (permission: EmployeePermission) => {
    const employee = employeeMap.get(permission.employeeId)
    const name = employee ? `${employee.firstName} ${employee.lastName}` : 'əməkdaş'
    if (!window.confirm(`${name} üçün bu icazə deaktiv edilsin?`)) return
    try {
      await employeePermissionApi.remove(permission.id)
      await fetchData()
      toast.success('İcazə deaktiv edildi')
    } catch (requestError: unknown) {
      const message = getApiErrorMessage(requestError, 'İcazəni deaktiv etmək mümkün olmadı')
      setError(message)
      toast.error('İcazə deaktiv edilmədi', message)
    }
  }

  const openCreate = () => {
    setEditingPermission(undefined)
    setShowModal(true)
  }

  const openEdit = (permission: EmployeePermission) => {
    setEditingPermission(permission)
    setShowModal(true)
  }

  return (
    <Wrapper>
      <div className="space-y-5 p-6">
        <div className="flex items-center justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold text-slate-900">İcazələr</h1>
            <p className="mt-1 text-sm text-slate-500">Əməkdaşların saatlıq və tam günlük icazələrini idarə edin</p>
          </div>
          <button type="button" onClick={openCreate} disabled={permissionTypes.length === 0} className="rounded-md bg-purple-600 px-4 py-2 text-sm font-medium text-white hover:bg-purple-700 disabled:cursor-not-allowed disabled:opacity-50">+ Yeni icazə</button>
        </div>

        {error && <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>}
        {!loading && permissionTypes.length === 0 && <div className="rounded-md border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">İcazə növü yaradılmayıb.</div>}

        <div className="grid grid-cols-4 gap-3 border-y border-slate-200 bg-white py-4">
          <input value={search} onChange={event => setSearch(event.target.value)} placeholder="Əməkdaş, FİN və ya səbəb axtar" className="rounded-md border border-slate-300 px-3 py-2 text-sm" />
          <select value={typeFilter} onChange={event => setTypeFilter(event.target.value)} className="rounded-md border border-slate-300 px-3 py-2 text-sm">
            <option value="ALL">Bütün icazə növləri</option>
            {permissionTypes.map(type => <option key={type.id} value={type.id}>{type.name}</option>)}
          </select>
          <select value={statusFilter} onChange={event => setStatusFilter(event.target.value)} className="rounded-md border border-slate-300 px-3 py-2 text-sm">
            <option value="ACTIVE">Aktiv icazələr</option>
            <option value="ALL">Bütün vəziyyətlər</option>
            <option value="PENDING">Gözləmədə</option>
            <option value="REJECTED">Rədd edilib</option>
            <option value="INACTIVE">Deaktiv</option>
          </select>
          <input type="date" value={dateFilter} onChange={event => setDateFilter(event.target.value)} className="rounded-md border border-slate-300 px-3 py-2 text-sm" />
        </div>

        <div className="overflow-x-auto border-y border-slate-200 bg-white">
          <table className="min-w-full text-sm">
            <thead className="bg-slate-50 text-left text-xs uppercase text-slate-500">
              <tr>
                <th className="px-4 py-3">Əməkdaş</th>
                <th className="px-4 py-3">İcazə növü</th>
                <th className="px-4 py-3">Tarix</th>
                <th className="px-4 py-3">Saat</th>
                <th className="px-4 py-3">İş vaxtına təsir</th>
                <th className="px-4 py-3">Vəziyyət</th>
                <th className="px-4 py-3">Səbəb</th>
                <th className="px-4 py-3 text-right">Əməliyyat</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {loading ? (
                <tr><td colSpan={8} className="px-4 py-10 text-center text-slate-500">Yüklənir...</td></tr>
              ) : filteredPermissions.length === 0 ? (
                <tr><td colSpan={8} className="px-4 py-10 text-center text-slate-500">Seçilmiş filtrə uyğun icazə yoxdur</td></tr>
              ) : filteredPermissions.map(permission => {
                const employee = employeeMap.get(permission.employeeId)
                return (
                  <tr key={permission.id} className="text-slate-700">
                    <td className="whitespace-nowrap px-4 py-3">
                      <div className="font-medium text-slate-900">{employee ? `${employee.firstName} ${employee.lastName}` : `#${permission.employeeId}`}</div>
                      <div className="text-xs text-slate-500">{employee?.employeeId ?? '—'} · {employee?.departmentName ?? 'Departament yoxdur'}</div>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3">{typeMap.get(permission.permissionTypeId)?.name ?? '—'}</td>
                    <td className="whitespace-nowrap px-4 py-3">{permission.startDate === permission.endDate ? permission.startDate : `${permission.startDate} – ${permission.endDate}`}</td>
                    <td className="whitespace-nowrap px-4 py-3">{permission.startTime && permission.endTime ? `${inputTime(permission.startTime)} – ${inputTime(permission.endTime)}` : 'Tam gün'}</td>
                    <td className="whitespace-nowrap px-4 py-3">{permission.deductFromWorkHours ? 'İş vaxtından çıxılır' : 'İş vaxtına daxildir'}</td>
                    <td className="whitespace-nowrap px-4 py-3"><span className={`rounded-full px-2.5 py-1 text-xs font-medium ${permissionStatusStyle(permission.status)}`}>{statusLabel(permission.status)}</span></td>
                    <td className="max-w-xs px-4 py-3 text-slate-600">{permission.reason || '—'}</td>
                    <td className="whitespace-nowrap px-4 py-3 text-right">
                      <button type="button" onClick={() => openEdit(permission)} className="mr-3 text-purple-700 hover:text-purple-900">Redaktə et</button>
                      {permission.status !== 'INACTIVE' && <button type="button" onClick={() => void deactivatePermission(permission)} className="text-red-600 hover:text-red-800">Deaktiv et</button>}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>

      {showModal && <PermissionModal employees={employees.filter(employee => employee.employmentStatus === 'ACTIVE' || employee.id === editingPermission?.employeeId)} permissionTypes={permissionTypes} initial={editingPermission} onClose={() => setShowModal(false)} onSave={savePermission} />}
    </Wrapper>
  )
}
