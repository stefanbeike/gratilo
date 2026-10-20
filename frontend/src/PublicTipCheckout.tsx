import { useEffect, useMemo, useState } from 'react'
import type { Session } from './api'
import { paymentsEnabled } from './stripe'

type Quote = { tipAmountMinor: number; serviceFeeMinor: number; totalAmountMinor: number; currency: string; serviceFeePercent: number; fixedFeeMinor: number; recipientAmountMinor: number }
type Recipient = { userId: string; firstName?: string; lastName?: string; organisation?: string; profileImage?: string | null; paymentEnabled?: boolean; poolName?: string | null; memberNames?: string[] }
type SavedFlow = { amount: number; message: string; feeCovered: boolean; step: 1 | 2 | 3; attemptKey?: string; pendingSessionId?: string }
type CheckoutStatus = { status: 'PAID' | 'PENDING' | 'FAILED'; confirmed: boolean; transactionId?: string; tipAmountMinor?: number; amountPaidMinor?: number; recipientUserId?: string; checkoutUrl?: string }
const MIN_TIP = 100
const MAX_TIP = 1_000_000
const money = (minor: number) => new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(minor / 100)
const parseMoney = (raw: string): number | null => {
  const value = raw.trim().replace(',', '.')
  if (!/^\d+(?:\.\d{0,2})?$/.test(value)) return null
  const [euros, cents = ''] = value.split('.')
  const minor = Number(euros) * 100 + Number(cents.padEnd(2, '0'))
  return Number.isSafeInteger(minor) && minor >= MIN_TIP && minor <= MAX_TIP ? minor : null
}
const savedFlow = (key: string): SavedFlow => {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) || 'null')
    if (value && Number.isSafeInteger(value.amount) && value.amount >= MIN_TIP && value.amount <= MAX_TIP)
      return { amount: value.amount, message: typeof value.message === 'string' ? value.message.slice(0, 500) : '', feeCovered: value.feeCovered === true, step: [1, 2, 3].includes(value.step) ? value.step : 1, attemptKey: value.attemptKey, pendingSessionId: value.pendingSessionId }
  } catch { /* Ignore damaged local checkout data. */ }
  return { amount: 500, message: '', feeCovered: false, step: 1 }
}

