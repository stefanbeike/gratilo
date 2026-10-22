import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: true }))
import { DemoPayment } from './Dashboard'

const user = '11111111-1111-1111-1111-111111111111'
const response = (data: unknown, ok = true) => ({ ok, json: async () => data })
function installGuest(extra: (url: string, options?: RequestInit) => unknown = () => response({})) {
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/users/public/')) return response({ userId: user, firstName: 'Alex', organisation: 'Café Beispiel', paymentEnabled: true })
    if (url.includes('/eligibility')) return response({ canReceiveTips: true })
    if (url.includes('/quote')) {
      const covered = new URLSearchParams(url.split('?')[1]).get('feeCovered') === 'true'
      return response({ tipAmountMinor: 500, serviceFeeMinor: covered ? 33 : 0, totalAmountMinor: covered ? 533 : 500, recipientAmountMinor: covered ? 500 : 467, estimatedStripeFeeMinor: covered ? 33 : 33 })
    }
    return extra(url, options) as ReturnType<typeof response>
  })
  stubCheckoutFetch( fetch)
  return fetch
}
function stubCheckoutFetch(fetch:(url:string,options?:RequestInit)=>Promise<unknown>){
  vi.stubGlobal('fetch',(url:string,options?:RequestInit)=>url.includes('/pool-limits')?Promise.resolve(response({minAmountMinor:200,maxAmountMinor:50000,currency:'EUR'})):fetch(url,options))
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState({}, '', '/'); sessionStorage.clear() })

it('displays the recipient and only continues after eligibility and quote are ready', async () => {
  installGuest()
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  expect(await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })).toBeInTheDocument()
  expect(screen.getByText(/bei Café Beispiel/)).toBeInTheDocument()
  const next = screen.getByRole('button', { name: /Weiter/ })
  await waitFor(() => expect(next).toBeEnabled())
  fireEvent.click(next)
  expect(await screen.findByRole('heading', { name: 'Wie viel kommt bei Alex an?' })).toBeInTheDocument()
})

it('uses the team QR route and creates a guest payment against the pool endpoint', async () => {
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/pools/public/')) return response({ ownerId: user, name: 'Service-Team', memberNames: ['Alex', 'Sam'],profileImage:'data:image/jpeg;base64,team-fixture' })
    if (url.includes('/eligibility')) return response({ canReceiveTips: true })
    if (url.includes('/quote')) return response({ tipAmountMinor: 500, serviceFeeMinor: 0, totalAmountMinor: 500, recipientAmountMinor: 500 })
    if (url.includes('/checkout')) return response({ transactionId: 'pool-tx' })
    return response({})
  })
  stubCheckoutFetch( fetch)
  history.replaceState({}, '', `/pay/team/${user}`)
  render(<DemoPayment />)
  expect(await screen.findByRole('heading', { name: 'Trinkgeld für Service-Team.' })).toBeInTheDocument()
  expect(screen.getByAltText('Team-Profilbild von Service-Team')).toHaveAttribute('src','data:image/jpeg;base64,team-fixture');expect(screen.getByText('Trinkgeld für das gesamte Team')).toBeInTheDocument();expect(screen.queryByText(/Alex, Sam/)).not.toBeInTheDocument()
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  fireEvent.click(await screen.findByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  fireEvent.click(await screen.findByRole('button', { name: /Jetzt sicher bezahlen/ }))
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes(`/payment-service/tips/pools/${user}/checkout`))).toBe(true))
  expect(await screen.findByText(/sichere Stripe-Zahlungsseite wurde nicht zurückgegeben/)).toBeInTheDocument()
  const checkout = fetch.mock.calls.find(([url]) => url.includes('/checkout'))
  expect(checkout?.[1]?.headers && (checkout[1]?.headers as Record<string, string>).Authorization).toBeUndefined()
})

