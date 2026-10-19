import { StrictMode } from 'react'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ stripePublishableKey: 'pk_test_fixture' }))
const sdk = vi.hoisted(() => ({ initialize: vi.fn() }))
vi.mock('@stripe/connect-js', () => ({ loadConnectAndInitialize: sdk.initialize }))
import { StripeOnboarding } from './StripeOnboarding'

const session = { accessToken: 'signed-test-jwt', userId: '11111111-1111-1111-1111-111111111111', email: 'tester@example.invalid' }
let loadFailure: () => void
let loaderStart: () => void
beforeEach(() => {
  sdk.initialize.mockReset().mockImplementation(() => ({ create: () => Object.assign(document.createElement('div'), {
    setOnLoadError: (handler: () => void) => { loadFailure = handler },
    setOnLoaderStart: (handler: () => void) => { loaderStart = handler },
  }) }))
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
async function clientSecretFetcher(index = 0): Promise<() => Promise<string>> {
  await waitFor(() => expect(sdk.initialize.mock.calls.length).toBeGreaterThan(index))
  return sdk.initialize.mock.calls[index][0].fetchClientSecret
}

it('creates a fresh authenticated account session on every SDK request, including expiry refresh', async () => {
  let sequence = 0
  const fetch = vi.fn(async () => ({ ok: true, json: async () => ({ onboardingClientSecret: `session_fixture_${++sequence}` }) }))
  vi.stubGlobal('fetch', fetch)
  render(<StripeOnboarding session={session} />)
  expect(screen.getByRole('status')).toHaveTextContent('Stripe-Onboarding wird geladen')
  const getSecret = await clientSecretFetcher()
  expect(await getSecret()).toBe('session_fixture_1')
  expect(await getSecret()).toBe('session_fixture_2')
  expect(fetch).toHaveBeenCalledTimes(2)
  for (const call of fetch.mock.calls as unknown as [string, RequestInit][]) {
    expect(call[0]).toBe('/payment-service/connect-account/onboarding/start')
    expect(call[1].method).toBe('POST')
    expect(call[1].headers).toEqual({ 'Content-Type': 'application/json', Authorization: 'Bearer signed-test-jwt' })
    expect(JSON.parse(String(call[1].body))).toEqual({})
  }
  act(() => loaderStart())
  await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
})

it('shows SDK load errors and retries with a new component and a fresh session', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ onboardingClientSecret: 'new_session_fixture' }) })))
  render(<StripeOnboarding session={session} />)
  await clientSecretFetcher()
  act(() => loadFailure())
  expect(await screen.findByRole('alert')).toHaveTextContent('Stripe-Testschlüssel')
  fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }))
  const getSecret = await clientSecretFetcher(1)
  expect(await getSecret()).toBe('new_session_fixture')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it.each([null, { onboardingClientSecret: '' }, { onboardingClientSecret: '   ' }])('rejects a missing session secret without caching a previous value (%j)', async value => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => value })))
  render(<StripeOnboarding session={session} />)
  const getSecret = await clientSecretFetcher()
  await expect(getSecret()).rejects.toThrow('Stripe-Onboarding konnte nicht geladen werden')
  expect(await screen.findByRole('alert')).toBeInTheDocument()
})

it('does not expose raw API errors or credential-like values in UI or SDK rejections', async () => {
  const marker = 'private-session-fixture-do-not-display'
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 502, json: async () => ({ message: marker, detail: marker }) })))
  render(<StripeOnboarding session={session} />)
  const getSecret = await clientSecretFetcher()
  await expect(getSecret()).rejects.not.toThrow(marker)
  expect(await screen.findByRole('alert')).not.toHaveTextContent(marker)
})

it('invalidates callbacks on account changes and uses the current JWT for new sessions', async () => {
  const fetch = vi.fn(async () => ({ ok: true, json: async () => ({ onboardingClientSecret: 'session_for_current_user' }) }))
  vi.stubGlobal('fetch', fetch)
  const view = render(<StripeOnboarding session={session} />)
  const oldFetcher = await clientSecretFetcher()
  view.rerender(<StripeOnboarding session={{ ...session, accessToken: 'new-signed-jwt' }} />)
  const currentFetcher = await clientSecretFetcher(1)
  await expect(oldFetcher()).rejects.toThrow()
  expect(fetch).not.toHaveBeenCalled()
  expect(await currentFetcher()).toBe('session_for_current_user')
  expect((fetch.mock.calls as unknown as [string, RequestInit][])[0][1].headers).toMatchObject({ Authorization: 'Bearer new-signed-jwt' })
})

it('removes obsolete elements and invalidates their callbacks during StrictMode cleanup', async () => {
  const fetch = vi.fn(async () => ({ ok: true, json: async () => ({ onboardingClientSecret: 'strict_mode_current_session' }) }))
  vi.stubGlobal('fetch', fetch)
  const view = render(<StrictMode><StripeOnboarding session={session} /></StrictMode>)
  const oldFetcher = await clientSecretFetcher()
  const currentFetcher = await clientSecretFetcher(1)
  expect(view.container.querySelector('.stripe-onboarding-container')?.children).toHaveLength(1)
  await expect(oldFetcher()).rejects.toThrow()
  expect(fetch).not.toHaveBeenCalled()
  expect(await currentFetcher()).toBe('strict_mode_current_session')
  view.unmount()
  await expect(currentFetcher()).rejects.toThrow()
  expect(fetch).toHaveBeenCalledTimes(1)
})
