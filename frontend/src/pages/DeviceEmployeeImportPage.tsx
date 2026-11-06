import { useEffect, useMemo, useState, type ReactNode } from 'react'
import Layout from '../components/Layout.tsx'
import { branchApi } from '../api/branchApi.ts'
import { deviceApi } from '../api/deviceApi.ts'
import {
  setupImportApi,
  DeviceEmployeeImportResult,
} from '../api/setupImportApi.ts'
import { Branch, DeviceConfig } from '../types'
import { useAuthStore } from '../store/authStore.ts'
import { Navigate } from 'react-router-dom'
import { doorRoleLabel } from '../i18n/labels'

/**
 * Setup-team page: import existing device users into employees for one branch.
 * Match key = device employeeNo (person ID).
 */
export default function DeviceEmployeeImportPage() {
  const { user } = useAuthStore()
  const isHeadOfficeHr = user?.userType === 'HEAD_OFFICE_HR'

  const [branches, setBranches] = useState<Branch[]>([])
  const [devices, setDevices] = useState<DeviceConfig[]>([])
  const [branchId, setBranchId] = useState<number | ''>('')
  const [selectedDeviceIds, setSelectedDeviceIds] = useState<number[]>([])
  const [useDeviceSubset, setUseDeviceSubset] = useState(false)
  const [writeToOtherBranchDevices, setWriteToOtherBranchDevices] = useState(true)
  const [loadingMeta, setLoadingMeta] = useState(true)
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<DeviceEmployeeImportResult | null>(null)

  useEffect(() => {
    if (!isHeadOfficeHr) return
    Promise.all([branchApi.getAll(), deviceApi.getAll()])
      .then(([branchRes, deviceRes]) => {
        setBranches(branchRes.data?.data ?? [])
        setDevices(deviceRes.data?.data ?? [])
      })
      .catch(() => setError('Ərazi və cihaz siyahısı yüklənmədi'))
      .finally(() => setLoadingMeta(false))
  }, [isHeadOfficeHr])

  const branchDevices = useMemo(
    () => (branchId === '' ? [] : devices.filter((d) => d.branchId === branchId)),
    [devices, branchId]
  )

  const selectedBranch = useMemo(
    () => (branchId === '' ? null : branches.find((b) => b.id === branchId) ?? null),
    [branches, branchId]
  )

  const previewPrefix = useMemo(() => {
    if (!selectedBranch) return ''
    const raw = (selectedBranch.code || selectedBranch.name || 'BR').toUpperCase().replace(/[^A-Z0-9]+/g, '')
    if (!raw) return 'BR'
    return raw.length > 8 ? raw.slice(0, 8) : raw
  }, [selectedBranch])

  useEffect(() => {
    setSelectedDeviceIds(branchDevices.map((d) => d.id))
    setUseDeviceSubset(false)
    setResult(null)
    setError(null)
  }, [branchId, branchDevices])

  if (!isHeadOfficeHr) {
    return <Navigate to="/" replace />
  }

  const toggleDevice = (id: number) => {
    setSelectedDeviceIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
    )
  }

  const handleImport = async () => {
    if (branchId === '') {
      setError('Ərazi seçin')
      return
    }
    if (branchDevices.length === 0) {
      setError('Bu ərazidə cihaz yoxdur')
      return
    }
    if (useDeviceSubset && selectedDeviceIds.length === 0) {
      setError('Ən azı bir cihaz seçin')
      return
    }

    setImporting(true)
    setError(null)
    setResult(null)
    try {
      const payload = {
        branchId: Number(branchId),
        writeToOtherBranchDevices,
        ...(useDeviceSubset ? { deviceConfigIds: selectedDeviceIds } : {}),
      }
      const res = await setupImportApi.importEmployees(payload)
      setResult(res.data?.data ?? null)
    } catch (e: any) {
      setError(e?.response?.data?.message || e?.message || 'İdxal alınmadı')
    } finally {
      setImporting(false)
    }
  }

  return (
    <Layout>
      <div className="p-6 max-w-5xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">Cihazdan əməkdaş idxalı</h1>
          <p className="text-sm text-gray-500 mt-1">
            Qurulum komandası üçün: ərazi cihazlarındakı mövcud istifadəçiləri və üz şəkillərini sistemə köçürür.
            Əməkdaş kodu ərazi prefiksi ilə yazılır (məs. <span className="font-medium text-gray-700">BAK-1001</span>),
            cihazdakı şəxs ID isə punch/sync üçün saxlanır. Bir cihazda tapılan şəxsi eyni ərazinin digər
            cihazlarına da yaza bilərsiniz.
          </p>
        </div>

        <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-5 space-y-4">
          {loadingMeta ? (
            <p className="text-sm text-gray-500">Yüklənir…</p>
          ) : (
            <>
              <div>
                <label className="block text-sm font-medium text-gray-700 mb-1">Əsas ərazi</label>
                <select
                  className="w-full max-w-md rounded-lg border border-gray-300 px-3 py-2 text-sm"
                  value={branchId}
                  onChange={(e) => setBranchId(e.target.value ? Number(e.target.value) : '')}
                >
                  <option value="">Ərazi seçin</option>
                  {branches.map((b) => (
                    <option key={b.id} value={b.id}>{b.name}{b.code ? ` (${b.code})` : ''}</option>
                  ))}
                </select>
                {selectedBranch && (
                  <p className="text-xs text-gray-500 mt-1.5">
                    Prefiks: <span className="font-mono font-medium text-gray-800">{previewPrefix}</span>
                    {' '}→ employeeId nümunəsi: <span className="font-mono">{previewPrefix}-1001</span>
                    {!selectedBranch.code && ' (ərazi kodu yoxdur — addan götürülür; daha yaxşı nəticə üçün Ərazilər-də kod təyin edin)'}
                  </p>
                )}
              </div>

              {branchId !== '' && (
                <div className="space-y-3">
                  <div className="flex items-center justify-between gap-3 flex-wrap">
                    <p className="text-sm text-gray-600">
                      Bu ərazidə <span className="font-semibold">{branchDevices.length}</span> cihaz var.
                      Standart: hamısı skan edilir.
                    </p>
                    <label className="flex items-center gap-2 text-sm text-gray-700 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={useDeviceSubset}
                        onChange={(e) => setUseDeviceSubset(e.target.checked)}
                      />
                      Cihazları əl ilə seç (problem olarsa)
                    </label>
                  </div>

                  {branchDevices.length === 0 ? (
                    <p className="text-sm text-amber-700 bg-amber-50 border border-amber-200 rounded-lg px-3 py-2">
                      Bu ərazidə cihaz yoxdur. Əvvəlcə Cihazlar səhifəsindən cihaz əlavə edin.
                    </p>
                  ) : (
                    <div className="grid gap-2 sm:grid-cols-2">
                      {branchDevices.map((d) => {
                        const checked = selectedDeviceIds.includes(d.id)
                        return (
                          <label
                            key={d.id}
                            className={`flex items-start gap-3 rounded-lg border px-3 py-2.5 text-sm ${
                              useDeviceSubset ? 'cursor-pointer' : 'opacity-90'
                            }`}
                            style={{
                              borderColor: useDeviceSubset && checked ? '#a855f7' : '#e5e7eb',
                              background: useDeviceSubset && checked ? 'rgba(168,85,247,0.06)' : '#fff',
                            }}
                          >
                            {useDeviceSubset && (
                              <input
                                type="checkbox"
                                className="mt-1"
                                checked={checked}
                                onChange={() => toggleDevice(d.id)}
                              />
                            )}
                            <span>
                              <span className="font-medium text-gray-900 block">
                                {d.deviceName || `Cihaz #${d.id}`}
                              </span>
                              <span className="text-xs text-gray-500">
                                {d.deviceIp}
                                {d.doorRole ? ` · ${doorRoleLabel(d.doorRole)}` : ''}
                              </span>
                            </span>
                          </label>
                        )
                      })}
                    </div>
                  )}
                </div>
              )}

              {branchId !== '' && branchDevices.length > 1 && (
                <label className="flex items-start gap-3 rounded-lg border border-gray-200 bg-slate-50 px-3 py-3 cursor-pointer">
                  <input
                    type="checkbox"
                    className="mt-1"
                    checked={writeToOtherBranchDevices}
                    onChange={(e) => setWriteToOtherBranchDevices(e.target.checked)}
                  />
                  <span>
                    <span className="block text-sm font-medium text-gray-900">
                      Ərazinin digər cihazlarına da yaz
                    </span>
                    <span className="block text-xs text-gray-500 mt-0.5">
                      Bir cihazda tapılan şəxs eyni ərazinin digər cihazlarında yoxdursa, ora da əlavə olunur
                      (ad və üz şəkli ilə, əgər üz varsa).
                    </span>
                  </span>
                </label>
              )}

              {error && (
                <div className="text-sm text-red-700 bg-red-50 border border-red-200 rounded-lg px-3 py-2">
                  {error}
                </div>
              )}

              <button
                type="button"
                onClick={handleImport}
                disabled={importing || branchId === '' || branchDevices.length === 0}
                className="inline-flex items-center gap-2 rounded-lg px-4 py-2.5 text-sm font-medium text-white disabled:opacity-50"
                style={{ background: '#a855f7' }}
              >
                {importing ? 'İdxal edilir…' : 'İdxalı başlat'}
              </button>
            </>
          )}
        </div>

        {result && (
          <div className="bg-white rounded-xl border border-gray-200 shadow-sm p-5 space-y-5">
            <div>
              <h2 className="text-lg font-semibold text-gray-900">Nəticə</h2>
              <p className="text-sm text-gray-500 mt-1">{result.message}</p>
            </div>

            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              <Stat label="Yaradılan" value={result.created} tone="green" />
              <Stat label="Mövcud (keçildi)" value={result.skippedExisting} />
              <Stat label="İşdən çıxmış (keçildi)" value={result.skippedTerminated || 0} />
              <Stat label="Digər ərazi əlaqəsi" value={result.crossBranchLinked || 0} tone="green" />
              <Stat label="Konflikt" value={result.skippedConflict} tone="amber" />
              <Stat label="Xəta" value={result.errors} tone="red" />
              <Stat label="Cihaz skanı" value={result.devicesScanned} />
              <Stat label="Cihaz xətası" value={result.devicesFailed} tone="red" />
              <Stat label="Giriş əlaqələndirildi" value={result.accessLinked} tone="green" />
              <Stat label="Üz şəkli sinxron" value={result.facesSynced || 0} tone="green" />
              <Stat label="Üzü olmayan" value={result.facesSkippedNoMatch || 0} />
              <Stat label="Mövcud üz şəkli (unikal)" value={result.facesSkippedExisting || 0} />
              <Stat label="Şəkil xətası" value={result.facesFailed || 0} tone="red" />
              <Stat label="Digər cihazlara yazıldı" value={result.writtenToOtherDevices || 0} tone="green" />
              <Stat label="Artıq digər cihazda var idi" value={result.alreadyOnOtherDevices || 0} />
              <Stat label="Digər cihaza yazıla bilmədi" value={result.otherDeviceWriteFailed || 0} tone="red" />
              <Stat label="Üz digər cihaza yazıldı" value={result.facesWrittenToOtherDevices || 0} tone="green" />
            </div>

            {(result.branchPrefix || result.branchName) && (
              <p className="text-sm text-gray-600">
                Əsas ərazi: <span className="font-medium">{result.branchName}</span>
                {result.branchPrefix ? <> · prefiks <span className="font-mono">{result.branchPrefix}</span></> : null}
              </p>
            )}

            {result.deviceStatuses?.length > 0 && (
              <Section title="Cihaz vəziyyətləri">
                <div className="overflow-x-auto">
                  <table className="min-w-full text-sm">
                    <thead>
                      <tr className="text-left text-gray-500 border-b">
                        <th className="py-2 pr-3">Cihaz</th>
                        <th className="py-2 pr-3">IP</th>
                        <th className="py-2 pr-3">Vəziyyət</th>
                        <th className="py-2 pr-3">İstifadəçi</th>
                        <th className="py-2 pr-3">Üz</th>
                        <th className="py-2">Şəkil sinxron</th>
                      </tr>
                    </thead>
                    <tbody>
                      {result.deviceStatuses.map((s) => (
                        <tr key={s.deviceConfigId} className="border-b border-gray-100">
                          <td className="py-2 pr-3">{s.deviceName || s.deviceConfigId}</td>
                          <td className="py-2 pr-3 text-gray-500">{s.deviceIp}</td>
                          <td className="py-2 pr-3">
                            {s.success ? (
                              <span className="text-emerald-700">Uğurlu</span>
                            ) : (
                              <span className="text-red-600" title={s.error}>Xəta</span>
                            )}
                          </td>
                          <td className="py-2 pr-3">{s.userCount}</td>
                          <td className="py-2 pr-3">{s.faceCount ?? '—'}</td>
                          <td className="py-2">{s.facesSynced ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Section>
            )}

            {result.conflicts?.length > 0 && (
              <Section title="Konfliktlər (əl ilə yoxlayın)">
                <PersonList items={result.conflicts} />
              </Section>
            )}

            {result.errorsDetail?.length > 0 && (
              <Section title="Xətalar">
                <PersonList items={result.errorsDetail} />
              </Section>
            )}

            {result.createdPersons?.length > 0 && (
              <Section title={`Yaradılan əməkdaşlar (${result.createdPersons.length})`}>
                <div className="overflow-x-auto max-h-64 overflow-y-auto">
                  <table className="min-w-full text-sm">
                    <thead>
                      <tr className="text-left text-gray-500 border-b">
                        <th className="py-2 pr-3">Əməkdaş kodu</th>
                        <th className="py-2 pr-3">Cihaz ID</th>
                        <th className="py-2 pr-3">Ad</th>
                        <th className="py-2">Cihazlar</th>
                      </tr>
                    </thead>
                    <tbody>
                      {result.createdPersons.map((p) => (
                        <tr key={p.employeePk} className="border-b border-gray-100">
                          <td className="py-2 pr-3 font-mono text-xs">{p.employeeId || p.employeeNo}</td>
                          <td className="py-2 pr-3 font-mono text-xs text-gray-500">{p.deviceEmployeeNo || p.employeeNo}</td>
                          <td className="py-2 pr-3">{p.firstName} {p.lastName}</td>
                          <td className="py-2 text-gray-500">{(p.deviceConfigIds || []).join(', ')}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </Section>
            )}
          </div>
        )}
      </div>
    </Layout>
  )
}

function Stat({
  label,
  value,
  tone,
}: {
  label: string
  value: number
  tone?: 'green' | 'amber' | 'red'
}) {
  const color =
    tone === 'green' ? '#065f46' : tone === 'amber' ? '#92400e' : tone === 'red' ? '#991b1b' : '#111827'
  return (
    <div className="rounded-lg border border-gray-200 bg-gray-50 px-3 py-2">
      <p className="text-xs text-gray-500">{label}</p>
      <p className="text-xl font-semibold" style={{ color }}>{value}</p>
    </div>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <h3 className="text-sm font-semibold text-gray-800 mb-2">{title}</h3>
      {children}
    </div>
  )
}

function PersonList({ items }: { items: { employeeNo: string; name?: string; reason: string }[] }) {
  return (
    <ul className="space-y-2">
      {items.map((item, idx) => (
        <li key={`${item.employeeNo}-${idx}`} className="text-sm rounded-lg border border-gray-200 px-3 py-2">
          <span className="font-mono text-xs text-gray-700">{item.employeeNo}</span>
          {item.name ? <span className="text-gray-600"> — {item.name}</span> : null}
          <p className="text-xs text-gray-500 mt-0.5">{item.reason}</p>
        </li>
      ))}
    </ul>
  )
}
