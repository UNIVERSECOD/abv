/** Accept stored IPv4:port and [IPv6]:port while keeping the form's port editable. */
export function splitDeviceEndpoint(value: string, fallbackPort = 80) {
  const address = value.trim()
  const bracketed = address.match(/^\[([^\]]+)\](?::(\d+))?$/)
  if (bracketed) return { host: bracketed[1], port: Number(bracketed[2] || fallbackPort) }
  const ipv4 = address.match(/^([^:]+):(\d+)$/)
  if (ipv4) return { host: ipv4[1], port: Number(ipv4[2]) }
  return { host: address, port: fallbackPort }
}

export function deviceEndpoint(value: string, port?: number) {
  const parsed = splitDeviceEndpoint(value)
  const host = parsed.host.includes(':') ? `[${parsed.host}]` : parsed.host
  return `${host}:${port ?? parsed.port}`
}
