import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { MemberStripeBadge, STRIPE_STATUS_MAX_AGE_MS, memberTransferReady, memberTransferState, markMemberStripeStatusesUnavailable, mergeMemberStripeStatuses, type MemberStripeStatus } from './poolStripeStatus'
afterEach(cleanup)
for (const [status, label] of Object.entries({ NOT_STARTED:'Onboarding ausstehend',INCOMPLETE:'Onboarding unvollständig',UNDER_REVIEW:'Stripe-Prüfung läuft',READY:'Stripe-bereit',ACTION_REQUIRED:'Handlungsbedarf',UNAVAILABLE:'Stripe-Status derzeit nicht überprüfbar' })) {
  it(`shows ${status} without sensitive account information`, () => {
    render(<MemberStripeBadge status={{ userId:'user',status,onboardingStatus:'IN_PROGRESS',transfersEnabled:status==='READY',payoutsEnabled:false,checkedAt:new Date().toISOString() }} />)
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.getByText(status==='UNAVAILABLE'?/Stripe-Payout-Fähigkeit: Status unbekannt/:/Stripe-Payout-Fähigkeit: Nicht bestätigt/)).toBeInTheDocument()
  })
}
it('does not trust missing or expired status or missing transfer capability', () => {
  expect(memberTransferReady()).toBe(false)
  const ready:MemberStripeStatus={ userId:'user',status:'READY',onboardingStatus:'COMPLETE',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString() }
  expect(memberTransferReady(ready)).toBe(true)
  expect(memberTransferReady({...ready,transfersEnabled:false})).toBe(false)
  expect(memberTransferReady({...ready,checkedAt:'2020-01-01T00:00:00Z'})).toBe(false)
})
it('shows failed lookups as UNKNOWN instead of claiming transfer denial',()=>{
 const unknown:MemberStripeStatus={userId:'member',status:'UNAVAILABLE',onboardingStatus:'COMPLETE',transfersEnabled:null,payoutsEnabled:null,checkedAt:null}
 expect(memberTransferState(unknown)).toBe('UNKNOWN')
 const {unmount}=render(<MemberStripeBadge status={unknown}/>);expect(screen.getByText('Stripe-Status derzeit nicht überprüfbar')).toBeInTheDocument();expect(screen.getByText(/Transferempfang: Status unbekannt/)).toBeInTheDocument();expect(screen.queryByText(/Transferempfang: Nicht freigegeben/)).not.toBeInTheDocument();unmount()
})
it('retains a recent successful READY status as explicitly stale during transient refresh failures only',()=>{
 const now=Date.now(),ready:MemberStripeStatus={userId:'member',status:'READY',onboardingStatus:'COMPLETE',transfersEnabled:true,payoutsEnabled:false,checkedAt:new Date(now-1000).toISOString()}
 const merged=mergeMemberStripeStatuses({member:ready},[{...ready,status:'UNAVAILABLE',transfersEnabled:null,payoutsEnabled:null}],now)
 expect(memberTransferState(merged.member,now)).toBe('READY');expect(merged.member.refreshFailed).toBe(true)
 const expired=markMemberStripeStatusesUnavailable({member:ready},now+STRIPE_STATUS_MAX_AGE_MS+1)
 expect(memberTransferState(expired.member,now+STRIPE_STATUS_MAX_AGE_MS+1)).toBe('UNKNOWN')
 const confirmed=mergeMemberStripeStatuses({member:ready},[{...ready,status:'ACTION_REQUIRED',transfersEnabled:false}],now)
 expect(memberTransferState(confirmed.member,now)).toBe('NOT_READY')
})
it('keeps bank payout readiness separate from transfer reception',async()=>{
 const {memberPayoutReady}=await import('./poolStripeStatus')
 const status={userId:'user',status:'UNDER_REVIEW',onboardingStatus:'SUBMITTED',transfersEnabled:false,payoutsEnabled:true,checkedAt:new Date().toISOString()}
 expect(memberTransferReady(status)).toBe(false);expect(memberPayoutReady(status)).toBe(true)
})