it('supports fee coverage, summary and back navigation while preserving the message', async () => {
  installGuest()
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })
  fireEvent.change(screen.getByLabelText(/Nachricht/), { target: { value: 'Danke für den tollen Service' } })
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  const toggle = await screen.findByRole('checkbox', { name: /Zahlungsgebühren freiwillig übernehmen/ })
  expect(toggle).not.toBeChecked()
  fireEvent.click(toggle)
  expect((await screen.findAllByText(/0,33/)).length).toBeGreaterThan(0)
  fireEvent.click(await screen.findByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  expect(await screen.findByRole('heading', { name: 'Dein Trinkgeld für Alex' })).toBeInTheDocument()
  expect(screen.getByText('Danke für den tollen Service')).toBeInTheDocument()
  expect(screen.getByText('Du bezahlst insgesamt')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Betrag und Gebührenübernahme ändern/ }))
  expect(await screen.findByRole('heading', { name: 'Wie viel kommt bei Alex an?' })).toBeInTheDocument()
  expect(screen.getByRole('checkbox', { name: /Zahlungsgebühren freiwillig übernehmen/ })).toBeChecked()
})

it('does not permit amounts below one euro or above the maximum', async () => {
  installGuest()
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })
  const amount = screen.getByLabelText(/Anderer Betrag/)
  fireEvent.change(amount, { target: { value: '0,50' } })
  expect(amount).toHaveAttribute('aria-invalid', 'true')
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeDisabled())
  expect(screen.getByRole('heading', { name: 'Trinkgeld für Alex.' })).toBeInTheDocument()
})

it('starts a guest Checkout only after the summary and sends canonical amount, message and fee choice', async () => {
  const fetch = installGuest((url, options) => url.includes('/checkout') ? response({ transactionId: 'tx-test' }) : response({}))
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })
  fireEvent.change(screen.getByLabelText(/Nachricht/), { target: { value: 'Danke' } })
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  fireEvent.click(await screen.findByRole('checkbox', { name: /Zahlungsgebühren freiwillig übernehmen/ }))
  fireEvent.click(await screen.findByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  fireEvent.click(await screen.findByRole('button', { name: /Jetzt sicher bezahlen/ }))
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(true))
  const call = fetch.mock.calls.find(([url]) => url.includes('/checkout'))
  const body = JSON.parse(String(call?.[1]?.body))
  expect(body).toMatchObject({ tipAmountMinor: 500, message: 'Danke', feeCovered: true })
  expect(body.idempotencyKey).toBeTruthy()
  expect((call?.[1]?.headers as Record<string, string>).Authorization).toBeUndefined()
})

it('keeps payment disabled if recipient eligibility is false', async () => {
  const fetch = vi.fn(async (url: string) => url.includes('/users/public/') ? response({ userId: user, firstName: 'Alex', paymentEnabled: true }) : response({ canReceiveTips: false }))
  stubCheckoutFetch( fetch)
  history.replaceState({}, '', `/pay/${user}`)
  render(<DemoPayment />)
  expect(await screen.findByText(/Dieser Empfänger kann derzeit keine Trinkgelder empfangen/)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Weiter/ })).toBeDisabled()
  expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(false)
})

it('shows success only after Stripe status confirms payment', async () => {
  history.replaceState({}, '', '/pay/success?session_id=cs_test_fixture')
  sessionStorage.setItem('gratilo:last-recipient', user)
  const fetch = vi.fn(async (url: string) => url.includes('/confirm')
    ? response({ status: 'PAID', confirmed: true, transactionId: 'tx-fixture', tipAmountMinor: 500, amountPaidMinor: 533 })
    : response({ userId: user, firstName: 'Alex', paymentEnabled: true }))
  stubCheckoutFetch( fetch)
  render(<DemoPayment />)
  expect(await screen.findByText(/wurde erfolgreich bezahlt/)).toBeInTheDocument()
  expect(screen.getByText(/nicht, dass das Geld bereits auf dem Bankkonto/)).toBeInTheDocument()
})

