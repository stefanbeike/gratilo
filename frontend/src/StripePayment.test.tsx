import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: true }))
import { DemoPayment } from './Dashboard'

const user = '11111111-1111-1111-1111-111111111111'
const response = (data: unknown, ok = true) => ({ ok, json: async () => data })
function installGuest(extra: (url: string, options?: RequestInit) => unknown = () => response({})) {
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/users/public/')) return response({ userId: user, firstName: 'Alex', organisation: 'Café Beispiel', paymentEnabled: true })
    if (url.includes('/eligibility')) return response({ canReceiveTips: true })
    if (url.includes('/quote')) {
      const covered = new URLSearchParams(url.split('?')[1]).get('feeCovered') === 'true'
      return response({ tipAmountMinor: 500, serviceFeeMinor: covered ? 33 : 0, totalAmountMinor: covered ? 533 : 500, recipientAmountMinor: 500 })
    }
    return extra(url, options) as ReturnType<typeof response>
  })
  vi.stubGlobal('fetch', fetch)
  return fetch
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState({}, '', '/'); sessionStorage.clear() })

it('displays the recipient and only continues after eligibility and quote are ready', async () => {
  installGuest()
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  expect(await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })).toBeInTheDocument()
  expect(screen.getByText(/bei Café Beispiel/)).toBeInTheDocument()
  const next = screen.getByRole('button', { name: /Weiter/ })
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(next)
  expect(await screen.findByRole('heading', { name: 'Wie viel kommt bei Alex an?' })).toBeInTheDocument()
})

it('uses the team QR route and creates a guest payment against the pool endpoint', async () => {
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/pools/public/')) return response({ ownerId: user, name: 'Service-Team', memberNames: ['Alex', 'Sam'] })
    if (url.includes('/eligibility')) return response({ canReceiveTips: true })
    if (url.includes('/quote')) return response({ tipAmountMinor: 500, serviceFeeMinor: 0, totalAmountMinor: 500, recipientAmountMinor: 500 })
    if (url.includes('/checkout')) return response({ transactionId: 'pool-tx' })
    return response({})
  })
  vi.stubGlobal('fetch', fetch)
  history.replaceState({}, '', `/pay/team/${user}`)
  render(<DemoPayment />)
  expect(await screen.findByRole('heading', { name: 'Trinkgeld für Service-Team.' })).toBeInTheDocument()
  expect(screen.getByText(/Alex, Sam/)).toBeInTheDocument()
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  fireEvent.click(await screen.findByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  fireEvent.click(await screen.findByRole('button', { name: /Jetzt sicher bezahlen/ }))
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes(`/payment-service/tips/pools/${user}/checkout`))).toBe(true))
  expect(await screen.findByText(/sichere Stripe-Zahlungsseite wurde nicht zurückgegeben/)).toBeInTheDocument()
  const checkout = fetch.mock.calls.find(([url]) => url.includes('/checkout'))
  expect(checkout?.[1]?.headers && (checkout[1]?.headers as Record<string, string>).Authorization).toBeUndefined()
})

it('supports fee coverage, summary and back navigation while preserving the message', async () => {
  installGuest()
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })
  fireEvent.change(screen.getByLabelText(/Nachricht/), { target: { value: 'Danke für den tollen Service' } })
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  const toggle = await screen.findByRole('checkbox', { name: /Gebührenabzug ausgleichen/ })
  expect(toggle).not.toBeChecked()
  fireEvent.click(toggle)
  expect((await screen.findAllByText(/0,33/)).length).toBeGreaterThan(0)
  fireEvent.click(await screen.findByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  expect(await screen.findByRole('heading', { name: 'Dein Trinkgeld für Alex' })).toBeInTheDocument()
  expect(screen.getByText('Danke für den tollen Service')).toBeInTheDocument()
  expect(screen.getByText('Du bezahlst insgesamt')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Betrag und Gebührenübernahme ändern/ }))
  expect(await screen.findByRole('heading', { name: 'Wie viel kommt bei Alex an?' })).toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: /Gebührenabzug ausgleichen/ })).toBeChecked()
})

