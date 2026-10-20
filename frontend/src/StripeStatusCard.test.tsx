import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { StripeStatusCard, stripeDashboardStatus, type ConnectedAccount } from './StripeStatusCard'
afterEach(cleanup)
const account=(patch:Partial<ConnectedAccount>={}):ConnectedAccount=>({stripeAccountId:'acct_test_fixture',onboardingStatus:'COMPLETE',stripeStatus:'READY',statusCheckedAt:new Date().toISOString(),detailsSubmitted:true,chargesEnabled:true,payoutsEnabled:true,transfersEnabled:true,...patch})
it.each([
  [null,'Status wird geprüft.'],
  [account({stripeAccountId:undefined,onboardingStatus:'NOT_STARTED'}),'Richte deinen Zahlungsempfang ein.'],
  [account({onboardingStatus:'IN_PROGRESS',chargesEnabled:false,transfersEnabled:false,detailsSubmitted:false,stripeStatus:'IN_PROGRESS'}),'Einrichtung läuft'],
  [account({stripeStatus:'INCOMPLETE',chargesEnabled:false}),'Stripe benötigt weitere Angaben.'],
  [account({stripeStatus:'UNDER_REVIEW',chargesEnabled:false,transfersEnabled:false}),'Einrichtung läuft'],
  [account({payoutsEnabled:false}),'Zahlungen möglich'],
  [account({chargesEnabled:false}),'Zahlungen möglich'],
  [account(),'Vollständig aktiv'],
  [account({stripeStatus:'ACTION_REQUIRED'}),'Stripe benötigt weitere Angaben.'],
  [account({stripeStatus:'UNAVAILABLE'}),'Status wird geprüft.'],
  [account({statusCheckedAt:'2020-01-01T00:00:00Z'}),'Status wird geprüft.'],
])('uses the authoritative separate capabilities and fails closed (%j)',(value,title)=>{expect(stripeDashboardStatus(value).title).toBe(title)})
it('renders a compact activated status and expands again after restrictions',()=>{
 const view=render(<StripeStatusCard account={account()} enabled onStart={vi.fn()} onRefresh={vi.fn()}/>);
 expect(screen.getByRole('region',{name:'Zentraler Stripe-Status'})).toHaveClass('stripe-compact')
 const onStart=vi.fn();view.rerender(<StripeStatusCard account={account({stripeStatus:'ACTION_REQUIRED'})} enabled onStart={onStart} onRefresh={vi.fn()}/>);
 expect(screen.getByRole('region',{name:'Zentraler Stripe-Status'})).not.toHaveClass('stripe-compact');fireEvent.click(screen.getByRole('button',{name:'Angaben vervollständigen'}));expect(onStart).toHaveBeenCalledOnce()
})
it('does not allow setup before the private test enrollment is authorized',()=>{render(<StripeStatusCard account={null} enabled={false} onStart={vi.fn()} onRefresh={vi.fn()}/>);expect(screen.getByRole('button',{name:'Jetzt einrichten'})).toBeDisabled()})
