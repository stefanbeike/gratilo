import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
vi.mock('./api', () => ({ api: vi.fn() }))
import { api } from './api'
import { StripeOnboarding } from './StripeOnboarding'

const session = { accessToken: 'signed-test-jwt', userId: '11111111-1111-1111-1111-111111111111', email: 'tester@example.invalid' }
const redirect = vi.fn()
beforeEach(() => {
  vi.mocked(api).mockReset()
  redirect.mockReset()
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ type: 'opaque' }))
})
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('requests an authenticated one-use Stripe hosted onboarding link and redirects to Stripe', async () => {
  vi.mocked(api).mockResolvedValue({ onboardingUrl: 'https://connect.stripe.com/setup/test-link' })
  render(<StripeOnboarding session={session} onRedirect={redirect} />)
  expect(screen.getByRole('status')).toHaveTextContent('weitergeleitet')
  await waitFor(() => expect(redirect).toHaveBeenCalledWith('https://connect.stripe.com/setup/test-link'))
  expect(fetch).toHaveBeenCalledWith('https://api.stripe.com/', expect.objectContaining({ mode: 'no-cors', cache: 'no-store' }))
  expect(api).toHaveBeenCalledWith('/payment-service/connect-account/onboarding/start', {}, 'signed-test-jwt')
})

it('reports Stripe network or DNS failures before requesting a single-use link', async () => {
  vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'))
  render(<StripeOnboarding session={session} onRedirect={redirect} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Stripe ist aus diesem Netzwerk nicht erreichbar')
  expect(screen.getByRole('alert')).toHaveTextContent('DNS- oder Werbefilter')
  expect(api).not.toHaveBeenCalled()
  expect(redirect).not.toHaveBeenCalled()
})

it.each([
  null,
  {},
  { onboardingUrl: 'http://connect.stripe.com/setup/insecure' },
  { onboardingUrl: 'https://evil.example/setup/redirect' },
])('refuses missing or non-Stripe onboarding URLs (%j)', async value => {
  vi.mocked(api).mockResolvedValue(value as never)
  render(<StripeOnboarding session={session} onRedirect={redirect} />)
  expect(await screen.findByRole('alert')).toHaveTextContent('Stripe-Onboarding konnte nicht gestartet werden')
  expect(redirect).not.toHaveBeenCalled()
})

it('does not expose raw backend details and retries the link request', async () => {
  vi.mocked(api).mockRejectedValueOnce(new Error('secret credential detail'))
    .mockResolvedValueOnce({ onboardingUrl: 'https://connect.stripe.com/setup/retry' })
  render(<StripeOnboarding session={session} onRedirect={redirect} />)
  const alert = await screen.findByRole('alert')
  expect(alert).not.toHaveTextContent('secret credential detail')
  fireEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }))
  await waitFor(() => expect(redirect).toHaveBeenCalledWith('https://connect.stripe.com/setup/retry'))
  expect(api).toHaveBeenCalledTimes(2)
})

it('ignores a late link response after the authenticated user changes', async () => {
  let resolveOld!: (value: { onboardingUrl: string }) => void
  vi.mocked(api).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }) as never)
    .mockResolvedValueOnce({ onboardingUrl: 'https://connect.stripe.com/setup/current-user' })
  const view = render(<StripeOnboarding session={session} onRedirect={redirect} />)
  view.rerender(<StripeOnboarding session={{ ...session, accessToken: 'new-signed-jwt' }} onRedirect={redirect} />)
  await waitFor(() => expect(redirect).toHaveBeenCalledWith('https://connect.stripe.com/setup/current-user'))
  resolveOld({ onboardingUrl: 'https://connect.stripe.com/setup/old-user' })
  await waitFor(() => expect(redirect).toHaveBeenCalledTimes(1))
  expect(api).toHaveBeenLastCalledWith('/payment-service/connect-account/onboarding/start', {}, 'new-signed-jwt')
})
