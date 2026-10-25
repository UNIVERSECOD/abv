import { useEffect, useRef, useState } from 'react'
import { leaveApi, LeaveType, LeaveTypePayload } from '../api/leaveApi.ts'
import { getApiErrorMessage } from '../utils/apiError.ts'
import { toast } from '../store/toastStore.ts'

const empty: LeaveTypePayload = { leaveName: '', shortName: '', deductFromBalance: false }
const input = 'mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm'
export default function LeaveTypesPanel() {
  const [types, setTypes] = useState<LeaveType[]>([])
  const [editing, setEditing] = useState<number | null | undefined>(undefined)
  const [form, setForm] = useState(empty)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    if (editing !== undefined) dialog.current?.showModal()
  }, [editing])
  const load = async () => {
    try { setTypes((await leaveApi.getTypes()).data.data.filter(t => !t.archived)); setError('') }
    catch (e) { setError(getApiErrorMessage(e, 'Məzuniyyət növləri yüklənmədi')) }
  }
  useEffect(() => { void load() }, [])
  const save = async () => {
    const next: Record<string, string> = {}
    if (!form.leaveName.trim() || form.leaveName.trim().length > 200) next.name = 'Ad 1–200 simvol olmalıdır'
    if (!/^\p{L}{1,3}$/u.test(form.shortName.trim())) next.code = 'Qısaltma 1–3 hərfdən ibarət olmalıdır'
    setErrors(next)
    if (Object.keys(next).length) return
    setBusy(true)
    try {
      if (editing == null) await leaveApi.createType(form)
      else await leaveApi.updateType(editing, form)
      setEditing(undefined); await load(); toast.success('Məzuniyyət növü yadda saxlanıldı')
    } catch (e) { setErrors({ save: getApiErrorMessage(e, 'Yadda saxlamaq mümkün olmadı') }) }
    finally { setBusy(false) }
  }
  const remove = async (type: LeaveType) => {
    if (!window.confirm(`${type.leaveName} silinsin? Əvvəlki məzuniyyət qeydləri saxlanılacaq.`)) return
    setBusy(true)
    try { await leaveApi.deleteType(type.id); await load(); toast.success('Məzuniyyət növü silindi') }
    catch (e) { toast.error('Silinmədi', getApiErrorMessage(e, 'Yenidən cəhd edin')) }
    finally { setBusy(false) }
  }
  return <section className="space-y-4 p-4 sm:p-6">
    <div className="flex items-center justify-between"><h2 className="text-xl font-semibold">Məzuniyyət növləri</h2>
      <button className="rounded-md bg-purple-600 px-4 py-2 text-sm text-white" onClick={() => { setEditing(null); setForm(empty); setErrors({}) }}>+ Yeni növ</button></div>
    {error && <p role="alert" className="text-red-600">{error}</p>}
    {editing !== undefined && <dialog ref={dialog} aria-labelledby="leave-type-title"
      onCancel={event => { event.preventDefault(); if (!busy) setEditing(undefined) }}
      className="max-h-[90vh] w-[calc(100%-2rem)] max-w-xl space-y-4 overflow-y-auto rounded-xl border-0 bg-white p-6 shadow-xl backdrop:bg-black/40">
      <div className="flex items-center justify-between"><h3 id="leave-type-title" className="text-lg font-semibold">{editing == null ? 'Yeni məzuniyyət növü' : 'Məzuniyyət növünü redaktə et'}</h3>
        <button disabled={busy} onClick={() => setEditing(undefined)} aria-label="Bağla">×</button></div>
      <label className="block text-sm">Məzuniyyətin adı<input className={input} maxLength={200} value={form.leaveName} onChange={e => setForm({ ...form, leaveName: e.target.value })} />
        {errors.name && <span role="alert" className="text-red-600">{errors.name}</span>}</label>
      <label className="block text-sm">Tabeldə qısaldılmış adı (ən çox 3 hərf)<input className={input} maxLength={3} value={form.shortName} onChange={e => setForm({ ...form, shortName: e.target.value.toLocaleUpperCase('az') })} />
        {errors.code && <span role="alert" className="text-red-600">{errors.code}</span>}</label>
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={form.deductFromBalance} onChange={e => setForm({ ...form, deductFromBalance: e.target.checked })} />Məzuniyyət balansından çıxılsın</label>
      {errors.save && <p role="alert" className="text-sm text-red-600">{errors.save}</p>}
      <div className="flex gap-3"><button disabled={busy} className="rounded-md bg-purple-600 px-4 py-2 text-sm text-white" onClick={() => void save()}>Yadda saxla</button>
        <button disabled={busy} onClick={() => setEditing(undefined)}>Bağla</button></div>
    </dialog>}
    <div className="overflow-x-auto rounded-lg border bg-white"><table className="w-full text-left text-sm">
      <thead className="bg-slate-50"><tr>{['Məzuniyyətin adı', 'Qısaltma', 'Balansdan çıxılır', 'Əməliyyat'].map(h => <th className="p-3" key={h}>{h}</th>)}</tr></thead>
      <tbody>{types.map(type => <tr className="border-t" key={type.id}>
        <td className="p-3">{type.leaveName}</td><td className="p-3">{type.shortName}</td><td className="p-3">{type.deductFromBalance ? 'Bəli' : 'Xeyr'}</td>
        <td className="whitespace-nowrap p-3"><button disabled={busy} className="mr-4 text-purple-700" onClick={() => { setEditing(type.id); setForm(type); setErrors({}) }}>Redaktə et</button>
          <button disabled={busy} className="text-red-600" onClick={() => void remove(type)}>Sil</button></td>
      </tr>)}</tbody></table></div>
  </section>
}
