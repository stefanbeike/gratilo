/** Parse relative percentage-point weights without binary-float addition. */
export function percentageBasisPoints(raw: string): number | null {
  const match = /^(\d{1,3})(?:[,.](\d{1,2}))?$/.exec(raw.trim())
  if (!match) return null
  const result = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'))
  return result <= 10000 ? result : null
}

interface WeightedMember {
  id: string
  userId: string
  active: boolean
  status: string
  sharePercent: number
  effectiveSharePercent?: number
}

/** Mirrors PoolWeights.effectiveShares: largest remainder, ties by user UUID. */
export function poolDistribution(members: readonly WeightedMember[], drafts: Readonly<Record<string, string>> = {}) {
  const parsed = members.map(member => ({
    member,
    weight: percentageBasisPoints(drafts[member.id] ?? String(member.sharePercent)),
  }))
  const preview = parsed.some(({ member, weight }) =>
    weight === null || weight !== percentageBasisPoints(String(member.sharePercent)))
  const invalid = parsed.some(({ weight }) => weight === null)
  const eligible = parsed.filter(({ member, weight }) =>
    member.active && member.status === 'ACTIVE' && weight !== null && weight > 0)
    .sort((a, b) => a.member.userId < b.member.userId ? -1 : a.member.userId > b.member.userId ? 1 : 0)
  const shares: Record<string, number> = Object.fromEntries(members.map(member => [member.id, 0]))
  const total = eligible.reduce((sum, entry) => sum + BigInt(entry.weight!), 0n)
  if (total > 0n) {
    const allocations = eligible.map(({ member, weight }) => {
      const numerator = BigInt(weight!) * 10000n
      return { id: member.id, basis: Number(numerator / total), remainder: numerator % total }
    })
    const remaining = 10000 - allocations.reduce((sum, entry) => sum + entry.basis, 0)
    // Stable sort keeps the UUID order for equal remainders, just like the server.
    const order = [...allocations].sort((a, b) => a.remainder > b.remainder ? -1 : a.remainder < b.remainder ? 1 : 0)
    for (let index = 0; index < remaining; index++) order[index].basis++
    allocations.forEach(entry => { shares[entry.id] = entry.basis / 100 })
  }
  // Use a complete confirmed allocation; older/partial API responses use the same local fallback.
  const confirmed = eligible.map(({ member }) => member.effectiveSharePercent)
  if (!preview && confirmed.length > 0 && confirmed.every(value =>
    value !== undefined && Number.isFinite(value) && value >= 0 && value <= 100) &&
    confirmed.reduce<number>((sum, value) => sum + Math.round(value! * 100), 0) === 10000) {
    eligible.forEach(({ member }) => { shares[member.id] = member.effectiveSharePercent! })
  }
  return { shares, preview, invalid }
}
