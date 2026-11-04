import { useEffect, useRef, useState } from 'react'
import Layout from '../components/Layout.tsx'
import EmployeeDetailModal from '../components/EmployeeDetailModal.tsx'
import EmployeeAvatar from '../components/EmployeeAvatar.tsx'
import EmployeePhotoCapture from '../components/EmployeePhotoCapture.tsx'
import DataTransferControls from '../components/DataTransferControls.tsx'
import { useEmployeeStore } from '../store/employeeStore.ts'
import { useBranchStore } from '../store/branchStore.ts'
import { Department, Employee, Position, Timetable } from '../types'
import { employeeApi } from '../api/employeeApi.ts'
import { departmentApi } from '../api/departmentApi.ts'
import { positionApi } from '../api/positionApi.ts'
import { deviceApi } from '../api/deviceApi.ts'
import { deviceUserApi } from '../api/deviceUserApi.ts'
import { timetableApi } from '../api/timetableApi.ts'
import { getApiErrorMessage } from '../utils/apiError.ts'
import { todayInAppTimeZone } from '../utils/dateTime.ts'
import { validateFaceImageSize } from '../utils/faceImage.ts'
import { toast } from '../store/toastStore.ts'

const UI_SHIFT_TYPES = ['STANDARD', 'FLEXIBLE'] as const
const SHIFT_TYPE_LABELS: Record<string, string> = {
  STANDARD: 'Standart Növbə',
  FLEXIBLE: 'Sərbəst Növbə',
  FIRST_ENTRY: 'Sərbəst Növbə',
  SERBEST: 'Sərbəst Növbə',
  FREE_SHIFT: 'Sərbəst Növbə',
  FREE: 'Sərbəst Növbə',
  MORNING: 'Standart Növbə',
  NIGHT: 'Standart Növbə',
}

interface EmployeeFormData {
  deviceEmployeeNo: string
  firstName: string
  lastName: string
  fatherName: string
  email: string
  mobilePhone: string
  gender: string
  finNumber: string
  serialNumber: string
  birthDate: string
  positionId: number | ''
  departmentId: number | ''
  contractNumber: string
  branchId: number | ''
  areaIds: number[]
  hireDate: string
  contractEndDate: string
  annualLeaveDuration: number | ''
  annualLeaveBalance: number | ''
  employmentStatus: string
  timetableId: number | ''
  shiftType: string
  salary: number | ''
  netSalary: number | ''
  actualSalary: number | ''
  priorExperienceYears: number | ''
  priorExperienceMonths: number | ''
  priorExperienceDays: number | ''
  hourlyRate: number | ''
  allowance: string
  emergencyContact: string
  address: string
  registeredAddress: string
  notes: string
  area: string
}

type EmployeeFormErrors = Partial<Record<keyof EmployeeFormData, string>>

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null
const extractStatusCode = (value: unknown): number | undefined => {
  if (!isRecord(value)) return undefined
  const response = value.response
  if (!isRecord(response)) return undefined
  return typeof response.status === 'number' ? response.status : undefined
}

const defaultForm: EmployeeFormData = {
  deviceEmployeeNo: '',
  firstName: '',
  lastName: '',
  fatherName: '',
  email: '',
  mobilePhone: '',
  gender: '',
  finNumber: '',
  serialNumber: '',
  birthDate: '',
  positionId: '',
  departmentId: '',
  contractNumber: '',
  branchId: '',
  areaIds: [],
  hireDate: todayInAppTimeZone(),
  contractEndDate: '',
  annualLeaveDuration: 30,
  annualLeaveBalance: 30,
  employmentStatus: 'ACTIVE',
  timetableId: '',
  shiftType: '',
  salary: '',
  netSalary: '',
  actualSalary: '',
  priorExperienceYears: '',
  priorExperienceMonths: '',
  priorExperienceDays: '',
  hourlyRate: '',
  allowance: '',
  emergencyContact: '',
  address: '',
  registeredAddress: '',
  notes: '',
  area: '',
}

const AVATAR_COLORS = ['#6366f1', '#a855f7', '#10b981', '#f59e0b', '#ef4444', '#3b82f6']

const experienceFromHireDate = (hireDate: string) => {
  if (!hireDate) return { years: 0, months: 0, days: 0 }
  const start = new Date(`${hireDate}T00:00:00`)
  const end = new Date()
  if (Number.isNaN(start.getTime()) || start > end) return { years: 0, months: 0, days: 0 }
  let years = end.getFullYear() - start.getFullYear()
  let months = end.getMonth() - start.getMonth()
  let days = end.getDate() - start.getDate()
  if (days < 0) {
    months -= 1
    days += new Date(end.getFullYear(), end.getMonth(), 0).getDate()
  }
  if (months < 0) { years -= 1; months += 12 }
  return { years, months, days }
}

const addExperience = (a: { years: number; months: number; days: number }, b: { years: number; months: number; days: number }) => {
  let days = a.days + b.days
  let months = a.months + b.months + Math.floor(days / 30)
  days %= 30
  const years = a.years + b.years + Math.floor(months / 12)
  months %= 12
  return { years, months, days }
}

const experienceLabel = (value: { years: number; months: number; days: number }) =>
  `${value.years} il, ${value.months} ay, ${value.days} gün`

function getAvatarColor(name: string) {
  let hash = 0
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash)
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length]
}

/** Match ISAPI device user by Hikvision person ID (deviceEmployeeNo), not prefixed HR code. */
function matchesDevicePerson(deviceEmployeeNo: string | undefined, employeeId: string | undefined, candidateNo: unknown): boolean {
  const no = String(candidateNo || '').trim()
  if (!no) return false
  const deviceNo = (deviceEmployeeNo || '').trim()
  if (deviceNo && no === deviceNo) return true
  const hrId = (employeeId || '').trim()
  return !!hrId && no === hrId
}

