const methods: Record<string, { path: string; label: string }> = {
  ANPR: { path: 'M5 17H3v-4l2-3 2-5h10l2 5 2 3v4h-2m-14 0v2m14-2v2M5 17h14M5 10h14M7 14h1m8 0h1', label: 'Nəqliyyat (ANPR)' },
  FACE: { path: 'M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5M8 9v1m8-1v1m-7 5a4 4 0 0 0 6 0', label: 'Üz tanıma' },
  CARD: { path: 'M3 6h18v12H3zM3 10h18m-14 4h3', label: 'Kart' },
  ACCESS: { path: 'M13 4h7v16h-7m-9-8h11m-4-4 4 4-4 4', label: 'Giriş terminalı' },
  MANUAL: { path: 'M4 16 16 4l4 4L8 20H4zM13 7l4 4', label: 'Əl ilə düzəliş' },
}

export default function AttendanceMethodTime({ time, method }: { time: string; method?: string }) {
  const source = method ? methods[method] : undefined
  return <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
    {time}
    {source && <svg role="img" aria-label={source.label} className="h-4 w-4 shrink-0 text-slate-600"
      viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.7}
      strokeLinecap="round" strokeLinejoin="round">
      <title>{source.label}</title>
      <path d={source.path} />
    </svg>}
  </span>
}
