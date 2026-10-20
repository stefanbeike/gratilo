export type ConnectedAccount = { transfersEnabled?: boolean; stripeStatus?: string; statusCheckedAt?: string; stripeAccountId?: string; onboardingStatus: string; chargesEnabled: boolean; payoutsEnabled: boolean; detailsSubmitted: boolean; payoutScheduleInterval?: string }
export function stripeDashboardStatus(account: ConnectedAccount | null) {
  if (!account) return { title: 'Status wird geprüft.', detail: 'Die Stripe-Freigabe ist noch nicht bestätigt.', tone: 'inactive', action: null }
  if (!account.stripeAccountId || account.onboardingStatus === 'NOT_STARTED') return { title: 'Richte deinen Zahlungsempfang ein.', detail: 'Verbinde dein eigenes Stripe-Konto, um Trinkgelder zu erhalten.', tone: 'pending', action: 'Jetzt einrichten' }
  if (!account.statusCheckedAt || !Number.isFinite(Date.parse(account.statusCheckedAt)) || Date.now() - Date.parse(account.statusCheckedAt) > 120000 || account.stripeStatus === 'UNAVAILABLE') return { title: 'Status wird geprüft.', detail: 'Der letzte Stripe-Status ist nicht zuverlässig verfügbar.', tone: 'inactive', action: null }
  if (account.stripeStatus === 'ACTION_REQUIRED') return { title: 'Stripe benötigt weitere Angaben.', detail: 'Dein Konto ist eingeschränkt. Prüfe die erforderlichen Angaben direkt bei Stripe.', tone: 'blocked', action: 'Angaben vervollständigen' }
  if (account.stripeStatus === 'INCOMPLETE') return { title: 'Stripe benötigt weitere Angaben.', detail: 'Vervollständige deine Einrichtung, damit Stripe deinen Zahlungsempfang freigeben kann.', tone: 'pending', action: 'Angaben vervollständigen' }
  const receives = account.transfersEnabled === true
  if (receives && account.chargesEnabled && account.payoutsEnabled) return { title: 'Vollständig aktiv', detail: 'Zahlungsempfang und Bankauszahlungen sind freigegeben.', tone: 'booked', action: null }
  if(receives&&!account.chargesEnabled)return {title:'Zahlungen möglich',detail:'Team-Transfers sind freigegeben. Persönliche Kartenzahlungen sind noch nicht vollständig freigegeben.',tone:'pending',action:account.detailsSubmitted?null:'Einrichtung fortsetzen'}
  if (receives) return { title: 'Zahlungen möglich', detail: 'Du kannst Trinkgelder empfangen. Bankauszahlungen sind noch nicht freigegeben.', tone: 'pending', action: account.detailsSubmitted ? null : 'Einrichtung fortsetzen' }
  return { title: 'Einrichtung läuft', detail: account.stripeStatus === 'UNDER_REVIEW' ? 'Stripe prüft deine Angaben. Die Freigabe steht noch aus.' : 'Schließe die Einrichtung deines Stripe-Kontos ab.', tone: 'pending', action: account.stripeStatus === 'UNDER_REVIEW' ? null : 'Einrichtung fortsetzen' }
}
export function StripeStatusCard({ account, enabled, onStart, onRefresh }: { account: ConnectedAccount | null; enabled: boolean; onStart: () => void; onRefresh: () => void }) {
  const status = stripeDashboardStatus(account)
  const compact = status.title === 'Vollständig aktiv'
  return <section className={`account-card stripe-central ${compact ? 'stripe-compact' : ''}`} aria-label="Zentraler Stripe-Status">
    <span className={`stripe-status-symbol ${status.tone}`} aria-hidden="true">{compact ? '✓' : status.tone === 'blocked' ? '!' : '◷'}</span>
    <div><span className="step-label">ZAHLUNGSEMPFANG · STRIPE</span><h2>{status.title}</h2><p>{enabled ? status.detail : 'Zahlungen sind derzeit deaktiviert oder noch nicht für den Test freigegeben.'}</p>
    {!compact && <small>Kartenzahlungen: {enabled && account?.chargesEnabled && status.tone !== 'inactive' && status.tone !== 'blocked' ? 'Freigegeben' : 'Nicht bestätigt'} · Team-Transferempfang: {enabled && account?.transfersEnabled && status.tone !== 'inactive' && status.tone !== 'blocked' ? 'Freigegeben' : 'Nicht bestätigt'} · Bankauszahlung: {enabled && account?.payoutsEnabled && status.tone !== 'inactive' && status.tone !== 'blocked' ? 'Freigegeben' : 'Nicht bestätigt'}. Die Team-Freigabe wird separat geprüft.</small>}</div>
    <div className="stripe-central-actions">{(status.action || !enabled) && <button className="solid-button" disabled={!enabled} onClick={onStart}>{status.action || 'Jetzt einrichten'}</button>}<button className="text-button" disabled={!enabled} onClick={onRefresh}>Status aktualisieren</button></div>
  </section>
}
