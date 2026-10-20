export type MemberStripeStatus = {
  userId: string; status: string; onboardingStatus: string; transfersEnabled: boolean; payoutsEnabled: boolean; checkedAt?: string
}
const descriptions: Record<string, [string, string, string]> = {
  NOT_STARTED: ['Onboarding ausstehend', 'Das Mitglied muss Stripe in seinem eigenen Profil einrichten.', 'pending'],
  INCOMPLETE: ['Onboarding unvollständig', 'Das Mitglied muss seine Stripe-Einrichtung im eigenen Profil fortsetzen.', 'pending'],
  UNDER_REVIEW: ['Stripe-Prüfung läuft', 'Stripe prüft die übermittelten Angaben. Die Transferfreigabe steht noch aus.', 'pending'],
  READY: ['Bereit für Trinkgelder', 'Der Empfang von Stripe-Transfers ist freigegeben.', 'booked'],
  ACTION_REQUIRED: ['Handlungsbedarf', 'Das Mitglied muss erforderliche Angaben oder Einschränkungen bei Stripe prüfen.', 'blocked'],
  UNAVAILABLE: ['Nicht verfügbar', 'Der Stripe-Status konnte nicht zuverlässig ermittelt werden. Es besteht keine Freigabe.', 'inactive'],
}
export function stripeDisplay(status?: MemberStripeStatus) {
  const current = status?.checkedAt && Date.now() - Date.parse(status.checkedAt) < 120000
  const code = current && (status?.status !== 'READY' || status.transfersEnabled) ? status!.status : 'UNAVAILABLE'
  return descriptions[code] || descriptions.UNAVAILABLE
}
export function memberTransferReady(status?: MemberStripeStatus) {
  return stripeDisplay(status)[2] === 'booked' && status?.transfersEnabled === true
}
export function memberPayoutReady(status?:MemberStripeStatus) {
  return !!status?.checkedAt && Date.now()-Date.parse(status.checkedAt)<120000 && status.status!=='UNAVAILABLE' && status.payoutsEnabled===true
}
export function MemberStripeBadge({ status }: { status?: MemberStripeStatus }) {
  const [label, explanation, tone] = stripeDisplay(status)
  return <div className="member-stripe-status"><span className={`status-pill ${tone}`}>{label}</span><small>{explanation}</small>
    <small>Transferempfang: {memberTransferReady(status) ? 'Freigegeben' : 'Nicht freigegeben'} · Bankauszahlung: {memberPayoutReady(status) ? 'Freigegeben' : 'Nicht bestätigt'}</small>
    {status?.checkedAt && <small>Zuletzt geprüft: {new Date(status.checkedAt).toLocaleString('de-DE')}</small>}
  </div>
}