export function PublicTipCheckout({ session }: { session?: Session | null }) {
  const path = location.pathname
  const isSuccess = path.endsWith('/success')
  const isCancelled = path.endsWith('/cancelled')
  const isPool = path.startsWith('/pay/team/') || ((isSuccess || isCancelled) && sessionStorage.getItem('gratilo:last-pool') === 'true')
  const recipientPath = isSuccess || isCancelled ? sessionStorage.getItem('gratilo:last-recipient') || '' : path.split('/').pop() || ''
  const flowStorageKey = `gratilo:checkout:${recipientPath || 'current'}`
  const [flow, setFlow] = useState<SavedFlow>(() => savedFlow(flowStorageKey))
  const [amountInput, setAmountInput] = useState((flow.amount / 100).toFixed(2).replace('.', ','))
  const [recipient, setRecipient] = useState<Recipient | null>(null)
  const [recipientId, setRecipientId] = useState('')
  const [eligibility, setEligibility] = useState<boolean | null>(null)
  const [quote, setQuote] = useState<Quote | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [checkoutStatus, setCheckoutStatus] = useState<CheckoutStatus | null>(null)
  const [statusBusy, setStatusBusy] = useState(false)
  const [loadRevision, setLoadRevision] = useState(0)
  const [retrying, setRetrying] = useState(false)
  const headers = useMemo<Record<string, string>>(() => session?.accessToken ? { Authorization: `Bearer ${session.accessToken}` } : {} as Record<string, string>, [session?.accessToken])
  const isResult = (isSuccess || isCancelled) && !retrying
  const name = recipient?.poolName || [recipient?.firstName, recipient?.lastName].filter(Boolean).join(' ') || recipient?.organisation || 'deinen Service'
  const validInputAmount = parseMoney(amountInput)

  useEffect(() => { sessionStorage.setItem(flowStorageKey, JSON.stringify(flow)) }, [flow, flowStorageKey])
  useEffect(() => {
    if (!recipientPath) return
    const endpoint = isPool ? `/user-service/pools/public/${encodeURIComponent(recipientPath)}`
      : `/user-service/users/public/${(/^[0-9a-f-]{36}$/i.test(recipientPath) ? recipientPath : `payment/${encodeURIComponent(recipientPath)}`)}`
    let current = true
    fetch(endpoint, { cache: 'no-store' }).then(response => response.ok ? response.json() : Promise.reject())
      .then((profile: Recipient | { ownerId: string; name: string; memberNames: string[] }) => { if (current) { const recipientProfile = isPool ? { userId: (profile as { ownerId: string }).ownerId, poolName: (profile as { name: string }).name, memberNames: (profile as { memberNames: string[] }).memberNames } : profile as Recipient; setRecipient(recipientProfile); setRecipientId(recipientProfile.userId); sessionStorage.setItem('gratilo:last-recipient', recipientPath); sessionStorage.setItem('gratilo:last-pool', String(isPool)) } })
      .catch(() => { if (current) setError('Das Gratilo-Profil konnte nicht geladen werden.') })
    return () => { current = false }
  }, [recipientPath, isPool])
  useEffect(() => {
    if (!recipientId || isResult) return
    let current = true
    fetch(`/payment-service/tips/${isPool ? `pools/${recipientId}` : recipientId}/eligibility`, { headers, cache: 'no-store' })
      .then(response => response.ok ? response.json() : Promise.reject())
      .then(value => { if (current) setEligibility(value?.canReceiveTips === true) })
      .catch(() => { if (current) setEligibility(false) })
    return () => { current = false }
  }, [recipientId, headers, isResult, isPool])
  useEffect(() => {
    if (!recipientId || isResult || !validInputAmount) { setQuote(null); return }
    let current = true
    setQuote(null)
    fetch(`/payment-service/tips/${isPool ? `pools/${recipientId}` : recipientId}/quote?amountMinor=${validInputAmount}&feeCovered=${flow.feeCovered}`, { headers, cache: 'no-store' })
      .then(response => response.ok ? response.json() : Promise.reject())
      .then(value => { if (current) { setQuote(value); setError('') } })
      .catch(() => { if (current) { setQuote(null); setError('Der Betrag konnte gerade nicht berechnet werden. Bitte versuche es erneut.') } })
    return () => { current = false }
  }, [recipientId, validInputAmount, flow.feeCovered, headers, isResult, loadRevision, isPool])
  const sessionId = new URLSearchParams(location.search).get('session_id') || flow.pendingSessionId
  useEffect(() => {
    if (!isResult || !sessionId) return
    let current = true
    const refresh = async () => {
      setStatusBusy(true)
      try {
        const response = await fetch(`/payment-service/tips/checkout/confirm?sessionId=${encodeURIComponent(sessionId)}`, { headers, cache: 'no-store' })
        if (!response.ok) throw new Error('Status momentan nicht verfügbar')
        const result = await response.json() as CheckoutStatus
        if (current) setCheckoutStatus(result)
      } catch {
        if (current) setCheckoutStatus({ status: 'PENDING', confirmed: false })
      } finally { if (current) setStatusBusy(false) }
    }
    void refresh()
    return () => { current = false }
  }, [isResult, sessionId, headers, loadRevision])

  function update(patch: Partial<SavedFlow>) { setFlow(previous => ({ ...previous, ...patch })) }
  function nextKey() { return crypto.randomUUID() }
  async function startCheckout() {
    if (!recipientId || eligibility !== true || !quote || validInputAmount !== flow.amount) return
    setBusy(true); setError('')
    const attemptKey = flow.attemptKey || nextKey()
    update({ attemptKey })
    try {
      const response = await fetch(`/payment-service/tips/${isPool ? `pools/${recipientId}` : recipientId}/checkout`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify({ tipAmountMinor: flow.amount, message: flow.message || null, idempotencyKey: attemptKey, feeCovered: flow.feeCovered }) })
      const result = await response.json()
      if (!response.ok) throw new Error(result.message || 'Stripe Checkout konnte nicht gestartet werden.')
      if (!result.checkoutUrl) throw new Error('Die sichere Stripe-Zahlungsseite wurde nicht zurückgegeben. Der Versuch bleibt für eine sichere Wiederholung gespeichert.')
      if (result.transactionId) sessionStorage.setItem('gratilo:last-transaction', result.transactionId)
      location.assign(result.checkoutUrl)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Zahlung konnte nicht gestartet werden.') }
    finally { setBusy(false) }
  }
  function retryAfterConfirmedFailure() {
    update({ step: 3, attemptKey: nextKey(), pendingSessionId: undefined })
    setRetrying(true)
  }
  async function refreshStatus() { setLoadRevision(value => value + 1) }

  if (!paymentsEnabled) return <main className="tip-page"><CheckoutBrand /><section className="tip-card"><h1>Zahlungen sind derzeit deaktiviert.</h1><p>Aktuell sind keine Zahlungen möglich.</p></section></main>
  if (recipient && recipient.paymentEnabled === false) return <main className="tip-page"><CheckoutBrand /><section className="tip-card"><span className="step-label">ZAHLUNGSSEITE</span><h1>Momentan nicht verfügbar.</h1><p className="tip-intro">Diese Zahlungsseite ist derzeit deaktiviert.</p></section></main>

  if (isResult) {
    const status = checkoutStatus?.status || 'PENDING'
    const paid = status === 'PAID' && checkoutStatus?.confirmed === true
    const failed = status === 'FAILED'
    return <main className="tip-page"><CheckoutBrand /><section className="tip-card checkout-result" aria-live="polite">
      {recipient?.profileImage && <img className="tip-profile-image" src={recipient.profileImage} alt={`Profilbild von ${name}`} />}
      {paid ? <><span className="step-label">ZAHLUNG BESTÄTIGT</span><h1>Danke!</h1><p>Dein Trinkgeld für {name} wurde erfolgreich bezahlt.</p><div className="tip-breakdown"><SummaryLine label="Bezahlt" value={money(checkoutStatus.amountPaidMinor || flow.amount)} /><SummaryLine label="Referenz" value={checkoutStatus.transactionId || sessionStorage.getItem('gratilo:last-transaction') || '—'} /></div><p className="tip-note">Die Zahlungsbestätigung bedeutet nicht, dass das Geld bereits auf dem Bankkonto des Empfängers ausgezahlt wurde.</p></>
        : failed ? <><span className="step-label">ZAHLUNG FEHLGESCHLAGEN</span><h1>Das hat leider nicht geklappt.</h1><p>Dieser Checkout wurde von Stripe als abgelaufen oder fehlgeschlagen bestätigt. Deine Betragsauswahl und Nachricht bleiben erhalten.</p><div className="tip-breakdown"><SummaryLine label="Dein Betrag" value={money(flow.amount)} /><SummaryLine label="Gebührenübernahme" value={flow.feeCovered ? 'Ausgewählt' : 'Nicht ausgewählt'} /></div><button className="tip-pay" onClick={retryAfterConfirmedFailure}>Erneut versuchen</button></>
        : <><span className="step-label">ZAHLUNG WIRD GEPRÜFT</span><h1>Bitte noch nicht erneut bezahlen.</h1><p>Der Status bei Stripe ist noch offen oder konnte gerade nicht bestätigt werden. Es wird keine neue Zahlung angelegt.</p>{checkoutStatus?.checkoutUrl && <a className="tip-pay checkout-hosted-link" href={checkoutStatus.checkoutUrl}>Bestehenden Stripe-Checkout fortsetzen</a>}<button className="outline-button result-action" disabled={statusBusy} onClick={refreshStatus}>{statusBusy ? 'Wird geprüft …' : 'Zahlungsstatus aktualisieren'}</button></>}
      <button className="text-button result-action" onClick={() => { location.href = `/pay/${encodeURIComponent(recipientPath)}` }}>Zur Zahlungsseite</button>
    </section></main>
  }

  if (!recipient) return <main className="tip-page"><CheckoutBrand /><section className="tip-card"><p role="status">{error || 'Gratilo-Profil wird geladen …'}</p></section></main>
  return <main className="tip-page"><CheckoutBrand /><section className="tip-card">
    {recipient.profileImage && <img className="tip-profile-image" src={recipient.profileImage} alt={`Profilbild von ${name}`} />}
    <span className="step-label">SCHRITT {flow.step} VON 3 · {flow.step === 1 ? 'TRINKGELD' : flow.step === 2 ? 'GEBÜHREN' : 'ZUSAMMENFASSUNG'}</span>
    <h1>{flow.step === 2 ? `Wie viel kommt bei ${name} an?` : flow.step === 3 ? `Dein Trinkgeld für ${name}` : `Trinkgeld für ${name}.`}</h1>
    {flow.step === 1 && <>
      <p className="tip-intro">{isPool ? `Dein Trinkgeld wird nach der angezeigten Verteilungsregel an zahlungsberechtigte Mitglieder von ${name} verteilt.` : `Dein Trinkgeld geht direkt an ${name}${recipient.organisation && recipient.organisation !== name ? ` bei ${recipient.organisation}` : ''}.`} Ohne Gratilo- oder Stripe-Konto für dich als Gast.</p>
      {isPool && recipient.memberNames?.length ? <p className="pool-public-members"><strong>Team:</strong> {recipient.memberNames.join(', ')}</p> : null}
      {eligibility !== true && <p role="status">{eligibility === null ? 'Zahlungsberechtigung wird geprüft …' : 'Dieser Empfänger kann derzeit keine Trinkgelder empfangen oder der Stripe-Status konnte nicht geprüft werden.'}</p>}
      {error && <div className="message error" role="alert">{error}</div>}
      <div className="tip-amounts">{[100, 200, 500, 1000].map(value => <button type="button" key={value} aria-pressed={flow.amount === value} className={flow.amount === value ? 'selected' : ''} onClick={() => { update({ amount: value }); setAmountInput((value / 100).toFixed(2).replace('.', ',')) }}>{money(value)}</button>)}</div>
      <label className="other-amount" htmlFor="checkout-amount">Anderer Betrag (mindestens 1,00 €)<div className="euro-input"><input id="checkout-amount" inputMode="decimal" min="1" max="10000" step="0.01" aria-invalid={validInputAmount === null} value={amountInput} onChange={event => { setAmountInput(event.target.value); const parsed = parseMoney(event.target.value); if (parsed !== null) update({ amount: parsed }) }} onBlur={() => { if (validInputAmount !== null) setAmountInput((validInputAmount / 100).toFixed(2).replace('.', ',')) }} /><span>€</span></div></label>
      {flow.message && <div className="tip-note checkout-message-preview"><strong>Deine Nachricht:</strong> {flow.message}</div>}
      <label htmlFor="checkout-message">Nachricht <span>(optional)</span><textarea id="checkout-message" maxLength={500} value={flow.message} onChange={event => update({ message: event.target.value })} placeholder="Sag noch ein paar nette Worte …" /></label>
      <p className="tip-note">Stripe zeigt die verfügbaren Zahlungsmethoden im sicheren Checkout an.</p>
      <button className="tip-pay" disabled={busy || eligibility !== true || validInputAmount === null || !quote} onClick={() => update({ step: 2 })}>Weiter <span aria-hidden="true">→</span></button>
    </>}
    {flow.step === 2 && !quote && <p role="status">Gebührenberechnung wird aktualisiert …</p>}
    {flow.step === 2 && quote && <>
      <p className="tip-intro">Dein Trinkgeld beträgt {money(flow.amount)}. Wenn du möchtest, kannst du die bei Gratilo berücksichtigte Gebühr freiwillig ausgleichen.</p>
      <label className="fee-toggle"><input type="checkbox" checked={flow.feeCovered} onChange={event => update({ feeCovered: event.target.checked })} /><span>Gebührenabzug ausgleichen</span></label>
      <p className="hint">Die freiwillige Erhöhung deckt die konfigurierte Gratilo-Berechnung möglichst genau ab. Stripe- oder spätere Auszahlungskosten können abweichen und sind nicht garantiert abgedeckt.</p>
      <div className="tip-breakdown" aria-live="polite">
        <SummaryLine label="Ursprünglich gewähltes Trinkgeld" value={money(quote.tipAmountMinor)} />
        <SummaryLine label="Freiwillige Erhöhung" value={money(quote.serviceFeeMinor)} />
        <SummaryLine label="Gesamtes Trinkgeld" value={money(quote.totalAmountMinor)} />
        <SummaryLine label="Berücksichtigte Gratilo-Gebühr" value={money(quote.serviceFeeMinor)} />
        <SummaryLine label="Für den Empfänger vorgesehen" value={money(quote.recipientAmountMinor)} />
        <SummaryLine label="Du bezahlst insgesamt" value={money(quote.totalAmountMinor)} total />
      </div>
      <p className="tip-note">Keine späteren Auszahlungsgebühren oder sonstigen Stripe-Gebühren eingerechnet. Der Empfängerbetrag ist der für den Connect-Transfer vorgesehene Betrag.</p>
      {error && <div className="message error" role="alert">{error}</div>}
      <button className="tip-pay" disabled={!quote} onClick={() => update({ step: 3 })}>Weiter zur Zusammenfassung <span aria-hidden="true">→</span></button>
      <button className="text-button result-action" onClick={() => update({ step: 1 })}>← Betrag ändern</button>
    </>}
    {flow.step === 3 && quote && <>
      <p className="tip-intro">Prüfe deine Angaben, bevor du sicher zu Stripe weitergehst.</p>
      <div className="tip-breakdown">
        <SummaryLine label="Empfänger" value={name} />
        <SummaryLine label="Ursprüngliches Trinkgeld" value={money(quote.tipAmountMinor)} />
        <SummaryLine label="Freibetrag" value="Kein separater Freibetrag · Mindestbetrag 1,00 €" />
        {flow.message.length > 0 && <SummaryLine label="Deine Nachricht" value={flow.message} />}
        <SummaryLine label="Gebührenausgleich" value={flow.feeCovered ? 'Freiwillig ausgewählt' : 'Nicht ausgewählt'} />
        <SummaryLine label="Freiwillige Erhöhung" value={money(quote.serviceFeeMinor)} />
        <SummaryLine label="Gesamtes Trinkgeld" value={money(quote.totalAmountMinor)} />
        <SummaryLine label="Berücksichtigte Gratilo-Gebühr" value={money(quote.serviceFeeMinor)} />
        <SummaryLine label="Für den Empfänger vorgesehen" value={money(quote.recipientAmountMinor)} />
        <SummaryLine label="Du bezahlst insgesamt" value={money(quote.totalAmountMinor)} total />
      </div>
      <p className="tip-note">Sichere Zahlungsabwicklung durch Stripe. Als Gast brauchst du kein Gratilo-Konto. Stripe verarbeitet deine Zahlungsdaten; Gratilo erhält keine Kartendaten.</p>
      {error && <div className="message error" role="alert">{error}</div>}
      <button className="tip-pay" disabled={busy || eligibility !== true || !quote} onClick={startCheckout}>{busy ? 'Stripe Checkout wird geöffnet …' : 'Jetzt sicher bezahlen'} <span aria-hidden="true">→</span></button>
      <button className="text-button result-action" disabled={busy} onClick={() => update({ step: 2 })}>← Betrag und Gebührenübernahme ändern</button>
    </>}
    <p className="tip-note">Stripe-Testmodus · Keine echten Zahlungen oder Auszahlungen.</p>
  </section></main>
}

function CheckoutBrand() { return <div className="tip-brand"><span className="brand-mark">g</span>gratilo<span className="brand-dot">.</span></div> }
function SummaryLine({ label, value, total = false }: { label: string; value: string; total?: boolean }) {
  return <div className={total ? 'tip-total' : ''}><span>{label}</span><strong>{value}</strong></div>
}