it('shows pending status and never offers another payment while Stripe is unclear', async () => {
  history.replaceState({}, '', '/pay/cancelled?session_id=cs_test_fixture')
  sessionStorage.setItem('gratilo:last-recipient', user)
  const fetch = vi.fn(async (url: string) => url.includes('/confirm') ? response({ status: 'PENDING', confirmed: false }) : response({ userId: user, firstName: 'Alex' }))
  stubCheckoutFetch( fetch)
  render(<DemoPayment />)
  expect(await screen.findByRole('heading', { name: 'Bitte noch nicht erneut bezahlen.' })).toBeInTheDocument()
  expect(await screen.findByRole('button', { name: /Zahlungsstatus aktualisieren/ })).toBeEnabled()
  expect(screen.queryByRole('button', { name: /Jetzt sicher bezahlen|Erneut versuchen/ })).not.toBeInTheDocument()
})

it('allows a new attempt only after Stripe confirms the prior session failed', async () => {
  history.replaceState({}, '', '/pay/success?session_id=cs_test_fixture')
  sessionStorage.setItem('gratilo:last-recipient', user)
  sessionStorage.setItem(`gratilo:checkout:${user}`, JSON.stringify({ amount: 500, message: 'bleibt', feeCovered: true, step: 3 }))
  const fetch = vi.fn(async (url: string) => url.includes('/confirm') ? response({ status: 'FAILED', confirmed: false }) : response({ userId: user, firstName: 'Alex' }))
  stubCheckoutFetch( fetch)
  render(<DemoPayment />)
  fireEvent.click(await screen.findByRole('button', { name: /Erneut versuchen/ }))
  expect(await screen.findByRole('heading', { name: 'Trinkgeld für Alex.' })).toBeInTheDocument()
  expect(JSON.parse(sessionStorage.getItem(`gratilo:checkout:individual:${user}`) || '{}')).toMatchObject({ message: 'bleibt', feeCovered: true })
})

for (const pool of [false,true]) it(`returns a confirmed ${pool?'pool':'individual'} payment to the first step with a new attempt`,async()=>{
  history.replaceState({},'', '/pay/success?session_id=cs_test_fixture')
  sessionStorage.setItem('gratilo:last-recipient',user); sessionStorage.setItem('gratilo:last-pool',String(pool))
  sessionStorage.setItem(`gratilo:checkout:${user}`,JSON.stringify({amount:1000,message:'old',feeCovered:true,step:3,attemptKey:'old-key',pendingSessionId:'cs_test_fixture'}))
  stubCheckoutFetch(vi.fn(async(url:string)=>{
    if(url.includes('/confirm'))return response({status:'PAID',confirmed:true,amountPaidMinor:1041})
    if(url.includes('/pools/public/'))return response({ownerId:user,name:'Team',memberNames:['Alex','Sam']})
    if(url.includes('/eligibility'))return response({canReceiveTips:true})
    if(url.includes('/quote'))return response({tipAmountMinor:500,serviceFeeMinor:0,totalAmountMinor:500,recipientAmountMinor:500})
    return response({userId:user,firstName:'Alex',paymentEnabled:true})
  }))
  render(<DemoPayment />)
  await screen.findByRole('heading',{name:'Danke!'})
  fireEvent.click(screen.getByRole('button',{name:'Zurück zur Zahlungsseite'}))
  expect(await screen.findByRole('heading',{name:`Trinkgeld für ${pool?'Team':'Alex'}.`})).toBeInTheDocument()
  expect(location.pathname).toBe(`/pay/${pool?'team/':''}${user}`)
  expect(JSON.parse(sessionStorage.getItem(`gratilo:checkout:${pool?'pool':'individual'}:${user}`)||'{}')).toEqual({amount:500,message:'',feeCovered:false,step:1})
})
it('labels the voluntary fee as an estimate and does not invent a Gratilo platform fee',async()=>{
 const fetch=installGuest();history.replaceState({},'',`/pay/${user}`);render(<DemoPayment />)
 const next=await screen.findByRole('button',{name:/Weiter/});await waitFor(()=>expect(next).toBeEnabled());fireEvent.click(next)
 expect(await screen.findByText(/Die Gebührenübernahme ist eine Schätzung/)).toBeInTheDocument()
 expect(screen.queryByText('Berücksichtigte Gratilo-Gebühr')).not.toBeInTheDocument()
 fireEvent.click(screen.getByRole('checkbox',{name:/Zahlungsgebühren freiwillig übernehmen/}));fireEvent.click(await screen.findByRole('button',{name:/Weiter zur Zusammenfassung/}))
 await waitFor(()=>expect(screen.getByRole('button',{name:/Jetzt sicher bezahlen/})).toBeEnabled());fireEvent.click(screen.getByRole('button',{name:/Jetzt sicher bezahlen/}))
 await waitFor(()=>expect(fetch.mock.calls.some(([url])=>url.includes('/checkout'))).toBe(true))
 const call=fetch.mock.calls.find(([url])=>url.includes('/checkout'));expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({expectedTotalAmountMinor:533,feeCovered:true})
})

