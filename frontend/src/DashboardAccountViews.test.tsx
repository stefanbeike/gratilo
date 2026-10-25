import { cleanup, render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe',()=>({paymentsEnabled:true}))
vi.mock('./features',()=>({useTipPoolsEnabled:()=>true}))
import { Dashboard } from './Dashboard'
afterEach(()=>{cleanup();vi.unstubAllGlobals();history.replaceState({},'','/account')})
function mount({multiple=false,admin=false,balanceError=false,eligibilityFailsAfterFirst=false,memberStatusFailsAfterFirst=false}:{multiple?:boolean;admin?:boolean;balanceError?:boolean;eligibilityFailsAfterFirst?:boolean;memberStatusFailsAfterFirst?:boolean}={}){
 let eligibilityCalls=0,memberStatusCalls=0
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
  else if(url==='/user-service/pools/all')data=multiple?[a,b]:admin?[a]:[a]
  else if(url.endsWith('/eligibility')){eligibilityCalls++;data={canReceiveTips:true};if(eligibilityFailsAfterFirst&&eligibilityCalls>1)ok=false}
  else if(url.endsWith('/members')){memberStatusCalls++;data=[{userId:'member',status:'READY',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString()}];if(memberStatusFailsAfterFirst&&memberStatusCalls>1)ok=false}
  return {ok,status:ok?200:502,json:async()=>data}
});vi.stubGlobal('fetch',calls);Object.defineProperty(calls,'eligibilityCalls',{get:()=>eligibilityCalls})
 render(<Dashboard session={{userId:'member',email:'member@example.test',accessToken:'private-test-token'}} profile={{email:'member@example.test',firstName:'Alex'}} onProfile={vi.fn()} onLogout={vi.fn()}/>);return calls
}
it('keeps the same personal Stripe balance when switching views and teams',async()=>{
 const calls=mount({multiple:true});await screen.findByText('1.234,56 €');
 fireEvent.click(screen.getByRole('button',{name:'Meine Pools'}));
 expect(await screen.findByRole('button',{name:'Transaktion pool-transfer-a ansehen'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Transaktion pool-transfer-b ansehen'})).not.toBeInTheDocument()
 fireEvent.change(screen.getByLabelText('Teamansicht und Historie'),{target:{value:'team-b'}})
 expect(await screen.findByRole('button',{name:'Transaktion pool-transfer-b ansehen'})).toBeInTheDocument();expect(screen.queryByRole('button',{name:'Transaktion pool-transfer-a ansehen'})).not.toBeInTheDocument()
 expect(screen.getByText('1.234,56 €')).toBeInTheDocument();expect(calls.mock.calls.filter(([url])=>url.endsWith('/balance'))).toHaveLength(1)
 expect(screen.queryByText('Pool-Einstellungen und Mitglieder verwalten')).not.toBeInTheDocument()
 const balanceCall=calls.mock.calls.find(([url])=>url.endsWith('/balance'))!;expect(balanceCall[1]?.headers).toEqual({Authorization:'Bearer private-test-token'})
})
it('keeps a confirmed READY pool when the member-status refresh changes or eligibility briefly fails',async()=>{
 const calls=mount({admin:true,eligibilityFailsAfterFirst:true});fireEvent.click(await screen.findByRole('button',{name:'Meine Pools'}))
 expect(await screen.findAllByText('Pool zahlungsbereit')).not.toHaveLength(0)
 window.dispatchEvent(new Event('focus'))
 await waitFor(()=>expect(calls.mock.calls.filter(([url])=>String(url).endsWith('/members')).length).toBeGreaterThan(1))
 expect((calls as typeof calls & {eligibilityCalls:number}).eligibilityCalls).toBeGreaterThanOrEqual(2)
 expect(screen.getAllByText(/Letzte Prüfung: zahlungsbereit/)).not.toHaveLength(0)
 expect(screen.getByTitle('Team-Trinkgeld QR-Code')).toBeInTheDocument()
})
it('keeps the recent confirmed member READY when a later member-status request fails',async()=>{
 const calls=mount({admin:true,memberStatusFailsAfterFirst:true});fireEvent.click(await screen.findByRole('button',{name:'Meine Pools'}));
 expect(await screen.findByText('Stripe-bereit')).toBeInTheDocument();window.dispatchEvent(new Event('focus'));
 await waitFor(()=>expect(calls.mock.calls.filter(([url])=>String(url).endsWith('/members')).length).toBeGreaterThan(1));
 expect(await screen.findByText('Stripe-bereit · letzte Prüfung')).toBeInTheDocument();expect(screen.getByText(/aktuelle Aktualisierung ist vorübergehend fehlgeschlagen/)).toBeInTheDocument();expect(screen.queryByText(/Transferempfang: Nicht freigegeben/)).not.toBeInTheDocument()
})
it('shows a balance API failure without substituting personal or pool income',async()=>{
 mount({balanceError:true});const region=screen.getByRole('region',{name:'Persönliches Stripe-Guthaben'});await waitFor(()=>expect(within(region).getByRole('alert')).toHaveTextContent('nicht geladen'))
 expect(region).toHaveTextContent('—');expect(region).not.toHaveTextContent('9,50 €')
})
it('keeps owner image upload while hiding motif presets',async()=>{
 mount({admin:true});await screen.findByText('1.234,56 €');fireEvent.click(screen.getByRole('button',{name:'Meine Pools'}));
 expect(await screen.findByText('Pool-Einstellungen und Mitglieder verwalten')).toBeInTheDocument();expect(screen.getByLabelText('Team-Profilbild bearbeiten')).toBeInTheDocument();expect(screen.queryByText('Eigenes Motiv oder eine Vorlage:')).not.toBeInTheDocument()
})
