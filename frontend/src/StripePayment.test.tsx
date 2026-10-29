import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: true }))
import { DemoPayment } from './Dashboard'

const user = '11111111-1111-1111-1111-111111111111'
const poolId = '22222222-2222-2222-2222-222222222222'
const response = (data: unknown, ok = true) => ({ ok, json: async () => data })
type Options = {pool?: boolean; version?: number; eligible?: boolean; result?: unknown; post?: unknown; quote?: (url: string) => Promise<ReturnType<typeof response>>}
function mount({pool = false, version = pool ? 18 : 17, eligible = true, result, post, quote}: Options = {}) {
  if (!result) history.replaceState({}, '', `/pay/${pool ? 'team/' : ''}${user}`)
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/pool-limits')) return response({minAmountMinor:200,maxAmountMinor:50000})
    if (url.includes('/public/')) return response(pool ? {ownerId:user,poolId,name:'Team Sonnenseite',paymentTarget:'POOL'} : {userId:user,firstName:'Emily',lastName:'Erdbeer',organisation:'Café Sonnenseite',paymentEnabled:true})
    if (url.includes('/eligibility')) return response({canReceiveTips:eligible})
    if (url.includes('/confirm')) return response(result)
    if (url.includes('/quote')) {
      if (quote) return quote(url)
      const query = new URLSearchParams(url.split('?')[1]), amount = Number(query.get('amountMinor')), covered = query.get('feeCovered') === 'true'
      const fee = 45 + Math.floor(amount * .05 + .5)
      const extra = covered ? ([17,18].includes(version) ? fee : 42) : 0
      return response({tipAmountMinor:amount,serviceFeeMinor:extra,totalAmountMinor:amount+extra,recipientAmountMinor:amount-fee+extra,platformFeeMinor:fee,platformFeeNetMinor:59,platformFeeTaxMinor:11,platformFeeVatPercent:19,pricingVersion:version,recipientAmountEstimated:false})
    }
    if (url.includes('/checkout')) return response(post || {transactionId:'tx-test'}, !post)
    throw new Error(`Unexpected request ${url} ${options?.method}`)
  })
  vi.stubGlobal('fetch',fetch)
  render(<DemoPayment />)
  return fetch
}
async function choose(amount = '5,00') {
  const input = await screen.findByLabelText('Anderer Betrag')
  fireEvent.change(input,{target:{value:amount}})
  await waitFor(()=>expect(screen.getByRole('button',{name:/^Weiter/})).toBeEnabled())
}
async function review() { await choose(); fireEvent.click(screen.getByRole('button',{name:/^Weiter/})) }
const pay = () => screen.getByRole('button',{name:/Weiter zur Zahlung/})
afterEach(()=>{cleanup();vi.unstubAllGlobals();sessionStorage.clear();history.replaceState({},'','/')})

it('starts without a selection and shows the saved identity, logo and test banner',async()=>{
  const fetch = mount()
  expect(await screen.findByRole('heading',{name:'Ein Dankeschön für Emily Erdbeer'})).toBeInTheDocument()
  expect(screen.getByText('Emily Erdbeer · Café Sonnenseite')).toBeInTheDocument()
  expect(screen.getByAltText(/Gratilo/)).toHaveAttribute('src','/gratilo-logo.svg')
  expect(screen.getByText(/Testmodus/)).toBeInTheDocument()
  expect(screen.getByLabelText('Anderer Betrag')).toHaveValue('')
  expect(screen.getByRole('button',{name:/^Weiter/})).toBeDisabled()
  expect(screen.queryAllByRole('button',{pressed:true})).toHaveLength(0)
  expect(fetch.mock.calls.some(([url])=>url.includes('/quote'))).toBe(false)
})

for (const invalid of ['', '0', '-1', '1,99', '2,001', '10000,01', '1.000,00', 'abc']) it(`blocks invalid amount ${invalid}`,async()=>{
  const fetch=mount();await choose()
  fireEvent.change(screen.getByLabelText('Anderer Betrag'),{target:{value:invalid}})
  expect(screen.getByRole('button',{name:/^Weiter/})).toBeDisabled()
  expect(screen.getByRole('alert')).toHaveTextContent('Bitte gib einen Betrag ab 2 € ein.')
  expect(fetch.mock.calls.some(([url])=>url.includes('/checkout'))).toBe(false)
})

it('uses one amount state for presets and comma input and accepts the minimum',async()=>{
  mount();await screen.findByLabelText('Anderer Betrag')
  fireEvent.click(screen.getByRole('button',{name:/^5,00/}))
  expect(screen.getByLabelText('Anderer Betrag')).toHaveValue('5')
  await choose('2,00')
  expect(screen.getByRole('button',{name:/^2,00/})).toHaveAttribute('aria-pressed','true')
  expect(screen.getByRole('button',{name:/^5,00/})).toHaveAttribute('aria-pressed','false')
})

