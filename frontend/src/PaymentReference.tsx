import { useState } from 'react'

export function CopyableReference({ value, label = 'Referenz kopieren' }: { value: string; label?: string }) {
  const [feedback, setFeedback] = useState('')
  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setFeedback('Kopiert')
    } catch {
      setFeedback('Bitte markiere und kopiere die Referenz.')
    }
  }
  return <span className="copyable-reference">
    <code>{value}</code>
    <button type="button" className="text-button" onClick={copy}>{label}</button>
    <span role="status">{feedback}</span>
  </span>
}

const methodNames: Record<string, string> = {
  card: 'Kredit- oder Debitkarte', paypal: 'PayPal', klarna: 'Klarna', apple_pay: 'Apple Pay',
  google_pay: 'Google Pay', link: 'Link', sepa_debit: 'SEPA-Lastschrift', ideal: 'iDEAL',
  p24: 'Przelewy24', eps: 'EPS', bancontact: 'Bancontact', revolut_pay: 'Revolut Pay',
  amazon_pay: 'Amazon Pay', blik: 'BLIK', mb_way: 'MB WAY', kakao_pay: 'Kakao Pay',
  naver_pay: 'Naver Pay', payco: 'PAYCO', samsung_pay: 'Samsung Pay', pay_by_bank: 'Pay by Bank',
  pix: 'Pix', satispay: 'Satispay',
}

export function paymentMethodName(method?: string | null, wallet?: string | null) {
  const type = wallet || method
  return type ? methodNames[type] || type.replaceAll('_', ' ') : null
}

export function formatPaymentAmount(minor: number, currency: string) {
  const format = new Intl.NumberFormat(document.documentElement.lang || 'de-DE', { style: 'currency', currency })
  const isoCurrency = currency.toUpperCase()
  // Stripe's charge API represents ISK and UGX with two decimal places.
  const decimals = ['ISK', 'UGX'].includes(isoCurrency) ? 2 : (format.resolvedOptions().maximumFractionDigits ?? 2)
  return format.format(minor / 10 ** decimals)
}

export function formatPaymentDate(value: string) {
  return new Intl.DateTimeFormat(document.documentElement.lang || 'de-DE', {
    dateStyle: 'medium', timeStyle: 'short',
  }).format(new Date(value))
}
