export function leaveOverlapsPeriod(startDate: string, endDate: string, from: string, through: string) {
  if (from && through && from > through) return false
  return (!from || endDate >= from) && (!through || startDate <= through)
}

export function leaveCalendarDays(startDate: string, endDate: string) {
  return Math.max(0, Math.round((Date.parse(endDate) - Date.parse(startDate)) / 86400000) + 1)
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