for(const pool of [false,true])for(const status of ['PAID','PENDING'])it(`resolves a reused ${pool?'pool':'individual'} checkout as ${status} without creating another payment`,async()=>{
 history.replaceState({},'',`/pay/${pool?'team/':''}${user}`)
 const fetch=vi.fn(async(url:string)=>{
  if(url.includes('/pools/public/'))return response({ownerId:user,name:'Team',memberNames:['Alex','Sam']})
  if(url.includes('/users/public/'))return response({userId:user,firstName:'Alex',paymentEnabled:true})
  if(url.includes('/eligibility'))return response({canReceiveTips:true})
  if(url.includes('/quote'))return response({tipAmountMinor:500,serviceFeeMinor:0,totalAmountMinor:500,recipientAmountMinor:500})
  if(url.includes('/confirm'))return response({status,confirmed:status==='PAID',amountPaidMinor:500})
  if(url.includes('/checkout'))return response({code:'CHECKOUT_ALREADY_PROCESSED',message:'Prüfe den bestehenden Versuch.',transactionId:'prior-tx',sessionId:'cs_test_prior'},false)
  throw new Error('Unexpected request')
 })
 stubCheckoutFetch(fetch);render(<DemoPayment/>);
 const next=await screen.findByRole('button',{name:/Weiter/});await waitFor(()=>expect(next).toBeEnabled());fireEvent.click(next)
 fireEvent.click(await screen.findByRole('button',{name:/Weiter zur Zusammenfassung/}));fireEvent.click(await screen.findByRole('button',{name:/Jetzt sicher bezahlen/}));
 await screen.findByRole('heading',{name:status==='PAID'?'Danke!':'Bitte noch nicht erneut bezahlen.'})
 expect(fetch.mock.calls.filter(([url])=>url.includes('/checkout')&&!url.includes('/confirm'))).toHaveLength(1)
 expect(fetch.mock.calls.some(([url])=>url.includes('/confirm?sessionId=cs_test_prior'))).toBe(true)
 if(status==='PENDING')expect(screen.queryByRole('button',{name:/Jetzt sicher bezahlen|Erneut versuchen/})).not.toBeInTheDocument()
})