export default function EmployeesPage() {
  const defaultDeviceId = Number(import.meta.env.VITE_DEFAULT_DEVICE_ID || 1)
  const { employees, loading, error, fetchEmployees, terminateEmployee, totalPages, currentPage, totalElements } = useEmployeeStore()
  const { branches, fetchBranches } = useBranchStore()
  const [search, setSearch] = useState('')
  const [departments, setDepartments] = useState<Department[]>([])
  const [positions, setPositions] = useState<Position[]>([])
  const [timetables, setTimetables] = useState<Timetable[]>([])
  const [employeeDoors, setEmployeeDoors] = useState<string[]>([])
  const [filterDept, setFilterDept] = useState<string>('')
  const [filterStatus, setFilterStatus] = useState<string>('')
  const [filterShift, setFilterShift] = useState<string>('')
  const [filterBranch, setFilterBranch] = useState<string>('')
  const [showWizard, setShowWizard] = useState(false)
  const [currentStep, setCurrentStep] = useState(1)
  const [editingEmployee, setEditingEmployee] = useState<Employee | null>(null)
  const [form, setForm] = useState<EmployeeFormData>(defaultForm)
  const companyExperience = experienceFromHireDate(form.hireDate)
  const previousExperience = {
    years: Number(form.priorExperienceYears || 0),
    months: Number(form.priorExperienceMonths || 0),
    days: Number(form.priorExperienceDays || 0),
  }
  const totalExperience = addExperience(previousExperience, companyExperience)
  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<EmployeeFormErrors>({})
  const [deleteConfirm, setDeleteConfirm] = useState<Employee | null>(null)
  const [terminationDate, setTerminationDate] = useState(todayInAppTimeZone())
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [deletingEmployee, setDeletingEmployee] = useState(false)
  const [employeeView, setEmployeeView] = useState<'active' | 'terminated'>('active')
  const [terminatedEmployees, setTerminatedEmployees] = useState<Employee[]>([])
  const [terminatedLoading, setTerminatedLoading] = useState(false)
  const [terminatedError, setTerminatedError] = useState<string | null>(null)
  const [retryingTerminationId, setRetryingTerminationId] = useState<number | null>(null)
  const [uploadingFaceEmployeeId, setUploadingFaceEmployeeId] = useState<number | null>(null)
  const [uploadFaceError, setUploadFaceError] = useState<string | null>(null)
  const [showProfileModal, setShowProfileModal] = useState(false)
  const [profileLoading, setProfileLoading] = useState(false)
  const [profileError, setProfileError] = useState<string | null>(null)
  const [selectedEmployee, setSelectedEmployee] = useState<Employee | null>(null)
  const [profileImageSrc, setProfileImageSrc] = useState<string | null>(null)
  const [wizardImagePreview, setWizardImagePreview] = useState<string | null>(null)
  const [wizardImageFile, setWizardImageFile] = useState<File | null>(null)
  const [wizardImageError, setWizardImageError] = useState<string | null>(null)
  const [wizardImageRemovalRequested, setWizardImageRemovalRequested] = useState(false)
  const wizardImageObjectUrlRef = useRef<string | null>(null)
  const wizardImageLoadTokenRef = useRef(0)

  useEffect(() => {
    fetchEmployees(0, 20)
    departmentApi.getAll().then((res) => setDepartments(res.data?.data ?? []))
    positionApi.getAll().then((res) => setPositions(res.data?.data ?? []))
    fetchBranches()
    timetableApi.getAll().then((res) => setTimetables(res.data?.data ?? []))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => () => {
    if (wizardImageObjectUrlRef.current) {
      URL.revokeObjectURL(wizardImageObjectUrlRef.current)
    }
  }, [])

  const formatDate = (value?: string) => {
    if (!value) return '—'
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) return value
    return date.toLocaleDateString('az-AZ')
  }

  const statusLabel = (status: Employee['employmentStatus']) => {
    if (status === 'ACTIVE') return 'Aktiv'
    if (status === 'ON_LEAVE') return 'Məzuniyyətdə'
    if (status === 'TERMINATED') return 'İşdən çıxarılıb'
    return 'Deaktiv'
  }

  const loadTerminatedEmployees = async () => {
    setTerminatedLoading(true)
    setTerminatedError(null)
    try {
      const response = await employeeApi.getByStatus('TERMINATED')
      setTerminatedEmployees(response.data.data ?? [])
    } catch (requestError: unknown) {
      const message = getApiErrorMessage(requestError, 'İşdən çıxarılan əməkdaşları yükləmək alınmadı')
      setTerminatedError(message)
      toast.error('Siyahı yüklənmədi', message)
    } finally {
      setTerminatedLoading(false)
    }
  }

  const stepTitles = ['Ümumi məlumat', 'İş məlumatları', 'Şəkil']

  useEffect(() => {
    if (employeeView !== 'terminated') return
    let cancelled = false
    const interval = window.setInterval(async () => {
      try {
        const response = await employeeApi.getByStatus('TERMINATED')
        if (!cancelled) setTerminatedEmployees(response.data.data ?? [])
      } catch {
        // Preserve the last known status; manual refresh reports connection errors.
      }
    }, 15000)
    return () => { cancelled = true; window.clearInterval(interval) }
  }, [employeeView])

  const branchLabelById = (branchId?: number) => {
    if (!branchId) return '—'
    return branches.find((b) => b.id === branchId)?.name || '—'
  }

  const loadEmployeeDoors = async (employeeId?: number) => {
    if (!employeeId) {
      setEmployeeDoors([])
      return
    }
    try {
      const res = await employeeApi.getDoors(employeeId)
      setEmployeeDoors(res.data?.data ?? [])
    } catch {
      setEmployeeDoors([])
    }
  }

  const openCreate = () => {
    wizardImageLoadTokenRef.current += 1
    if (wizardImageObjectUrlRef.current) {
      URL.revokeObjectURL(wizardImageObjectUrlRef.current)
      wizardImageObjectUrlRef.current = null
    }
    setEditingEmployee(null)
    setForm({ ...defaultForm, hireDate: todayInAppTimeZone() })
    setEmployeeDoors([])
    setWizardImageFile(null)
    setWizardImageError(null)
    setWizardImageRemovalRequested(false)
    setWizardImagePreview(null)
    setCurrentStep(1)
    setFormError(null)
    setFieldErrors({})
    setShowWizard(true)
  }

  const openEdit = (emp: Employee) => {
    const imageLoadToken = ++wizardImageLoadTokenRef.current
    setEditingEmployee(emp)
    setForm({
      deviceEmployeeNo: emp.deviceEmployeeNo || emp.employeeId || '',
      firstName: emp.firstName,
      lastName: emp.lastName,
      fatherName: emp.fatherName || '',
      email: emp.email || '',
      mobilePhone: emp.mobilePhone || '',
      gender: emp.gender || '',
      finNumber: emp.finNumber || '',
      serialNumber: emp.serialNumber || '',
      birthDate: emp.birthDate || '',
      positionId: emp.positionId || '',
      departmentId: emp.departmentId || '',
      contractNumber: emp.contractNumber || '',
      branchId: emp.branchId || '',
      areaIds: emp.areaIds?.length ? emp.areaIds : (emp.branchId ? [emp.branchId] : []),
      hireDate: emp.hireDate || todayInAppTimeZone(),
      contractEndDate: emp.contractEndDate || '',
      annualLeaveDuration: emp.annualLeaveDuration ?? 30,
      annualLeaveBalance: emp.annualLeaveBalance ?? 30,
      employmentStatus: emp.employmentStatus || 'ACTIVE',
      timetableId: emp.timetableId || '',
      shiftType: emp.shiftType || '',
      salary: emp.salary ?? '',
      netSalary: emp.netSalary ?? '',
      actualSalary: emp.actualSalary ?? '',
      priorExperienceYears: emp.priorExperienceYears ?? '',
      priorExperienceMonths: emp.priorExperienceMonths ?? '',
      priorExperienceDays: emp.priorExperienceDays ?? '',
      hourlyRate: emp.hourlyRate ?? '',
      allowance: emp.allowance || '',
      emergencyContact: emp.emergencyContact || '',
      address: emp.address || '',
      registeredAddress: emp.registeredAddress || '',
      notes: emp.notes || '',
      area: emp.area || '',
    })
    loadEmployeeDoors(emp.id)
    setWizardImageFile(null)
    setWizardImageError(null)
    setWizardImageRemovalRequested(false)
    if (wizardImageObjectUrlRef.current) {
      URL.revokeObjectURL(wizardImageObjectUrlRef.current)
      wizardImageObjectUrlRef.current = null
    }
    setWizardImagePreview(null)
    setCurrentStep(1)
    setFormError(null)
    setFieldErrors({})
    setShowWizard(true)

    if (emp.faceImageUrl) {
      void employeeApi.getFaceImage(emp.id)
        .then((response) => {
          if (wizardImageLoadTokenRef.current !== imageLoadToken) return
          const previewUrl = URL.createObjectURL(response.data)
          if (wizardImageObjectUrlRef.current) {
            URL.revokeObjectURL(wizardImageObjectUrlRef.current)
          }
          wizardImageObjectUrlRef.current = previewUrl
          setWizardImagePreview(previewUrl)
        })
        .catch(() => {
          if (wizardImageLoadTokenRef.current === imageLoadToken) {
            setWizardImagePreview(null)
          }
        })
    }
  }

  const closeWizard = () => {
    wizardImageLoadTokenRef.current += 1
    if (wizardImageObjectUrlRef.current) {
      URL.revokeObjectURL(wizardImageObjectUrlRef.current)
      wizardImageObjectUrlRef.current = null
    }
    setShowWizard(false)
    setWizardImageFile(null)
    setWizardImageError(null)
    setWizardImageRemovalRequested(false)
    setWizardImagePreview(null)
    setCurrentStep(1)
    setFormError(null)
    setFieldErrors({})
  }

  const openProfile = async (employee: Employee) => {
    setShowProfileModal(true)
    setProfileLoading(true)
    setProfileError(null)
    setSelectedEmployee(employee)
    try {
      let res = await employeeApi.getById(employee.id)
      if (res.data?.data) {
        let details = res.data.data
        if (!details.faceImageUrl && details.deviceIds && details.deviceIds.length > 0) {
          const devicesRes = await deviceApi.getAll()
          const devicesList = (devicesRes.data as any)?.data ?? (Array.isArray(devicesRes.data) ? devicesRes.data : [])
          const deviceMap = new Map<number, number>()
          for (const d of devicesList) {
            if (d.id != null && d.deviceId != null) {
              deviceMap.set(Number(d.id), Number(d.deviceId))
            }
          }
          // Match device user by Hikvision person ID (deviceEmployeeNo), not prefixed HR employeeId.
          for (const backendDeviceId of details.deviceIds) {
            const isapiDeviceId = deviceMap.get(Number(backendDeviceId))
            if (!isapiDeviceId) continue
            try {
              const usersRes = await deviceUserApi.getAll(isapiDeviceId)
              const users = Array.isArray(usersRes.data) ? usersRes.data : (usersRes.data as any)?.data ?? []
              const deviceUser = users.find((u: any) =>
                matchesDevicePerson(details.deviceEmployeeNo, details.employeeId, u.employeeNo)
              )
              if (deviceUser) {
                const syncRes = await deviceUserApi.syncFaceFromDevice(isapiDeviceId, deviceUser.id, details.id)
                if (syncRes.data?.status === 'SUCCESS' || (syncRes.data as any)?.status === 'SUCCESS') {
                  res = await employeeApi.getById(employee.id)
                  details = res.data?.data || details
                  break
                }
              }
            } catch {
              // Try next device
            }
          }
        }
        if (profileImageSrc) {
          URL.revokeObjectURL(profileImageSrc)
          setProfileImageSrc(null)
        }
        if (details.faceImageUrl) {
          try {
            const imageResponse = await employeeApi.getFaceImage(details.id)
            setProfileImageSrc(URL.createObjectURL(imageResponse.data))
          } catch {
            setProfileImageSrc(null)
          }
        }
        setSelectedEmployee(details)
      }
    } catch (e: unknown) {
      setProfileError((e as Error).message || 'Əməkdaş məlumatları yüklənmədi')
    } finally {
      setProfileLoading(false)
    }
  }

  const closeProfile = () => {
    if (profileImageSrc) {
      URL.revokeObjectURL(profileImageSrc)
    }
    setProfileImageSrc(null)
    setShowProfileModal(false)
    setProfileError(null)
  }

  const setFormField = <K extends keyof EmployeeFormData>(key: K, value: EmployeeFormData[K]) => {
    setForm((prev) => ({ ...prev, [key]: value }))
    setFieldErrors((prev) => {
      if (!prev[key]) return prev
      const next = { ...prev }
      delete next[key]
      return next
    })
  }

  const getStepValidationErrors = (step: number): EmployeeFormErrors => {
    const errors: EmployeeFormErrors = {}

    if (step === 1) {
      if (!editingEmployee && !form.deviceEmployeeNo.trim()) errors.deviceEmployeeNo = 'Cihazdakı əməkdaş ID daxil edilməlidir.'
      if (!form.finNumber.trim()) errors.finNumber = 'FIN daxil edilməlidir.'
      if (!form.firstName.trim()) errors.firstName = 'Ad daxil edilməlidir.'
      if (!form.lastName.trim()) errors.lastName = 'Soyad daxil edilməlidir.'
    }

    if (step === 2) {
      if (!form.branchId) errors.branchId = 'Əsas ərazi seçilməlidir.'
      if (!form.departmentId) errors.departmentId = 'Departament seçilməlidir.'
      if (!form.timetableId) errors.timetableId = 'İş cədvəli seçilməlidir.'
    }

    return errors
  }

  const validateStep = (step: number) => {
    const errors = getStepValidationErrors(step)
    setFieldErrors(errors)
    return Object.keys(errors).length === 0
  }

  const handleNextStep = () => {
    if (!validateStep(currentStep)) return
    setFormError(null)
    setCurrentStep((step) => Math.min(step + 1, stepTitles.length))
  }

  const resolveIsapiDeviceIds = async (employee: Employee): Promise<number[]> => {
    const assignedDeviceIds = employee.deviceIds && employee.deviceIds.length > 0
      ? employee.deviceIds
      : [defaultDeviceId]

    try {
      const devicesRes = await deviceApi.getAll()
      const devices = devicesRes.data?.data ?? (Array.isArray(devicesRes.data) ? devicesRes.data : [])
      const bridgeIdByBackendId = new Map<number, string>()
      for (const device of devices) {
        if (device.id != null && device.deviceId != null) {
          bridgeIdByBackendId.set(device.id, device.deviceId)
        }
      }
      return [...new Set(assignedDeviceIds
        .map((id) => Number(bridgeIdByBackendId.get(Number(id)) ?? id))
        .filter((id) => Number.isFinite(id) && id > 0))]
    } catch (error) {
      console.info('[resolveIsapiDeviceIds] device list unavailable, using assigned IDs', error)
      return [...new Set(assignedDeviceIds.map(Number).filter((id) => Number.isFinite(id) && id > 0))]
    }
  }

  /**
   * Same captured photo is used for:
   * 1) employee profile photo (always persisted)
   * 2) Hikvision / face detection (best-effort per assigned device)
   */
  const uploadFaceForEmployee = async (employee: Employee, file: File | null): Promise<string[]> => {
    if (!file) return []

    const response = await employeeApi.uploadFaceImage(employee.id, file)
    return response.data?.data?.errors ?? []
  }

  const deleteFaceForEmployee = async (employee: Employee): Promise<string[]> => {
    // The local profile image is authoritative and must be removable even when a terminal is offline.
    await employeeApi.deleteFaceImage(employee.id)

    const errors: string[] = []
    const isapiDeviceIds = await resolveIsapiDeviceIds(employee)
    for (const isapiDeviceId of isapiDeviceIds) {
      try {
        const usersRes = await deviceUserApi.getAll(isapiDeviceId)
        const users = Array.isArray(usersRes.data) ? usersRes.data : []
        const deviceUser = users.find((user) =>
          matchesDevicePerson(employee.deviceEmployeeNo, employee.employeeId, user.employeeNo)
        )
        if (!deviceUser) continue

        try {
          await deviceUserApi.deleteFace(isapiDeviceId, deviceUser.id)
        } catch (error) {
          if (extractStatusCode(error) !== 404) throw error
        }
      } catch (error) {
        errors.push(`Cihaz ${isapiDeviceId}: ${getApiErrorMessage(error, 'üz şəkli silinmədi')}`)
      }
    }
    return errors
  }

  const handleSave = async () => {
    if (!validateStep(1)) {
      setCurrentStep(1)
      return
    }
    if (!validateStep(2)) {
      setCurrentStep(2)
      return
    }
    setSaving(true)
    setFormError(null)
    try {
      const payload: Partial<Employee> = {
        deviceEmployeeNo: form.deviceEmployeeNo.trim() || undefined,
        firstName: form.firstName.trim(),
        lastName: form.lastName.trim(),
        fatherName: form.fatherName,
        email: form.email,
        mobilePhone: form.mobilePhone,
        gender: form.gender,
        finNumber: form.finNumber.trim(),
        serialNumber: form.serialNumber,
        birthDate: form.birthDate || undefined,
        departmentId: Number(form.departmentId),
        positionId: form.positionId ? Number(form.positionId) : undefined,
        contractNumber: form.contractNumber,
        branchId: form.branchId ? Number(form.branchId) : undefined,
        areaIds: form.areaIds,
        hireDate: form.hireDate,
        contractEndDate: form.contractEndDate || undefined,
        annualLeaveDuration: form.annualLeaveDuration === '' ? undefined : Number(form.annualLeaveDuration),
        annualLeaveBalance: form.annualLeaveBalance === '' ? undefined : Number(form.annualLeaveBalance),
        employmentStatus: form.employmentStatus as 'ACTIVE' | 'INACTIVE' | 'ON_LEAVE',
        timetableId: Number(form.timetableId),
        shiftType: form.shiftType,
        salary: form.salary === '' ? undefined : Number(form.salary),
        netSalary: form.netSalary === '' ? undefined : Number(form.netSalary),
        actualSalary: form.actualSalary === '' ? undefined : Number(form.actualSalary),
        priorExperienceYears: form.priorExperienceYears === '' ? undefined : Number(form.priorExperienceYears),
        priorExperienceMonths: form.priorExperienceMonths === '' ? undefined : Number(form.priorExperienceMonths),
        priorExperienceDays: form.priorExperienceDays === '' ? undefined : Number(form.priorExperienceDays),
        hourlyRate: form.hourlyRate === '' ? undefined : Number(form.hourlyRate),
        allowance: form.allowance,
        emergencyContact: form.emergencyContact,
        address: form.address,
        registeredAddress: form.registeredAddress,
        notes: form.notes,
        area: form.area,
      }

      let savedEmployee: Employee | undefined
      if (editingEmployee) {
        const res = await employeeApi.update(editingEmployee.id, payload)
        savedEmployee = res.data?.data
      } else {
        const res = await employeeApi.create(payload)
        savedEmployee = res.data?.data
      }

      if (savedEmployee && (wizardImageFile || wizardImageRemovalRequested)) {
        try {
          const freshRes = await employeeApi.getById(savedEmployee.id)
          if (freshRes.data?.data) {
            savedEmployee = freshRes.data.data
          }
        } catch (e) {
          console.info('[handleSave] refetch employee failed, using original', e)
        }
      }

      if (savedEmployee && wizardImageRemovalRequested && !wizardImageFile) {
        try {
          const faceErrors = await deleteFaceForEmployee(savedEmployee)
          if (faceErrors.length > 0) {
            console.info('[handleSave] Hikvision face delete warnings:', faceErrors)
            toast.warning('Şəkil profildən silindi', 'Bəzi cihazlardan silmək mümkün olmadı.')
          }
        } catch (e) {
          const message = getApiErrorMessage(e, 'Profil şəkli silinmədi')
          setFormError(message)
          toast.error('Şəkil silinmədi', message)
          setSaving(false)
          return
        }
      }

      if (savedEmployee && wizardImageFile) {
        try {
          const faceErrors = await uploadFaceForEmployee(savedEmployee, wizardImageFile)
          if (faceErrors.length > 0) {
            if (!editingEmployee) {
              setEditingEmployee(savedEmployee)
            }
            const message = `Şəkil profilə saxlanıldı, amma cihaz sinxronu tamamlanmadı: ${faceErrors.join(' | ')}`
            setFormError(message)
            toast.warning('Əməkdaş saxlanıldı', message)
            return
          }
        } catch (e) {
          const message = getApiErrorMessage(e, 'Profil şəkli yadda saxlanılmadı')
          setFormError(message)
          toast.error('Şəkil saxlanılmadı', message)
          setSaving(false)
          return
        }
      }

      const refreshPage = editingEmployee ? currentPage : Math.floor(totalElements / 20)
      const refreshed = await fetchEmployees(refreshPage, 20)
      if (!refreshed) {
        if (!editingEmployee && savedEmployee) {
          setEditingEmployee(savedEmployee)
        }
        const message = 'Əməkdaş saxlanıldı, amma siyahı yenilənmədi. Yenidən cəhd edin.'
        setFormError(message)
        toast.warning('Siyahı yenilənmədi', message)
        return
      }
      closeWizard()
      toast.success(
        editingEmployee ? 'Əməkdaş yeniləndi' : 'Əməkdaş əlavə edildi',
        wizardImageFile
          ? 'Profil şəkli saxlanıldı. Cihaza göndərilərkən avtomatik uyğunlaşdırılır.'
          : undefined,
      )
      if (showProfileModal && selectedEmployee?.id === savedEmployee?.id) {
        openProfile(savedEmployee)
      }
    } catch (e: unknown) {
      const message = getApiErrorMessage(e, 'Saxlamaq alınmadı')
      setFormError(message)
      toast.error('Əməkdaş saxlanılmadı', message)
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!deleteConfirm) return
    setDeletingEmployee(true)
    setDeleteError(null)
    try {
      const result = await terminateEmployee(deleteConfirm.id, terminationDate)
      setDeleteConfirm(null)
      if (selectedEmployee?.id === deleteConfirm.id) {
        closeProfile()
      }
      if (result.failedDevices > 0) {
        toast.warning(
          'İşdən çıxarılıb — cihazlardan silinmə gözlənilir',
          `${result.removedDevices}/${result.totalDevices} cihazdan silindi. Online olduqda avtomatik təkrar ediləcək. ${result.errors.join(' | ')}`,
        )
      } else {
        toast.success(
          'Əməkdaş işdən çıxarıldı',
          result.totalDevices > 0
            ? `${result.removedDevices} cihazdan uğurla silindi.`
            : 'Əməkdaşın tarixi məlumatları saxlanıldı.',
        )
      }
    } catch (e: unknown) {
      const message = getApiErrorMessage(e, 'Əməkdaşı işdən çıxarmaq alınmadı')
      setDeleteError(message)
      toast.error('Əməkdaş işdən çıxarılmadı', message)
    } finally {
      setDeletingEmployee(false)
    }
  }

  const openDeleteConfirm = (employee: Employee) => {
    setDeleteError(null)
    setTerminationDate(todayInAppTimeZone())
    setDeleteConfirm(employee)
  }

  const closeDeleteConfirm = () => {
    if (deletingEmployee) return
    setDeleteError(null)
    setTerminationDate(todayInAppTimeZone())
    setDeleteConfirm(null)
  }

  const handleRetryDeviceRemoval = async (employee: Employee) => {
    setRetryingTerminationId(employee.id)
    try {
      const response = await employeeApi.terminate(employee.id)
      const result = response.data.data
      if (result.failedDevices > 0) {
        toast.warning(
          'Cihazlardan silinmə qismən tamamlandı',
          `${result.removedDevices}/${result.totalDevices} cihazdan silindi. Qalanları avtomatik təkrar ediləcək. ${result.errors.join(' | ')}`,
        )
      } else {
        toast.success('Cihazlardan silinmə tamamlandı', `${result.removedDevices} cihaz yoxlanıldı.`)
      }
      await loadTerminatedEmployees()
    } catch (requestError: unknown) {
      toast.error(
        'Cihazlardan silinmə təkrarlanmadı',
        getApiErrorMessage(requestError, 'Əməliyyatı təkrarlamaq alınmadı'),
      )
    } finally {
      setRetryingTerminationId(null)
    }
  }

  const handleSearch = () => {
    if (!search.trim()) {
      if (employeeView === 'terminated') {
        void loadTerminatedEmployees()
      } else {
        void fetchEmployees(0, 20)
      }
    }
  }

  const handleFaceUpload = async (employee: Employee, file: File | undefined, input?: HTMLInputElement) => {
    if (!file) return
    setUploadFaceError(null)
    const validationError = validateFaceImageSize(file)
    if (validationError) {
      setUploadFaceError(validationError)
      toast.error('Şəkil yüklənmədi', validationError)
      if (input) input.value = ''
      return
    }
    setUploadingFaceEmployeeId(employee.id)
    try {
      const faceErrors = await uploadFaceForEmployee(employee, file)
      if (faceErrors.length > 0) {
        const message = `Şəkil profilə saxlanıldı, amma cihaz sinxronu tamamlanmadı: ${faceErrors.join(' | ')}`
        setUploadFaceError(message)
        toast.warning('Şəkil saxlanıldı', message)
      } else {
        toast.success(
          'Şəkil yeniləndi',
          'Profil şəkli saxlanıldı. Cihaza göndərilərkən avtomatik uyğunlaşdırılır.',
        )
      }
      await fetchEmployees(currentPage, 20)
    } catch (e: unknown) {
      const message = getApiErrorMessage(e, 'Şəkil yüklənmədi')
      setUploadFaceError(message)
      toast.error('Şəkil yüklənmədi', message)
    } finally {
      setUploadingFaceEmployeeId(null)
      if (input) {
        input.value = ''
      }
    }
  }

  const onWizardPhotoSelected = (file: File) => {
    const validationError = validateFaceImageSize(file)
    if (validationError) {
      setWizardImageError(validationError)
      return
    }
    wizardImageLoadTokenRef.current += 1
    if (wizardImageObjectUrlRef.current) {
      URL.revokeObjectURL(wizardImageObjectUrlRef.current)
    }
    const previewUrl = URL.createObjectURL(file)
    wizardImageObjectUrlRef.current = previewUrl
    setWizardImageFile(file)
    setWizardImageError(null)
    setWizardImageRemovalRequested(false)
    setWizardImagePreview(previewUrl)
  }

  const onWizardPhotoRemoved = () => {
    wizardImageLoadTokenRef.current += 1
    if (wizardImageObjectUrlRef.current) {
      URL.revokeObjectURL(wizardImageObjectUrlRef.current)
      wizardImageObjectUrlRef.current = null
    }
    setWizardImageFile(null)
    setWizardImageError(null)
    setWizardImagePreview(null)
    setWizardImageRemovalRequested(Boolean(editingEmployee?.faceImageUrl))
  }

  const visibleEmployees = employeeView === 'terminated' ? terminatedEmployees : employees
  const viewLoading = employeeView === 'terminated' ? terminatedLoading : loading
  const viewError = employeeView === 'terminated' ? terminatedError : error

  const filtered = visibleEmployees.filter((e: Employee) => {
    const matchSearch = !search || `${e.firstName} ${e.lastName}`.toLowerCase().includes(search.toLowerCase()) || e.employeeId.toLowerCase().includes(search.toLowerCase())
    const matchDept = !filterDept || String(e.departmentId) === filterDept
    const matchStatus = !filterStatus || e.employmentStatus === filterStatus
    const matchShift = !filterShift || e.shiftType === filterShift || (
      filterShift === 'STANDARD' && ['MORNING', 'NIGHT', 'STANDARD'].includes((e.shiftType ?? '').toUpperCase())
    ) || (
      filterShift === 'FLEXIBLE' && ['FLEXIBLE', 'FIRST_ENTRY', 'SERBEST', 'FREE_SHIFT', 'FREE'].includes((e.shiftType ?? '').toUpperCase())
    )
    const matchBranch = !filterBranch || (e.areaIds?.length
      ? e.areaIds.some((areaId) => String(areaId) === filterBranch)
      : String(e.branchId) === filterBranch)
    return matchSearch && matchDept && matchStatus && matchShift && matchBranch
  })

  const activeCount = employees.filter((e) => e.employmentStatus === 'ACTIVE').length
  const onLeaveCount = employees.filter((e) => e.employmentStatus === 'ON_LEAVE').length

  return (
    <Layout>
      <div className="p-4 sm:p-8" style={{ background: '#f8fafc', minHeight: '100vh' }}>
        {/* Header */}
        <div className="flex flex-wrap items-start justify-between gap-3 mb-6">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">
              {employeeView === 'terminated' ? 'İşdən çıxarılanlar' : 'Bütün əməkdaşlar'}
            </h1>
            <p className="text-sm text-gray-500 mt-1">
              {employeeView === 'terminated' ? (
                <><span className="font-medium text-red-600">{terminatedEmployees.length}</span> işdən çıxarılıb</>
              ) : (
                <>
                  <span className="font-medium text-gray-700">{totalElements ?? employees.length}</span> ümumi ·&nbsp;
                  <span className="text-green-600 font-medium">{activeCount} aktiv</span> ·&nbsp;
                  <span className="text-yellow-600 font-medium">{onLeaveCount} məzuniyyətdə</span>
                </>
              )}
            </p>
          </div>
          <div className="flex flex-wrap items-center justify-end gap-2">
            {employeeView === 'active' && (
              <DataTransferControls
                entity="employees"
                onImported={async () => {
                  await fetchEmployees(0, 20)
                }}
              />
            )}
            <button
              onClick={() => employeeView === 'terminated'
                ? loadTerminatedEmployees()
                : fetchEmployees(currentPage, 20)}
              className="flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-gray-600 bg-white border border-gray-200 rounded-lg hover:bg-gray-50"
            >
              <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
              </svg>
              Yenilə
            </button>
            {employeeView === 'active' && (
              <button
                onClick={openCreate}
                className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white rounded-lg"
                style={{ background: '#a855f7' }}
              >
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
                </svg>
                + Əməkdaş əlavə et
              </button>
            )}
          </div>
        </div>

        <div className="mb-4 inline-flex rounded-xl border border-gray-200 bg-white p-1 shadow-sm">
          <button
            type="button"
            onClick={() => {
              setEmployeeView('active')
              setFilterStatus('')
            }}
            className={`rounded-lg px-4 py-2 text-sm font-medium ${employeeView === 'active' ? 'bg-purple-600 text-white' : 'text-gray-600 hover:bg-gray-50'}`}
          >
            Əsas siyahı
          </button>
          <button
            type="button"
            onClick={() => {
              setEmployeeView('terminated')
              setFilterStatus('')
              void loadTerminatedEmployees()
            }}
            className={`rounded-lg px-4 py-2 text-sm font-medium ${employeeView === 'terminated' ? 'bg-red-600 text-white' : 'text-gray-600 hover:bg-gray-50'}`}
          >
            İşdən çıxarılanlar
          </button>
        </div>

        {/* Search + Filters */}
        <div className="bg-white rounded-xl shadow-sm p-4 mb-4 flex flex-wrap items-center gap-3">
          <div className="flex items-center gap-2 flex-1 min-w-[200px] border border-gray-200 rounded-lg px-3 py-2">
            <svg className="w-4 h-4 text-gray-400 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
            <input
              type="text"
              placeholder="Əməkdaş axtar..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
              className="flex-1 outline-none text-sm text-gray-700 placeholder-gray-400"
            />
            {search && (
              <button onClick={() => {
                setSearch('')
                if (employeeView === 'active') void fetchEmployees(0, 20)
              }} className="text-gray-400 hover:text-gray-600">
                <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            )}
          </div>

          <select value={filterDept} onChange={e => setFilterDept(e.target.value)} className="border border-gray-200 rounded-lg px-3 py-2 text-sm text-gray-600 focus:outline-none focus:ring-1 focus:ring-purple-400">
            <option value="">Bütün departamentlər</option>
            {departments.map(d => <option key={d.id} value={String(d.id)}>{d.departmentName}</option>)}
          </select>

          {employeeView === 'active' && (
            <select value={filterStatus} onChange={e => setFilterStatus(e.target.value)} className="border border-gray-200 rounded-lg px-3 py-2 text-sm text-gray-600 focus:outline-none focus:ring-1 focus:ring-purple-400">
              <option value="">Bütün statuslar</option>
              <option value="ACTIVE">Aktiv</option>
              <option value="INACTIVE">Deaktiv</option>
              <option value="ON_LEAVE">Məzuniyyətdə</option>
            </select>
          )}

          <select value={filterShift} onChange={e => setFilterShift(e.target.value)} className="border border-gray-200 rounded-lg px-3 py-2 text-sm text-gray-600 focus:outline-none focus:ring-1 focus:ring-purple-400">
            <option value="">Bütün növbələr</option>
            {UI_SHIFT_TYPES.map(s => (
              <option key={s} value={s}>{SHIFT_TYPE_LABELS[s] ?? s}</option>
            ))}
          </select>

          <select value={filterBranch} onChange={e => setFilterBranch(e.target.value)} className="border border-gray-200 rounded-lg px-3 py-2 text-sm text-gray-600 focus:outline-none focus:ring-1 focus:ring-purple-400">
            <option value="">Bütün ərazilər</option>
            {branches.map(b => <option key={b.id} value={String(b.id)}>{b.name}</option>)}
          </select>
        </div>

        {/* Table */}
        {uploadFaceError && (
          <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-lg mb-4 text-sm">{uploadFaceError}</div>
        )}
        {viewLoading ? (
          <div className="bg-white rounded-xl shadow-sm p-12 text-center text-gray-400">
            <div className="w-8 h-8 border-2 border-purple-300 border-t-purple-600 rounded-full animate-spin mx-auto mb-3"></div>
            Yüklənir...
          </div>
        ) : viewError ? (
          <div className="bg-white rounded-xl shadow-sm p-8 text-center text-red-500">{viewError}</div>
        ) : filtered.length === 0 ? (
          <div className="bg-white rounded-xl shadow-sm p-12 text-center text-gray-400">
            {search
              ? 'Axtarışa uyğun əməkdaş tapılmadı.'
              : employeeView === 'terminated'
              ? 'İşdən çıxarılan əməkdaş yoxdur.'
              : 'Hələ heç bir əməkdaş yoxdur.'}
          </div>
        ) : (
          <div className="bg-white rounded-xl shadow-sm overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead>
                <tr style={{ background: '#f9fafb' }}>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">ƏMƏLİYYATLAR</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">ŞƏKİL</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">ƏMƏKDAŞ ID</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">AD VƏ SOYAD</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">ATA ADI</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">DEPARTAMENT</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">VƏZİFƏ</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">ƏRAZİLƏR</th>
                  <th className="px-4 py-3 text-left text-xs font-semibold text-gray-500 uppercase tracking-wider">STATUS</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {filtered.map((emp: Employee) => {
                  const name = `${emp.firstName} ${emp.lastName}`
                  const initials = `${emp.firstName.charAt(0)}${emp.lastName.charAt(0)}`.toUpperCase()
                  const avatarColor = getAvatarColor(name)
                  return (
                    <tr key={emp.id} className="hover:bg-gray-50 transition-colors">
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1.5">
                          <button
                            onClick={() => openProfile(emp)}
                            className="p-1.5 rounded hover:bg-violet-50 transition-colors"
                            title="Məlumatlara bax"
                          >
                            <svg className="w-4 h-4" style={{ color: '#a855f7' }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M2.458 12C3.732 7.943 7.523 5 12 5s8.268 2.943 9.542 7c-1.274 4.057-5.065 7-9.542 7S3.732 16.057 2.458 12z" />
                            </svg>
                          </button>
                          {employeeView === 'active' && (
                            <>
                          <button
                            onClick={() => openEdit(emp)}
                            className="p-1.5 rounded hover:bg-purple-50 transition-colors"
                            title="Redaktə et"
                          >
                            <svg className="w-4 h-4" style={{ color: '#a855f7' }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                            </svg>
                          </button>
                          <label className="p-1.5 rounded hover:bg-blue-50 transition-colors cursor-pointer" title="Şəkil yüklə">
                            <input
                              type="file"
                              accept="image/jpeg,image/png"
                              className="hidden"
                              aria-label={`${emp.firstName} ${emp.lastName} üçün şəkil yüklə`}
                              onChange={(e) => {
                                handleFaceUpload(emp, e.target.files?.[0], e.currentTarget)
                              }}
                            />
                            <svg className={`w-4 h-4 ${uploadingFaceEmployeeId === emp.id ? 'animate-pulse' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24" style={{ color: '#2563eb' }}>
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 16l4-4a3 3 0 014.243 0L15 15.757m-2-2 1.586-1.586a3 3 0 014.243 0L21 14m-6-10h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z" />
                            </svg>
                          </label>
                          <button
                            onClick={() => openDeleteConfirm(emp)}
                            className="p-1.5 rounded hover:bg-amber-50 transition-colors"
                            title="İşdən çıxar"
                            aria-label={`${name} — İşdən çıxar`}
                          >
                            <svg className="w-4 h-4 text-amber-600" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 17l5-5m0 0l-5-5m5 5H9m4-9H5a2 2 0 00-2 2v14a2 2 0 002 2h8" />
                            </svg>
                          </button>
                          <button
                            disabled
                            className="p-1.5 rounded opacity-40 cursor-not-allowed"
                            title="Əməkdaşı sil — deaktivdir"
                            aria-label="Əməkdaşı sil — deaktivdir"
                          >
                            <svg className="w-4 h-4 text-red-400" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                            </svg>
                          </button>
                            </>
                          )}
                          {employeeView === 'terminated' && (
                            <button
                              type="button"
                              onClick={() => handleRetryDeviceRemoval(emp)}
                              disabled={retryingTerminationId === emp.id}
                              className="p-1.5 rounded hover:bg-red-50 transition-colors disabled:opacity-50"
                              title="Cihazlardan silinməni təkrarla"
                            >
                              <svg className={`w-4 h-4 text-red-500 ${retryingTerminationId === emp.id ? 'animate-spin' : ''}`} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9M20 20v-5h-.581m0 0a8.003 8.003 0 01-15.357-2" />
                              </svg>
                            </button>
                          )}
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <EmployeeAvatar
                          faceImageUrl={emp.faceImageUrl}
                          initials={initials}
                          background={avatarColor}
                          alt={name}
                        />
                      </td>
                      <td className={`px-4 py-3 font-mono text-xs ${emp.employmentStatus === 'TERMINATED' ? 'text-red-600' : 'text-gray-600'}`}>{emp.employeeId}</td>
                      <td className={`px-4 py-3 font-semibold ${emp.employmentStatus === 'TERMINATED' ? 'text-red-600' : 'text-gray-800'}`}>{name}</td>
                      <td className="px-4 py-3 text-gray-600">{emp.fatherName || '—'}</td>
                      <td className="px-4 py-3 text-gray-600">{emp.departmentName || '—'}</td>
                      <td className="px-4 py-3 text-gray-600">{emp.positionName || '—'}</td>
                      <td className="px-4 py-3 text-gray-600">
                        {emp.areaNames?.length ? emp.areaNames.join(', ') : (emp.branchName || branchLabelById(emp.branchId))}
                      </td>
                      <td className="px-4 py-3">
                        <span className="px-2 py-0.5 rounded-full text-xs font-medium"
                          style={emp.employmentStatus === 'ACTIVE'
                            ? { background: '#d1fae5', color: '#065f46' }
                            : emp.employmentStatus === 'ON_LEAVE'
                            ? { background: '#fef3c7', color: '#92400e' }
                            : { background: '#fee2e2', color: '#991b1b' }}
                        >
                          {statusLabel(emp.employmentStatus)}
                        </span>
                        {emp.employmentStatus === 'TERMINATED' && (emp.pendingDeviceRemovals ?? 0) > 0 && (
                          <div className="mt-1 text-xs text-amber-700" role="status">
                            {emp.pendingDeviceRemovals} cihazdan silinmə gözlənilir — avtomatik təkrar edilir
                          </div>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* Pagination */}
        {employeeView === 'active' && totalPages > 1 && (
          <div className="mt-4 flex items-center justify-between">
            <button onClick={() => fetchEmployees(currentPage - 1)} disabled={currentPage === 0} className="px-4 py-2 text-sm border border-gray-300 bg-white rounded-lg disabled:opacity-50 hover:bg-gray-50">
              Əvvəlki
            </button>
            <span className="text-sm text-gray-500">Səhifə {currentPage + 1} / {totalPages}</span>
            <button onClick={() => fetchEmployees(currentPage + 1)} disabled={currentPage >= totalPages - 1} className="px-4 py-2 text-sm border border-gray-300 bg-white rounded-lg disabled:opacity-50 hover:bg-gray-50">
              Növbəti
            </button>
          </div>
        )}
      </div>

      {showWizard && (
        <div className="fixed inset-0 z-50 bg-black/50 p-4 md:p-6 overflow-y-auto">
          <div className="min-h-full rounded-2xl bg-white p-6 md:p-8">
            <div className="flex items-start justify-between mb-6">
              <div>
                <h2 className="text-2xl font-bold text-gray-900">{editingEmployee ? 'Əməkdaşı redaktə et' : 'Yeni əməkdaş əlavə et'}</h2>
                <p className="text-sm text-gray-500 mt-1">3 addımda əməkdaş məlumatlarını tamamlayın</p>
              </div>
              <button onClick={closeWizard} className="p-2 rounded-lg hover:bg-gray-100 text-gray-500">
                <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </div>

            <div className="grid grid-cols-1 md:grid-cols-3 gap-3 mb-6">
              {stepTitles.map((title, idx) => {
                const step = idx + 1
                const isActive = step === currentStep
                const isCompleted = step < currentStep
                return (
                  <button
                    type="button"
                    key={title}
                    onClick={() => setCurrentStep(step)}
                    disabled={step > currentStep}
                    className={`border rounded-lg p-3 flex items-center gap-3 text-left ${step > currentStep ? 'cursor-not-allowed' : 'cursor-pointer'}`}
                    style={
                      isCompleted
                        ? { borderColor: '#86efac', background: '#f0fdf4' }
                        : isActive
                        ? { borderColor: '#a855f7', background: '#f5edff' }
                        : { borderColor: '#e5e7eb', background: '#ffffff' }
                    }
                  >
                    <div
                      className="w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold"
                      style={
                        isCompleted
                          ? { background: '#16a34a', color: '#ffffff' }
                          : isActive
                          ? { background: '#a855f7', color: '#ffffff' }
                          : { background: '#e5e7eb', color: '#6b7280' }
                      }
                    >
                      {isCompleted ? '✓' : step}
                    </div>
                    <div className="text-xs font-semibold text-gray-700">{title}</div>
                  </button>
                )
              })}
            </div>

            {formError && (
              <div className="bg-red-50 border border-red-200 text-red-700 px-3 py-2 rounded-lg mb-4 text-sm">{formError}</div>
            )}

            {currentStep === 1 && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">CİHAZDAKI ƏMƏKDAŞ ID {!editingEmployee && <span className="text-red-600">*</span>}</label>
                  <input value={form.deviceEmployeeNo} onChange={(e) => setFormField('deviceEmployeeNo', e.target.value)} disabled={Boolean(editingEmployee)} className={`w-full border rounded-lg px-3 py-2 text-sm ${fieldErrors.deviceEmployeeNo ? 'border-red-500' : 'border-gray-300'} disabled:bg-gray-50`} />
                  {fieldErrors.deviceEmployeeNo && <p className="mt-1 text-xs text-red-600">{fieldErrors.deviceEmployeeNo}</p>}
                </div>
                <div>
                  <label htmlFor="employee-fin" className="block text-xs font-semibold text-gray-500 mb-1">
                    FIN <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="employee-fin"
                    value={form.finNumber}
                    onChange={(e) => setFormField('finNumber', e.target.value)}
                    aria-required="true"
                    aria-invalid={Boolean(fieldErrors.finNumber)}
                    aria-describedby={fieldErrors.finNumber ? 'employee-fin-error' : undefined}
                    className={`w-full border rounded-lg px-3 py-2 text-sm ${fieldErrors.finNumber ? 'border-red-500 focus:border-red-500' : 'border-gray-300'}`}
                  />
                  {fieldErrors.finNumber && (
                    <p id="employee-fin-error" className="mt-1 text-xs text-red-600" role="alert">{fieldErrors.finNumber}</p>
                  )}
                </div>
                <div>
                  <label htmlFor="employee-first-name" className="block text-xs font-semibold text-gray-500 mb-1">
                    AD <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="employee-first-name"
                    value={form.firstName}
                    onChange={(e) => setFormField('firstName', e.target.value)}
                    aria-required="true"
                    aria-invalid={Boolean(fieldErrors.firstName)}
                    className={`w-full border rounded-lg px-3 py-2 text-sm ${fieldErrors.firstName ? 'border-red-500 focus:border-red-500' : 'border-gray-300'}`}
                  />
                  {fieldErrors.firstName && (
                    <p className="mt-1 text-xs text-red-600" role="alert">{fieldErrors.firstName}</p>
                  )}
                </div>
                <div>
                  <label htmlFor="employee-last-name" className="block text-xs font-semibold text-gray-500 mb-1">
                    SOYAD <span className="text-red-600">*</span>
                  </label>
                  <input
                    id="employee-last-name"
                    value={form.lastName}
                    onChange={(e) => setFormField('lastName', e.target.value)}
                    aria-required="true"
                    aria-invalid={Boolean(fieldErrors.lastName)}
                    className={`w-full border rounded-lg px-3 py-2 text-sm ${fieldErrors.lastName ? 'border-red-500 focus:border-red-500' : 'border-gray-300'}`}
                  />
                  {fieldErrors.lastName && (
                    <p className="mt-1 text-xs text-red-600" role="alert">{fieldErrors.lastName}</p>
                  )}
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">E-POÇT</label>
                  <input type="email" value={form.email} onChange={(e) => setFormField('email', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">ATA ADI</label>
                  <input value={form.fatherName} onChange={(e) => setFormField('fatherName', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">SERİYA NÖMRƏSİ</label>
                  <input value={form.serialNumber} onChange={(e) => setFormField('serialNumber', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">DOĞUM TARİXİ</label>
                  <input type="date" value={form.birthDate} onChange={(e) => setFormField('birthDate', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">TELEFON NÖMRƏSİ</label>
                  <input value={form.mobilePhone} onChange={(e) => setFormField('mobilePhone', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">TƏCİLİ ƏLAQƏ</label>
                  <input value={form.emergencyContact} onChange={(e) => setFormField('emergencyContact', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div className="md:col-span-2">
                  <label className="block text-xs font-semibold text-gray-500 mb-1">QEYDİYYATDA OLDUĞU ÜNVAN</label>
                  <input value={form.registeredAddress} onChange={(e) => setFormField('registeredAddress', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div className="md:col-span-2">
                  <label className="block text-xs font-semibold text-gray-500 mb-1">FAKTİKİ YAŞAYIŞ ÜNVANI</label>
                  <input value={form.address} onChange={(e) => setFormField('address', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div className="md:col-span-2">
                  <label className="block text-xs font-semibold text-gray-500 mb-1">ƏLAVƏ QEYDLƏR</label>
                  <textarea value={form.notes} onChange={(e) => setFormField('notes', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm h-24 resize-none" />
                </div>
              </div>
            )}

            {currentStep === 2 && (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">
                    ƏSAS ƏRAZİ <span className="text-red-600">*</span>
                  </label>
                  <select
                    value={form.branchId}
                    onChange={(e) => {
                      const val = e.target.value ? Number(e.target.value) : ''
                      setFormField('branchId', val)
                      setFormField('areaIds', val
                        ? [Number(val), ...form.areaIds.filter((id) => id !== Number(val))]
                        : form.areaIds)
                      setFormField('departmentId', '')
                      setFormField('positionId', '')
                    }}
                    aria-required="true"
                    aria-invalid={Boolean(fieldErrors.branchId)}
                    className={`w-full border rounded-lg px-3 py-2 text-sm ${fieldErrors.branchId ? 'border-red-500 focus:border-red-500' : 'border-gray-300'}`}
                  >
                    <option value="">Seçin...</option>
                    {branches.map((b) => (
                      <option key={b.id} value={b.id}>{b.name}</option>
                    ))}
                  </select>
                  {fieldErrors.branchId && (
                    <p className="mt-1 text-xs text-red-600" role="alert">{fieldErrors.branchId}</p>
                  )}
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">
                    DEPARTAMENT <span className="text-red-600">*</span>
                  </label>
                  <select
                    value={form.departmentId}
                    onChange={(e) => {
                      const val = e.target.value ? Number(e.target.value) : ''
                      setFormField('departmentId', val)
                      setFormField('positionId', '')
                    }}
                    aria-required="true"
                    aria-invalid={Boolean(fieldErrors.departmentId)}
                    className={`w-full border rounded-lg px-3 py-2 text-sm ${fieldErrors.departmentId ? 'border-red-500 focus:border-red-500' : 'border-gray-300'}`}
                  >
                    <option value="">Seçin...</option>
                    {departments
                      .filter((d) => !form.branchId || d.allAreas || d.branchId === Number(form.branchId))
                      .map((d) => (
                        <option key={d.id} value={d.id}>{d.departmentName}</option>
                      ))}
                  </select>
                  {fieldErrors.departmentId && (
                    <p className="mt-1 text-xs text-red-600" role="alert">{fieldErrors.departmentId}</p>
                  )}
                </div>
                <div className="md:col-span-2">
                  <label className="block text-xs font-semibold text-gray-500 mb-2">ƏLAVƏ ƏRAZİLƏR</label>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 border border-gray-200 rounded-lg p-3 max-h-40 overflow-y-auto">
                    {branches.length === 0 ? (
                      <p className="text-sm text-gray-500">Ərazi tapılmadı.</p>
                    ) : branches.map((branch) => {
                      const isPrimary = Number(form.branchId) === branch.id
                      const checked = form.areaIds.includes(branch.id)
                      return (
                        <label key={branch.id} className="flex items-center gap-2 text-sm text-gray-700">
                          <input
                            type="checkbox"
                            checked={checked}
                            disabled={isPrimary}
                            onChange={(e) => setFormField(
                              'areaIds',
                              e.target.checked
                                ? [...new Set([...form.areaIds, branch.id])]
                                : form.areaIds.filter((id) => id !== branch.id)
                            )}
                          />
                          <span>{branch.name}</span>
                          {isPrimary && <span className="text-xs text-violet-600">Əsas</span>}
                        </label>
                      )
                    })}
                  </div>
                  <p className="mt-1 text-xs text-gray-500">Əməkdaş seçilən ərazilərin cihazlarına təyin ediləcək.</p>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">VƏZİFƏ</label>
                  <select
                    value={form.positionId}
                    onChange={(e) => setFormField('positionId', e.target.value ? Number(e.target.value) : '')}
                    className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm"
                  >
                    <option value="">Seçin...</option>
                    {positions
                      .filter((p) => !form.departmentId || p.departmentId === Number(form.departmentId))
                      .map((p) => (
                        <option key={p.id} value={p.id}>{p.positionName}</option>
                      ))}
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">İŞƏ BAŞLAMA TARİXİ</label>
                  <input type="date" value={form.hireDate} onChange={(e) => setFormField('hireDate', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">ŞİRKƏTDƏKİ İŞ STAJI</label>
                  <div className="w-full rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm">{experienceLabel(companyExperience)}</div>
                </div>
                <div className="md:col-span-2">
                  <label className="block text-xs font-semibold text-gray-500 mb-1">İŞƏ QƏBUL OLUNARKƏN ƏVVƏLKİ İŞ STAJI (KÖNÜLLÜ)</label>
                  <div className="grid grid-cols-3 gap-2">
                    <input type="number" min="0" placeholder="İl" value={form.priorExperienceYears} onChange={(e) => setFormField('priorExperienceYears', e.target.value === '' ? '' : Number(e.target.value))} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm" />
                    <input type="number" min="0" max="11" placeholder="Ay" value={form.priorExperienceMonths} onChange={(e) => setFormField('priorExperienceMonths', e.target.value === '' ? '' : Number(e.target.value))} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm" />
                    <input type="number" min="0" max="30" placeholder="Gün" value={form.priorExperienceDays} onChange={(e) => setFormField('priorExperienceDays', e.target.value === '' ? '' : Number(e.target.value))} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm" />
                  </div>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">ÜMUMİ İŞ STAJI</label>
                  <div className="w-full rounded-lg border border-gray-200 bg-gray-50 px-3 py-2 text-sm">{experienceLabel(totalExperience)}</div>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">GROSS ƏMƏK HAQQI</label>
                  <input type="number" min="0" step="0.01" value={form.salary} onChange={(e) => setFormField('salary', e.target.value === '' ? '' : Number(e.target.value))} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">NET ƏMƏK HAQQI</label>
                  <input type="number" min="0" step="0.01" value={form.netSalary} onChange={(e) => setFormField('netSalary', e.target.value === '' ? '' : Number(e.target.value))} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">FAKTİKİ ƏMƏK HAQQI</label>
                  <input type="number" min="0" step="0.01" value={form.actualSalary} onChange={(e) => setFormField('actualSalary', e.target.value === '' ? '' : Number(e.target.value))} className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">İLLİK MƏZUNİYYƏT MÜDDƏTİ</label>
                  <input type="number" value={form.annualLeaveDuration} onChange={(e) => setFormField('annualLeaveDuration', e.target.value ? Number(e.target.value) : '')} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">MƏZUNİYYƏTİN BAŞLANĞIC QALIĞI</label>
                  <input type="number" value={form.annualLeaveBalance} onChange={(e) => setFormField('annualLeaveBalance', e.target.value ? Number(e.target.value) : '')} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm" />
                  <p className="mt-1 text-xs text-gray-500">Avtomatik hesablamanın başlanğıc qalığıdır. Cari qalıq Məzuniyyətlər bölməsində göstərilir.</p>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">MƏŞĞULLUQ STATUSU</label>
                  <select value={form.employmentStatus} onChange={(e) => setFormField('employmentStatus', e.target.value)} className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm">
                    <option value="ACTIVE">Aktiv</option>
                    <option value="INACTIVE">Deaktiv</option>
                    <option value="ON_LEAVE">Məzuniyyətdə</option>
                  </select>
                </div>
                <div>
                  <label className="block text-xs font-semibold text-gray-500 mb-1">
                    İŞ CƏDVƏLİ <span className="text-red-600">*</span>
                  </label>
                  <select
                    value={form.timetableId}
                    onChange={(e) => {
                      const value = e.target.value ? Number(e.target.value) : ''
                      setFormField('timetableId', value)
                      if (value !== '') {
                        const selectedTimetable = timetables.find((t) => t.id === value)
                        if (selectedTimetable?.shiftType) {
                          setFormField('shiftType', selectedTimetable.shiftType)
                        }
                      }
                    }}
                    aria-required="true"
                    aria-invalid={Boolean(fieldErrors.timetableId)}
                    className={`w-full border rounded-lg px-3 py-2 text-sm ${fieldErrors.timetableId ? 'border-red-500 focus:border-red-500' : 'border-gray-300'}`}
                  >
                    <option value="">Seçin...</option>
                    {timetables.map((t) => (
                      <option key={t.id} value={t.id}>{t.name}</option>
                    ))}
                  </select>
                  {fieldErrors.timetableId && (
                    <p className="mt-1 text-xs text-red-600" role="alert">{fieldErrors.timetableId}</p>
                  )}
                </div>
              </div>
            )}

            {currentStep === 3 && (
              <EmployeePhotoCapture
                previewUrl={wizardImagePreview}
                error={wizardImageError}
                onPhotoSelected={onWizardPhotoSelected}
                onPhotoRemoved={onWizardPhotoRemoved}
              />
            )}

            <div className="flex items-center justify-between mt-8">
              <button onClick={closeWizard} className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50">Ləğv et</button>
              <div className="flex gap-2">
                {currentStep > 1 && (
                  <button onClick={() => setCurrentStep((s) => s - 1)} className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50">Əvvəlki</button>
                )}
                {currentStep < stepTitles.length ? (
                  <button onClick={handleNextStep} className="px-4 py-2 text-sm text-white rounded-lg" style={{ background: '#a855f7' }}>Növbəti</button>
                ) : (
                  <button onClick={handleSave} disabled={saving} className="px-4 py-2 text-sm text-white rounded-lg disabled:opacity-50" style={{ background: '#a855f7' }}>
                    {saving ? 'Saxlanılır...' : 'Yadda saxla'}
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {showProfileModal && selectedEmployee && (
        <EmployeeDetailModal
          employee={selectedEmployee}
          profileImageSrc={profileImageSrc}
          loading={profileLoading}
          error={profileError}
          branchLabel={branchLabelById(selectedEmployee.branchId)}
          onClose={closeProfile}
          onEdit={(emp) => { closeProfile(); openEdit(emp) }}
        />
      )}

      {/* Delete Confirmation */}
      {deleteConfirm && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl shadow-xl w-full max-w-sm mx-4 p-6">
            <h2 className="text-lg font-bold text-gray-900 mb-2">Əməkdaşı işdən çıxar</h2>
            <div className="flex items-center gap-3 mb-4">
              <EmployeeAvatar
                faceImageUrl={deleteConfirm.faceImageUrl}
                initials={`${deleteConfirm.firstName?.[0] || ''}${deleteConfirm.lastName?.[0] || ''}`.toUpperCase()}
                background={getAvatarColor(`${deleteConfirm.firstName} ${deleteConfirm.lastName}`)}
                sizeClass="w-16 h-16"
                textClass="text-lg"
                alt={`${deleteConfirm.firstName} ${deleteConfirm.lastName}`}
              />
              <p className="text-gray-600 text-sm">
                <strong>{deleteConfirm.firstName} {deleteConfirm.lastName}</strong> adlı əməkdaşı işdən çıxarmaq istədiyinizdən əminsiniz? Əməkdaş əsas siyahıdan çıxacaq və təyin olunduğu cihazlardan silinəcək. Offline cihazlar üçün silinmə avtomatik təkrar ediləcək. Silinənədək həmin cihazlarda keçid imkanı qala bilər. Köhnə Tabel və profil məlumatları saxlanılacaq.
              </p>
            </div>
            <label className="mb-4 block text-sm font-medium text-gray-700">
              İşdən azad edilmə tarixi
              <input
                type="date"
                value={terminationDate}
                min={deleteConfirm.hireDate}
                max={todayInAppTimeZone()}
                onChange={(event) => setTerminationDate(event.target.value)}
                className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
              />
              <span className="mt-1 block text-xs font-normal text-gray-500">
                Bu tarixdən ayın sonuna qədər Tabeldə “i/azad” göstəriləcək.
              </span>
            </label>
            {deleteError && (
              <p className="mb-4 text-sm text-red-600" role="alert">{deleteError}</p>
            )}
            <div className="flex justify-end gap-3">
              <button
                onClick={closeDeleteConfirm}
                disabled={deletingEmployee}
                className="px-4 py-2 text-sm border border-gray-300 rounded-lg hover:bg-gray-50 disabled:opacity-50"
              >
                Ləğv et
              </button>
              <button
                onClick={handleDelete}
                disabled={deletingEmployee}
                className="px-4 py-2 text-sm bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:opacity-50"
              >
                {deletingEmployee ? 'İşdən çıxarılır...' : 'İşdən çıxar'}
              </button>
            </div>
          </div>
        </div>
      )}
    </Layout>
  )
}
