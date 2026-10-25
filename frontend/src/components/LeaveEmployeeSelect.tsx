import { useEffect, useId, useRef, useState } from 'react'
import { Employee } from '../types'

export default function LeaveEmployeeSelect({ employees, value, onChange, disabled }: {
  employees: Employee[]; value: number; onChange: (id: number) => void; disabled: boolean
}) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [active, setActive] = useState(0)
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const id = useId()
  const selected = employees.find(employee => employee.id === value)
  const matches = employees.filter(employee => [employee.lastName, employee.firstName, employee.employeeId, employee.finNumber]
    .join(' ').toLocaleLowerCase('az').includes(search.trim().toLocaleLowerCase('az')))
  useEffect(() => {
    if (!open) return
    const closeOutside = (event: PointerEvent) => { if (!root.current?.contains(event.target as Node)) setOpen(false) }
    document.addEventListener('pointerdown', closeOutside)
    return () => document.removeEventListener('pointerdown', closeOutside)
  }, [open])
  const choose = (employee: Employee) => { onChange(employee.id); setOpen(false); trigger.current?.focus() }
  return <div ref={root} className="relative" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false)
  }}>
    <span id={id + '-label'} className="text-sm">Əməkdaş seç</span>
    <button ref={trigger} type="button" disabled={disabled} aria-labelledby={id + '-label'} aria-haspopup="listbox" aria-expanded={open}
      className="mt-1 flex w-full items-center justify-between rounded-md border border-slate-300 px-3 py-2 text-left text-sm"
      onClick={() => { setOpen(!open); setSearch(''); setActive(0) }}>
      <span>{selected ? selected.lastName + ' ' + selected.firstName + ' · ' + selected.employeeId : 'Seçin'}</span><span aria-hidden="true">▾</span>
    </button>
    {open && <div className="absolute left-0 right-0 top-full z-10 mt-1 rounded-lg border border-slate-200 bg-white p-2 shadow-lg">
      <input autoFocus role="combobox" aria-label="Əməkdaşları axtar" aria-expanded="true" aria-controls={id + '-options'}
        aria-activedescendant={matches[active] ? id + '-' + matches[active].id : undefined}
        placeholder="Ad, soyad və ya ID..." value={search} className="mb-2 w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm"
        onChange={event => { setSearch(event.target.value); setActive(0) }} onKeyDown={event => {
          if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); setOpen(false); trigger.current?.focus() }
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault()
            const next = Math.max(0, Math.min(matches.length - 1, active + (event.key === 'ArrowDown' ? 1 : -1)))
            setActive(next); document.getElementById(id + '-' + matches[next]?.id)?.scrollIntoView({ block: 'nearest' })
          }
          if (event.key === 'Enter') { event.preventDefault(); if (matches[active]) choose(matches[active]) }
        }} />
      <div id={id + '-options'} role="listbox" aria-label="Əməkdaşlar" className="max-h-48 overflow-y-auto">
        {matches.map((employee, index) => <button type="button" role="option" id={id + '-' + employee.id} key={employee.id}
          aria-selected={employee.id === value} className={'block w-full rounded px-2 py-2 text-left text-sm hover:bg-purple-50 ' + (active === index ? 'bg-purple-50' : '')}
          onClick={() => choose(employee)}>{employee.lastName} {employee.firstName} · {employee.employeeId}</button>)}
        {!matches.length && <p className="px-2 py-3 text-sm text-slate-500">Əməkdaş tapılmadı</p>}
      </div>
    </div>}
  </div>
}
