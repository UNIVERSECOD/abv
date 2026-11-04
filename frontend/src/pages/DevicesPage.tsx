import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import Layout from '../components/Layout.tsx'
import DeviceReconciliationModal from '../components/DeviceReconciliationModal'
import { useAuthStore } from '../store/authStore'
import { useDeviceStore } from '../store/deviceStore.ts'
import { DeviceConfig, Branch, Door, DeviceEmployeeAssignmentView } from '../types'
import { branchApi } from '../api/branchApi.ts'
import { doorApi } from '../api/doorApi.ts'
import { deviceApi } from '../api/deviceApi.ts'
import { t } from '../i18n/index.ts'
import { doorRoleLabel, statusLabel } from '../i18n/labels.ts'
import { getApiErrorMessage } from '../utils/apiError.ts'
import { isDeviceOnline, relativeTime, ONLINE_THRESHOLD_MINUTES } from '../utils/deviceOnline.ts'
import { toast } from '../store/toastStore.ts'

interface DeviceFormData {
  deviceName: string
  deviceIp: string
  devicePort: number | ''
  username: string
  password: string
  branchId: number | ''
  status: string
}

const defaultForm: DeviceFormData = {
  deviceName: '',
  deviceIp: '',
  devicePort: 80,
  username: 'admin',
  password: '',
  branchId: '',
  status: 'ACTIVE',
}

