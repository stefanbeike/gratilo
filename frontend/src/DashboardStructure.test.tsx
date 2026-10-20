import { cleanup, render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe',()=>({paymentsEnabled:false}))
vi.mock('./features',()=>({useTipPoolsEnabled:()=>true}))
import { Dashboard } from './Dashboard'
const session={userId:'member',email:'member@example.test',accessToken:'token'}
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.history.replaceState({},'','/account')})
function mount({team=true,reason='STRIPE_INSUFFICIENT_FUNDS',status='PENDING',failure=false}:{team?:boolean;reason?:string;status?:string;failure?:boolean}={}){
 const calls=vi.fn(async(url:string)=>{if(failure&&url==='/payment-service/pool-transfers')throw Error('offline');return {ok:true,json:async()=>{
  if(url==='/payment-service/transactions')return [{id:'personal',amountMinor:2500,recipientAmountMinor:2396,type:'TIP',status:'BOOKED',createdAt:new Date().toISOString()}]
  if(url==='/payment-service/pool-transfers')return [{id:'allocation',transactionId:'payment',poolName:'Team Sonnenseite',amountMinor:1198,reversedAmountMinor:0,status,settlementReason:reason,createdAt:new Date().toISOString(),historicalSharePercent:50,poolDistributionAmountMinor:2396,selectedTipAmountMinor:2500,personalGrossAmountMinor:1250,personalFeeAmountMinor:52,guestPaymentStatus:'BOOKED'}]
  if(url==='/user-service/pools')return null
  if(url==='/user-service/pools/memberships')return team?[{id:'team',name:'Team Sonnenseite',ownerId:'owner',members:[{id:'membership',userId:'member',active:true,status:'ACTIVE',sharePercent:50,effectiveSharePercent:50}]}]:[]
  return {}
 }}});vi.stubGlobal('fetch',calls)
 render(<Dashboard session={session} profile={{email:session.email,firstName:'Alex'}} onProfile={vi.fn()} onLogout={vi.fn()}/>);return calls
}
it('separates personal and team histories while keeping one central Stripe card',async()=>{
 const calls=mount();await screen.findByRole('button',{name:'Transaktion personal ansehen'});expect(screen.queryByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'})).not.toBeInTheDocument()
 const stripe=screen.getByRole('region',{name:'Zentraler Stripe-Status'});fireEvent.click(screen.getByRole('button',{name:'Mein Team'}));
 expect(screen.getByRole('region',{name:'Zentraler Stripe-Status'})).toBe(stripe)
 expect(await screen.findByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Transaktion personal ansehen'})).not.toBeInTheDocument()
 expect(screen.queryByText('Meine Pools')).not.toBeInTheDocument();expect(screen.queryByText('Team erstellen')).not.toBeInTheDocument();expect(screen.queryByText('Team-Einstellungen und Mitglieder verwalten')).not.toBeInTheDocument()
 expect(calls.mock.calls.filter(([url])=>url.includes('connect-account'))).toHaveLength(0)
})
it('shows immutable gross/net amounts and separates a successful payment from delayed transfer',async()=>{
 mount();await screen.findByRole('button',{name:'Transaktion personal ansehen'});fireEvent.click(screen.getByRole('button',{name:'Mein Team'}));fireEvent.click(await screen.findByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'}));
 const dialog=screen.getByRole('dialog');expect(within(dialog).getByText('Zahlung erfolgreich')).toBeInTheDocument();expect(within(dialog).getByText('Transfer verzögert')).toBeInTheDocument();expect(within(dialog).getByText(/12,50/)).toBeInTheDocument();expect(within(dialog).getByText(/0,52/)).toBeInTheDocument();expect(within(dialog).getAllByText(/11,98/).length).toBeGreaterThan(0);expect(within(dialog).getByText('50 %')).toBeInTheDocument()
 fireEvent.keyDown(dialog,{key:'Escape'});expect(screen.queryByRole('dialog')).not.toBeInTheDocument();expect(screen.getByText('Transfer verzögert')).toBeInTheDocument()
})
it('preserves the team deep link and shows a truthful empty state with creation only when allowed',async()=>{
 window.history.replaceState({},'','/account#team');mount({team:false});expect(await screen.findByText('Du bist noch keinem Team zugeordnet.')).toBeInTheDocument();expect(screen.getByRole('button',{name:'Team erstellen'})).toBeDisabled();expect(screen.getByRole('button',{name:'Mein Team'})).toHaveAttribute('aria-current','page')
})
it('preserves the profile editing deep link',async()=>{window.history.replaceState({},'','/account#profile');mount();expect(screen.getByLabelText('Vorname')).toBeInTheDocument()})
it('does not silently show a zero history when an API request fails',async()=>{mount({failure:true});expect(await screen.findByRole('alert')).toHaveTextContent('nicht vollständig geladen');expect(screen.getByRole('button',{name:'Erneut laden'})).toBeInTheDocument()})

it('shows manual reconciliation as action required rather than a successful transfer',async()=>{mount({reason:'RECONCILIATION_REQUIRED'});await screen.findByRole('button',{name:'Transaktion personal ansehen'});fireEvent.click(screen.getByRole('button',{name:'Mein Team'}));expect(await screen.findByText('Handlungsbedarf')).toBeInTheDocument();expect(screen.queryByText('Gebucht')).not.toBeInTheDocument()})
it('marks a completed personal team transfer as booked',async()=>{mount({status:'TRANSFERRED',reason:''});await screen.findByRole('button',{name:'Transaktion personal ansehen'});fireEvent.click(screen.getByRole('button',{name:'Mein Team'}));expect(await screen.findByText('Gebucht')).toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'Transaktion pool-transfer-allocation ansehen'}));expect(within(screen.getByRole('dialog')).getByText('Transfer erfolgreich')).toBeInTheDocument()})
