import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: true }))
vi.mock('./StripeOnboarding', () => ({ StripeOnboarding: () => null }))
import { Dashboard } from './Dashboard'
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers() })

it('automatically detects completed onboarding and only then exposes the QR code', async () => {
  let ready = false
  const account = () => ({ stripeAccountId: 'acct_fixture', chargesEnabled: ready, payoutsEnabled: ready, transfersEnabled: ready, detailsSubmitted:ready, stripeStatus:ready?'READY':'IN_PROGRESS', statusCheckedAt:new Date().toISOString(), onboardingStatus: ready ? 'COMPLETE' : 'IN_PROGRESS' })
  const fetch = vi.fn(async (url: string) => ({ ok: true, json: async () => {
    if (url.endsWith('/test-access')) return { restricted: true, enabled: true, canActivate: true }
    if (url.endsWith('/onboarding/start')) return { onboardingUrl: 'https://connect.stripe.com/setup/local-fixture' }
    if (url.includes('/connect-account')) return account()
    if (url.endsWith('/profile')) return { email: 'tester@example.invalid' }
    return []
  } }))
  vi.stubGlobal('fetch', fetch)
  render(<Dashboard session={{ accessToken: 'test-jwt', userId: '11111111-1111-1111-1111-111111111111', email: 'tester@example.invalid' }} profile={{ email: 'tester@example.invalid' }} onProfile={vi.fn()} onLogout={vi.fn()} />)
  await waitFor(() => expect(screen.getByRole('button', { name: /Jetzt einrichten|Einrichtung fortsetzen/ })).toBeEnabled())
  expect(screen.queryByTitle('QR-Code zur Test-Trinkgeldseite')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Jetzt einrichten|Einrichtung fortsetzen/ }))
  await screen.findByText('Auszahlungen einrichten')
  ready = true
  fireEvent(window, new Event('focus'))
  await waitFor(() => expect(screen.queryByText('Auszahlungen einrichten')).not.toBeInTheDocument())
  expect(screen.getByTitle('QR-Code zur Test-Trinkgeldseite')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Zahlungslink kopieren' })).toBeEnabled()
})

it('does not announce activation or close onboarding while transfer permission is missing',async()=>{
  const account={stripeAccountId:'acct_fixture',chargesEnabled:true,payoutsEnabled:true,transfersEnabled:false,detailsSubmitted:true,stripeStatus:'UNDER_REVIEW',onboardingStatus:'SUBMITTED',statusCheckedAt:new Date().toISOString()}
  vi.stubGlobal('fetch',vi.fn(async(url:string)=>({ok:true,json:async()=>url.endsWith('/test-access')?{restricted:false,enabled:true}:url.includes('/connect-account')?account:[]})))
  window.history.replaceState({},'','/account?stripe_onboarding=refresh')
  render(<Dashboard session={{accessToken:'token',userId:'owner',email:'owner@example.test'}} profile={{email:'owner@example.test'}} onProfile={vi.fn()} onLogout={vi.fn()}/>);
  const check=await screen.findByRole('button',{name:'Status bei Stripe prüfen'});fireEvent.click(check)
  await screen.findByText('Der Stripe-Status wurde aktualisiert. Prüfe die einzelnen Freigaben in der Statuskarte.')
  expect(screen.getByText('Auszahlungen einrichten')).toBeInTheDocument();expect(screen.queryByText(/Trinkgelder sind aktiv/)).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'Zahlungslink kopieren'})).toBeDisabled();window.history.replaceState({},'','/account')
})
