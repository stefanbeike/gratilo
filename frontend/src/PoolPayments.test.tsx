import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe',()=>({paymentsEnabled:true}))
vi.mock('./features',()=>({useTipPoolsEnabled:()=>true}))
import { Dashboard } from './Dashboard'
const session={userId:'owner',accessToken:'token',email:'owner@example.test'}
afterEach(()=>{cleanup();vi.unstubAllGlobals();window.history.replaceState({},'','/account')})
it('keeps the personal QR active for owners while an unready team stays blocked',async()=>{
  vi.stubGlobal('confirm',vi.fn(()=>true))
  const requestUrls:string[]=[]
  const pool={id:'pool',ownerId:'owner',name:'Team',paymentUrl:'http://localhost:3000/pay/gratilo-002',members:[{id:'m1',userId:'owner',name:'Alex',email:session.email,status:'ACTIVE',active:true,sharePercent:50},{id:'m2',userId:'other',name:'Sam',email:'sam@example.test',status:'ACTIVE',active:true,sharePercent:50}]}
  vi.stubGlobal('fetch',vi.fn(async(url:string,options?:RequestInit)=>{requestUrls.push(url);return {ok:true,json:async()=>{
    if(url.endsWith('/test-access'))return {enabled:true,restricted:false}
    if(url.includes('/connect-account/pools/'))return [{userId:'owner',status:'READY',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString()},{userId:'other',status:'NOT_STARTED',transfersEnabled:false,payoutsEnabled:false,checkedAt:new Date().toISOString()}]
    if(url.includes('/connect-account'))return {stripeAccountId:'acct_fixture',chargesEnabled:true,payoutsEnabled:true,transfersEnabled:true,stripeStatus:'READY',onboardingStatus:'COMPLETE',detailsSubmitted:true,statusCheckedAt:new Date().toISOString()}
    if(url==='/user-service/pools/all')return [pool]
    if(url.endsWith('/memberships')||url.endsWith('/transactions')||url.endsWith('/pool-transfers'))return []
    return {}
  }}}))
  render(<Dashboard session={session} profile={{email:session.email,paymentEnabled:true,paymentUrl:'/pay/original'}} onProfile={vi.fn()} onLogout={vi.fn()} />)
  await waitFor(()=>expect(screen.getByRole('button',{name:'Zahlungslink kopieren'})).toBeEnabled())
  expect(screen.queryByText(/Dein persönlicher Zahlungslink ist pausiert/)).not.toBeInTheDocument()
  expect(screen.getByRole('button',{name:'Zahlungslink kopieren'})).toBeEnabled()
  fireEvent.click(screen.getByRole('button',{name:'Meine Pools'}))
  expect(await screen.findByLabelText('Team-Trinkgeldlink')).toHaveValue('http://localhost:3000/pay/gratilo-002')
  await waitFor(()=>expect(requestUrls.some(url=>url.includes('/connect-account/pools/pool/members'))).toBe(true))
  expect(requestUrls.some(url=>url.includes('/connect-account/pools/owner/members'))).toBe(false)
  fireEvent.click(await screen.findByText('Pool-Einstellungen und Mitglieder verwalten'))
  expect(await screen.findByText('1 von 2 aktiven Mitgliedern mit Anteil sind bestätigt transferbereit.')).toBeInTheDocument()
  expect(screen.getByText('Onboarding ausstehend')).toBeInTheDocument()
  expect(screen.getByRole('button',{name:'Team-Link kopieren'})).toBeEnabled()
  fireEvent.click(screen.getByRole('button',{name:'Pool löschen'}))
  fireEvent.click(screen.getByRole('button',{name:'Meine Trinkgelder'}))
  await waitFor(()=>expect(screen.queryByText(/Dein persönlicher Zahlungslink ist pausiert/)).not.toBeInTheDocument())
  expect(screen.getByRole('button',{name:'Zahlungslink kopieren'})).toBeEnabled()
  expect(screen.getByLabelText('Dein persönlicher Link')).toHaveValue('/pay/original')
})