for(const pool of [false,true]) for(const covered of [false,true]) it(`submits canonical ${pool?'team':'individual'} amounts, coverage=${covered}, from the review step`,async()=>{
  const fetch=mount({pool});await review()
  expect(screen.getByText('Schritt 2 von 3 · Prüfen')).toBeInTheDocument()
  const toggle=screen.getByRole('checkbox',{name:/Servicegebühr freiwillig ausgleichen/})
  expect(toggle).not.toBeChecked()
  expect(toggle).toHaveAccessibleName(/0,70/)
  expect(pay()).toHaveTextContent('5,00')
  if(covered) fireEvent.click(toggle)
  expect(pay()).toHaveTextContent(covered?'5,70':'5,00')
  fireEvent.click(pay())
  await waitFor(()=>expect(fetch.mock.calls.filter(([url])=>url.endsWith('/checkout'))).toHaveLength(1))
  const call=fetch.mock.calls.find(([url])=>url.endsWith('/checkout'))!
  expect(call[0]).toBe(`/payment-service/tips/${pool?`pools/${poolId}`:user}/checkout`)
  expect(JSON.parse(String(call[1]?.body))).toMatchObject({tipAmountMinor:500,feeCovered:covered,expectedTotalAmountMinor:covered?570:500,expectedRecipientAmountMinor:covered?500:430})
  expect((call[1]?.headers as Record<string,string>).Authorization).toBeUndefined()
  expect(screen.queryByText(/Freibetrag|Stripe-Kosten enthalten|Stripe-Kosten \(Schätzung/)).not.toBeInTheDocument()
  expect(screen.queryByText(/Mitarbeiter|Empfänger-Team/)).not.toBeInTheDocument()
})

it('updates totals immediately for toggles and recalculates after editing, preserving the message',async()=>{
  mount();await choose()
  expect(screen.queryByRole('textbox',{name:'Deine Nachricht (optional)'})).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button',{name:'Nachricht hinzufügen (optional)'}))
  fireEvent.change(screen.getByRole('textbox',{name:'Deine Nachricht (optional)'}),{target:{value:'Danke für den Service!'}})
  fireEvent.click(screen.getByRole('button',{name:/^Weiter/}))
  const toggle=screen.getByRole('checkbox');fireEvent.click(toggle);expect(pay()).toHaveTextContent('5,70')
  fireEvent.click(toggle);expect(pay()).toHaveTextContent('5,00');fireEvent.click(toggle)
  fireEvent.click(screen.getByRole('button',{name:'Bearbeiten'}))
  expect(screen.getByRole('textbox',{name:'Deine Nachricht (optional)'})).toHaveValue('Danke für den Service!')
  await choose('10,00');fireEvent.click(screen.getByRole('button',{name:/^Weiter/}))
  expect(screen.getByRole('checkbox')).toBeChecked();expect(pay()).toHaveTextContent('10,95')
})

it('ignores late quotes for a previous amount',async()=>{
  let resolveOld: (value:ReturnType<typeof response>)=>void = ()=>{}
  const fetch=mount({quote:async url=>{
    const q=new URLSearchParams(url.split('?')[1]),a=Number(q.get('amountMinor'))
    if(a===500)return new Promise(resolve=>{resolveOld=resolve})
    return response({tipAmountMinor:a,totalAmountMinor:a,recipientAmountMinor:a-95,serviceFeeMinor:0})
  }})
  await screen.findByLabelText('Anderer Betrag')
  fireEvent.change(screen.getByLabelText('Anderer Betrag'),{target:{value:'5'}})
  await waitFor(()=>expect(fetch.mock.calls.some(([url])=>url.includes('amountMinor=500'))).toBe(true))
  await choose('10');resolveOld(response({tipAmountMinor:500,totalAmountMinor:500,recipientAmountMinor:430,serviceFeeMinor:0}))
  fireEvent.click(screen.getByRole('button',{name:/^Weiter/}));expect(pay()).toHaveTextContent('10,00')
})

it('retains the existing extra-tip semantics until the full compensation model is approved',async()=>{
  mount({version:15});await review()
  const toggle=screen.getByRole('checkbox',{name:/Zusätzliches Trinkgeld für Emily Erdbeer geben/})
  expect(toggle).toHaveAccessibleName(/0,42/);fireEvent.click(toggle)
  expect(pay()).toHaveTextContent('5,42')
  expect(screen.getByText(/er gleicht die Servicegebühr nicht vollständig aus/)).toBeInTheDocument()
})

it('blocks an unready team without exposing KYC internals',async()=>{
  const fetch=mount({pool:true,eligible:false});await screen.findByText('Dieses Team kann aktuell noch kein Trinkgeld empfangen. Bitte versuche es später erneut.')
  fireEvent.change(screen.getByLabelText('Anderer Betrag'),{target:{value:'5'}})
  expect(screen.getByRole('button',{name:/^Weiter/})).toBeDisabled()
  expect(fetch.mock.calls.some(([url])=>url.includes('/checkout'))).toBe(false)
})

for(const status of ['PENDING','PAID','FAILED']) it(`renders server status ${status} and never pays merely from a return URL`,async()=>{
  history.replaceState({},'','/pay/success?session_id=cs_test_fixture');sessionStorage.setItem('gratilo:last-recipient',user)
  const fetch=mount({result:{status,confirmed:status==='PAID',recipientUserId:user,amountPaidMinor:570,recipientAmountMinor:500}})
  await screen.findByRole('heading',{name:status==='PAID'?'Danke für dein Trinkgeld!':status==='FAILED'?'Das hat leider nicht geklappt.':'Bitte noch nicht erneut bezahlen.'})
  expect(fetch.mock.calls.filter(([url])=>url.endsWith('/checkout'))).toHaveLength(0)
  if(status==='PAID') {
    expect(screen.getByText('Deine Zahlung war erfolgreich.')).toBeInTheDocument()
    expect(screen.queryByText(/Nachricht wurde/)).not.toBeInTheDocument()
    expect(screen.getByText(/Auszahlung auf das Bankkonto/)).toBeInTheDocument()
  } else if(status==='PENDING') expect(screen.queryByRole('button',{name:'Erneut versuchen'})).not.toBeInTheDocument()
  else {fireEvent.click(screen.getByRole('button',{name:'Erneut versuchen'}));expect(await screen.findByLabelText('Anderer Betrag')).toBeInTheDocument()}
})

it('does not claim success for an unconfirmed PAID response',async()=>{
  history.replaceState({},'','/pay/success?session_id=cs_test_fixture')
  mount({result:{status:'PAID',confirmed:false}})
  expect(await screen.findByRole('heading',{name:'Bitte noch nicht erneut bezahlen.'})).toBeInTheDocument()
})

for (const pool of [false,true]) it(`resolves an already processed ${pool?'team':'individual'} attempt without another charge`,async()=>{
  const fetch=mount({pool,post:{code:'CHECKOUT_ALREADY_PROCESSED',sessionId:'cs_test_prior'},result:undefined})
  await review();fireEvent.click(pay())
  await waitFor(()=>expect(fetch.mock.calls.some(([url])=>url.includes('/confirm?sessionId=cs_test_prior'))).toBe(true))
  expect(fetch.mock.calls.filter(([url])=>url.endsWith('/checkout'))).toHaveLength(1)
})

it('keeps a failed network attempt idempotent on retry',async()=>{
  const fetch=mount();await review();fireEvent.click(pay())
  await screen.findByRole('alert');fireEvent.click(pay())
  await waitFor(()=>expect(fetch.mock.calls.filter(([url])=>url.endsWith('/checkout'))).toHaveLength(2))
  const bodies=fetch.mock.calls.filter(([url])=>url.endsWith('/checkout')).map(([,o])=>JSON.parse(String(o?.body)))
  expect(bodies[0].idempotencyKey).toBe(bodies[1].idempotencyKey)
})

it('locks edits after an uncertain checkout start and retries the same immutable attempt',async()=>{
  mount();await review();fireEvent.click(pay());await screen.findByRole('alert')
  expect(screen.getByRole('checkbox')).toBeDisabled()
  expect(screen.getByRole('button',{name:'Betrag ändern'})).toBeDisabled()
  expect(pay()).toBeEnabled()
})

it('routes a mapped personal link to the team and restores only that team draft',async()=>{
  sessionStorage.setItem(`gratilo:checkout:pool:${user}`,JSON.stringify({amount:1000,message:'Team danke',feeCovered:false,step:2}))
  const fetch=mount({pool:true})
  // The public link is initially a personal route; the server mapping is authoritative.
  cleanup();history.replaceState({},'',`/pay/${user}`);render(<DemoPayment />)
  expect(await screen.findByRole('heading',{name:'Dein Dankeschön für Team Sonnenseite'})).toBeInTheDocument()
  await waitFor(()=>expect(pay()).toHaveTextContent('10,00'))
  fireEvent.click(pay())
  await waitFor(()=>expect(fetch.mock.calls.some(([url])=>url===`/payment-service/tips/pools/${poolId}/checkout`)).toBe(true))
})
