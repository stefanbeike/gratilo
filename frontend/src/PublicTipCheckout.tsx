import { useEffect, useMemo, useState, useRef } from 'react'
import type { Session } from './api'
import { paymentsEnabled } from './stripe'
import { TeamAvatar } from './TeamImageEditor'

type Quote = { tipAmountMinor: number; serviceFeeMinor: number; totalAmountMinor: number; currency: string; serviceFeePercent: number; fixedFeeMinor: number; recipientAmountMinor: number; feeEstimated?: boolean; estimatedStripeFeeMinor?: number; feeEstimateBasis?: string; pricingVersion?: number; platformFeeMinor?: number; platformFeeNetMinor?: number; platformFeeTaxMinor?: number; platformFeeVatPercent?: number; platformFeePercent?: number; feesCollector?: string; recipientAmountEstimated?: boolean }
type Recipient = { userId: string; firstName?: string; lastName?: string; organisation?: string; profileImage?: string | null; paymentEnabled?: boolean; personalLinkPaused?: boolean; poolId?: string; poolName?: string | null; memberNames?: string[] }
type SavedFlow = { amount: number; message: string; feeCovered: boolean; step: 1 | 2; attemptKey?: string; pendingSessionId?: string }
type CheckoutStatus = { status: 'PAID' | 'PENDING' | 'FAILED'; confirmed: boolean; transactionId?: string; tipAmountMinor?: number; amountPaidMinor?: number; recipientUserId?: string; recipientAmountMinor?: number; recipientAmountEstimated?: boolean; checkoutUrl?: string; poolId?: string | null; poolName?: string | null }
const MIN_TIP = 200
const MAX_TIP = 1_000_000
const money = (minor: number) => new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(minor / 100)
const fullFeeCompensation = (quote: Quote) => [17, 18].includes(quote.pricingVersion || 0)
const recipientExtraPricing = (quote: Quote) => [15, 16].includes(quote.pricingVersion || 0)
const moneyLabel = (minor: number) => `${minor / 100}`.replace('.', ',')
const parseMoney = (raw: string, minimum = MIN_TIP, maximum = MAX_TIP): number | null => {
  const value = raw.trim().replace(',', '.')
  if (!/^\d+(?:\.\d{0,2})?$/.test(value)) return null
  const [euros, cents = ''] = value.split('.')
  const minor = Number(euros) * 100 + Number(cents.padEnd(2, '0'))
  return Number.isSafeInteger(minor) && minor >= minimum && minor <= maximum ? minor : null
}
const savedFlow = (key: string, legacyKey: string, pool: boolean): SavedFlow => {
  try {
    const previousKind = sessionStorage.getItem('gratilo:last-pool')
    const compatibleLegacy = previousKind === null || previousKind === String(pool)
    const value = JSON.parse(sessionStorage.getItem(key) || (compatibleLegacy ? sessionStorage.getItem(legacyKey) : null) || 'null')
    if (value && Number.isSafeInteger(value.amount) && (value.amount === 0 || value.amount >= MIN_TIP) && value.amount <= MAX_TIP)
      return { amount: value.amount, message: typeof value.message === 'string' ? value.message.slice(0, 500) : '', feeCovered: value.feeCovered === true, step: value.step === 2 || value.step === 3 ? 2 : 1, attemptKey: value.attemptKey, pendingSessionId: value.pendingSessionId }
  } catch { /* Ignore damaged local checkout data. */ }
  return { amount: 0, message: '', feeCovered: false, step: 1 }
}

