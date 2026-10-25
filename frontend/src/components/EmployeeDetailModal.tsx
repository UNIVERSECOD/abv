import { useEffect } from 'react'
import { Employee } from '../types'
import LeaveUsageTable from './LeaveUsageTable.tsx'

interface EmployeeDetailModalProps {
  employee: Employee
  profileImageSrc?: string | null
  loading: boolean
  error?: string | null
  branchLabel?: string
  onClose: () => void
  onEdit: (employee: Employee) => void
  onViewPermissionHistory?: (employee: Employee) => void
}

const statusLabel = (status: Employee['employmentStatus']) => {
  if (status === 'ACTIVE') return 'Aktiv'
  if (status === 'ON_LEAVE') return 'Məzuniyyətdə'
  if (status === 'TERMINATED') return 'İşdən çıxarılıb'
  return 'Deaktiv'
}

const formatDate = (value?: string) => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleDateString('az-AZ')
}

export default function EmployeeDetailModal({
  employee,
  profileImageSrc,
  loading,
  error,
  branchLabel,
  onClose,
  onEdit,
  onViewPermissionHistory,
}: EmployeeDetailModalProps) {
  // Close on Escape key
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [onClose])

  const branchDisplay = employee.areaNames?.length
    ? employee.areaNames.join(', ')
    : employee.branchName || branchLabel || '—'

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 z-50 p-4 md:p-6 overflow-y-auto">
      <div className="min-h-full bg-[#f4f5fb] rounded-3xl p-6 md:p-8">
        <div className="flex items-start justify-between mb-4">
          <h2 className="text-2xl font-bold text-[#1f2d6b]">Əməkdaş profili</h2>
          <button
            onClick={onClose}
            className="p-2 rounded-lg hover:bg-white/70 text-[#8b94b8]"
            title="Bağla"
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {loading ? (
          <div className="bg-white rounded-2xl p-8 text-center text-gray-400">
            <div className="w-8 h-8 border-2 border-purple-300 border-t-purple-600 rounded-full animate-spin mx-auto mb-3"></div>
            Məlumatlar yüklənir...
          </div>
        ) : error ? (
          <div className="bg-red-50 border border-red-200 text-red-700 rounded-xl px-4 py-3 text-sm">{error}</div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-[380px_1fr] xl:grid-cols-[420px_1fr] gap-5">
            {/* Left card: photo + personal info */}
            <div className="bg-white rounded-3xl p-5">
              {profileImageSrc ? (
                <img
                  src={profileImageSrc}
                  alt={`${employee.firstName} ${employee.lastName}`}
                  className="w-full aspect-square rounded-2xl object-cover"
                />
              ) : (
                <div
                  className="w-full aspect-square rounded-2xl flex items-center justify-center"
                  style={{ background: '#e8def6' }}
                >
                  <span className="text-6xl font-bold" style={{ color: '#9333ea' }}>
                    {`${employee.firstName.charAt(0)}${employee.lastName.charAt(0)}`.toUpperCase()}
                  </span>
                </div>
              )}

              <div className="flex items-center justify-between mt-4 text-sm text-[#8b94b8]">
                <span className="font-semibold">Mənim profilim</span>
                <span>{branchDisplay}</span>
              </div>

              <div className="mt-4 space-y-3 text-sm">
                <div className="grid grid-cols-2 gap-2">
                  <span className="text-[#98a2c8]">AD VƏ SOYAD</span>
                  <span className="text-right text-[#1f2d6b] font-semibold">
                    {employee.firstName} {employee.lastName}
                  </span>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <span className="text-[#98a2c8]">ATA ADI</span>
                  <span className="text-right text-[#1f2d6b] font-semibold">{employee.fatherName || '—'}</span>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <span className="text-[#98a2c8]">TELEFON</span>
                  <span className="text-right text-[#1f2d6b] font-semibold">{employee.mobilePhone || '—'}</span>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <span className="text-[#98a2c8]">E-POÇT</span>
                  <span className="text-right text-[#1f2d6b] font-semibold">{employee.email || '—'}</span>
                </div>
                <div className="grid grid-cols-2 gap-2">
                  <span className="text-[#98a2c8]">FIN / SERİYA</span>
                  <span className="text-right text-[#1f2d6b] font-semibold">
                    {employee.finNumber || '—'} / {employee.serialNumber || '—'}
                  </span>
                </div>
              </div>

              <div className="mt-4 rounded-2xl bg-[#f3f4f6] p-3 flex items-center justify-between">
                <div className="flex items-center gap-2">
                  <span
                    className="w-2.5 h-2.5 rounded-full"
                    style={{
                      background:
                        employee.employmentStatus === 'ACTIVE'
                          ? '#22c55e'
                          : employee.employmentStatus === 'ON_LEAVE'
                          ? '#eab308'
                          : '#ef4444',
                    }}
                  />
                  <span className="text-sm font-semibold text-[#1f2d6b]">
                    {statusLabel(employee.employmentStatus)}
                  </span>
                </div>
                {employee.employmentStatus !== 'TERMINATED' && (
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => onEdit(employee)}
                    className="p-1.5 rounded hover:bg-white"
                    title="Redaktə et"
                  >
                    <svg className="w-4 h-4" style={{ color: '#a855f7' }} fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" />
                    </svg>
                  </button>
                  <button
                    disabled
                    className="p-1.5 rounded opacity-40 cursor-not-allowed"
                    title="Əməkdaşı sil — deaktivdir"
                    aria-label="Əməkdaşı sil — deaktivdir"
                  >
                    <svg className="w-4 h-4 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" />
                    </svg>
                  </button>
                </div>
                )}
              </div>
              {onViewPermissionHistory && (
                <button
                  onClick={() => onViewPermissionHistory(employee)}
                  className="mt-3 w-full px-3 py-2 rounded-lg text-sm font-medium border border-indigo-200 text-indigo-600 hover:bg-indigo-50"
                >
                  İcazə tarixçəsi
                </button>
              )}
            </div>

            {/* Right side: employment + security + compensation */}
            <div className="space-y-4">
              <div className="bg-white rounded-3xl p-5">
                <h4 className="text-base font-bold text-[#1f2d6b] mb-3">Məşğulluq məlumatları</h4>
                <div className="space-y-2 text-sm">
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">FİLİAL</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{branchDisplay}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">DEPARTAMENT</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.departmentName || '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">VƏZİFƏ</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.positionName || '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">MƏŞĞULLUQ STATUSU</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{statusLabel(employee.employmentStatus)}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">NÖVBƏ TİPİ</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">
                      {{
                        STANDARD: 'Standart Növbə',
                        FLEXIBLE: 'Sərbəst Növbə',
                        FIRST_ENTRY: 'Sərbəst Növbə',
                        SERBEST: 'Sərbəst Növbə',
                        FREE_SHIFT: 'Sərbəst Növbə',
                        FREE: 'Sərbəst Növbə',
                        MORNING: 'Standart Növbə',
                        NIGHT: 'Standart Növbə',
                      }[employee.shiftType ?? ''] ?? employee.shiftType ?? '—'}
                    </span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">QRUP</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.groupName || '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">MÜQAVİLƏ NÖMRƏSİ</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.contractNumber || '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">İŞƏ BAŞLAMA TARİXİ</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{formatDate(employee.hireDate)}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">MÜQAVİLƏ BİTMƏ TARİXİ</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{formatDate(employee.contractEndDate)}</span>
                  </div>
                </div>
              </div>

              <div className="bg-white rounded-3xl p-5">
                <h4 className="text-base font-bold text-[#1f2d6b] mb-3">Giriş və təhlükəsizlik</h4>
                <div className="flex gap-3 mb-3">
                  <div className="flex-1 rounded-xl p-3" style={{ background: '#ffe4e6' }}>
                    <p className="text-xs text-[#9f1239] mb-1">Kart</p>
                    <p className="font-semibold text-[#9f1239]">{employee.cardId || 'X'}</p>
                  </div>
                  <div className="flex-1 rounded-xl p-3" style={{ background: '#ffe4e6' }}>
                    <p className="text-xs text-[#9f1239] mb-1">Barmaq izi</p>
                    <p className="font-semibold text-[#9f1239]">{employee.faceId || 'X'}</p>
                  </div>
                </div>
              </div>

              <div className="bg-white rounded-3xl p-5">
                <h4 className="text-base font-bold text-[#1f2d6b] mb-3">Məvacib və əlavə məlumatlar</h4>
                <div className="space-y-2 text-sm">
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">MƏVACİB</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.salary ?? '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">SAATLIQ DƏRƏCƏ</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.hourlyRate ?? '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">MÜAVİNƏT</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.allowance || '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">TƏCİLİ ƏLAQƏ</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.emergencyContact || '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">ÜNVAN</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.address || '—'}</span>
                  </div>
                  <div className="grid grid-cols-2">
                    <span className="text-[#98a2c8]">QEYDLƏR</span>
                    <span className="text-right font-semibold text-[#1f2d6b]">{employee.notes || '—'}</span>
                  </div>
                </div>
              </div>
              <LeaveUsageTable employeeId={employee.id} />
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
