export function leaveOverlapsPeriod(startDate: string, endDate: string, from: string, through: string) {
  if (from && through && from > through) return false
  return (!from || endDate >= from) && (!through || startDate <= through)
}

export function leaveCalendarDays(startDate: string, endDate: string) {
  return Math.max(0, Math.round((Date.parse(endDate) - Date.parse(startDate)) / 86400000) + 1)
}

interface DebitRequest {
  id: number
  employeeId: number
  leaveTypeId: number
  startDate: string
  endDate: string
  status: string
}

/** Allocate shared-balance days once, including overlapping legacy requests. */
export function allocateLeaveDebitDays(requests: readonly DebitRequest[], debitTypeIds: ReadonlySet<number>,
  openingDates: ReadonlyMap<number, string>) {
  const allocated = new Map<number, number>()
  const coveredByEmployee = new Map<number, [number, number][]>()
  for (const request of [...requests].sort((a, b) => a.id - b.id)) {
    allocated.set(request.id, 0)
    const opening = openingDates.get(request.employeeId)
    if (request.status !== 'APPROVED' || !debitTypeIds.has(request.leaveTypeId) || !opening) continue
    const start = Math.max(Date.parse(request.startDate), Date.parse(opening)) / 86400000
    const end = Date.parse(request.endDate) / 86400000
    if (!Number.isFinite(start) || !Number.isFinite(end) || start > end) continue

    const covered = coveredByEmployee.get(request.employeeId) ?? []
    const overlap = covered.reduce((days, [from, through]) =>
      days + Math.max(0, Math.min(end, through) - Math.max(start, from) + 1), 0)
    allocated.set(request.id, end - start + 1 - overlap)

    // Store merged intervals rather than individual dates; long leaves stay inexpensive.
    const intervals: [number, number][] = [...covered, [start, end]]
    intervals.sort((a, b) => a[0] - b[0])
    const merged: [number, number][] = []
    for (const interval of intervals) {
      const previous = merged[merged.length - 1]
      if (previous && interval[0] <= previous[1] + 1) previous[1] = Math.max(previous[1], interval[1])
      else merged.push([...interval])
    }
    coveredByEmployee.set(request.employeeId, merged)
  }
  return allocated
}

export function leaveMonthRange(month: string) {
  if (!month) return { from: '', through: '' }
  const [year, number] = month.split('-').map(Number)
  return { from: month + '-01', through: month + '-' + new Date(Date.UTC(year, number, 0)).getUTCDate() }
}

export function visibleLeaveTypes<T extends { id: number; archived: boolean }>(types: T[], requests: { leaveTypeId: number }[]) {
  const referenced = new Set(requests.map(request => request.leaveTypeId))
  return types.filter(type => !type.archived || referenced.has(type.id))
}
