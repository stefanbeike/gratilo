import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe',()=>({paymentsEnabled:true}))
vi.mock('./features',()=>({useTipPoolsEnabled:()=>true}))
import { Dashboard } from './Dashboard'
const session={userId:'owner',accessToken:'token',email:'owner@example.test'}
afterEach(()=>{cleanup();vi.unstubAllGlobals()})
it('pauses the personal QR for owners, shows member readiness and reactivates it after pool deletion',async()=>{
  const pool={id:'pool',ownerId:'owner',name:'Team',members:[{id:'m1',userId:'owner',name:'Alex',email:session.email,status:'ACTIVE',active:true,sharePercent:50},{id:'m2',userId:'other',name:'Sam',email:'sam@example.test',status:'ACTIVE',active:true,sharePercent:50}]}
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>({ok:true,json:async()=>{
    if(url.endsWith('/test-access'))return {enabled:true,restricted:false}
    if(url.includes('/connect-account/pools/'))return [{userId:'owner',status:'READY',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString()},{userId:'other',status:'NOT_STARTED',transfersEnabled:false,payoutsEnabled:false,checkedAt:new Date().toISOString()}]
    if(url.includes('/connect-account'))return {stripeAccountId:'acct_fixture',chargesEnabled:true,payoutsEnabled:true}
    if(url==='/user-service/pools')return pool
    if(url.endsWith('/memberships')||url.endsWith('/transactions')||url.endsWith('/pool-transfers'))return []
    return {}
  }})))
  render(<Dashboard session={session} profile={{email:session.email,paymentEnabled:true,paymentUrl:'/pay/original'}} onProfile={vi.fn()} onLogout={vi.fn()} />)
  expect(await screen.findByText(/Dein persönlicher Zahlungslink ist pausiert/)).toBeInTheDocument()
  expect(screen.getByRole('button',{name:'Zahlungslink kopieren'})).toBeDisabled()
  expect(await screen.findByText('1 von 2 Mitgliedern sind für Stripe bereit.')).toBeInTheDocument()
  expect(screen.getByText('Onboarding ausstehend')).toBeInTheDocument()
  expect(screen.getByRole('button',{name:'Team-Trinkgeldlink kopieren'})).toBeDisabled()
  fireEvent.click(screen.getByRole('button',{name:'Pool löschen'}))
  await waitFor(()=>expect(screen.queryByText(/Dein persönlicher Zahlungslink ist pausiert/)).not.toBeInTheDocument())
  expect(screen.getByRole('button',{name:'Zahlungslink kopieren'})).toBeEnabled()
  expect(screen.getByLabelText('Dein persönlicher Link')).toHaveValue('/pay/original')
})