export default function DevicesPage() {
  const [searchParams] = useSearchParams()
  const linkOpened = useRef(false)
  const { devices, loading, error, fetchDevices, syncDevice, createDevice, updateDevice, deleteDevice } = useDeviceStore()
  const [branches, setBranches] = useState<Branch[]>([])
  const [showModal, setShowModal] = useState(false)
  const [editingDevice, setEditingDevice] = useState<DeviceConfig | null>(null)
  const [form, setForm] = useState<DeviceFormData>(defaultForm)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [deleteConfirm, setDeleteConfirm] = useState<DeviceConfig | null>(null)
  const [syncingId, setSyncingId] = useState<number | null>(null)
  const [syncFeedback, setSyncFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null)
  const [doors, setDoors] = useState<Door[]>([])
  const [selectedDoorId, setSelectedDoorId] = useState<number | '' | 'new'>('')
  const [doorRole, setDoorRole] = useState<'ENTRY' | 'EXIT' | 'BOTH' | ''>('')
  const [newDoorName, setNewDoorName] = useState('')
  const [showDoorManager, setShowDoorManager] = useState(false)
  const [managerBranchId, setManagerBranchId] = useState<number | ''>('')
  const [managerDoors, setManagerDoors] = useState<Door[]>([])
  const [managerLoading, setManagerLoading] = useState(false)
  const [doorDeleteConfirm, setDoorDeleteConfirm] = useState<Door | null>(null)
  const [assignmentDevice, setAssignmentDevice] = useState<DeviceConfig | null>(null)
  const [reconciliationDevice, setReconciliationDevice] = useState<DeviceConfig | null>(null)
  const canReconcile = useAuthStore(s => s.user?.userType === 'HEAD_OFFICE_HR')
  const [assignmentView, setAssignmentView] = useState<DeviceEmployeeAssignmentView | null>(null)
  const [manualEmployeeIds, setManualEmployeeIds] = useState<number[]>([])
  const [assignmentLoading, setAssignmentLoading] = useState(false)
  const [assignmentSaving, setAssignmentSaving] = useState(false)
  const [assignmentError, setAssignmentError] = useState<string | null>(null)
  const [assignmentSearch, setAssignmentSearch] = useState('')
  const [groupAreaId, setGroupAreaId] = useState<number | ''>('')
  const [employeeSyncingId, setEmployeeSyncingId] = useState<number | null>(null)
  // Live clock tick — re-renders every 30 s so online/offline badge updates automatically
  const [, setTick] = useState(0)
  const tickRef = useRef<ReturnType<typeof setInterval> | null>(null)

  useEffect(() => {
    fetchDevices()
    branchApi.getAll().then((res) => setBranches(res.data?.data ?? []))
    // Refresh online badges every 30 seconds without a network call
    tickRef.current = setInterval(() => setTick((t) => t + 1), 30_000)
    return () => { if (tickRef.current) clearInterval(tickRef.current) }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const loadDoors = async (branchId: number) => {
    try {
      const res = await doorApi.getByBranch(branchId)
      setDoors(res.data?.data ?? [])
    } catch {
      setDoors([])
    }
  }

  const loadManagerDoors = async (branchId: number) => {
    setManagerLoading(true)
    try {
      const res = await doorApi.getByBranch(branchId)
      setManagerDoors(res.data?.data ?? [])
    } catch {
      setManagerDoors([])
    } finally {
      setManagerLoading(false)
    }
  }

  const handleDeleteDoor = async () => {
    if (!doorDeleteConfirm) return
    try {
      await doorApi.delete(doorDeleteConfirm.id)
      setDoorDeleteConfirm(null)
      if (managerBranchId) {
        await loadManagerDoors(Number(managerBranchId))
      }
      // Also refresh device list since devices may have been unassigned
      await fetchDevices()
      toast.success('Qapı silindi')
    } catch (error: unknown) {
      toast.error('Qapı silinmədi', getApiErrorMessage(error, 'Qapını silmək alınmadı'))
    }
  }

  const openCreate = () => {
    setEditingDevice(null)
    setForm(defaultForm)
    setSelectedDoorId('')
    setDoorRole('')
    setNewDoorName('')
    setDoors([])
    setFormError(null)
    setShowModal(true)
  }

  const openEdit = (device: DeviceConfig) => {
    setEditingDevice(device)
    setForm({
      deviceName: device.deviceName || '',
      deviceIp: device.deviceIp,
      devicePort: device.devicePort || 80,
      username: device.username || 'admin',
      password: '',
      branchId: device.branchId || '',
      status: device.status || 'ACTIVE',
    })
    setSelectedDoorId(device.doorId ?? '')
    setDoorRole((device.doorRole as 'ENTRY' | 'EXIT' | 'BOTH') ?? '')
    setNewDoorName('')
    setFormError(null)
    if (device.branchId) {
      loadDoors(device.branchId)
    }
    setShowModal(true)
  }

  useEffect(() => {
    if (linkOpened.current) return
    const deviceId = Number(searchParams.get('device'))
    const areaId = Number(searchParams.get('area'))
    const device = devices.find(d => d.id === deviceId)
    if (device) { linkOpened.current = true; openEdit(device) }
    else if (areaId > 0) { linkOpened.current = true; openCreate(); setForm({ ...defaultForm, branchId: areaId }); void loadDoors(areaId) }
    // Open a deep link once, after the existing device list arrives.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [devices, searchParams])

  const handleSave = async () => {
    if (!form.deviceIp.trim()) { setFormError('Cihaz IP-si tələb olunur.'); return }
    if (!form.username.trim()) { setFormError('İstifadəçi adı tələb olunur.'); return }
    if (!editingDevice && !form.password.trim()) { setFormError('Şifrə tələb olunur.'); return }
    if (form.branchId && selectedDoorId === 'new' && !newDoorName.trim()) {
      setFormError('Yeni qapı yaradarkən qapı adı tələb olunur.')
      return
    }
    setSaving(true)
    setFormError(null)
    try {
      const payload = {
        ...form,
        devicePort: form.devicePort ? Number(form.devicePort) : 80,
        // Area membership is saved through the local endpoint shared with the area panel.
        branchId: undefined,
      }
      let deviceId = editingDevice?.id
      if (editingDevice) {
        await updateDevice(editingDevice.id, payload)
        deviceId = editingDevice.id
      } else {
        const createRes = await deviceApi.create(payload)
        const created = Array.isArray(createRes.data) ? createRes.data[0] : createRes.data?.data ?? createRes.data
        deviceId = typeof created?.id === 'number' ? created.id : undefined
      }

      if (deviceId) {
        await deviceApi.assignArea(deviceId, form.branchId ? Number(form.branchId) : null)
      }

      // Door and Role assignment
      if (deviceId && form.branchId) {
        let doorId = selectedDoorId && selectedDoorId !== 'new' ? Number(selectedDoorId) : undefined;
        if (selectedDoorId === 'new') {
          const createDoorRes = await doorApi.create({
            branchId: Number(form.branchId),
            name: newDoorName.trim(),
            status: 'ACTIVE',
          });
          doorId = createDoorRes.data?.data?.id;
        }
        await deviceApi.assignDoor(deviceId, {
          doorId: doorId || undefined,
          role: doorRole || undefined
        });
      }

      await fetchDevices()
      setShowModal(false)
      toast.success(editingDevice ? 'Cihaz yeniləndi' : 'Cihaz əlavə edildi')
    } catch (e: unknown) {
      const message = getApiErrorMessage(e, 'Cihazı yadda saxlamaq alınmadı')
      setFormError(message)
      toast.error('Cihaz saxlanılmadı', message)
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!deleteConfirm) return
    try {
      await deleteDevice(deleteConfirm.id)
      setDeleteConfirm(null)
      toast.success('Cihaz silindi')
    } catch (error: unknown) {
      toast.error('Cihaz silinmədi', getApiErrorMessage(error, 'Cihazı silmək alınmadı.'))
    }
  }

  const handleSync = async (id: number) => {
    setSyncingId(id)
    setSyncFeedback(null)
    try {
      await syncDevice(id)
      const message = 'Cihaz onlayndır. Davamiyyət tarixçəsi arxa planda sinxronlaşdırılır.'
      setSyncFeedback({ type: 'success', message })
      toast.success('Sinxronizasiya başladı', message)
    } catch (error: unknown) {
      const status = (error as { response?: { status?: number } })?.response?.status
      const message = status === 502 || status === 503
        ? 'Cihazla əlaqə yaratmaq mümkün olmadı. IP ünvanını, şəbəkəni və cihaz şifrəsini yoxlayın.'
        : getApiErrorMessage(error, 'Cihazı sinxronlaşdırmaq alınmadı.')
      setSyncFeedback({ type: 'error', message })
      toast.error('Sinxronizasiya alınmadı', message)
    } finally {
      setSyncingId(null)
    }
  }

  const openEmployeeAssignments = async (device: DeviceConfig) => {
    setAssignmentDevice(device)
    setAssignmentView(null)
    setManualEmployeeIds([])
    setAssignmentSearch('')
    setGroupAreaId('')
    setAssignmentError(null)
    setAssignmentLoading(true)
    try {
      const response = await deviceApi.getEmployeeAssignments(device.id)
      const view = response.data?.data
      setAssignmentView(view)
      setManualEmployeeIds(view?.employees.filter((employee) => employee.manuallyAssigned)
        .map((employee) => employee.employeeId) ?? [])
    } catch (error: unknown) {
      const message = getApiErrorMessage(error, 'Əməkdaş təyinatları yüklənmədi.')
      setAssignmentError(message)
      toast.error('Təyinatlar yüklənmədi', message)
    } finally {
      setAssignmentLoading(false)
    }
  }

  const toggleManualEmployee = (employeeId: number) => {
    setManualEmployeeIds((current) => current.includes(employeeId)
      ? current.filter((id) => id !== employeeId)
      : [...current, employeeId])
  }

  const addAreaGroup = () => {
    if (!assignmentView || groupAreaId === '') return
    const groupEmployeeIds = assignmentView.employees
      .filter((employee) => !employee.areaAssigned && employee.areaIds.includes(Number(groupAreaId)))
      .map((employee) => employee.employeeId)
    setManualEmployeeIds((current) => [...new Set([...current, ...groupEmployeeIds])])
  }

  const saveEmployeeAssignments = async () => {
    if (!assignmentDevice) return
    setAssignmentSaving(true)
    setAssignmentError(null)
    try {
      const response = await deviceApi.updateEmployeeAssignments(assignmentDevice.id, manualEmployeeIds)
      const view = response.data?.data
      setAssignmentView(view)
      setManualEmployeeIds(view?.employees.filter((employee) => employee.manuallyAssigned)
        .map((employee) => employee.employeeId) ?? [])
      setSyncFeedback({ type: 'success', message: 'Cihazın əməkdaş təyinatları yadda saxlanıldı.' })
      setAssignmentDevice(null)
      toast.success('Əməkdaş təyinatları saxlanıldı')
    } catch (error: unknown) {
      const message = getApiErrorMessage(error, 'Əməkdaş təyinatlarını saxlamaq alınmadı.')
      setAssignmentError(message)
      toast.error('Təyinatlar saxlanılmadı', message)
    } finally {
      setAssignmentSaving(false)
    }
  }

  const handleEmployeeSync = async (device: DeviceConfig) => {
    setEmployeeSyncingId(device.id)
    setSyncFeedback(null)
    try {
      const response = await deviceApi.syncEmployees(device.id)
      const result = response.data?.data
      if (!result) throw new Error('Sinxron nəticəsi alınmadı')
      const message = `${result.succeeded}/${result.total} əməkdaş sinxronlaşdırıldı` +
        (result.facesSynced ? `, ${result.facesSynced} üz şəkli göndərildi` : '') +
        (result.failed || result.facesFailed ? `; ${result.failed + result.facesFailed} xəta` : '.')
      setSyncFeedback({
        type: result.failed || result.facesFailed ? 'error' : 'success',
        message: result.errors?.length ? `${message} ${result.errors.join(' | ')}` : message,
      })
      if (result.failed || result.facesFailed) {
        toast.warning('Sinxronizasiya qismən tamamlandı', message)
      } else {
        toast.success('Əməkdaşlar sinxronlaşdırıldı', message)
      }
    } catch (error: unknown) {
      const message = getApiErrorMessage(error, 'Əməkdaşları cihaza sinxronlaşdırmaq alınmadı.')
      setSyncFeedback({
        type: 'error',
        message,
      })
      toast.error('Sinxronizasiya alınmadı', message)
    } finally {
      setEmployeeSyncingId(null)
    }
  }

  // Combine immediate bridge state with the last successful sync freshness.
  const activeCount = devices.filter((d: DeviceConfig) => isDeviceOnline(d.status, d.lastSyncTime, d.online)).length
  const inactiveCount = devices.length - activeCount

  return (
    <Layout>
      <div className="p-4 sm:p-8" style={{ background: '#f8fafc', minHeight: '100vh' }}>
        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-3 mb-6">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Cihazlar</h1>
            <p className="text-sm text-gray-500 mt-1">
              <span className="inline-flex items-center gap-1.5 mr-3">
                <span className="w-2 h-2 rounded-full bg-green-500 inline-block"></span>
                {activeCount} onlayn
              </span>
              <span className="inline-flex items-center gap-1.5">
                <span className="w-2 h-2 rounded-full bg-red-400 inline-block"></span>
                {inactiveCount} oflayn
              </span>
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => fetchDevices()}
              className="flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
              Siyahını yenilə
            </button>
            <button
              onClick={() => { setShowDoorManager(true); setManagerBranchId(''); setManagerDoors([]) }}
              className="flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-50 transition-colors"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z" />
              </svg>
              Qapıları idarə et
            </button>
            <button
              onClick={openCreate}
              className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white rounded-lg transition-colors"
              style={{ background: '#a855f7' }}
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
              </svg>
              Cihaz əlavə et
            </button>
          </div>
        </div>

        {syncFeedback && (
          <div
            className={`mb-4 border px-4 py-3 text-sm rounded-lg ${
              syncFeedback.type === 'success'
                ? 'border-green-200 bg-green-50 text-green-700'
                : 'border-red-200 bg-red-50 text-red-700'
            }`}
            role="status"
          >
            {syncFeedback.message}
          </div>
        )}

        {/* Summary cards */}
        <div className="grid grid-cols-3 gap-4 mb-6">
          <div className="bg-white rounded-xl p-4 shadow-sm flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg flex items-center justify-center" style={{ background: '#f3e8ff' }}>
              <svg className="w-5 h-5" style={{ color: '#a855f7' }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
            </div>
            <div>
              <p className="text-xs text-gray-400">Ümumi cihazlar</p>
              <p className="text-lg font-bold text-gray-900">{devices.length}</p>
            </div>
          </div>
          <div className="bg-white rounded-xl p-4 shadow-sm flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg flex items-center justify-center bg-green-50">
              <span className="w-3 h-3 rounded-full bg-green-500 inline-block"></span>
            </div>
            <div>
              <p className="text-xs text-gray-400">Onlayn</p>
              <p className="text-lg font-bold text-gray-900">{activeCount}</p>
            </div>
          </div>
          <div className="bg-white rounded-xl p-4 shadow-sm flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg flex items-center justify-center bg-red-50">
              <span className="w-3 h-3 rounded-full bg-red-400 inline-block"></span>
            </div>
            <div>
              <p className="text-xs text-gray-400">Oflayn</p>
              <p className="text-lg font-bold text-gray-900">{inactiveCount}</p>
            </div>
          </div>
        </div>

        {/* Device List */}
        {loading ? (
          <div className="bg-white rounded-xl shadow-sm p-12 text-center text-gray-400">
            <div className="w-8 h-8 border-2 border-purple-300 border-t-purple-600 rounded-full animate-spin mx-auto mb-3"></div>
            Cihazlar yüklənir...
          </div>
        ) : error ? (
          <div className="bg-white rounded-xl shadow-sm p-8 text-center text-red-500">{error}</div>
        ) : devices.length === 0 ? (
          <div className="bg-white rounded-xl shadow-sm p-12 text-center">
            <div className="w-16 h-16 rounded-full bg-purple-50 flex items-center justify-center mx-auto mb-4">
              <svg className="w-8 h-8 text-purple-300" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
              </svg>
            </div>
            <p className="text-gray-500 text-sm">Hələ heç bir cihaz qurulmayıb.</p>
            <button onClick={openCreate} className="mt-3 text-sm font-medium" style={{ color: '#a855f7' }}>
              + İlk cihazı əlavə et
            </button>
          </div>
        ) : (
          <div className="space-y-3">
            {devices.map((device: DeviceConfig) => (
              <div key={device.id} className="bg-white rounded-xl shadow-sm p-5 flex flex-wrap items-center gap-5">
                {/* Icon */}
                <div className="w-12 h-12 rounded-xl flex items-center justify-center flex-shrink-0" style={{ background: '#f3e8ff' }}>
                  <svg className="w-6 h-6" style={{ color: '#a855f7' }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
                  </svg>
                </div>

                {/* Info */}
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-0.5">
                    <p className="font-semibold text-gray-900 text-sm">{device.deviceName || device.deviceId}</p>
                    {(() => {
                      const online = isDeviceOnline(device.status, device.lastSyncTime, device.online)
                      return (
                        <span
                          className="px-2 py-0.5 rounded-full text-xs font-medium flex items-center gap-1"
                          style={online
                            ? { background: '#d1fae5', color: '#065f46' }
                            : { background: '#fee2e2', color: '#991b1b' }}
                          title={online
                            ? `Onlayn — son ${ONLINE_THRESHOLD_MINUTES} dəqiqə ərzində sinxronlaşdı`
                            : `Oflayn — son sinxron: ${relativeTime(device.lastSyncTime)}`}
                        >
                          {online ? (
                            <span className="relative flex h-2 w-2">
                              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                              <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
                            </span>
                          ) : (
                            <span className="inline-block w-2 h-2 rounded-full bg-red-400"></span>
                          )}
                          {online ? 'Onlayn' : 'Oflayn'}
                        </span>
                      )
                    })()}
                    {device.doorRole && (
                      <span
                        className="px-2 py-0.5 rounded-full text-xs font-medium"
                        style={{ background: '#e0e7ff', color: '#3730a3' }}
                      >
                        {doorRoleLabel(device.doorRole)}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-gray-400 font-mono">{device.deviceId}</p>
                  {device.branchId && (
                    <p className="text-xs text-gray-500 mt-0.5">
                      {branches.find((branch) => branch.id === device.branchId)?.name || 'Ərazi təyin edilib'}
                    </p>
                  )}
                </div>

                {/* IP */}
                <div className="hidden md:block text-center min-w-[130px]">
                  <p className="text-xs text-gray-400 mb-0.5">IP ünvanı</p>
                  <p className="text-sm font-mono text-gray-700">
                    {device.deviceIp}{device.devicePort && device.devicePort !== 80 ? `:${device.devicePort}` : ''}
                  </p>
                </div>

                {/* Son sinxron — relative + absolute time */}
                <div className="hidden lg:block text-center min-w-[160px]">
                  <p className="text-xs text-gray-400 mb-0.5">Son sinxron</p>
                  {device.lastSyncTime ? (
                    <>
                      <p className="text-sm font-medium text-gray-700">{relativeTime(device.lastSyncTime)}</p>
                      <p className="text-xs text-gray-400">{new Date(device.lastSyncTime).toLocaleString()}</p>
                    </>
                  ) : (
                    <p className="text-sm text-gray-400">Heç vaxt</p>
                  )}
                </div>

                {/* Actions */}
                <div className="flex flex-wrap items-center justify-end gap-2 flex-shrink-0">
                  <button
                    onClick={() => handleSync(device.id)}
                    disabled={syncingId === device.id}
                    className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors disabled:opacity-50"
                    style={{ color: '#a855f7', borderColor: '#e9d5ff', background: '#faf5ff' }}
                  >
                    <svg className={`w-3.5 h-3.5 ${syncingId === device.id ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
                    </svg>
                    {syncingId === device.id ? 'Sinxronlaşdırılır...' : 'Sinxron'}
                  </button>
                  <button
                    onClick={() => openEmployeeAssignments(device)}
                    className="px-3 py-1.5 text-xs font-medium text-blue-700 bg-blue-50 rounded-lg hover:bg-blue-100 transition-colors"
                  >
                    Əməkdaşlar
                  </button>
                  {canReconcile && <button onClick={() => setReconciliationDevice(device)}
                    className="px-3 py-1.5 text-xs font-medium text-purple-700 bg-purple-50 rounded-lg hover:bg-purple-100 transition-colors">
                    Əməkdaşları uyğunlaşdır
                  </button>}
                  <button
                    onClick={() => handleEmployeeSync(device)}
                    disabled={employeeSyncingId === device.id}
                    className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-emerald-700 bg-emerald-50 rounded-lg hover:bg-emerald-100 transition-colors disabled:opacity-50"
                    title="Təyin edilmiş əməkdaşları və mövcud üz şəkillərini cihaza göndər"
                  >
                    <svg className={`w-3.5 h-3.5 ${employeeSyncingId === device.id ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v12m0 0l-4-4m4 4l4-4M5 20h14" />
                    </svg>
                    {employeeSyncingId === device.id ? 'Göndərilir...' : 'Əməkdaş sinxronu'}
                  </button>
                  <button
                    onClick={() => openEdit(device)}
                    className="px-3 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 rounded-lg hover:bg-gray-200 transition-colors"
                  >
                    Redaktə et
                  </button>
                  <button
                    onClick={() => setDeleteConfirm(device)}
                    className="px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 rounded-lg hover:bg-red-100 transition-colors"
                  >
                    Sil
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Create/Edit Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 p-6">
            <h2 className="text-xl font-bold text-gray-900 mb-4">
              {editingDevice ? 'Cihazı redaktə et' : 'Cihaz əlavə et'}
            </h2>
            {formError && (
              <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-lg mb-4 text-sm">
                {formError}
              </div>
            )}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Cihaz adı</label>
                <input
                  type="text"
                  value={form.deviceName}
                  onChange={(e) => setForm({ ...form, deviceName: e.target.value })}
                  placeholder="məs., Əsas giriş"
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">IP ünvanı *</label>
                <input
                  type="text"
                  value={form.deviceIp}
                  onChange={(e) => setForm({ ...form, deviceIp: e.target.value })}
                  placeholder="192.168.1.100"
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Port</label>
                <input
                  type="number"
                  value={form.devicePort}
                  onChange={(e) => setForm({ ...form, devicePort: e.target.value ? Number(e.target.value) : '' })}
                  placeholder="80"
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">İstifadəçi adı</label>
                <input
                  type="text"
                  value={form.username}
                  onChange={(e) => setForm({ ...form, username: e.target.value })}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">
                  Şifrə {editingDevice ? '(boş saxlayın ki, dəyişməsin)' : ''}
                </label>
                <input
                  type="password"
                  autoComplete="new-password"
                  value={form.password}
                  onChange={(e) => setForm({ ...form, password: e.target.value })}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Ərazi</label>
                <select
                  value={form.branchId}
                  onChange={(e) => {
                    const branchId = e.target.value ? Number(e.target.value) : ''
                    setForm({ ...form, branchId })
                    setSelectedDoorId('')
                    setDoorRole('')
                    if (branchId) {
                      loadDoors(branchId)
                    } else {
                      setDoors([])
                    }
                  }}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                >
                  <option value="">Ərazi seçin...</option>
                  {branches.map((b) => (
                    <option key={b.id} value={b.id}>{b.name}</option>
                  ))}
                </select>
              </div>
              {form.branchId && (
                <>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Qapı</label>
                    <select
                      value={selectedDoorId}
                      onChange={(e) => {
                        const val = e.target.value
                        setSelectedDoorId(val === 'new' ? 'new' : val ? Number(val) : '')
                      }}
                      className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                    >
                      <option value="">Yoxdur</option>
                      {doors.map((d) => (
                        <option key={d.id} value={d.id}>{d.name}</option>
                      ))}
                      <option value="new">+ Yeni qapı yarat...</option>
                    </select>
                  </div>
                  {selectedDoorId === 'new' && (
                    <div>
                      <label className="block text-sm font-medium text-gray-700 mb-1">Yeni qapı adı</label>
                      <input
                        type="text"
                        value={newDoorName}
                        onChange={(e) => setNewDoorName(e.target.value)}
                        placeholder="məs., Əsas qapı"
                        className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                      />
                    </div>
                  )}
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Rol</label>
                    <select
                      value={doorRole}
                      onChange={(e) => setDoorRole(e.target.value as 'ENTRY' | 'EXIT' | 'BOTH' | '')}
                      className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                    >
                      <option value="">Rol seçin...</option>
                      <option value="ENTRY">Giriş</option>
                      <option value="EXIT">Çıxış</option>
                      <option value="BOTH">Giriş və çıxış</option>
                    </select>
                    {doorRole === 'BOTH' && (
                      <p className="mt-2 text-xs text-amber-700">
                        Eyni ərazidə bu roldakı cihazların günün ilk keçidi giriş, son keçidi çıxış sayılır.
                        Çıxış saatı günün sonunadək yenilənir. Tək keçiddə çıxış yoxdur.
                        Sərbəst qrafik dəstəklənmir; gecə yarısından sonrakı keçid yeni günə aiddir.
                        Yeni rol və ərazi təyinatı saxlandıqdan sonrakı keçidlərə tətbiq olunur.
                      </p>
                    )}
                  </div>
                </>
              )}
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">{t('common.status')}</label>
                <select
                  value={form.status}
                  onChange={(e) => setForm({ ...form, status: e.target.value })}
                  className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
                >
                  <option value="ACTIVE">{statusLabel('ACTIVE')}</option>
                  <option value="INACTIVE">{statusLabel('INACTIVE')}</option>
                </select>
              </div>
            </div>
            <div className="flex justify-end gap-3 mt-6">
              <button
                onClick={() => setShowModal(false)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50"
              >
                Ləğv et
              </button>
              <button
                onClick={handleSave}
                disabled={saving}
                className="px-4 py-2 text-sm text-white rounded-lg disabled:opacity-50 transition-colors"
                style={{ background: '#a855f7' }}
              >
                {saving ? 'Yadda saxlanılır...' : editingDevice ? 'Yenilə' : 'Yarat'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Device employee assignment modal */}
      {assignmentDevice && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-3xl p-6 max-h-[88vh] flex flex-col">
            <div className="flex items-start justify-between gap-3 mb-4">
              <div>
                <h2 className="text-xl font-bold text-gray-900">Cihaz əməkdaşları</h2>
                <p className="text-sm text-gray-500 mt-1">
                  {assignmentDevice.deviceName || assignmentDevice.deviceId}
                  {assignmentView?.areaName ? ` · ${assignmentView.areaName}` : ''}
                </p>
              </div>
              <button
                onClick={() => setAssignmentDevice(null)}
                className="p-2 text-gray-400 hover:text-gray-700"
                aria-label="Bağla"
              >
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            {assignmentError && (
              <div className="mb-3 border border-red-200 bg-red-50 text-red-700 rounded-lg px-3 py-2 text-sm">
                {assignmentError}
              </div>
            )}

            {assignmentLoading ? (
              <div className="py-12 text-center text-gray-500">Yüklənir...</div>
            ) : assignmentView ? (
              <>
                <div className="flex flex-wrap gap-2 mb-3">
                  <input
                    value={assignmentSearch}
                    onChange={(event) => setAssignmentSearch(event.target.value)}
                    placeholder="Ad, kod və ya FIN axtar"
                    className="flex-1 min-w-[220px] border border-gray-300 rounded-lg px-3 py-2 text-sm"
                  />
                  <select
                    value={groupAreaId}
                    onChange={(event) => setGroupAreaId(event.target.value ? Number(event.target.value) : '')}
                    className="border border-gray-300 rounded-lg px-3 py-2 text-sm"
                  >
                    <option value="">Qrup üçün ərazi seçin</option>
                    {branches.map((branch) => (
                      <option key={branch.id} value={branch.id}>{branch.name}</option>
                    ))}
                  </select>
                  <button
                    type="button"
                    onClick={addAreaGroup}
                    disabled={groupAreaId === ''}
                    className="px-3 py-2 text-sm font-medium border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-50"
                  >
                    Qrupu əlavə et
                  </button>
                </div>

                <p className="text-xs text-gray-500 mb-2">
                  Cihazın öz ərazisindəki əməkdaşlar avtomatik seçilir. Digər əməkdaşları fərdi və ya ərazi qrupu ilə əlavə edə bilərsiniz.
                </p>
                <div className="border border-gray-200 rounded-lg overflow-y-auto flex-1 min-h-[260px]">
                  {assignmentView.employees
                    .filter((employee) => {
                      const query = assignmentSearch.trim().toLowerCase()
                      return !query || `${employee.fullName} ${employee.employeeCode} ${employee.finNumber || ''}`
                        .toLowerCase().includes(query)
                    })
                    .map((employee) => {
                      const checked = employee.areaAssigned || manualEmployeeIds.includes(employee.employeeId)
                      return (
                        <label
                          key={employee.employeeId}
                          className="flex items-center gap-3 px-3 py-2.5 border-b border-gray-100 last:border-b-0 hover:bg-gray-50"
                        >
                          <input
                            type="checkbox"
                            checked={checked}
                            disabled={employee.areaAssigned}
                            onChange={() => toggleManualEmployee(employee.employeeId)}
                          />
                          <span className="min-w-0 flex-1">
                            <span className="block text-sm font-medium text-gray-900 truncate">{employee.fullName}</span>
                            <span className="block text-xs text-gray-500 truncate">
                              {employee.employeeCode}{employee.finNumber ? ` · ${employee.finNumber}` : ''}
                              {employee.areaNames?.length ? ` · ${employee.areaNames.join(', ')}` : ''}
                            </span>
                          </span>
                          <span className={`text-xs ${employee.areaAssigned ? 'text-emerald-700' : checked ? 'text-blue-700' : 'text-gray-400'}`}>
                            {employee.areaAssigned ? 'Ərazi ilə' : checked ? 'Fərdi' : 'Təyin edilməyib'}
                          </span>
                        </label>
                      )
                    })}
                </div>
              </>
            ) : null}

            <div className="flex justify-end gap-3 mt-5">
              <button
                onClick={() => setAssignmentDevice(null)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50"
              >
                Ləğv et
              </button>
              <button
                onClick={saveEmployeeAssignments}
                disabled={assignmentSaving || assignmentLoading || !assignmentView}
                className="px-4 py-2 text-sm text-white rounded-lg disabled:opacity-50"
                style={{ background: '#a855f7' }}
              >
                {assignmentSaving ? 'Yadda saxlanılır...' : 'Yadda saxla'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Door Manager Modal */}
      {showDoorManager && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 p-6">
            <h2 className="text-xl font-bold text-gray-900 mb-4">Qapıları idarə et</h2>
            <div className="mb-4">
              <label className="block text-sm font-medium text-gray-700 mb-1">Ərazi</label>
              <select
                value={managerBranchId}
                onChange={(e) => {
                  const val = e.target.value ? Number(e.target.value) : ''
                  setManagerBranchId(val)
                  if (val) loadManagerDoors(Number(val))
                  else setManagerDoors([])
                }}
                className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-purple-500 text-sm"
              >
                <option value="">Ərazi seçin...</option>
                {branches.map((b) => (
                  <option key={b.id} value={b.id}>{b.name}</option>
                ))}
              </select>
            </div>
            <div className="max-h-64 overflow-y-auto space-y-2">
              {managerLoading ? (
                <div className="text-center text-gray-400 text-sm py-4">Yüklənir...</div>
              ) : managerDoors.length === 0 ? (
                <div className="text-center text-gray-400 text-sm py-4">
                  {managerBranchId ? 'Bu ərazi üçün qapı tapılmadı.' : 'Qapıları görmək üçün ərazi seçin.'}
                </div>
              ) : (
                managerDoors.map((door) => (
                  <div key={door.id} className="flex items-center justify-between bg-gray-50 rounded-lg px-3 py-2">
                    <div>
                      <p className="text-sm font-medium text-gray-800">{door.name}</p>
                      <p className="text-xs text-gray-400">{statusLabel(door.status)}</p>
                    </div>
                    <button
                      onClick={() => setDoorDeleteConfirm(door)}
                      className="px-2 py-1 text-xs font-medium text-red-600 bg-red-50 rounded hover:bg-red-100 transition-colors"
                    >
                      Sil
                    </button>
                  </div>
                ))
              )}
            </div>
            <div className="flex justify-end mt-5">
              <button
                onClick={() => setShowDoorManager(false)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50"
              >
                Bağla
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Door Delete Confirmation */}
      {doorDeleteConfirm && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-sm mx-4 p-6">
            <h2 className="text-lg font-bold text-gray-900 mb-2">{t('devices.deleteDoorTitle')}</h2>
            <p className="text-gray-600 mb-6 text-sm">
              {t('devices.deleteDoorConfirm')}
              {' '}
              <strong>{doorDeleteConfirm.name}</strong>
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setDoorDeleteConfirm(null)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50"
              >
                Ləğv et
              </button>
              <button
                onClick={handleDeleteDoor}
                className="px-4 py-2 text-sm bg-red-600 text-white rounded-lg hover:bg-red-700"
              >
                Sil
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Device Delete Confirmation */}
      {deleteConfirm && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-sm mx-4 p-6">
            <h2 className="text-lg font-bold text-gray-900 mb-2">{t('devices.deleteDeviceTitle')}</h2>
            <p className="text-gray-600 mb-6 text-sm">
              {t('devices.deleteDeviceConfirm')}
              {' '}
              <strong>{deleteConfirm.deviceName || deleteConfirm.deviceId}</strong>
            </p>
            <div className="flex justify-end gap-3">
              <button
                onClick={() => setDeleteConfirm(null)}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50"
              >
                Ləğv et
              </button>
              <button
                onClick={handleDelete}
                className="px-4 py-2 text-sm bg-red-600 text-white rounded-lg hover:bg-red-700"
              >
                Sil
              </button>
            </div>
          </div>
        </div>
      )}
      {reconciliationDevice && <DeviceReconciliationModal device={reconciliationDevice} onClose={() => setReconciliationDevice(null)} />}
    </Layout>
  )
}
