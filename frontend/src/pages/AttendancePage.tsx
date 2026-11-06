import { useEffect, useMemo, useState } from 'react'
import Layout from '../components/Layout.tsx'
import { attendanceApi } from '../api/attendanceApi.ts'
import { employeeApi } from '../api/employeeApi.ts'
import {
  DoorAttendanceSyncResult,
  EmployeeAttendanceRow,
  EmployeeAttendanceSummary,
  EmployeeSearchResult,
  OpenAttendanceSession,
} from '../types'
import { useDebounce } from '../hooks/useSearch.ts'
import { t } from '../i18n/index.ts'
import { formatAttendanceDateTime, formatAttendanceTime, todayInAppTimeZone } from '../utils/dateTime.ts'

type PeriodType = 'THIS_MONTH' | 'LAST_MONTH' | 'THIS_YEAR' | 'LAST_YEAR' | 'CUSTOM'

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

const statusStyles: Record<EmployeeAttendanceRow['status'], string> = {
  PRESENT: 'bg-green-100 text-green-700',
  LATE: 'bg-amber-100 text-amber-700',
  ABSENT: 'bg-red-100 text-red-700',
  ON_LEAVE: 'bg-blue-100 text-blue-700',
  EARLY_LEAVE: 'bg-purple-100 text-purple-700',
  WORKDAY_COMPLETE: 'bg-slate-200 text-slate-700',
  DAY_OFF: 'bg-gray-100 text-gray-600',
  OPEN_SESSION: 'bg-blue-100 text-blue-700',
  MISSING_EXIT: 'bg-red-100 text-red-700',
  ON_PERMISSION: 'bg-cyan-100 text-cyan-800',
  PERMITTED_EARLY_LEAVE: 'bg-indigo-100 text-indigo-700',
}

const defaultSummary: EmployeeAttendanceSummary = {
  totalDays: 0,
  workingDays: 0,
  totalHours: 0,
  absentDays: 0,
  lateDays: 0,
  leaveDays: 0,
}

const statusLabels: Record<EmployeeAttendanceRow['status'], string> = {
  PRESENT: 'İşdə olub',
  LATE: 'Gecikib',
  ABSENT: 'İşdə deyil',
  ON_LEAVE: 'Məzuniyyətdə',
  EARLY_LEAVE: 'Erkən çıxış',
  WORKDAY_COMPLETE: 'İşdə olub',
  DAY_OFF: 'İstirahət günü',
  OPEN_SESSION: 'İşdə',
  MISSING_EXIT: 'Çıxış yoxdur',
  ON_PERMISSION: 'İcazədə',
  PERMITTED_EARLY_LEAVE: 'İcazəli erkən çıxış',
}

function displayStatus(row: EmployeeAttendanceRow): EmployeeAttendanceRow['status'] {
  if (row.status === 'OPEN_SESSION' || row.status === 'MISSING_EXIT') {
    return row.checkInTime && row.date === todayInAppTimeZone() ? 'OPEN_SESSION' : 'MISSING_EXIT'
  }
  return row.status
}

function formatEmployeeLabel(employee: EmployeeSearchResult) {
  return `${employee.firstName} ${employee.lastName} · ${employee.employeeId}${employee.finNumber ? ` · FIN ${employee.finNumber}` : ''}`
}