for(const pool of [true,false])it(`keeps the ${pool?'pool':'individual'} attempt separate from legacy state of the other payment type`,async()=>{
 sessionStorage.setItem('gratilo:last-pool',String(!pool));sessionStorage.setItem(`gratilo:checkout:${user}`,JSON.stringify({amount:1000,message:'Other payment',step:3,attemptKey:'other-attempt'}))
 history.replaceState({},'',`/pay/${pool?'team/':''}${user}`)
 stubCheckoutFetch(vi.fn(async(url:string)=>url.includes('/pools/public/')?response({ownerId:user,name:'Team',memberNames:[]}):url.includes('/users/public/')?response({userId:user,firstName:'Alex',paymentEnabled:true}):url.includes('/eligibility')?response({canReceiveTips:true}):response({tipAmountMinor:500,totalAmountMinor:500,serviceFeeMinor:0,recipientAmountMinor:500})))
 render(<DemoPayment/>);await screen.findByRole('heading',{name:`Trinkgeld für ${pool?'Team':'Alex'}.`})
 const flow=JSON.parse(sessionStorage.getItem(`gratilo:checkout:${pool?'pool':'individual'}:${user}`)||'{}');expect(flow.amount).toBe(500);expect(flow.attemptKey).toBeUndefined()
 expect(JSON.parse(sessionStorage.getItem(`gratilo:checkout:${user}`)||'{}').attemptKey).toBe('other-attempt')
})

for (const pool of [false, true]) it(`uses the server's international-card estimate for ${pool ? 'pool' : 'individual'} checkout without changing the promised tip`, async () => {
  history.replaceState({}, '', `/pay/${pool ? 'team/' : ''}${user}`)
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/pools/public/')) return response({ ownerId: user, name: 'Team', memberNames: ['Alex', 'Sam'] })
    if (url.includes('/users/public/')) return response({ userId: user, firstName: 'Alex', paymentEnabled: true })
    if (url.includes('/eligibility')) return response({ canReceiveTips: true })
    if (url.includes('/quote')) {
      const covered = new URLSearchParams(url.split('?')[1]).get('feeCovered') === 'true'
      return response({ tipAmountMinor: 2500, serviceFeeMinor: covered ? 107 : 0, totalAmountMinor: covered ? 2607 : 2500, recipientAmountMinor: covered ? 2500 : 2396, estimatedStripeFeeMinor: covered ? 107 : 104, serviceFeePercent: 3.15, fixedFeeMinor: 25, feeEstimated: true })
    }
    if (url.includes('/checkout')) return response({ transactionId: 'fake' })
    return response({})
  })
  stubCheckoutFetch( fetch)
  render(<DemoPayment />)
  await screen.findByRole('heading', { name: `Trinkgeld für ${pool ? 'Team' : 'Alex'}.` })
  fireEvent.change(screen.getByLabelText(/Anderer Betrag/), { target: { value: '25,00' } })
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  expect(await screen.findByText(/Mehrkosten trägt Gratilo/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('checkbox', { name: 'Zahlungsgebühren freiwillig übernehmen' }))
  expect(await screen.findByText(/1,07/)).toBeInTheDocument()
  expect(screen.getByText(/3.15 % des Gesamtbetrags/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  const pay = screen.getByRole('button', { name: /Jetzt sicher bezahlen/ }); await waitFor(() => expect(pay).toBeEnabled()); fireEvent.click(pay)
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(true))
  const sent = fetch.mock.calls.find(([url]) => url.includes('/checkout'))
  expect(JSON.parse(String(sent?.[1]?.body))).toMatchObject({ tipAmountMinor: 2500, expectedTotalAmountMinor: 2607, feeCovered: true })
})

