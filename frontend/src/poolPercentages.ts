/** Stored percentage-point weights, not a frontend prediction of authoritative shares. */
export function percentageBasisPoints(raw: string): number | null {
  const match = /^(\d{1,3})(?:[,.](\d{1,2}))?$/.exec(raw.trim())
  if (!match) return null
  const result = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'))
  return result <= 10000 ? result : null
}