function toDateInputValue(date: Date) {
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${year}-${month}-${day}`
}

function getPresetPeriod(periodType: PeriodType, selectedMonth: number, selectedYear: number) {
  if (periodType === 'CUSTOM') {
    return null
  }

  if (periodType === 'THIS_YEAR' || periodType === 'LAST_YEAR') {
    return {
      start: `${selectedYear}-01-01`,
      end: `${selectedYear}-12-31`,
    }
  }

  const start = new Date(selectedYear, selectedMonth, 1)
  const end = new Date(selectedYear, selectedMonth + 1, 0)
  return {
    start: toDateInputValue(start),
    end: toDateInputValue(end),
  }
}

function formatTime(value?: string) {
  return formatAttendanceTime(value)
}

function formatWorkedHours(hours?: number) {
  if (hours == null || hours <= 0) return '—'
  const totalMinutes = Math.round(hours * 60)
  const h = Math.floor(totalMinutes / 60)
  const m = totalMinutes % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

function toDateTimeInput(value?: string) {
  return value ? value.slice(0, 16) : ''
}

export default function AttendancePage() {
  const now = new Date()
  const currentMonth = now.getMonth()
  const currentYear = now.getFullYear()
  const lastMonthDate = new Date(currentYear, currentMonth - 1, 1)
  const lastMonth = lastMonthDate.getMonth()
  const lastMonthYear = lastMonthDate.getFullYear()

  const [employeeQuery, setEmployeeQuery] = useState('')
  const [selectedEmployee, setSelectedEmployee] = useState<EmployeeSearchResult | null>(null)
  const [employeeResults, setEmployeeResults] = useState<EmployeeSearchResult[]>([])
  const [searchLoading, setSearchLoading] = useState(false)
  const debouncedEmployeeQuery = useDebounce(employeeQuery, 300)

  const [periodType, setPeriodType] = useState<PeriodType>('THIS_MONTH')
  const [selectedMonth, setSelectedMonth] = useState(currentMonth)
  const [selectedYear, setSelectedYear] = useState(currentYear)
  const [customStartDate, setCustomStartDate] = useState(toDateInputValue(new Date(currentYear, currentMonth, 1)))
  const [customEndDate, setCustomEndDate] = useState(toDateInputValue(now))

  const [attendanceRows, setAttendanceRows] = useState<EmployeeAttendanceRow[]>([])
  const [summary, setSummary] = useState<EmployeeAttendanceSummary>(defaultSummary)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [syncWarning, setSyncWarning] = useState<string | null>(null)
  const [openSessions, setOpenSessions] = useState<OpenAttendanceSession[]>([])
  const [openSessionsLoading, setOpenSessionsLoading] = useState(false)
  const [openRefreshToken, setOpenRefreshToken] = useState(0)
  const [attendanceRefreshToken, setAttendanceRefreshToken] = useState(0)
  const [editingSession, setEditingSession] = useState<OpenAttendanceSession | null>(null)
  const [correctionCheckIn, setCorrectionCheckIn] = useState('')
  const [correctionCheckOut, setCorrectionCheckOut] = useState('')
  const [correctionReason, setCorrectionReason] = useState('')
  const [correctionSaving, setCorrectionSaving] = useState(false)
  const [correctionError, setCorrectionError] = useState<string | null>(null)



  useEffect(() => {
    if (periodType === 'THIS_MONTH') {
      setSelectedMonth(currentMonth)
      setSelectedYear(currentYear)
    } else if (periodType === 'LAST_MONTH') {
      setSelectedMonth(lastMonth)
      setSelectedYear(lastMonthYear)
    } else if (periodType === 'THIS_YEAR') {
      setSelectedYear(currentYear)
    } else if (periodType === 'LAST_YEAR') {
      setSelectedYear(currentYear - 1)
    }
  }, [currentMonth, currentYear, lastMonth, lastMonthYear, periodType])

  useEffect(() => {
    if (selectedEmployee && employeeQuery === formatEmployeeLabel(selectedEmployee)) {
      setEmployeeResults([])
      setSearchLoading(false)
      return
    }

    let cancelled = false
    const query = debouncedEmployeeQuery.trim()

    if (!query) {
      setEmployeeResults([])
      setSearchLoading(false)
      return
    }

    setSearchLoading(true)
    employeeApi.searchEmployees(query)
      .then((response) => {
        if (!cancelled) {
          setEmployeeResults(response.data?.data ?? [])
        }
      })
      .catch(() => {
        if (!cancelled) {
          setEmployeeResults([])
        }
      })
      .finally(() => {
        if (!cancelled) {
          setSearchLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [debouncedEmployeeQuery, employeeQuery, selectedEmployee])

  const activePeriod = useMemo(() => {
    if (periodType === 'CUSTOM') {
      return { start: customStartDate, end: customEndDate }
    }
    return getPresetPeriod(periodType, selectedMonth, selectedYear)
  }, [customEndDate, customStartDate, periodType, selectedMonth, selectedYear])

  useEffect(() => {
    let cancelled = false
    setOpenSessionsLoading(true)
    attendanceApi.getOpenSessions(0, 10)
      .then((response) => { if (!cancelled) setOpenSessions(response.data?.data?.content ?? []) })
      .catch(() => { if (!cancelled) setOpenSessions([]) })
      .finally(() => { if (!cancelled) setOpenSessionsLoading(false) })
    return () => { cancelled = true }
  }, [openRefreshToken])

  useEffect(() => {
    if (!selectedEmployee || !activePeriod?.start || !activePeriod?.end) {
      setAttendanceRows([])
      setSummary(defaultSummary)
      return
    }

    let cancelled = false
    setLoading(true)
    setError(null)
    setSyncWarning(null)

    const loadStoredAttendance = async () => {
      const responses = await Promise.all([
        attendanceApi.getEmployeeAttendance(selectedEmployee.employeePk, activePeriod.start, activePeriod.end),
        attendanceApi.getEmployeeAttendanceSummary(selectedEmployee.employeePk, activePeriod.start, activePeriod.end),
      ])
      if (!cancelled) {
        const [attendanceResponse, summaryResponse] = responses
        setAttendanceRows(attendanceResponse.data?.data ?? [])
        setSummary(summaryResponse.data?.data ?? defaultSummary)
        setError(null)
      }
    }

    const run = async () => {
      // Start device sync in parallel so an offline/slow terminal cannot block stored data.
      const syncPromise = attendanceApi.syncAll({
        start: `${activePeriod.start}T00:00:00`,
        end: `${activePeriod.end}T23:59:59.999999999`,
      }).then(() => true).catch(() => false)

      try {
        await loadStoredAttendance()
        if (!cancelled) setOpenRefreshToken((value) => value + 1)
      } catch (requestError: unknown) {
        if (!cancelled) {
          setAttendanceRows([])
          setSummary(defaultSummary)
          setError((requestError as Error).message || t('attendance.fetchFailed'))
        }
      } finally {
        if (!cancelled) setLoading(false)
      }

      const syncSucceeded = await syncPromise
      if (cancelled) return
      if (!syncSucceeded) {
        setSyncWarning('Cihaz sinxronu alınmadı. Saxlanmış davamiyyət məlumatları göstərilir.')
        return
      }

      try {
        await loadStoredAttendance()
      } catch {
        if (!cancelled) setSyncWarning('Sinxron tamamlandı, amma yenilənmiş məlumatları yükləmək alınmadı.')
      }
    }

    void run()

    return () => {
      cancelled = true
    }
  }, [activePeriod, attendanceRefreshToken, selectedEmployee])

  const openCorrection = (session: OpenAttendanceSession) => {
    setEditingSession(session)
    setCorrectionCheckIn(toDateTimeInput(session.checkInTime))
    setCorrectionCheckOut('')
    setCorrectionReason('')
    setCorrectionError(null)
  }

  const submitCorrection = async () => {
    if (!editingSession || !correctionCheckIn || !correctionCheckOut || !correctionReason.trim()) return
    setCorrectionSaving(true)
    setCorrectionError(null)
    try {
      await attendanceApi.correctSession(editingSession.attendanceLogId, { checkInTime: correctionCheckIn, checkOutTime: correctionCheckOut, reason: correctionReason.trim() })
      setEditingSession(null)
      setOpenRefreshToken((value) => value + 1)
      setAttendanceRefreshToken((value) => value + 1)
    } catch (requestError: unknown) {
      setCorrectionError((requestError as Error).message || 'Düzəliş saxlanılmadı')
    } finally { setCorrectionSaving(false) }
  }




  const summaryCards = [
    { label: t('attendance.totalWorkingDays'), value: summary.workingDays, accent: 'text-slate-900' },
    { label: t('attendance.totalWorkingHours'), value: summary.totalHours.toFixed(2), accent: 'text-slate-900' },
    { label: t('attendance.absentDays'), value: summary.absentDays, accent: 'text-red-600' },
    { label: t('attendance.lateDays'), value: summary.lateDays, accent: 'text-amber-600' },
    { label: t('attendance.leaveDays'), value: summary.leaveDays, accent: 'text-blue-600' },
  ]

  const yearOptions = Array.from({ length: 7 }, (_, index) => currentYear - 3 + index)

  return (
    <Layout>
      <div className="p-4 sm:p-8 min-h-screen bg-slate-50">
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-slate-900">{t('attendance.title')}</h1>
          <p className="mt-1 text-sm text-slate-500">{t('attendance.subtitle')}</p>
        </div>

        <div className="grid gap-6 lg:grid-cols-[2fr,1fr]">
          <div className="space-y-6">
            <div className="rounded-2xl bg-white p-5 shadow-sm">
              <div className="grid gap-4 lg:grid-cols-2">
                <div className="relative">
                  <label className="mb-1.5 block text-xs font-medium text-slate-500">{t('attendance.employee')}</label>
                  <input
                    type="text"
                    value={employeeQuery}
                    onChange={(event) => {
                      setEmployeeQuery(event.target.value)
                      setSelectedEmployee(null)
                    }}
                    placeholder={t('attendance.searchEmployee')}
                    className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                  />
                  {!selectedEmployee && (searchLoading || employeeResults.length > 0 || debouncedEmployeeQuery.trim()) && (
                    <div className="absolute z-20 mt-2 max-h-64 w-full overflow-y-auto rounded-xl border border-slate-200 bg-white shadow-lg">
                      {searchLoading ? (
                        <div className="px-3 py-3 text-sm text-slate-500">{t('attendance.searchingEmployees')}</div>
                      ) : employeeResults.length > 0 ? (
                        employeeResults.map((employee) => (
                          <button
                            key={employee.employeePk}
                            type="button"
                            onClick={() => {
                              setSelectedEmployee(employee)
                              setEmployeeQuery(formatEmployeeLabel(employee))
                              setEmployeeResults([])
                            }}
                            className="block w-full border-b border-slate-100 px-3 py-3 text-left text-sm last:border-b-0 hover:bg-slate-50"
                          >
                            <div className="font-medium text-slate-900">{employee.firstName} {employee.lastName}</div>
                            <div className="mt-0.5 text-xs text-slate-500">
                              {employee.employeeId}
                              {employee.finNumber ? ` · FIN ${employee.finNumber}` : ''}
                              {employee.departmentName ? ` · ${employee.departmentName}` : ''}
                            </div>
                          </button>
                        ))
                      ) : (
                        <div className="px-3 py-3 text-sm text-slate-500">{t('attendance.noEmployeesFound')}</div>
                      )}
                    </div>
                  )}
                </div>

                <div>
                  <label className="mb-1.5 block text-xs font-medium text-slate-500">{t('attendance.periodType')}</label>
                  <select
                    value={periodType}
                    onChange={(event) => setPeriodType(event.target.value as PeriodType)}
                    className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                  >
                    <option value="THIS_MONTH">{t('attendance.thisMonth')}</option>
                    <option value="LAST_MONTH">{t('attendance.lastMonth')}</option>
                    <option value="THIS_YEAR">{t('attendance.thisYear')}</option>
                    <option value="LAST_YEAR">{t('attendance.lastYear')}</option>
                    <option value="CUSTOM">{t('attendance.customRange')}</option>
                  </select>
                </div>
              </div>

              <div className="mt-4 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
                {periodType === 'CUSTOM' ? (
                  <>
                    <div>
                      <label className="mb-1.5 block text-xs font-medium text-slate-500">{t('common.startDate')}</label>
                      <input
                        type="date"
                        value={customStartDate}
                        onChange={(event) => setCustomStartDate(event.target.value)}
                        className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                      />
                    </div>
                    <div>
                      <label className="mb-1.5 block text-xs font-medium text-slate-500">{t('common.endDate')}</label>
                      <input
                        type="date"
                        value={customEndDate}
                        onChange={(event) => setCustomEndDate(event.target.value)}
                        className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                      />
                    </div>
                  </>
                ) : (
                  <>
                    {(periodType === 'THIS_MONTH' || periodType === 'LAST_MONTH') && (
                      <div>
                        <label className="mb-1.5 block text-xs font-medium text-slate-500">{t('attendance.month')}</label>
                        <select
                          value={selectedMonth}
                          onChange={(event) => setSelectedMonth(Number(event.target.value))}
                          className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                        >
                          {monthOptions.map((month, index) => (
                            <option key={month} value={index}>{month}</option>
                          ))}
                        </select>
                      </div>
                    )}
                    <div>
                      <label className="mb-1.5 block text-xs font-medium text-slate-500">{t('attendance.year')}</label>
                      <select
                        value={selectedYear}
                        onChange={(event) => setSelectedYear(Number(event.target.value))}
                        className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                      >
                        {yearOptions.map((year) => (
                          <option key={year} value={year}>{year}</option>
                        ))}
                      </select>
                    </div>
                  </>
                )}

                <div className="rounded-xl border border-dashed border-slate-200 bg-slate-50 px-4 py-3 text-sm text-slate-600">
                  <div className="font-medium text-slate-700">{t('attendance.selectedPeriod')}</div>
                  <div className="mt-1">
                    {activePeriod ? `${activePeriod.start} → ${activePeriod.end}` : t('attendance.choosePeriod')}
                  </div>
                </div>
              </div>

              {selectedEmployee && (
                <div className="mt-4 rounded-xl bg-purple-50 px-4 py-3 text-sm text-purple-900">
                  {t('attendance.viewingAttendanceFor')} <span className="font-semibold">{selectedEmployee.firstName} {selectedEmployee.lastName}</span>
                  {selectedEmployee.departmentName ? ` · ${selectedEmployee.departmentName}` : ''}
                </div>
              )}
            </div>



            {error && (
              <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                {error}
              </div>
            )}
            {syncWarning && (
              <div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
                {syncWarning}
              </div>
            )}

            <div className="grid gap-4 sm:grid-cols-3 lg:grid-cols-5">
              {summaryCards.map((card) => (
                <div key={card.label} className="rounded-2xl bg-white p-4 shadow-sm">
                  <div className="text-xs text-slate-400">{card.label}</div>
                  <div className={`mt-2 text-2xl font-bold ${card.accent}`}>{card.value}</div>
                </div>
              ))}
            </div>

            <div className="overflow-hidden rounded-2xl bg-white shadow-sm">
              <div className="border-b border-slate-100 px-5 py-4">
                <h2 className="text-lg font-semibold text-slate-900">{t('attendance.dailyAttendance')}</h2>
                <p className="mt-1 text-sm text-slate-500">{t('attendance.dailyAttendanceDesc')}</p>
              </div>

              {!selectedEmployee ? (
                <div className="px-5 py-12 text-center text-sm text-slate-400">{t('attendance.searchToLoad')}</div>
              ) : loading ? (
                <div className="px-5 py-12 text-center text-sm text-slate-500">{t('attendance.loadingAttendance')}</div>
              ) : attendanceRows.length === 0 ? (
                <div className="px-5 py-12 text-center text-sm text-slate-400">{t('attendance.noAttendance')}</div>
              ) : (
                <div className="overflow-x-auto">
                  <table className="min-w-full divide-y divide-slate-100">
                    <thead className="bg-slate-50">
                      <tr className="text-left text-xs uppercase tracking-wide text-slate-500">
                        <th className="px-5 py-3">{t('attendance.date')}</th>
                        <th className="px-5 py-3">{t('attendance.checkIn')}</th>
                        <th className="px-5 py-3">{t('attendance.checkOut')}</th>
                        <th className="px-5 py-3">{t('attendance.hoursWorked')}</th>
                        <th className="px-5 py-3">Gecikmə</th>
                        <th className="px-5 py-3">Erkən çıxış</th>
                        <th className="px-5 py-3">{t('common.status')}</th>
                        <th className="px-5 py-3">{t('attendance.notes')}</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100 text-sm text-slate-700">
                      {attendanceRows.map((row) => (
                        <tr key={row.date}>
                          <td className="px-5 py-4 font-medium text-slate-900">
                            {new Date(row.date).toLocaleDateString()}
                          </td>
                          <td className="px-5 py-4 text-slate-800">{formatTime(row.checkInTime)}</td>
                          <td className="px-5 py-4">
                            {row.checkInTime && !row.checkOutTime ? (
                              <span className="inline-flex items-center rounded-full bg-amber-50 px-2.5 py-1 text-xs font-medium text-amber-700">
                                Çıxış gözlənilir
                              </span>
                            ) : (
                              formatTime(row.checkOutTime)
                            )}
                          </td>
                          <td className="px-5 py-4 font-semibold text-slate-900">
                            {formatWorkedHours(row.hoursWorked)}
                          </td>
                          <td className="px-5 py-4 text-amber-700">
                            {row.lateMinutes ? `${row.lateMinutes} dəq.` : '—'}
                          </td>
                          <td className="px-5 py-4 text-purple-700">
                            {row.earlyLeaveMinutes ? `${row.earlyLeaveMinutes} dəq.` : '—'}
                          </td>
                          <td className="px-5 py-4">
                            <span className={`rounded-full px-2.5 py-1 text-xs font-medium ${statusStyles[displayStatus(row)]}`}>
                              {statusLabels[displayStatus(row)]}
                            </span>
                          </td>
                          <td className="px-5 py-4 text-slate-500">{row.notes || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </div>

          <div className="space-y-6">
            <div className="rounded-2xl bg-white p-5 shadow-sm">
              <h2 className="text-lg font-semibold text-slate-900">{t('attendance.overview')}</h2>
              <div className="mt-4 space-y-3 text-sm text-slate-600">
                <div className="flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3">
                  <span>{t('attendance.totalDaysInPeriod')}</span>
                  <span className="font-semibold text-slate-900">{summary.totalDays}</span>
                </div>
                <div className="flex items-center justify-between rounded-xl bg-slate-50 px-4 py-3">
                  <span>{t('attendance.selectedEmployee')}</span>
                  <span className="max-w-[180px] truncate font-semibold text-slate-900">
                    {selectedEmployee ? `${selectedEmployee.firstName} ${selectedEmployee.lastName}` : '—'}
                  </span>
                </div>
                <div className="rounded-xl bg-slate-50 px-4 py-3">
                  <div className="text-xs uppercase tracking-wide text-slate-400">{t('attendance.period')}</div>
                  <div className="mt-1 font-medium text-slate-900">
                    {activePeriod ? `${activePeriod.start} → ${activePeriod.end}` : '—'}
                  </div>
                </div>
              </div>
            </div>

            <div className="rounded-2xl bg-white p-5 shadow-sm">
              <div className="flex items-center justify-between gap-3">
                <h2 className="text-lg font-semibold text-slate-900">Açıq sessiyalar</h2>
                <span className="rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-600">
                  {openSessions.length}
                </span>
              </div>
              <div className="mt-4 space-y-3">
                {openSessionsLoading ? (
                  <div className="text-sm text-slate-500">Yüklənir...</div>
                ) : openSessions.length === 0 ? (
                  <div className="text-sm text-slate-500">Açıq sessiya yoxdur</div>
                ) : openSessions.map((session) => (
                  <div key={session.attendanceLogId} className="border-b border-slate-100 pb-3 last:border-b-0 last:pb-0">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="truncate text-sm font-medium text-slate-900">{session.fullName}</div>
                        <div className="mt-1 text-xs text-slate-500">{session.employeeId}</div>
                        <div className="mt-1 text-xs text-slate-600">{formatAttendanceDateTime(session.checkInTime)}</div>
                      </div>
                      <span className={`shrink-0 rounded-full px-2 py-1 text-xs font-medium ${session.status === 'MISSING_EXIT' ? 'bg-red-50 text-red-700' : 'bg-blue-50 text-blue-700'}`}>
                        {session.status === 'MISSING_EXIT' ? 'Çıxış yoxdur' : 'Açıq'}
                      </span>
                    </div>
                    <button
                      type="button"
                      onClick={() => openCorrection(session)}
                      className="mt-3 text-sm font-medium text-purple-700 hover:text-purple-900"
                    >
                      Manual düzəlt
                    </button>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>

        {editingSession && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
            <div className="w-full max-w-md rounded-xl bg-white p-5 shadow-xl">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">Sessiyanı düzəlt</h2>
                  <p className="mt-1 text-sm text-slate-500">{editingSession.fullName}</p>
                </div>
                <button type="button" onClick={() => setEditingSession(null)} className="text-xl text-slate-400 hover:text-slate-700" aria-label="Bağla">×</button>
              </div>

              <div className="mt-5 space-y-4">
                <div>
                  <label className="mb-1 block text-xs font-medium text-slate-500">Giriş vaxtı</label>
                  <input type="datetime-local" required value={correctionCheckIn} onChange={(event) => setCorrectionCheckIn(event.target.value)} className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-slate-500">Çıxış vaxtı</label>
                  <input type="datetime-local" required value={correctionCheckOut} onChange={(event) => setCorrectionCheckOut(event.target.value)} className="w-full rounded-lg border border-slate-200 px-3 py-2 text-sm" />
                </div>
                <div>
                  <label className="mb-1 block text-xs font-medium text-slate-500">Düzəliş səbəbi</label>
                  <textarea required maxLength={500} value={correctionReason} onChange={(event) => setCorrectionReason(event.target.value)} rows={3} className="w-full resize-none rounded-lg border border-slate-200 px-3 py-2 text-sm" />
                </div>
                {correctionError && <div className="text-sm text-red-600">{correctionError}</div>}
              </div>

              <div className="mt-5 flex justify-end gap-3">
                <button type="button" onClick={() => setEditingSession(null)} className="rounded-lg border border-slate-200 px-4 py-2 text-sm font-medium text-slate-700">Ləğv et</button>
                <button
                  type="button"
                  disabled={correctionSaving || !correctionCheckIn || !correctionCheckOut || !correctionReason.trim()}
                  onClick={() => void submitCorrection()}
                  className="rounded-lg bg-purple-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                >
                  {correctionSaving ? 'Saxlanılır...' : 'Saxla'}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </Layout>
  )
}
