import { useEffect, useRef, useState } from 'react'
import { api } from './api'

type Eligibility = { simulation: boolean; eligible: boolean; availableMinor: number; pendingMinor: number; simulationAvailableMinor: number; currency: string; bankLast4: string; bankName: string; schedule: string; message: string }
type Payout = { id: string; amountMinor: number; status: string; provider: string; createdAt?: string; failureMessage?: string; metadata?: string }
const money = (minor: number) => new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(minor / 100)
export function payoutAmount(value: string): number | null {
  const match = /^(\d{1,12})(?:[,.](\d{1,2}))?$/.exec(value.trim())
  if (!match) return null
  const minor = BigInt(match[1]) * 100n + BigInt((match[2] || '').padEnd(2, '0'))
  return minor <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(minor) : null
}
function details(payout: Payout): Record<string, unknown> { try { return JSON.parse(payout.metadata || '{}') } catch { return {} } }
function label(payout: Payout) {
  const stage = details(payout).payoutStatus
  if (stage === 'unknown') return 'Ergebnis unklar – Statusabgleich erforderlich'
  if (stage === 'paid') return payout.provider === 'STRIPE_SANDBOX' ? 'Simulation abgeschlossen' : 'Ausgezahlt'
  if (stage === 'in_transit') return 'Auf dem Weg zur Bank'
  if (payout.status === 'BOOKED' || payout.status === 'COMPLETED') return payout.provider === 'STRIPE_SANDBOX' ? 'Simulation abgeschlossen' : 'Gebucht'
  if (payout.status === 'FAILED') return 'Fehlgeschlagen'
  if (payout.status === 'CANCELLED') return 'Storniert'
  return 'Wird verarbeitet'
}
export function PayoutManager({ accessToken, userId, onChanged }: { accessToken: string; userId: string; onChanged: () => void }) {
  const [eligibility, setEligibility] = useState<Eligibility | null>(null)
  const [payouts, setPayouts] = useState<Payout[]>([])
  const [amount, setAmount] = useState('')
  const [all, setAll] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const lock = useRef(false)
  const [error, setError] = useState('')
  const [historyError, setHistoryError] = useState('')
  const [notice, setNotice] = useState('')
  const key = `gratilo-payout-request:${userId}`
  async function load() {
    setLoading(true); setError(''); setHistoryError(''); setEligibility(null)
    await Promise.all([
      api<Eligibility>('/payment-service/payouts/eligibility', undefined, accessToken).then(value => {
        if (value.currency !== 'EUR' || typeof value.simulation !== 'boolean' || typeof value.eligible !== 'boolean' || !Number.isSafeInteger(value.availableMinor) || !Number.isSafeInteger(value.simulationAvailableMinor)) throw Error('Ungültige Stripe-Auszahlungsdaten.')
        setEligibility(value)
      }).catch(cause => setError(cause instanceof Error ? cause.message : 'Auszahlungsvoraussetzungen konnten nicht geladen werden.')),
      api<Payout[]>('/payment-service/payouts', undefined, accessToken).then(value => {
        if (!Array.isArray(value)) throw Error('Die Auszahlungshistorie konnte nicht geladen werden.')
        setPayouts(value)
      }).catch(() => setHistoryError('Die Auszahlungshistorie konnte nicht geladen werden.')),
    ])
    setLoading(false)
  }
  useEffect(() => {
    try {
      const saved = localStorage.getItem(key)
      if (saved) {
        const request = JSON.parse(saved) as { requestId?: string; amountMinor?: number | null }
        if (!request.requestId || (request.amountMinor !== null && !Number.isSafeInteger(request.amountMinor))) throw Error('Ungültige gespeicherte Anfrage.')
        setAll(request.amountMinor === null)
        if (typeof request.amountMinor === 'number') setAmount(`${Math.floor(request.amountMinor / 100)},${String(request.amountMinor % 100).padStart(2, '0')}`)
        setNotice('Eine vorherige Anfrage ist noch ungeklärt. Wiederholungen verwenden dieselbe Auszahlungs-ID.')
      }
    } catch { setNotice('Die gespeicherte Auszahlungsanfrage konnte nicht gelesen werden. Bitte zuerst die Historie prüfen.') }
    void load()
  }, [accessToken, userId])
  const available = eligibility ? (eligibility.simulation ? eligibility.simulationAvailableMinor : eligibility.availableMinor) : 0
  const minor = all ? available : payoutAmount(amount)
  const pending = payouts.some(p => ['PENDING', 'AUTHORIZED'].includes(p.status))
  const valid = eligibility?.eligible && !historyError && !pending && minor !== null && minor >= 100 && minor <= available
  async function submit() {
    if (lock.current || !valid || minor === null) return
    lock.current = true; setBusy(true); setError(''); setNotice('')
    try {
      const old = localStorage.getItem(key)
      const request: { requestId: string; amountMinor: number | null } = old ? JSON.parse(old) : { requestId: crypto.randomUUID(), amountMinor: all ? null : minor }
      if (old && request.amountMinor !== (all ? null : minor)) throw Error('Eine frühere Anfrage ist noch ungeklärt. Bitte mit demselben Betrag wiederholen oder den Auszahlungsstatus abgleichen.')
      localStorage.setItem(key, JSON.stringify(request))
      const result = await api<Payout>('/payment-service/payouts', request, accessToken)
      if (!result.id || !Number.isSafeInteger(result.amountMinor)) throw Error('Unklares Auszahlungsergebnis. Bitte dieselbe Anfrage wiederholen.')
      localStorage.removeItem(key); setConfirm(false)
      await load(); onChanged()
      setNotice(result.status === 'AUTHORIZED' ? 'Das Auszahlungsergebnis ist noch unklar. Bitte den Status abgleichen; keine neue Auszahlung anfordern.' : result.status === 'BOOKED' ? (result.provider === 'STRIPE_SANDBOX' ? 'Simulation abgeschlossen.' : 'Stripe hat die Auszahlung als ausgezahlt bestätigt.') : result.status === 'FAILED' ? result.failureMessage || 'Die Auszahlung wurde abgelehnt.' : result.provider === 'STRIPE_SANDBOX' ? 'Deine simulierte Auszahlung wurde angestoßen. Es erfolgt keine Bankauszahlung.' : 'Deine Auszahlung wurde angestoßen. Sie ist noch nicht auf deinem Bankkonto angekommen.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Unklares Ergebnis. Bitte dieselbe Anfrage erneut prüfen.') }
    finally { lock.current = false; setBusy(false) }
  }
  async function reconcile(payout: Payout) {
    if (lock.current) return
    lock.current = true; setBusy(true); setError('')
    try { await api(`/payment-service/payouts/${payout.id}/reconcile`, {}, accessToken); await load(); onChanged() }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Statusabgleich fehlgeschlagen.') }
    finally { lock.current = false; setBusy(false) }
  }
  async function prepareAccount() {
    if(lock.current)return
    lock.current=true;setBusy(true);setError('')
    try {
      const result=await api<{ready:boolean;message:string;code:string}>('/payment-service/payouts/setup',{},accessToken)
      await load()
      if(!result.ready)setError(result.message)
      else setNotice(result.message)
    }catch(cause){setError(cause instanceof Error?cause.message:'Die Stripe-Einrichtung konnte nicht geprüft werden.')}
    finally{lock.current=false;setBusy(false)}
  }
  return <div className="manual-payout-manager">
    {loading && <p role="status">Auszahlungsvoraussetzungen werden geprüft …</p>}
    {error && <p role="alert" className="message error">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    <button className="text-button" disabled={loading||busy} onClick={prepareAccount}>Auszahlungseinrichtung prüfen</button>
    {eligibility && <>
      <p><strong>{eligibility.simulation ? 'Testmodus · lokale Auszahlungssimulation' : 'Stripe-Testmodus · Standardauszahlung'}</strong></p>
      <p>{eligibility.simulation ? 'Hier wird keine Bankauszahlung ausgelöst. Simulierte Auszahlungen ändern dein echtes Stripe-Guthaben nicht.' : 'Stripe erstellt eine Testauszahlung für deine hinterlegte EUR-Bankverbindung. Es wird kein Geld auf ein Bankkonto überwiesen.'}</p>
      <p>Bankverbindung: {eligibility.bankName || 'Bei Stripe hinterlegte Bank'} · •••• {eligibility.bankLast4 || 'verborgen'}</p>
      <p>{eligibility.simulation ? 'Für die Simulation noch verfügbar' : 'Verfügbar zur Auszahlung'}: <strong>{money(available)}</strong></p>
      {eligibility.message && <p>{eligibility.message}</p>}
      {!eligibility.simulation&&<p>Bitte zahle verfügbares Guthaben regelmäßig aus. Stripe begrenzt die Haltedauer bei manuellen Auszahlungen; es kann nicht unbegrenzt auf dem Stripe-Konto verbleiben.</p>}
      <p>Gratilo erhebt keine zusätzliche Auszahlungsprovision. Individuelle Stripe-Vertragsgebühren sind hier nicht als bestätigt gebührenfrei ausgewiesen.</p>
      {pending && <p role="status">Eine Auszahlung wird bereits verarbeitet. Bitte gleiche ihren Status ab.</p>}
      <form onSubmit={event => { event.preventDefault(); if (valid) setConfirm(true) }}>
        <label htmlFor="payout-amount">Auszahlungsbetrag in EUR</label>
        <input id="payout-amount" inputMode="decimal" value={amount} disabled={busy || all || pending} onChange={event => setAmount(event.target.value)} placeholder="Zum Beispiel 25,00" aria-describedby="payout-amount-hint" />
        <label><input type="checkbox" checked={all} disabled={busy || pending} onChange={event => setAll(event.target.checked)} />Gesamtes verfügbares Guthaben</label>
        <p id="payout-amount-hint">Mindestens 1,00 €; höchstens {money(Math.max(0, available))}. Nur tatsächlich verfügbares EUR-Guthaben.</p>
        {amount && !all && (minor === null || minor < 100 || minor > available) && <p role="alert">Bitte gib einen gültigen Betrag innerhalb des verfügbaren Guthabens ein.</p>}
        <button type="submit" className="solid-button" disabled={!valid || busy || loading}>Auszahlen</button>
      </form>
    </>}
    <button type="button" className="text-button" disabled={busy || loading} onClick={() => void load()}>Auszahlungsdaten aktualisieren</button>
    <h4>Auszahlungshistorie</h4>
    {historyError ? <p role="alert">{historyError}</p> : !loading && !payouts.length ? <p>Noch keine Auszahlungen vorhanden.</p> : <ul className="manual-payout-history">{payouts.map(p => {
      const info = details(p); const arrival = typeof info.arrivalDate === 'number' ? new Date(info.arrivalDate * 1000).toLocaleDateString('de-DE') : null
      return <li key={p.id}><strong>{money(Math.abs(p.amountMinor))}</strong><span>{p.createdAt ? new Date(p.createdAt).toLocaleString('de-DE') : 'Anforderungsdatum unbekannt'}</span><span>{label(p)}</span>{p.provider === 'STRIPE_SANDBOX' && <small>Simulation</small>}{arrival && <small>Voraussichtliche Ankunft: {arrival}</small>}{p.failureMessage && <p>{p.failureMessage}</p>}{['PENDING', 'AUTHORIZED', 'BOOKED'].includes(p.status) && <button className="text-button" disabled={busy} onClick={() => void reconcile(p)}>Status abgleichen</button>}</li>
    })}</ul>}
    {confirm && <div className="account-dialog-backdrop"><section role="dialog" aria-modal="true" aria-labelledby="payout-confirm-title" className="account-dialog"><h3 id="payout-confirm-title">{eligibility?.simulation ? 'Simulierte Auszahlung bestätigen' : 'Standardauszahlung bestätigen'}</h3><p>{money(minor || 0)} auf deine bei Stripe hinterlegte EUR-Bankverbindung •••• {eligibility?.bankLast4}.</p><p>{eligibility?.simulation ? 'Es wird kein externer Stripe-Payout erstellt.' : 'Keine Sofortauszahlung. Die Bestätigung bedeutet noch keinen Geldeingang auf deinem Bankkonto.'}</p><div className="profile-actions"><button className="outline-button" disabled={busy} onClick={() => setConfirm(false)}>Abbrechen</button><button className="solid-button" disabled={busy || !valid} onClick={() => void submit()}>{busy ? 'Wird verarbeitet …' : 'Auszahlung bestätigen'}</button></div></section></div>}
  </div>
}