export function PublicTipCheckout({ session }: { session?: Session | null }) {
  const path = location.pathname
  const isSuccess = path.endsWith('/success')
  const isCancelled = path.endsWith('/cancelled')
  const routeIsPool = path.startsWith('/pay/team/') || ((isSuccess || isCancelled) && sessionStorage.getItem('gratilo:last-pool') === 'true')
  const [isPool, setIsPool] = useState(routeIsPool)
  const recipientPath = isSuccess || isCancelled ? sessionStorage.getItem('gratilo:last-recipient') || '' : path.split('/').pop() || ''
  const legacyFlowStorageKey = `gratilo:checkout:${recipientPath || 'current'}`
  const flowStorageKey = `gratilo:checkout:${isPool ? 'pool' : 'individual'}:${recipientPath || 'current'}`
  const [flow, setFlow] = useState<SavedFlow>(() => savedFlow(flowStorageKey, legacyFlowStorageKey, isPool))
  const [amountInput, setAmountInput] = useState(flow.amount ? (flow.amount / 100).toFixed(2).replace('.', ',') : '')
  const [recipient, setRecipient] = useState<Recipient | null>(null)
  const [recipientId, setRecipientId] = useState('')
  const [eligibility, setEligibility] = useState<boolean | null>(null)
  const [eligibilityCheckFailed, setEligibilityCheckFailed] = useState(false)
  const [eligibilityRevision, setEligibilityRevision] = useState(0)
  const [poolLimits, setPoolLimits] = useState<{minAmountMinor:number;maxAmountMinor:number}|null>(null)
  const [poolLimitsError,setPoolLimitsError] = useState('')
  const [quotes, setQuotes] = useState<{amount: number; base: Quote; covered: Quote} | null>(null)
  const [messageOpen, setMessageOpen] = useState(!!flow.message)
  const [amountTouched, setAmountTouched] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const checkoutInFlight = useRef(false)
  const [checkoutStatus, setCheckoutStatus] = useState<CheckoutStatus | null>(null)
  const [statusBusy, setStatusBusy] = useState(false)
  const [loadRevision, setLoadRevision] = useState(0)
  const [retrying, setRetrying] = useState(false)
  const headers = useMemo<Record<string, string>>(() => session?.accessToken ? { Authorization: `Bearer ${session.accessToken}` } : {} as Record<string, string>, [session?.accessToken])
  const isResult = (isSuccess || isCancelled) && !retrying
  const name = recipient?.poolName || [recipient?.firstName, recipient?.lastName].filter(Boolean).join(' ') || recipient?.organisation || 'deinen Service'
  const minimum = isPool ? poolLimits?.minAmountMinor ?? 200 : MIN_TIP
  const maximum = isPool ? poolLimits?.maxAmountMinor ?? 50000 : MAX_TIP
  const validInputAmount = parseMoney(amountInput,minimum,maximum)
  const quote = quotes?.amount === validInputAmount ? (flow.feeCovered ? quotes.covered : quotes.base) : null
  const baseQuote = quotes?.amount === validInputAmount ? quotes.base : null
  const coveredQuote = quotes?.amount === validInputAmount ? quotes.covered : null
  const optionAmount = coveredQuote ? coveredQuote.totalAmountMinor - coveredQuote.tipAmountMinor : 0
  useEffect(()=>{
    if(!isPool||isResult)return
    let current=true
    setPoolLimits(null);setPoolLimitsError('')
    fetch('/payment-service/tips/pool-limits',{cache:'no-store'}).then(async response=>{
      if(!response.ok)throw Error()
      const value=await response.json()
      if(!Number.isSafeInteger(value.minAmountMinor)||!Number.isSafeInteger(value.maxAmountMinor)
        ||value.minAmountMinor<200||value.maxAmountMinor>50000||value.minAmountMinor>value.maxAmountMinor)throw Error()
      if(current)setPoolLimits(value)
    }).catch(()=>{if(current)setPoolLimitsError('Die Team-Zahlungsgrenzen konnten nicht geladen werden. Bitte lade die Seite erneut.')})
    return()=>{current=false}
  },[isPool,isResult])

  useEffect(() => { sessionStorage.setItem(flowStorageKey, JSON.stringify(flow)) }, [flow, flowStorageKey])
  useEffect(() => {
    if (!recipientPath) return
    const endpoint = routeIsPool ? `/user-service/pools/public/${encodeURIComponent(recipientPath)}`
      : `/user-service/users/public/${(/^[0-9a-f-]{36}$/i.test(recipientPath) ? recipientPath : `payment/${encodeURIComponent(recipientPath)}`)}`
    let current = true
    fetch(endpoint, { cache: 'no-store' }).then(response => response.ok ? response.json() : Promise.reject())
      .then((profile: Recipient & { ownerId?: string; name?: string; paymentTarget?: string; poolId?: string }) => {
        if (!current) return
        const team = routeIsPool || profile.paymentTarget === 'POOL'
        const recipientProfile: Recipient = team ? { userId: profile.poolId || profile.ownerId!, poolName: profile.name, poolId: profile.poolId, profileImage: profile.profileImage } : profile
        if (team !== isPool) {
          const restored = savedFlow(`gratilo:checkout:${team ? 'pool' : 'individual'}:${recipientPath}`, legacyFlowStorageKey, team)
          setFlow(restored); setAmountInput(restored.amount ? moneyLabel(restored.amount) : ''); setMessageOpen(!!restored.message)
        }
        setIsPool(team); setRecipient(recipientProfile); setRecipientId(recipientProfile.userId)
        sessionStorage.setItem('gratilo:last-recipient', recipientPath); sessionStorage.setItem('gratilo:last-pool', String(team))
      })
      .catch(() => { if (current) setError('Das Gratilo-Profil konnte nicht geladen werden.') })
    return () => { current = false }
  }, [recipientPath, routeIsPool])
  useEffect(() => {
    if (!recipientId || isResult) return
    let current = true
    setEligibility(null); setEligibilityCheckFailed(false)
    fetch(`/payment-service/tips/${isPool ? `pools/${recipientId}` : recipientId}/eligibility`, { headers, cache: 'no-store' })
      .then(async response => {
        const value = await response.json().catch(() => ({}))
        if (response.status === 503) throw new Error(value?.message || 'Der Zahlungsstatus wird derzeit geprüft. Bitte versuche es erneut.')
        if (!response.ok) throw new Error(response.status === 403 ? 'Der Zugriff auf diese Zahlungsseite ist nicht erlaubt.' : 'Der Zahlungsstatus konnte nicht geprüft werden.')
        return value
      })
      .then(value => { if (current) setEligibility(value?.canReceiveTips === true) })
      .catch(failure => { if (current) { setEligibility(false); setEligibilityCheckFailed(true) } })
    return () => { current = false }
  }, [recipientId, headers, isResult, isPool, eligibilityRevision])
  useEffect(() => {
    if (!recipientId || isResult || !validInputAmount || (isPool && !poolLimits)) { setQuotes(null); return }
    let current = true
    setQuotes(null)
    const loadQuote = async (covered: boolean): Promise<Quote> => {
      const response = await fetch(`/payment-service/tips/${isPool ? `pools/${recipientId}` : recipientId}/quote?amountMinor=${validInputAmount}&feeCovered=${covered}`, { headers, cache: 'no-store' })
      const value = await response.json()
      if (!response.ok) throw new Error(['TIP_TOO_SMALL_AFTER_FEES','POOL_TIP_OUT_OF_RANGE'].includes(value.code) ? value.message : 'Der Betrag konnte gerade nicht berechnet werden. Bitte versuche es erneut.')
      if (value.tipAmountMinor !== validInputAmount || !Number.isSafeInteger(value.totalAmountMinor) || !Number.isSafeInteger(value.recipientAmountMinor)) throw new Error('Die Berechnung konnte nicht bestätigt werden. Bitte versuche es erneut.')
      return value
    }
    Promise.all([loadQuote(false), loadQuote(true)])
      .then(([base, covered]) => { if (current) { setQuotes({amount: validInputAmount, base, covered}); setError('') } })
      .catch(failure => { if (current) { setQuotes(null); setError(failure instanceof Error ? failure.message : 'Der Betrag konnte gerade nicht berechnet werden.') } })
    return () => { current = false }
  }, [recipientId, validInputAmount, headers, isResult, loadRevision, isPool, poolLimits])
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
    if (checkoutInFlight.current || !recipientId || eligibility !== true || !quote || validInputAmount !== flow.amount) return
    checkoutInFlight.current = true
    setBusy(true); setError('')
    const attemptKey = flow.attemptKey || nextKey()
    update({ attemptKey })
    try {
      const response = await fetch(`/payment-service/tips/${isPool ? `pools/${recipientId}` : recipientId}/checkout`, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify({ tipAmountMinor: flow.amount, message: flow.message || null, idempotencyKey: attemptKey, feeCovered: flow.feeCovered, expectedTotalAmountMinor: quote.totalAmountMinor, expectedRecipientAmountMinor: quote.recipientAmountMinor, expectedPoolId: isPool ? recipient?.poolId : undefined }) })
      const result = await response.json()
      if (!response.ok) {
        if (['CHECKOUT_ALREADY_PROCESSED','CHECKOUT_DETAILS_CONFLICT'].includes(result.code) && /^cs_test_[A-Za-z0-9_]+$/.test(result.sessionId || '')) {
          // Resolve the existing attempt through Stripe before permitting another payment.
          update({pendingSessionId:result.sessionId});setRetrying(false);setCheckoutStatus(null);
          window.history.replaceState({},'',`/pay/success?session_id=${encodeURIComponent(result.sessionId)}`);
          setLoadRevision(value=>value+1);return;
        }
        if(result.code==='QUOTE_CHANGED'){setLoadRevision(value=>value+1);update({step:2,attemptKey:undefined})} throw new Error(result.code === 'QUOTE_CHANGED' ? 'Die Beträge haben sich geändert. Bitte prüfe die aktualisierte Übersicht.' : 'Die Zahlung konnte nicht abgeschlossen werden. Bitte versuche es erneut oder wähle eine andere Zahlungsart.') }
      if (!result.checkoutUrl) throw new Error('Die sichere Stripe-Zahlungsseite wurde nicht zurückgegeben. Der Versuch bleibt für eine sichere Wiederholung gespeichert.')
      if (result.transactionId) sessionStorage.setItem('gratilo:last-transaction', result.transactionId)
      location.assign(result.checkoutUrl)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Zahlung konnte nicht gestartet werden.') }
    finally { checkoutInFlight.current = false; setBusy(false) }
  }
  function retryAfterConfirmedFailure() {
    update({ step: 1, attemptKey: undefined, pendingSessionId: undefined })
    setRetrying(true)
  }
  function returnToPayment() {
    if (!checkoutStatus || checkoutStatus.status === 'PENDING') return
    const fresh: SavedFlow = { amount: 0, message: '', feeCovered: false, step: 1 }
    sessionStorage.setItem(flowStorageKey, JSON.stringify(fresh))
    setFlow(fresh); setAmountInput(''); setMessageOpen(false); setAmountTouched(false); setCheckoutStatus(null); setRetrying(true)
    const confirmedPool = checkoutStatus?.poolId
    const confirmedRecipient = checkoutStatus?.recipientUserId
    const target = `/pay/${confirmedPool ? 'team/' : ''}${encodeURIComponent(confirmedPool || confirmedRecipient || recipientPath)}`
    location.assign(target)
  }
  async function refreshStatus() { setLoadRevision(value => value + 1) }

  useEffect(() => {
    if (!isResult || !checkoutStatus?.confirmed || !checkoutStatus.recipientUserId || checkoutStatus.poolId) return
    let current = true
    fetch(`/user-service/users/public/${encodeURIComponent(checkoutStatus.recipientUserId)}`, {cache: 'no-store'})
      .then(response => response.ok ? response.json() : Promise.reject())
      .then(profile => { if (current && profile.userId === checkoutStatus.recipientUserId) setRecipient(profile) })
      .catch(() => { /* Confirmed amounts remain visible if the public profile is unavailable. */ })
    return () => { current = false }
  }, [isResult, checkoutStatus?.confirmed, checkoutStatus?.recipientUserId, checkoutStatus?.poolId])

  if (!paymentsEnabled) return <main className="tip-page checkout-page"><CheckoutBrand /><section className="tip-card"><h1>Zahlungen sind derzeit deaktiviert.</h1><p>Aktuell sind keine Zahlungen möglich.</p><CheckoutLinks /></section></main>
  if (!isResult && recipient && recipient.paymentEnabled === false) return <main className="tip-page checkout-page"><CheckoutBrand /><section className="tip-card"><span className="step-label">ZAHLUNGSSEITE</span><h1>Momentan nicht verfügbar.</h1><p className="tip-intro">{'Diese Zahlungsseite ist derzeit deaktiviert.'}</p><CheckoutLinks /></section></main>

  if (isResult) {
    const resultIsPool = !!checkoutStatus?.poolId
    const resultName = resultIsPool ? checkoutStatus?.poolName || 'das Team' : recipient?.userId === checkoutStatus?.recipientUserId ? name : 'die empfangende Person'
    const status = checkoutStatus?.status || 'PENDING'
    const paid = status === 'PAID' && checkoutStatus?.confirmed === true
    const failed = status === 'FAILED'
    return <main className="tip-page checkout-page"><CheckoutBrand /><section className="tip-card checkout-result" aria-live="polite">
      {recipient?.profileImage && recipient.userId === checkoutStatus?.recipientUserId && !resultIsPool && <img className="tip-profile-image" src={recipient.profileImage} alt={`Profilbild von ${resultName}`} />}
      {paid ? <><span className="step-label">ZAHLUNG BESTÄTIGT</span><h1>Danke für dein Trinkgeld!</h1><p>Deine Zahlung war erfolgreich.</p><p>{resultName}</p><div className="tip-breakdown"><SummaryLine label="Du hast bezahlt" value={money(checkoutStatus.amountPaidMinor ?? 0)} />{checkoutStatus.recipientAmountMinor != null && <SummaryLine label={checkoutStatus.recipientAmountEstimated ? `${resultName} erhält voraussichtlich` : `${resultName} erhält`} value={money(checkoutStatus.recipientAmountMinor)} />}<SummaryLine label="Referenz" value={checkoutStatus.transactionId || sessionStorage.getItem('gratilo:last-transaction') || '—'} /></div><p className="tip-note">{resultIsPool ? 'Die Auszahlung auf die Bankkonten der Teammitglieder erfolgt später.' : `Die Auszahlung auf das Bankkonto von ${resultName} erfolgt später.`}</p></>
        : failed ? <><span className="step-label">ZAHLUNG FEHLGESCHLAGEN</span><h1>Das hat leider nicht geklappt.</h1><p>Die Zahlung wurde nicht abgeschlossen. Du kannst es erneut versuchen. Deine Betragsauswahl und Nachricht bleiben erhalten.</p><div className="tip-breakdown"><SummaryLine label="Dein Betrag" value={money(flow.amount)} /><SummaryLine label="Gebührenübernahme" value={flow.feeCovered ? 'Ausgewählt' : 'Nicht ausgewählt'} /></div><button className="tip-pay" onClick={retryAfterConfirmedFailure}>Erneut versuchen</button></>
        : <><span className="step-label">ZAHLUNG WIRD GEPRÜFT</span><h1>Bitte noch nicht erneut bezahlen.</h1><p>Der Status bei Stripe ist noch offen oder konnte gerade nicht bestätigt werden. Es wird keine neue Zahlung angelegt.</p>{checkoutStatus?.checkoutUrl && <a className="tip-pay checkout-hosted-link" href={checkoutStatus.checkoutUrl}>Bestehenden Stripe-Checkout fortsetzen</a>}<button className="outline-button result-action" disabled={statusBusy} onClick={refreshStatus}>{statusBusy ? 'Wird geprüft …' : 'Zahlungsstatus aktualisieren'}</button></>}
      <button className="text-button result-action" disabled={!checkoutStatus || (!paid && !failed)} onClick={returnToPayment}>Zurück zu {resultName}</button>
    <CheckoutLinks /></section></main>
  }

  if (!recipient) return <main className="tip-page checkout-page"><CheckoutBrand /><section className="tip-card"><p role="status">{error || 'Gratilo-Profil wird geladen …'}</p><CheckoutLinks /></section></main>
  return <main className="tip-page checkout-page"><CheckoutBrand /><section className="tip-card">
    {isPool ? <TeamAvatar image={recipient.profileImage} name={name}/> : recipient.profileImage
      ? <img className="tip-profile-image" src={recipient.profileImage} alt={`Profilbild von ${name}`} />
      : <div className="checkout-avatar" role="img" aria-label={`Profil von ${name}`}>{name.slice(0, 1)}</div>}
    <span className="step-label">Schritt {flow.step === 1 ? 1 : 2} von 3 · {flow.step === 1 ? 'Betrag auswählen' : 'Prüfen'}</span>
    <h1>{flow.step === 1 ? `Ein Dankeschön für ${name}` : `Dein Dankeschön für ${name}`}</h1>
    {recipient.organisation && recipient.organisation !== name && <p className="tip-intro">{name} · {recipient.organisation}</p>}
    {eligibility !== true && <p role={eligibilityCheckFailed ? 'alert' : 'status'}>{eligibility === null ? 'Zahlungsberechtigung wird geprüft …' : eligibilityCheckFailed ? 'Der Zahlungsstatus konnte nicht geprüft werden. Bitte versuche es erneut.' : isPool ? 'Dieses Team kann aktuell noch kein Trinkgeld empfangen. Bitte versuche es später erneut.' : 'Diese Person kann aktuell noch kein Trinkgeld empfangen. Bitte versuche es später erneut.'}</p>}
    {eligibilityCheckFailed && <button type="button" className="text-button" onClick={() => setEligibilityRevision(value => value + 1)}>Zahlungsstatus erneut prüfen</button>}
    {error && <div className="message error" role="alert">{error}</div>}
    {poolLimitsError && <p role="alert">{poolLimitsError}</p>}
    {flow.step === 1 && <>
      {isPool && <p className="tip-intro">Ein Dankeschön für das gesamte Team. Der Empfängerbetrag wird nach der hinterlegten Verteilung aufgeteilt.</p>}
      <h2>Wie viel möchtest du geben?</h2>
      <div className="tip-amounts">{[200, 500, 1000].filter(value => value >= minimum && value <= maximum).map(value => <button type="button" key={value} aria-pressed={validInputAmount === value} className={validInputAmount === value ? 'selected' : ''} onClick={() => { update({ amount: value }); setAmountInput(moneyLabel(value)); setAmountTouched(true) }}>{money(value)}</button>)}</div>
      <label className="other-amount" htmlFor="checkout-amount">Anderer Betrag<div className="euro-input"><input id="checkout-amount" aria-label="Anderer Betrag" inputMode="decimal" min={minimum / 100} max={maximum / 100} placeholder="Betrag in €" aria-describedby="checkout-amount-hint checkout-amount-error" aria-invalid={amountTouched && validInputAmount === null} value={amountInput} onChange={event => { setAmountTouched(true); setAmountInput(event.target.value); const parsed = parseMoney(event.target.value, minimum, maximum); update({ amount: parsed ?? 0 }) }} onBlur={() => { setAmountTouched(true); if (validInputAmount !== null) setAmountInput((validInputAmount / 100).toFixed(2).replace('.', ',')) }} /><span aria-hidden="true">€</span></div></label>
      <p className="tip-note" id="checkout-amount-hint">Mindestens {money(minimum)} · maximal {money(maximum)}</p>
      <p className="checkout-field-error" id="checkout-amount-error" role={amountTouched && validInputAmount === null ? 'alert' : undefined}>{amountTouched && validInputAmount === null ? `Bitte gib einen Betrag ab ${moneyLabel(minimum)} € ein. Maximal ${money(maximum)}, mit höchstens zwei Nachkommastellen.` : ''}</p>
      <button type="button" className="text-button" aria-expanded={messageOpen} aria-controls="checkout-message-panel" onClick={() => setMessageOpen(value => !value)}>{messageOpen ? 'Nachricht ausblenden' : 'Nachricht hinzufügen (optional)'}</button>
      <div id="checkout-message-panel" hidden={!messageOpen}><label htmlFor="checkout-message">Deine Nachricht (optional)<textarea id="checkout-message" maxLength={500} value={flow.message} onChange={event => update({ message: event.target.value })} /></label><p className="tip-note">{isPool ? 'Die Nachricht wird für diese Teamzahlung gespeichert.' : `Deine Nachricht sieht ${name}.`}</p></div>
      <p className="tip-note">{name} erhält dein Trinkgeld abzüglich der Servicegebühr. Im nächsten Schritt siehst du die genaue Aufteilung.</p>
      <button className="tip-pay" disabled={busy || eligibility !== true || validInputAmount === null || !quote} onClick={() => update({ step: 2 })}>Weiter <span aria-hidden="true">→</span></button>
      <p className="tip-note">Ohne Anmeldung · Sicher bezahlen mit Stripe</p>
    </>}
    {flow.step !== 1 && <>
      {flow.attemptKey && <p className="tip-note">Ein Zahlungsversuch wurde gestartet. Verwende „Weiter zur Zahlung“, um denselben Versuch sicher fortzusetzen.</p>}
      {!quote || !baseQuote || !coveredQuote ? <p role="status">Berechnung wird aktualisiert …</p> : <>
        <div className="tip-breakdown" aria-live="polite">
          <SummaryLine label="Ausgewählter Betrag" value={money(quote.tipAmountMinor)} />
          {quote.platformFeeMinor != null && <SummaryLine label="Servicegebühr" value={`−${money(quote.platformFeeMinor)}`} noWrap />}
          {flow.feeCovered && <SummaryLine label={fullFeeCompensation(quote) ? 'Freiwilliger Gebührenausgleich' : recipientExtraPricing(quote) ? 'Freiwilliges Zusatztrinkgeld' : 'Freiwilliger Zahlungsaufschlag'} value={`+${money(quote.serviceFeeMinor)}`} noWrap />}
          {!fullFeeCompensation(quote) && !recipientExtraPricing(quote) && <SummaryLine label="Weitere Zahlungsabzüge (geschätzt)" value={`−${money(quote.totalAmountMinor - quote.recipientAmountMinor - (quote.platformFeeMinor ?? 0))}`} noWrap />}
          <SummaryLine label={`${name} erhält${quote.recipientAmountEstimated ? ' voraussichtlich' : ''}`} value={money(quote.recipientAmountMinor)} noWrap />
        </div>
        <label className="fee-toggle"><input type="checkbox" disabled={busy || !!flow.attemptKey} checked={flow.feeCovered} onChange={event => update({ feeCovered: event.target.checked })} /><span>{fullFeeCompensation(quote) ? 'Servicegebühr freiwillig ausgleichen' : recipientExtraPricing(quote) ? `Zusätzliches Trinkgeld für ${name} geben` : 'Zahlungsgebühren freiwillig übernehmen'} (+{money(optionAmount)})</span></label>
        <p className="tip-note">{flow.feeCovered ? `${name} erhält ${money(coveredQuote.recipientAmountMinor)}.` : `Mit dieser Auswahl erhält ${name} ${money(coveredQuote.recipientAmountMinor)}. Du würdest insgesamt ${money(coveredQuote.totalAmountMinor)} zahlen.`}</p>
        <div className="tip-breakdown" aria-live="polite"><SummaryLine label="Du zahlst insgesamt" value={money(quote.totalAmountMinor)} total /></div>
        <details className="checkout-fee-details"><summary>Gebührendetails anzeigen</summary>
          {fullFeeCompensation(quote) ? <p>Die Servicegebühr beträgt 0,45 € plus 5 % des ausgewählten Betrags, einschließlich 19 % Umsatzsteuer. Wenn du sie freiwillig ausgleichst, erhält {name} den ausgewählten Betrag vollständig. Auf den freiwilligen Ausgleich fällt keine weitere Servicegebühr an.</p> : <p>Die Servicegebühr und der freiwillige Zusatzbetrag werden nach dem aktuell hinterlegten Gebührenmodell berechnet. {recipientExtraPricing(quote) ? 'Der Zusatzbetrag erhöht den Empfängerbetrag; er gleicht die Servicegebühr nicht vollständig aus.' : 'Die Zahlungsabzüge und der Empfängerbetrag können von den tatsächlichen Kosten der Zahlungsabwicklung abweichen.'}</p>}
        </details>
        {flow.message && <div className="checkout-message-preview"><p>Deine Nachricht: „{flow.message}“</p><button className="text-button" disabled={busy || !!flow.attemptKey} onClick={() => { setMessageOpen(true); update({step: 1}) }}>Bearbeiten</button></div>}
        <button className="tip-pay" disabled={busy || eligibility !== true || validInputAmount !== flow.amount} onClick={startCheckout}>{busy ? 'Zahlung wird geöffnet …' : `Weiter zur Zahlung · ${money(quote.totalAmountMinor)}`}</button>
        <p className="tip-note">Schritt 3 von 3 · Bezahlen findet bei Stripe statt. Die Zahlung bestätigst du dort.</p>
      </>}
      <button className="text-button result-action" disabled={busy || !!flow.attemptKey} onClick={() => update({ step: 1 })}>Betrag ändern</button>
    </>}
    <CheckoutLinks />
  </section></main>
}

