import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: true, stripePublishableKey: 'pk_test_fixture' }))
import { DemoPayment } from './Dashboard'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState({}, '', '/'); sessionStorage.clear() })

it('does not claim success just because the success URL was opened', async () => {
  history.replaceState({}, '', '/pay/success')
  vi.stubGlobal('fetch', vi.fn())
  render(<DemoPayment />)
  expect(await screen.findByRole('status')).toHaveTextContent('Deine Zahlung ist noch nicht bestätigt.')
  expect(screen.queryByText('Dein Trinkgeld wurde erfolgreich übermittelt.')).not.toBeInTheDocument()
})

it('shows success only after Stripe session confirmation', async () => {
  history.replaceState({}, '', '/pay/success?session_id=cs_test_fixture')
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ confirmed: true }) })
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  expect(await screen.findByText('Dein Trinkgeld wurde erfolgreich übermittelt.')).toBeInTheDocument()
  expect(fetch).toHaveBeenCalledWith('/payment-service/tips/checkout/confirm?sessionId=cs_test_fixture')
})

it('retains the idempotency key when retrying the same failed checkout', async () => {
  const user = '11111111-1111-1111-1111-111111111111'
  history.replaceState({}, '', `/pay/${user}`)
  const fetch = vi.fn(async (url: string) => {
    if (url.includes('/checkout')) return { ok: false, json: async () => ({ message: 'Verbindung unterbrochen' }) }
    if (url.includes('/quote')) return { ok: true, json: async () => ({ tipAmountMinor: 500, totalAmountMinor: 500, serviceFeeMinor: 0 }) }
    return { ok: true, json: async () => ({ userId: user, firstName: 'Alex', paymentEnabled: true }) }
  })
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  const button = screen.getByRole('button', { name: /Mit Apple Pay/ })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  await screen.findByText('Verbindung unterbrochen')
  fireEvent.click(button)
  await waitFor(() => expect(fetch.mock.calls.filter(([url]) => url.includes('/checkout'))).toHaveLength(2))
  const calls = fetch.mock.calls as unknown as Array<[string, { body: string }]>
  const attempts = calls.filter(([url]) => url.includes('/checkout')).map(([, options]) => JSON.parse(options.body))
  expect(attempts[0].idempotencyKey).toBeTruthy()
  expect(attempts[1].idempotencyKey).toBe(attempts[0].idempotencyKey)
})

it('shows cancellation without starting or confirming a payment', () => {
  history.replaceState({}, '', '/pay/cancelled')
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  expect(screen.getByText('Zahlung abgebrochen.')).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})
