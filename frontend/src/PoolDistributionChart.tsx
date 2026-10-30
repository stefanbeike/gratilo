import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { PieChart } from 'react-minimal-pie-chart'
import type { PoolParticipant } from './PoolMembersPanel'
import { poolDistribution } from './poolPercentages'

const emptyDrafts: Record<string, string> = {}
const percent = new Intl.NumberFormat('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const palette = ['#2875b9', '#318570', '#b76a30', '#8558aa', '#c04f73', '#367f92', '#8a7833', '#5664ae']

// Keep colors reserved while membership order, activity and weights change.
function colorForMember(id: string, colors: Map<string, string>) {
  const existing = colors.get(id)
  if (existing) return existing
  let hash = 2166136261
  for (const character of id) hash = Math.imul(hash ^ character.charCodeAt(0), 16777619)
  const used = new Set(colors.values())
  let color = palette[(hash >>> 0) % palette.length]
  if (used.has(color)) color = palette.find(candidate => !used.has(candidate)) ||
    `hsl(${((hash >>> 0) + colors.size * 137.508) % 360} 55% 43%)`
  colors.set(id, color)
  return color
}

type ChartEntry = { key: string; value: number }

// Interpolate geometry only; the legend always shows the exact current shares.
function useAnimatedShares(data: ChartEntry[]) {
  const initial = Object.fromEntries(data.map(entry => [entry.key, entry.value]))
  const current = useRef<Record<string, number>>(initial)
  const [values, setValues] = useState(initial)
  useEffect(() => {
    const target = Object.fromEntries(data.map(entry => [entry.key, entry.value]))
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches || !data.length) {
      current.current = target
      setValues(target)
      return
    }
    const from = current.current
    if (data.every(entry => from[entry.key] === entry.value)) return
    const start = performance.now()
    let frame: number
    const tick = (now: number) => {
      const progress = Math.min(1, (now - start) / 250)
      const eased = 1 - (1 - progress) ** 3
      const next = Object.fromEntries(data.map(entry => [entry.key,
        (from[entry.key] ?? 0) + (entry.value - (from[entry.key] ?? 0)) * eased]))
      current.current = next
      setValues(next)
      if (progress < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [data])
  return values
}

export function PoolDistributionChart({ members, drafts = emptyDrafts }: {
  members: PoolParticipant[]
  drafts?: Record<string, string>
}) {
  const headingId = useId()
  const legendId = useId()
  const [highlighted, setHighlighted] = useState<string | null>(null)
  const colors = useRef(new Map<string, string>())
  const distribution = useMemo(() => poolDistribution(members, drafts), [members, drafts])
  const data = useMemo(() => [...members].sort((a, b) => a.userId < b.userId ? -1 : a.userId > b.userId ? 1 : 0).filter(member => distribution.shares[member.id] > 0)
    .map(member => ({
      key: member.id,
      name: member.name || member.email,
      value: distribution.shares[member.id],
      color: colorForMember(member.userId, colors.current),
      title: `${member.name || member.email}: ${percent.format(distribution.shares[member.id])} %`,
    })), [members, distribution])
  const animatedShares = useAnimatedShares(data)
  const chartData = data.map(entry => ({ ...entry, value: animatedShares[entry.key] ?? 0 }))
  const selected = data.find(entry => entry.key === highlighted)
  const hasActiveMembers = members.some(member => member.active && member.status === 'ACTIVE')

  return <section className="account-card pool-distribution" aria-labelledby={headingId} onKeyDown={event => { if (event.key === 'Escape') setHighlighted(null) }}>
    <div className="pool-distribution-heading">
      <div><h2 id={headingId}>Trinkgeldverteilung</h2><p>Effektive Anteile der aktiven Pool-Mitglieder</p></div>
      {distribution.preview && <span className="status-pill pending" role="status">Vorschau – noch nicht gespeichert</span>}
    </div>
    {data.length > 0 ? <div className="pool-distribution-layout">
      <div className="pool-distribution-visual" role="img" aria-label="Trinkgeldverteilung als Donut-Diagramm" aria-describedby={legendId}>
        <PieChart data={chartData.some(entry => entry.value > 0) ? chartData : data} lineWidth={28} startAngle={-90}
          segmentsStyle={index => ({ opacity: selected && selected.key !== data[index].key ? 0.55 : 1 })}
          onMouseOver={(_, index) => setHighlighted(data[index].key)} onMouseOut={() => setHighlighted(null)}
          onClick={(_, index) => setHighlighted(previous => previous === data[index].key ? null : data[index].key)} />
        <div className="pool-distribution-center" aria-hidden="true"><strong>100 %</strong><span>verteilt</span></div>
        {selected && <div className="pool-distribution-tooltip" role="tooltip">{selected.title}</div>}
      </div>
      <ul className="pool-distribution-legend" id={legendId} aria-label="Mitglieder und effektive Anteile">
        {data.map(entry => <li key={entry.key}><button type="button" aria-pressed={selected?.key === entry.key}
          onMouseEnter={() => setHighlighted(entry.key)} onMouseLeave={() => setHighlighted(null)}
          onFocus={() => setHighlighted(entry.key)} onBlur={() => setHighlighted(null)}
          onClick={() => setHighlighted(previous => previous === entry.key ? null : entry.key)}>
          <span className="pool-distribution-swatch" style={{ backgroundColor: entry.color }} aria-hidden="true" />
          <span className="pool-distribution-name">{entry.name}</span><strong>{percent.format(entry.value)} %</strong>
        </button></li>)}
      </ul>
    </div> : <p className="muted pool-distribution-empty">{hasActiveMembers
      ? 'Noch keine Verteilung: Mindestens ein aktives Mitglied benötigt ein gültiges positives Prozentgewicht.'
      : 'Noch keine aktiven Mitglieder. Sobald ein Mitglied aktiv ist und ein positives Prozentgewicht hat, erscheint die Verteilung hier.'}</p>}
    {distribution.invalid && <p className="muted" role="status">Leere oder ungültige Gewichte werden in der Vorschau ausgelassen. Bitte gib zum Speichern Werte von 0 bis 100 mit höchstens zwei Nachkommastellen ein.</p>}
  </section>
}
