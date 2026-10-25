import { TableSort } from '../utils/tableSort.ts'

export default function SortableHeader({ columnKey, label, sort, onSort, className = 'whitespace-nowrap px-4 py-3' }: {
  columnKey: string; label: string; sort: TableSort | null; onSort: (key: string) => void; className?: string
}) {
  const selected = sort?.key === columnKey
  return <th className={className} aria-sort={selected ? sort.direction === 'asc' ? 'ascending' : 'descending' : 'none'}>
    <button type="button" onClick={() => onSort(columnKey)} className="flex items-center gap-1 text-left hover:text-purple-700"
      aria-label={label + ' üzrə sırala'}>
      {label}<span aria-hidden="true" className={selected ? 'text-purple-700' : 'text-slate-400'}>{selected ? sort.direction === 'asc' ? '↑' : '↓' : '↕'}</span>
    </button>
  </th>
}
