import { useState, type FormEvent } from 'react'
import { api, type Session } from './api'
import { CopyableReference, formatPaymentAmount, formatPaymentDate, paymentMethodName } from './PaymentReference'

const route = '/payment-service/admin/stripe-payment-audits/references'

type Attempt = {
  chargeId: string
  paymentIntentId: string
  chargeCreatedAt: string
  paidAt?: string
  chargedMinor?: number
  currency: string
  method?: string
  wallet?: string
  receiptUrl?: string
  references: { source: string; value: string }[]
}
type Payment = {
  transactionId: string
  publicReference: string
  paymentType: 'SINGLE' | 'POOL'
  status: string
  tipAmountMinor: number
  currency: string
  bookedAt?: string
  stripeAccount?: string
  contextUnavailable?: boolean
  checkoutSessionId?: string
  paymentIntentId?: string
  chargeId?: string
  attempts?: Attempt[]
}

export function AdminPaymentReferences({ session }: { session: Session }) {
  const [reference, setReference] = useState('')
  const [livemode, setLivemode] = useState(false)
  const [results, setResults] = useState<Payment[] | null>(null)
  const [selected, setSelected] = useState<Payment | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  async function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setBusy(true)
    setResults(null)
    setSelected(null)
    setError('')
    try {
      setResults(await api<Payment[]>(route + '/search', { reference: reference.trim(), livemode }, session.accessToken))
    } catch (failure) {
      setError((failure as { status?: number }).status === 403
        ? 'Diese Suche ist für die Admin-Rolle freigegeben.'
        : 'Die Suche konnte nicht ausgeführt werden.')
    } finally {
      setBusy(false)
    }
  }

  return <main className="shell payment-reference-admin">
    <section className="account-card">
      <h1>Zahlungsreferenzen</h1>
      <form onSubmit={search}>
        <label htmlFor="payment-search">Gratilo-, Stripe- oder Zahlungsanbieter-Referenz</label>
        <input id="payment-search" value={reference} onChange={event => setReference(event.target.value)} required maxLength={255} />
        <label htmlFor="payment-mode">Stripe-Modus</label>
        <select id="payment-mode" value={livemode ? 'live' : 'test'} disabled={busy} onChange={event => {
          setLivemode(event.target.value === 'live')
          setResults(null)
          setSelected(null)
          setError('')
        }}><option value="test">Test</option><option value="live">Live</option></select>
        <button className="primary" disabled={busy || !reference.trim()}>{busy ? 'Suche läuft …' : 'Suchen'}</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {results && <p role="status">{results.length === 0 ? 'Keine Zahlung gefunden.' : results.length === 1 ? 'Eine Zahlung gefunden.'
        : `${results.length} Zahlungen mit dieser Referenz. Prüfe die Details der Treffer.`}</p>}
      {!!results?.length && <ul>{results.map(payment => <li key={payment.transactionId}>
        <button type="button" className="text-button" onClick={() => setSelected(payment)}>
          {payment.publicReference} · {payment.paymentType === 'POOL' ? 'Poolzahlung' : 'Einzelzahlung'} · {formatPaymentAmount(payment.tipAmountMinor, payment.currency)} · {payment.status}
        </button>
      </li>)}</ul>}
      <a href="/account">Zum Konto</a>
    </section>
    {selected && <PaymentDetails key={selected.transactionId} payment={selected} />}
  </main>
}

function Detail({ label, value }: { label: string; value?: string | null }) {
  return value ? <><dt>{label}</dt><dd><CopyableReference value={value} /></dd></> : null
}

function PaymentDetails({ payment }: { payment: Payment }) {
  return <section className="account-card">
    <h2>Transaktionsdetails</h2>
    {payment.bookedAt && <p>Gebucht am {formatPaymentDate(payment.bookedAt)}</p>}
    <dl>
      <Detail label="Gratilo-Zahlungsreferenz" value={payment.publicReference} />
      <Detail label="Interne Gratilo-Transaktions-ID" value={payment.transactionId} />
      <Detail label="Stripe Checkout Session" value={payment.checkoutSessionId} />
      <Detail label="Stripe PaymentIntent" value={payment.paymentIntentId} />
      <Detail label="Stripe Charge" value={payment.chargeId} />
      <Detail label="Stripe-Konto" value={payment.contextUnavailable ? 'Kontokontext nicht verifizierbar' : payment.stripeAccount || 'Plattform'} />
    </dl>
    <h3>Zahlungsversuche</h3>
    {!payment.attempts?.length && <p>Für diese Transaktion sind keine Zahlungsversuche erfasst.</p>}
    {payment.attempts?.map(attempt => <section key={attempt.chargeId}>
      <h4>{paymentMethodName(attempt.method, attempt.wallet) || 'Zahlungsmethode unbekannt'}</h4>
      <p>Charge erstellt am {formatPaymentDate(attempt.chargeCreatedAt)}</p>
      {attempt.paidAt && <p>Bestätigt am {formatPaymentDate(attempt.paidAt)}</p>}
      {attempt.chargedMinor != null && <p>Belastet {formatPaymentAmount(attempt.chargedMinor, attempt.currency)}</p>}
      <dl>
        <Detail label="Stripe Charge" value={attempt.chargeId} />
        <Detail label="Stripe PaymentIntent" value={attempt.paymentIntentId} />
        {attempt.references.map(({ source, value }) => <Detail key={source + value} label={source} value={value} />)}
      </dl>
      {attempt.receiptUrl && <a href={attempt.receiptUrl} target="_blank" rel="noreferrer">Stripe-Zahlungsbeleg öffnen</a>}
    </section>)}
  </section>
}