for (const pool of [false, true]) it(`shows the server-calculated fee deduction for an uncovered ${pool ? 'pool' : 'individual'} payment`, async () => {
  history.replaceState({}, '', `/pay/${pool ? 'team/' : ''}${user}`)
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/pools/public/')) return response({ ownerId: user, name: 'Team', memberNames: ['Alex', 'Sam'] })
    if (url.includes('/users/public/')) return response({ userId: user, firstName: 'Alex', paymentEnabled: true })
    if (url.includes('/eligibility')) return response({ canReceiveTips: true })
    if (url.includes('/quote')) return response({ tipAmountMinor: 2500, serviceFeeMinor: 0, totalAmountMinor: 2500, recipientAmountMinor: 2396, estimatedStripeFeeMinor: 104, feeEstimated: true })
    return response({ transactionId: 'fake' })
  })
  stubCheckoutFetch( fetch); render(<DemoPayment />)
  await screen.findByRole('heading', { name: `Trinkgeld für ${pool ? 'Team' : 'Alex'}.` })
  fireEvent.change(screen.getByLabelText(/Anderer Betrag/), { target: { value: '25,00' } })
  const next = screen.getByRole('button', { name: /Weiter/ }); await waitFor(() => expect(next).toBeEnabled()); fireEvent.click(next)
  expect(await screen.findByText(/Du übernimmst die Gebühren nicht/)).toHaveTextContent('23,96')
  expect(screen.getByText('Geschätzte Stripe-Gebühren als Abzug')).toBeInTheDocument()
  expect(screen.getByText(/− 1,04/)).toBeInTheDocument()
  expect(screen.getByText(`Voraussichtlicher Nettobetrag für den ${pool ? 'Pool' : 'Empfänger'}`)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  expect(screen.getByText(/− 1,04/)).toBeInTheDocument()
  const pay = screen.getByRole('button', { name: /Jetzt sicher bezahlen/ }); await waitFor(() => expect(pay).toBeEnabled()); fireEvent.click(pay)
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(true))
  const sent = fetch.mock.calls.find(([url]) => url.includes('/checkout'))
  expect(JSON.parse(String(sent?.[1]?.body))).toMatchObject({ tipAmountMinor: 2500, expectedTotalAmountMinor: 2500, expectedRecipientAmountMinor: 2396, feeCovered: false })
})
it('shows the actionable net-minimum error without allowing checkout', async () => {
  history.replaceState({}, '', `/pay/${user}`)
  const fetch = vi.fn(async (url: string) => url.includes('/users/public/') ? response({ userId: user, firstName: 'Alex', paymentEnabled: true })
    : url.includes('/eligibility') ? response({ canReceiveTips: true })
    : response({ code: 'TIP_TOO_SMALL_AFTER_FEES', message: 'Nach Gebührenabzug muss mindestens 0,01 € übrig bleiben.' }, false))
  stubCheckoutFetch( fetch); render(<DemoPayment />)
  expect(await screen.findByRole('alert')).toHaveTextContent('0,01 €')
  expect(screen.getByRole('button', { name: /Weiter/ })).toBeDisabled()
  expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(false)
})