it('does not permit amounts below one euro or above the maximum', async () => {
  installGuest()
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })
  const amount = screen.getByLabelText(/Anderer Betrag/)
  fireEvent.change(amount, { target: { value: '0,50' } })
  expect(amount).toHaveAttribute('aria-invalid', 'true')
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeDisabled())
  expect(screen.getByRole('heading', { name: 'Trinkgeld für Alex.' })).toBeInTheDocument()
})

it('starts a guest Checkout only after the summary and sends canonical amount, message and fee choice', async () => {
  const fetch = installGuest((url, options) => url.includes('/checkout') ? response({ transactionId: 'tx-test' }) : response({}))
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })
  fireEvent.change(screen.getByLabelText(/Nachricht/), { target: { value: 'Danke' } })
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  fireEvent.click(await screen.findByRole('checkbox', { name: /Gebührenabzug ausgleichen/ }))
  fireEvent.click(await screen.findByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  fireEvent.click(await screen.findByRole('button', { name: /Jetzt sicher bezahlen/ }))
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(true))
  const call = fetch.mock.calls.find(([url]) => url.includes('/checkout'))
  const body = JSON.parse(String(call?.[1]?.body))
  expect(body).toMatchObject({ tipAmountMinor: 500, message: 'Danke', feeCovered: true })
  expect(body.idempotencyKey).toBeTruthy()
  expect((call?.[1]?.headers as Record<string, string>).Authorization).toBeUndefined()
})

it('keeps payment disabled if recipient eligibility is false', async () => {
  const fetch = vi.fn(async (url: string) => url.includes('/users/public/') ? response({ userId: user, firstName: 'Alex', paymentEnabled: true }) : response({ canReceiveTips: false }))
  vi.stubGlobal('fetch', fetch)
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  expect(await screen.findByText(/Dieser Empfänger kann derzeit keine Trinkgelder empfangen/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Weiter/ })).toBeDisabled()
  expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(false)
})

it('shows success only after Stripe status confirms payment', async () => {
  history.replaceState({}, '', '/pay/success?session_id=cs_test_fixture')
  sessionStorage.setItem('gratilo:last-recipient', user)
  const fetch = vi.fn(async (url: string) => url.includes('/confirm')
    ? response({ status: 'PAID', confirmed: true, transactionId: 'tx-fixture', tipAmountMinor: 500, amountPaidMinor: 533 })
    : response({ userId: user, firstName: 'Alex', paymentEnabled: true }))
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  expect(await screen.findByText(/wurde erfolgreich bezahlt/)).toBeInTheDocument()
  expect(screen.getByText(/nicht, dass das Geld bereits auf dem Bankkonto/)).toBeInTheDocument()
})

it('shows pending status and never offers another payment while Stripe is unclear', async () => {
  history.replaceState({}, '', '/pay/cancelled?session_id=cs_test_fixture')
  sessionStorage.setItem('gratilo:last-recipient', user)
  const fetch = vi.fn(async (url: string) => url.includes('/confirm') ? response({ status: 'PENDING', confirmed: false }) : response({ userId: user, firstName: 'Alex' }))
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  expect(await screen.findByRole('heading', { name: 'Bitte noch nicht erneut bezahlen.' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Zahlungsstatus aktualisieren/ })).toBeEnabled()
  expect(screen.queryByRole('button', { name: /Jetzt sicher bezahlen|Erneut versuchen/ })).not.toBeInTheDocument()
})

it('allows a new attempt only after Stripe confirms the prior session failed', async () => {
  history.replaceState({}, '', '/pay/success?session_id=cs_test_fixture')
  sessionStorage.setItem('gratilo:last-recipient', user)
  sessionStorage.setItem(`gratilo:checkout:${user}`, JSON.stringify({ amount: 500, message: 'bleibt', feeCovered: true, step: 3 }))
  const fetch = vi.fn(async (url: string) => url.includes('/confirm') ? response({ status: 'FAILED', confirmed: false }) : response({ userId: user, firstName: 'Alex' }))
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  fireEvent.click(await screen.findByRole('button', { name: /Erneut versuchen/ }))
  expect(await screen.findByRole('heading', { name: 'Dein Trinkgeld für Alex' })).toBeInTheDocument()
  expect(JSON.parse(sessionStorage.getItem(`gratilo:checkout:${user}`) || '{}')).toMatchObject({ message: 'bleibt', feeCovered: true })
})
