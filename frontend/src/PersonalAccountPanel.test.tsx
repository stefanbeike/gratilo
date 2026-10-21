import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { PersonalAccountPanel } from './PersonalAccountPanel'
afterEach(cleanup)
const account={stripeAccountId:'acct_own',onboardingStatus:'COMPLETE',chargesEnabled:true,transfersEnabled:true,payoutsEnabled:true,detailsSubmitted:true,stripeStatus:'READY',statusCheckedAt:new Date().toISOString()}
function panel(options:Record<string,unknown>={}){return render(<PersonalAccountPanel account={account} enabled balance={null} payouts={[]} onStart={vi.fn()} onRefresh={vi.fn()} {...options}/>)}
it('uses only a supplied real account balance and never an income statistic',()=>{
 panel({balance:{availableMinor:1234,pendingMinor:4750,currency:'EUR',checkedAt:new Date().toISOString()}})
 expect(screen.getByRole('region',{name:'Persönliches Stripe-Guthaben'})).toHaveTextContent('12,34');expect(screen.getByText('47,50 €')).toBeInTheDocument()
 expect(screen.getByText('Bankauszahlungen freigegeben')).toBeInTheDocument()
})
it('shows unknown rather than zero when account balance is unavailable',()=>{panel();expect(screen.getByRole('region',{name:'Persönliches Stripe-Guthaben'})).toHaveTextContent('—');expect(screen.getByText(/Kein verlässlich abgefragtes/)).toBeInTheDocument()})
it('separates account connection from payout readiness',()=>{panel({account:{...account,payoutsEnabled:false}});expect(screen.getByText('Auszahlungsfreigabe nicht bestätigt')).toBeInTheDocument();expect(screen.queryByText('Bankauszahlungen freigegeben')).not.toBeInTheDocument()})
it('keeps management safe in the sandbox and never creates a payout',()=>{const fetch=vi.fn();vi.stubGlobal('fetch',fetch);panel();fireEvent.click(screen.getByRole('button',{name:'Auszahlung verwalten'}));expect(screen.getByText('Noch keine Auszahlungen vorhanden.')).toBeInTheDocument();expect(screen.getByText(/keine Bankauszahlung ausgelöst/)).toBeInTheDocument();expect(fetch).not.toHaveBeenCalled();vi.unstubAllGlobals()})
it('handles a missing Stripe account and a failed balance request truthfully',()=>{panel({account:null,balanceError:'Stripe-Guthaben konnte nicht geladen werden.'});expect(screen.getByText('Verbinde zuerst dein eigenes Stripe-Konto.')).toBeInTheDocument();expect(screen.getByRole('alert')).toHaveTextContent('nicht geladen')})
