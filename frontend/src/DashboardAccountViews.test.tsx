import { cleanup, render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe',()=>({paymentsEnabled:true}))
vi.mock('./features',()=>({useTipPoolsEnabled:()=>true}))
import { Dashboard } from './Dashboard'
afterEach(()=>{cleanup();vi.unstubAllGlobals();history.replaceState({},'','/account')})
function mount({multiple=false,admin=false,balanceError=false}:{multiple?:boolean;admin?:boolean;balanceError?:boolean}={}){
 const member={id:'m',userId:'member',active:true,status:'ACTIVE',sharePercent:50,effectiveSharePercent:50}
 const a={id:'team-a',name:'Team A',ownerId:admin?'member':'owner-a',members:[member]}
 const b={id:'team-b',name:'Team B',ownerId:'owner-b',members:[{...member,id:'mb'}]}
 const calls=vi.fn(async(url:string,options?:RequestInit)=>{
  let data:unknown={};let ok=true
  if(url==='/payment-service/test-access')data={enabled:true}
  else if(url==='/payment-service/connect-account')data={stripeAccountId:'acct_own',stripeStatus:'READY',statusCheckedAt:new Date().toISOString(),onboardingStatus:'COMPLETE',chargesEnabled:true,transfersEnabled:true,payoutsEnabled:true,detailsSubmitted:true}
  else if(url==='/payment-service/connect-account/balance'){data={availableMinor:123456,pendingMinor:4750,currency:'EUR',checkedAt:new Date().toISOString()};ok=!balanceError}
  else if(url==='/payment-service/transactions')data=[{id:'personal',type:'TIP',amountMinor:1000,recipientAmountMinor:950,status:'BOOKED',createdAt:new Date().toISOString()}]
  else if(url==='/payment-service/pool-transfers')data=[{id:'a',poolId:'team-a',poolName:'Team A',amountMinor:4750,reversedAmountMinor:0,status:'TRANSFERRED',historicalSharePercent:50,poolDistributionAmountMinor:9500,selectedTipAmountMinor:10000,createdAt:new Date().toISOString()},...(multiple?[{id:'b',poolId:'team-b',poolName:'Team B',amountMinor:1333,reversedAmountMinor:0,status:'PENDING',createdAt:new Date().toISOString()}]:[])]
  else if(url==='/user-service/pools')data=admin?a:null
  else if(url==='/user-service/pools/memberships')data=multiple?[a,b]:admin?[]:[a]
  else if(url.endsWith('/eligibility'))data={canReceiveTips:true}
  else if(url.endsWith('/members'))data=[{userId:'member',status:'READY',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString()}]
  return {ok,status:ok?200:502,json:async()=>data}
 });vi.stubGlobal('fetch',calls)
 render(<Dashboard session={{userId:'member',email:'member@example.test',accessToken:'private-test-token'}} profile={{email:'member@example.test',firstName:'Alex'}} onProfile={vi.fn()} onLogout={vi.fn()}/>);return calls
}
it('keeps the same personal Stripe balance when switching views and teams',async()=>{
 const calls=mount({multiple:true});await screen.findByText('1.234,56 €');
 fireEvent.click(screen.getByRole('button',{name:'Team'}));
 expect(await screen.findByRole('button',{name:'Transaktion pool-transfer-a ansehen'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Transaktion pool-transfer-b ansehen'})).not.toBeInTheDocument()
 fireEvent.change(screen.getByLabelText('Teamansicht und Historie'),{target:{value:'team-b'}})
 expect(await screen.findByRole('button',{name:'Transaktion pool-transfer-b ansehen'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Transaktion pool-transfer-a ansehen'})).not.toBeInTheDocument()
 expect(screen.getByText('1.234,56 €')).toBeInTheDocument();expect(calls.mock.calls.filter(([url])=>url.endsWith('/balance'))).toHaveLength(1)
 expect(screen.queryByText('Team-Einstellungen und Mitglieder verwalten')).not.toBeInTheDocument()
 const balanceCall=calls.mock.calls.find(([url])=>url.endsWith('/balance'))!;expect(balanceCall[1]?.headers).toEqual({Authorization:'Bearer private-test-token'})
})
it('shows a balance API failure without substituting personal or pool income',async()=>{
 mount({balanceError:true});const region=screen.getByRole('region',{name:'Persönliches Stripe-Guthaben'});await waitFor(()=>expect(within(region).getByRole('alert')).toHaveTextContent('nicht geladen'))
 expect(region).toHaveTextContent('—');expect(region).not.toHaveTextContent('9,50 €')
})
it('keeps owner image upload while hiding motif presets',async()=>{
 mount({admin:true});await screen.findByText('1.234,56 €');fireEvent.click(screen.getByRole('button',{name:'Team'}));
 expect(await screen.findByText('Team-Einstellungen und Mitglieder verwalten')).toBeInTheDocument();expect(screen.getByLabelText('Team-Profilbild bearbeiten')).toBeInTheDocument();expect(screen.queryByText('Eigenes Motiv oder eine Vorlage:')).not.toBeInTheDocument()
})
