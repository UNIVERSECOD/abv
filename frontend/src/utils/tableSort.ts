export type TableSort = { key: string; direction: 'asc' | 'desc' }
export type SortValue = string | number | null | undefined

const collator = new Intl.Collator('az', { numeric: true, sensitivity: 'base' })

export function sortTableRows<T extends { id: number; createdAt?: string }>(
  rows: T[], sort: TableSort | null, value: (row: T, key: string) => SortValue,
): T[] {
  const newest = (a: T, b: T) => {
    const aTime = a.createdAt ? Date.parse(a.createdAt) : NaN
    const bTime = b.createdAt ? Date.parse(b.createdAt) : NaN
    const aKnown = Number.isFinite(aTime)
    const bKnown = Number.isFinite(bTime)
    if (aKnown !== bKnown) return aKnown ? -1 : 1
    return aKnown && aTime !== bTime ? bTime - aTime : b.id - a.id
  }
  return [...rows].sort((a, b) => {
    if (!sort) return newest(a, b)
    const left = value(a, sort.key)
    const right = value(b, sort.key)
    const leftMissing = left == null || left === ''
    const rightMissing = right == null || right === ''
    if (leftMissing || rightMissing) return leftMissing === rightMissing ? newest(a, b) : leftMissing ? 1 : -1
    const order = typeof left === 'number' && typeof right === 'number'
      ? left - right : collator.compare(String(left), String(right))
    return (sort.direction === 'asc' ? order : -order) || newest(a, b)
  })
}
