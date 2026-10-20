import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { MemberStripeBadge, memberTransferReady, type MemberStripeStatus } from './poolStripeStatus'
afterEach(cleanup)
for (const [status, label] of Object.entries({ NOT_STARTED:'Onboarding ausstehend',INCOMPLETE:'Onboarding unvollständig',UNDER_REVIEW:'Stripe-Prüfung läuft',READY:'Bereit für Trinkgelder',ACTION_REQUIRED:'Handlungsbedarf',UNAVAILABLE:'Nicht verfügbar' })) {
  it(`shows ${status} without sensitive account information`, () => {
    render(<MemberStripeBadge status={{ userId:'user',status,onboardingStatus:'IN_PROGRESS',transfersEnabled:status==='READY',payoutsEnabled:false,checkedAt:new Date().toISOString() }} />)
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.getByText(/Bankauszahlung: Nicht bestätigt/)).toBeInTheDocument()
  })
}
it('does not trust missing or expired status or missing transfer capability', () => {
  expect(memberTransferReady()).toBe(false)
  const ready:MemberStripeStatus={ userId:'user',status:'READY',onboardingStatus:'COMPLETE',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString() }
  expect(memberTransferReady(ready)).toBe(true)
  expect(memberTransferReady({...ready,transfersEnabled:false})).toBe(false)
  expect(memberTransferReady({...ready,checkedAt:'2020-01-01T00:00:00Z'})).toBe(false)
})
