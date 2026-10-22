import { cleanup, render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe',()=>({paymentsEnabled:false}))
vi.mock('./features',()=>({useTipPoolsEnabled:()=>true}))
import { Dashboard } from './Dashboard'
const session={userId:'member',email:'member@example.test',accessToken:'token'}
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.history.replaceState({},'','/account')})
function mount({team=true,admin=false,reason='STRIPE_INSUFFICIENT_FUNDS',status='PENDING',failure=false,pricingVersion,actualFees}:{team?:boolean;admin?:boolean;reason?:string;status?:string;failure?:boolean;pricingVersion?:number;actualFees?:{actualRecipientAmountMinor:number;actualFeeMinor:number}}={}){
 const calls=vi.fn(async(url:string)=>{if(failure&&url==='/payment-service/pool-transfers')throw Error('offline');return {ok:true,json:async()=>{
  if(url==='/payment-service/transactions')return [{id:'personal',amountMinor:2500,recipientAmountMinor:pricingVersion?(pricingVersion===4?2226:2271):2396,type:'TIP',status:'BOOKED',createdAt:new Date().toISOString(),metadata:pricingVersion?JSON.stringify({pricingVersion,platformFeeMinor:pricingVersion===4?170:125,estimatedStripeFeeMinor:104,feeCovered:false}):undefined}]
  if(url==='/payment-service/transactions/personal/fees')return actualFees || {}
  if(url==='/payment-service/pool-transfers')return [{id:'allocation',transactionId:'payment',poolId:'team',poolName:'Team Sonnenseite',amountMinor:1198,reversedAmountMinor:0,status,settlementReason:reason,createdAt:new Date().toISOString(),historicalSharePercent:50,poolDistributionAmountMinor:2396,selectedTipAmountMinor:2500,personalGrossAmountMinor:1250,personalFeeAmountMinor:52,guestPaymentStatus:'BOOKED'}]
  if(url==='/user-service/pools')return admin?{id:'owned-team',name:'Eigenes Team',ownerId:'member',profileImage:'https://example.test/saved-team.jpg',members:[{id:'owner-member',userId:'member',active:true,status:'ACTIVE',sharePercent:100,effectiveSharePercent:100}]}:null
  if(url==='/user-service/pools/memberships')return team?[{id:'team',name:'Team Sonnenseite',ownerId:'owner',members:[{id:'membership',userId:'member',active:true,status:'ACTIVE',sharePercent:50,effectiveSharePercent:50}]}]:[]
  return {}
 }}});vi.stubGlobal('fetch',calls)
 render(<Dashboard session={session} profile={{email:session.email,firstName:'Alex'}} onProfile={vi.fn()} onLogout={vi.fn()}/>);return calls
}
it('separates personal and team histories while keeping one central Stripe card',async()=>{
 const calls=mount();await screen.findByRole('button',{name:'Transaktion personal ansehen'});expect(screen.queryByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'})).not.toBeInTheDocument()
 const stripe=screen.getByRole('region',{name:'Zentraler Stripe-Status'});fireEvent.click(screen.getByRole('button',{name:'Team'}));
 expect(screen.getByRole('region',{name:'Zentraler Stripe-Status'})).toBe(stripe)
 expect(await screen.findByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Transaktion personal ansehen'})).not.toBeInTheDocument()
 expect(screen.queryByText('Meine Pools')).not.toBeInTheDocument();expect(screen.queryByText('Team erstellen')).not.toBeInTheDocument();expect(screen.queryByText('Team-Einstellungen und Mitglieder verwalten')).not.toBeInTheDocument()
 expect(calls.mock.calls.filter(([url])=>url.includes('connect-account'))).toHaveLength(0)
})
it('shows immutable gross/net amounts and separates a successful payment from delayed transfer',async()=>{
 mount();await screen.findByRole('button',{name:'Transaktion personal ansehen'});fireEvent.click(screen.getByRole('button',{name:'Team'}));fireEvent.click(await screen.findByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'}));
 const dialog=screen.getByRole('dialog');expect(within(dialog).getByText('Zahlung erfolgreich')).toBeInTheDocument();expect(within(dialog).getByText('Transfer verzögert')).toBeInTheDocument();expect(within(dialog).getByText(/12,50/)).toBeInTheDocument();expect(within(dialog).getByText(/0,52/)).toBeInTheDocument();expect(within(dialog).getAllByText(/11,98/).length).toBeGreaterThan(0);expect(within(dialog).getByText('50 %')).toBeInTheDocument()
 fireEvent.keyDown(dialog,{key:'Escape'});expect(screen.queryByRole('dialog')).not.toBeInTheDocument();expect(screen.getByText('Transfer verzögert')).toBeInTheDocument()
})
it('preserves the team deep link and shows a truthful empty state with creation only when allowed',async()=>{
 window.history.replaceState({},'','/account#team');mount({team:false});expect(await screen.findByText('Du bist noch keinem Team zugeordnet.')).toBeInTheDocument();expect(screen.getByRole('button',{name:'Team erstellen'})).toBeDisabled();expect(screen.getByRole('button',{name:'Team'})).toHaveAttribute('aria-current','page')
})
it('preserves the profile editing deep link',async()=>{window.history.replaceState({},'','/account#profile');mount();expect(screen.getByLabelText('Vorname')).toBeInTheDocument()})
it('keeps the personal account section mounted across views and hides team motif presets',async()=>{
 mount({admin:true,team:false});await screen.findByRole('button',{name:'Transaktion personal ansehen'});
 const account=screen.getByRole('region',{name:'Mein Konto und meine Auszahlungen'});
 fireEvent.click(screen.getByRole('button',{name:'Team'}));
 expect(screen.getByRole('region',{name:'Mein Konto und meine Auszahlungen'})).toBe(account);
 expect(await screen.findByText('Team-Einstellungen und Mitglieder verwalten')).toBeInTheDocument();
 expect(screen.queryByText('Eigenes Motiv oder eine Vorlage:')).not.toBeInTheDocument();
 expect(screen.getByLabelText('Team-Profilbild bearbeiten')).toBeInTheDocument();
 for (const image of screen.getAllByAltText('Team-Profilbild von Eigenes Team')) expect(image).toHaveAttribute('src','https://example.test/saved-team.jpg');
 expect(screen.getByLabelText('Poolname bearbeiten')).toBeInTheDocument();
})
it('does not silently show a zero history when an API request fails',async()=>{mount({failure:true});expect(await screen.findByRole('alert')).toHaveTextContent('nicht vollständig geladen');expect(screen.getByRole('button',{name:'Erneut laden'})).toBeInTheDocument()})

it('shows manual reconciliation as action required rather than a successful transfer',async()=>{mount({reason:'RECONCILIATION_REQUIRED'});await screen.findByRole('button',{name:'Transaktion personal ansehen'});fireEvent.click(screen.getByRole('button',{name:'Team'}));expect(await screen.findByText('Handlungsbedarf')).toBeInTheDocument();expect(screen.queryByText('Gebucht')).not.toBeInTheDocument()})
it('marks a completed personal team transfer as booked',async()=>{mount({status:'TRANSFERRED',reason:''});await screen.findByRole('button',{name:'Transaktion personal ansehen'});fireEvent.click(screen.getByRole('button',{name:'Team'}));expect(await screen.findByText('Gebucht')).toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'}));expect(within(screen.getByRole('dialog')).getByText('Transfer erfolgreich')).toBeInTheDocument()})

it.each([4,6])('shows the stored personal commission for pricing version %s without applying the current rule to history',async(pricingVersion)=>{
 mount({pricingVersion});fireEvent.click(await screen.findByRole('button',{name:'Transaktion personal ansehen'}));
 const dialog=screen.getByRole('dialog');const commission=within(dialog).getByText('Gratilo-Provision');
 expect(commission.nextElementSibling).toHaveTextContent(pricingVersion===4?'1,70':'1,25');
 const net=within(dialog).getByText('Persönlich zugeordneter Nettobetrag');
 expect(net.nextElementSibling).toHaveTextContent(pricingVersion===4?'22,26':'22,71');
})

for(const known of [false,true]) it(`distinguishes Stripe-collector projected net from confirmed fees, known=${known}`,async()=>{
 mount({team:false,pricingVersion:8,actualFees:known?{actualRecipientAmountMinor:2261,actualFeeMinor:114}:undefined});
 fireEvent.click(await screen.findByRole('button',{name:'Transaktion personal ansehen'}));
 const dialog=screen.getByRole('dialog');
 if(known){
  expect(within(dialog).getByText('Tatsächliche Stripe-Zahlungsgebühren').nextElementSibling).toHaveTextContent('1,14');
  expect(within(dialog).getByText('Persönlich zugeordneter Nettobetrag').nextElementSibling).toHaveTextContent('22,61');
 }else{
  expect(within(dialog).getByText('Voraussichtlicher persönlicher Nettobetrag').nextElementSibling).toHaveTextContent('22,71');
  expect(within(dialog).getByText(/Stripe zieht die tatsächlichen Zahlungsgebühren auf deinem Konto ein/)).toBeInTheDocument();
 }
});