function CheckoutBrand() { return <><div className="checkout-test-banner" role="status">Testmodus · Nur Stripe-Testzahlungsmittel verwenden. Keine echten Zahlungen.</div><div className="tip-brand"><img src="/gratilo-logo.svg" alt="Gratilo – Good people deserve more" width="220" height="98" /></div></> }
function CheckoutLinks() {
  const links = [['Impressum', import.meta.env.VITE_CHECKOUT_IMPRINT_URL], ['Datenschutz', import.meta.env.VITE_CHECKOUT_PRIVACY_URL], ['Gastbedingungen', import.meta.env.VITE_CHECKOUT_TERMS_URL], ['Hilfe', import.meta.env.VITE_CHECKOUT_HELP_URL]]
  return <nav className="checkout-links" aria-label="Rechtliches und Hilfe">{links.map(([label, url]) => url && /^https:\/\//.test(url) ? <a key={label} href={url} target="_blank" rel="noopener noreferrer">{label}</a> : <span key={label}>{label} (noch nicht hinterlegt)</span>)}</nav>
}

function SummaryLine({ label, value, total = false, noWrap = false }: { label: string; value: string; total?: boolean; noWrap?: boolean }) {
  return <div className={total ? 'tip-total' : ''}><span>{label}</span><strong className={noWrap ? 'checkout-money' : undefined}>{value}</strong></div>
}
