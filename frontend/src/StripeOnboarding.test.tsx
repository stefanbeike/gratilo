import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: true }))
vi.mock('./StripeOnboarding', () => ({ StripeOnboarding: () => null }))
import { Dashboard } from './Dashboard'
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })

it('automatically detects completed onboarding and only then exposes the QR code', async () => {
  let ready = false
  const account = () => ({ stripeAccountId: 'acct_fixture', chargesEnabled: ready, payoutsEnabled: ready, onboardingStatus: ready ? 'COMPLETE' : 'IN_PROGRESS' })
  const fetch = vi.fn(async (url: string) => ({ ok: true, json: async () => {
    if (url.endsWith('/test-access')) return { restricted: true, enabled: true, canActivate: true }
    if (url.endsWith('/onboarding/start')) return { onboardingUrl: 'https://connect.stripe.com/setup/local-fixture' }
    if (url.includes('/connect-account')) return account()
    if (url.endsWith('/profile')) return { email: 'tester@example.invalid' }
    return []
  } }))
  vi.stubGlobal('fetch', fetch)
  render(<Dashboard session={{ accessToken: 'test-jwt', userId: '11111111-1111-1111-1111-111111111111', email: 'tester@example.invalid' }} profile={{ email: 'tester@example.invalid' }} onProfile={vi.fn()} onLogout={vi.fn()} />)
  const start = screen.getByRole('button', { name: /Stripe einrichten|Stripe-Einrichtung fortsetzen/ })
  await waitFor(() => expect(start).toBeEnabled())
  expect(screen.queryByTitle('QR-Code zur Test-Trinkgeldseite')).not.toBeInTheDocument()
  fireEvent.click(start)
  await screen.findByText('Auszahlungen einrichten')
  ready = true
  fireEvent(window, new Event('focus'))
  await waitFor(() => expect(screen.queryByText('Auszahlungen einrichten')).not.toBeInTheDocument())
  expect(screen.getByTitle('QR-Code zur Test-Trinkgeldseite')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Zahlungslink kopieren' })).toBeEnabled()
})