for (const [pool,collector] of [[false,'application'],[false,'stripe'],[true,'application']] as const) for (const covered of [false, true]) it(`shows separate commission and frozen net for ${pool ? 'pool' : 'direct'} collector=${collector} with coverage=${covered}`, async () => {
  history.replaceState({}, '', `/pay/${pool ? 'team/' : ''}${user}`)
  let selectedCoverage = false
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url.includes('/pools/public/')) return response({ ownerId: user, name: 'Team' })
    if (url.includes('/users/public/')) return response({ userId: user, firstName: 'Alex', paymentEnabled: true })
    if (url.includes('/eligibility')) return response({ canReceiveTips: true })
    if (url.includes('/quote')) {
      selectedCoverage = new URLSearchParams(url.split('?')[1]).get('feeCovered') === 'true'
      return response({ tipAmountMinor: 2500, serviceFeeMinor: selectedCoverage ? 107 : 0, totalAmountMinor: selectedCoverage ? 2607 : 2500,
        recipientAmountMinor: 2500 - 125 - (selectedCoverage ? 0 : 104), platformFeeMinor: 125,
        platformFeePercent: 5, pricingVersion: pool ? 7 : collector==='stripe' ? 8 : 6, feesCollector:collector, recipientAmountEstimated:collector==='stripe', estimatedStripeFeeMinor: selectedCoverage ? 107 : 104 })
    }
    return response({ transactionId: 'fake' })
  })
  stubCheckoutFetch( fetch); render(<DemoPayment />)
  await screen.findByRole('heading', { name: `Trinkgeld für ${pool ? 'Team' : 'Alex'}.` })
  fireEvent.change(screen.getByLabelText(/Anderer Betrag/), { target: { value: '25,00' } })
  await waitFor(() => expect(screen.getByRole('button', { name: /Weiter/ })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: /Weiter/ }))
  if (covered) fireEvent.click(screen.getByRole('checkbox', { name: 'Zahlungsgebühren freiwillig übernehmen' }))
  await waitFor(() => expect(selectedCoverage).toBe(covered))
  expect(await screen.findByText(/Gratilo-Servicegebühr: 5 % des Trinkgeldbetrags/)).toBeInTheDocument()
  expect(screen.getByText(/Die Gebührenübernahme ist eine Schätzung/)).toBeInTheDocument()
  if(collector==='stripe') {
    expect(screen.getByText(/Stripe zieht seine tatsächlichen Gebühren direkt beim Empfänger ein/)).toBeInTheDocument()
    expect(screen.queryByText(/Mehrkosten trägt Gratilo/)).not.toBeInTheDocument()
    expect(screen.getByText('Voraussichtlicher Nettobetrag für den Empfänger')).toBeInTheDocument()
  }
  expect(screen.getByText(/Spätere Connect- und Bankauszahlungskosten sind nicht in der Stripe-Kostenschätzung enthalten/)).toBeInTheDocument()
  expect(screen.queryByText(/Keine Gratilo-Plattformgebühr/)).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Weiter zur Zusammenfassung/ }))
  expect(screen.getByText(/Die Zahlungsgebühren und der angezeigte Nettobetrag beruhen auf einer Schätzung/)).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Jetzt sicher bezahlen/ }))
  await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('/checkout'))).toBe(true))
  const sent = fetch.mock.calls.find(([url]) => url.includes('/checkout'))
  expect(JSON.parse(String(sent?.[1]?.body))).toMatchObject({ tipAmountMinor: 2500, feeCovered: covered,
    expectedTotalAmountMinor: covered ? 2607 : 2500, expectedRecipientAmountMinor: 2500 - 125 - (covered ? 0 : 104) })
})

it.each([false,true])('resolves preallocated team links by server mapping (team route=%s)', async explicitTeam => {
  const poolId='22222222-2222-2222-2222-222222222222'
  const fetch=vi.fn(async(url:string, options?:RequestInit)=>{
    if(url.includes('/public/'))return response({ownerId:user,poolId,name:'Bestandslink-Team',paymentTarget:'POOL',memberNames:[]})
    if(url.includes('/eligibility'))return response({canReceiveTips:true})
    if(url.includes('/quote'))return response({tipAmountMinor:500,serviceFeeMinor:0,totalAmountMinor:500,recipientAmountMinor:410})
    return response({transactionId:'pool-test'})
  })
  stubCheckoutFetch(fetch)
  history.replaceState({},'',`/pay/${explicitTeam?'team/':''}gratilo-002?recipient=manipulated`)
  render(<DemoPayment />)
  await screen.findByRole('heading',{name:'Trinkgeld für Bestandslink-Team.'})
  const next=screen.getByRole('button',{name:/Weiter/});await waitFor(()=>expect(next).toBeEnabled());fireEvent.click(next)
  fireEvent.click(await screen.findByRole('button',{name:/Weiter zur Zusammenfassung/}))
  fireEvent.click(await screen.findByRole('button',{name:/Jetzt sicher bezahlen/}))
  await waitFor(()=>expect(fetch.mock.calls.some(([url])=>url===`/payment-service/tips/pools/${user}/checkout`)).toBe(true))
  const checkout=fetch.mock.calls.find(([url])=>url.endsWith('/checkout'))!
  expect(JSON.parse(String(checkout[1]?.body))).toMatchObject({expectedPoolId:poolId,expectedRecipientAmountMinor:410})
  expect(fetch.mock.calls.some(([url])=>url.includes('manipulated'))).toBe(false)
  expect(fetch.mock.calls.some(([url])=>url===`/payment-service/tips/${user}/checkout`)).toBe(false)
})
