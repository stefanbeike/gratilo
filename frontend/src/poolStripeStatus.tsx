export type MemberStripeStatus = {
  userId: string
  status: string
  onboardingStatus: string
  transfersEnabled: boolean | null
  payoutsEnabled: boolean | null
  checkedAt?: string | null
  /** A recent successful result is being shown while a refresh is unavailable. */
  refreshFailed?: boolean
}

export type TransferReadiness = 'READY' | 'NOT_READY' | 'UNKNOWN'
export const STRIPE_STATUS_MAX_AGE_MS = 120_000

function isFresh(status?: MemberStripeStatus, now = Date.now()) {
  if (!status?.checkedAt) return false
  const checked = Date.parse(status.checkedAt)
  return Number.isFinite(checked) && checked <= now + 30_000 && now - checked <= STRIPE_STATUS_MAX_AGE_MS
}

export function memberTransferState(status?: MemberStripeStatus, now = Date.now()): TransferReadiness {
  if (!isFresh(status, now) || status?.status === 'UNAVAILABLE') return 'UNKNOWN'
  if (status?.status === 'READY' && status.transfersEnabled === true) return 'READY'
  if (status?.status === 'READY' || status?.status === 'UNKNOWN') return 'UNKNOWN'
  // These are returned only after a successful Accounts-v2 read and represent
  // confirmed setup/restriction states, not transport errors.
  if (['NOT_STARTED', 'INCOMPLETE', 'UNDER_REVIEW', 'ACTION_REQUIRED'].includes(status?.status || '')) return 'NOT_READY'
  return 'UNKNOWN'
}

function retained(previous: MemberStripeStatus | undefined, now: number): MemberStripeStatus | undefined {
  if (!previous || !isFresh(previous, now) || memberTransferState(previous, now) === 'UNKNOWN') return undefined
  return { ...previous, refreshFailed: true }
}

/** Keep a recent confirmed result during an outage; otherwise expose UNKNOWN. */
export function mergeMemberStripeStatuses(
  previous: Record<string, MemberStripeStatus>, incoming: MemberStripeStatus[], now = Date.now(),
) {
  const result: Record<string, MemberStripeStatus> = {}
  for (const item of incoming) {
    if (!item?.userId) continue
    if (item.status === 'UNAVAILABLE') result[item.userId] = retained(previous[item.userId], now) || item
    else result[item.userId] = item
  }
  return result
}

export function markMemberStripeStatusesUnavailable(previous: Record<string, MemberStripeStatus>, now = Date.now()) {
  return Object.fromEntries(Object.entries(previous).map(([userId, status]) => [
    userId,
    retained(status, now) || { userId, status: 'UNAVAILABLE', onboardingStatus: 'UNKNOWN', transfersEnabled: null, payoutsEnabled: null, checkedAt: status.checkedAt || null },
  ]))
}

const descriptions: Record<string, [string, string, string]> = {
  NOT_STARTED: ['Onboarding ausstehend', 'Das Mitglied muss Stripe in seinem eigenen Profil einrichten.', 'pending'],
  INCOMPLETE: ['Onboarding unvollständig', 'Das Mitglied muss seine Stripe-Einrichtung im eigenen Profil fortsetzen.', 'pending'],
  UNDER_REVIEW: ['Stripe-Prüfung läuft', 'Stripe prüft die übermittelten Angaben. Die Transferfreigabe steht noch aus.', 'pending'],
  READY: ['Stripe-bereit', 'Der Empfang von Stripe-Transfers ist freigegeben.', 'booked'],
  ACTION_REQUIRED: ['Handlungsbedarf', 'Stripe hat eine Einschränkung oder fehlende Angabe bestätigt.', 'blocked'],
  UNAVAILABLE: ['Stripe-Status derzeit nicht überprüfbar', 'Die Transferberechtigung ist aktuell nicht überprüfbar. Bitte später erneut prüfen.', 'inactive'],
}

export function stripeDisplay(status?: MemberStripeStatus, now = Date.now()) {
  const readiness = memberTransferState(status, now)
  if (readiness === 'UNKNOWN') return descriptions.UNAVAILABLE
  if (readiness === 'READY') return descriptions.READY
  return descriptions[status?.status || ''] || descriptions.UNAVAILABLE
}

export function memberTransferReady(status?: MemberStripeStatus, now = Date.now()) {
  return memberTransferState(status, now) === 'READY'
}

export function memberPayoutReady(status?: MemberStripeStatus, now = Date.now()) {
  return isFresh(status, now) && status?.status !== 'UNAVAILABLE' && status?.payoutsEnabled === true
}

export function MemberStripeBadge({ status }: { status?: MemberStripeStatus }) {
  const readiness = memberTransferState(status)
  const [baseLabel, baseExplanation, tone] = stripeDisplay(status)
  const label = status?.refreshFailed && readiness === 'READY' ? 'Stripe-bereit · letzte Prüfung' : baseLabel
  const explanation = status?.refreshFailed && readiness === 'READY' ? 'Die letzte erfolgreiche Prüfung hat den Transferempfang freigegeben. Die aktuelle Aktualisierung ist vorübergehend fehlgeschlagen.' : baseExplanation
  const transfer = readiness === 'READY' ? 'Freigegeben' : readiness === 'NOT_READY' ? 'Nicht freigegeben' : 'Status unbekannt'
  const payout = readiness === 'UNKNOWN' || status?.payoutsEnabled == null ? 'Status unbekannt' : memberPayoutReady(status) ? 'Aktiv' : 'Nicht bestätigt'
  return <div className="member-stripe-status">
    <span className={`status-pill ${tone}`}>{label}</span>
    <small>{explanation}</small>
    <small>Transferempfang: {transfer} · Stripe-Payout-Fähigkeit: {payout}</small>
    {status?.checkedAt && <small>Letzte erfolgreiche Prüfung: {new Date(status.checkedAt).toLocaleString('de-DE')}{status.refreshFailed ? ' · Aktualisierung derzeit nicht möglich' : ''}</small>}
  </div>
}
