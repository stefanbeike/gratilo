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
  expect(fetch).toHaveBeenCalledWith('/payment-service/tips/checkout/confirm?sessionId=cs_test_fixture', { headers: {} })
})

it('retains the idempotency key when retrying the same failed checkout', async () => {
  const user = '11111111-1111-1111-1111-111111111111'
  history.replaceState({}, '', `/pay/${user}`)
  const fetch = vi.fn(async (url: string) => {
    if (url.includes('/checkout')) return { ok: false, json: async () => ({ message: 'Verbindung unterbrochen' }) }
    if (url.includes('/eligibility')) return { ok: true, json: async () => ({ canReceiveTips: true }) }
    if (url.includes('/quote')) return { ok: true, json: async () => ({ tipAmountMinor: 500, totalAmountMinor: 500, serviceFeeMinor: 0 }) }
    return { ok: true, json: async () => ({ userId: user, firstName: 'Alex', paymentEnabled: true }) }
  })
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment session={{ accessToken: 'test-jwt-fixture', userId: user, email: 'test@example.invalid' }} />)
  const button = screen.getByRole('button', { name: /Mit Apple Pay/ })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  await screen.findByText('Verbindung unterbrochen')
  fireEvent.click(button)
  await waitFor(() => expect(fetch.mock.calls.filter(([url]) => url.includes('/checkout'))).toHaveLength(2))
  const firstCheckout = (fetch.mock.calls as unknown as Array<[string, { headers: Record<string, string> }]>).find(([url]) => url.includes('/checkout'))
  expect(firstCheckout?.[1].headers.Authorization).toBe('Bearer test-jwt-fixture')
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

it.each([false, null])('keeps payment disabled when Stripe eligibility is %s', async allowed => {
  const user = '11111111-1111-1111-1111-111111111111'
  history.replaceState({}, '', `/pay/${user}`)
  const fetch = vi.fn(async (url: string) => {
    if (url.includes('/eligibility')) {
      if (allowed === null) throw new Error('offline')
      return { ok: true, json: async () => ({ canReceiveTips: allowed }) }
    }
    if (url.includes('/quote')) return { ok: true, json: async () => ({ tipAmountMinor: 500, totalAmountMinor: 500, serviceFeeMinor: 0 }) }
    return { ok: true, json: async () => ({ userId: user, paymentEnabled: true, stripeOnboardingCompleted: true }) }
  })
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  await screen.findByText(/Dieser Empfänger kann derzeit keine Trinkgelder empfangen oder/)
  const button = screen.getByRole('button', { name: /Mit Apple Pay/ })
  expect(button).toBeDisabled()
  fireEvent.click(button)
  expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(false)
})

it('allows an unregistered guest to start checkout after positive eligibility and disables after revocation', async () => {
  const user = '11111111-1111-1111-1111-111111111111'
  history.replaceState({}, '', `/pay/${user}`)
  let allowed = true
  const fetch = vi.fn(async (url: string) => {
    if (url.includes('/eligibility')) return { ok: true, json: async () => ({ canReceiveTips: allowed }) }
    if (url.includes('/quote')) return { ok: true, json: async () => ({ tipAmountMinor: 500, totalAmountMinor: 500, serviceFeeMinor: 0 }) }
    if (url.includes('/checkout')) return { ok: true, json: async () => ({}) }
    return { ok: true, json: async () => ({ userId: user, paymentEnabled: true }) }
  })
  vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  const button = screen.getByRole('button', { name: /Mit Apple Pay/ })
  expect(button).toBeDisabled()
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(true))
  const calls = fetch.mock.calls as unknown as Array<[string, { headers: Record<string, string> }]>
  expect(calls.find(([url]) => url.includes('/checkout'))?.[1].headers.Authorization).toBeUndefined()
  allowed = false
  fireEvent(window, new Event('focus'))
  await waitFor(() => expect(button).toBeDisabled())
})
