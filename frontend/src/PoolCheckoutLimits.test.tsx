import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react'
import {afterEach,expect,it,vi} from 'vitest'
vi.mock('./stripe',()=>({paymentsEnabled:true}))
import {DemoPayment} from './Dashboard'
const owner='11111111-1111-1111-1111-111111111111'
afterEach(()=>{cleanup();vi.unstubAllGlobals();history.replaceState({},'','/');sessionStorage.clear()})
function mount(limits:unknown={minAmountMinor:200,maxAmountMinor:50000,currency:'EUR'}){
 history.replaceState({},'',`/pay/team/${owner}`)
 const response=(data:unknown,ok=true)=>({ok,json:async()=>data})
 const fetch=vi.fn(async(url:string,options?:RequestInit)=>{
  if(url.includes('/pool-limits'))return response(limits)
  if(url.includes('/pools/public/'))return response({ownerId:owner,name:'Team'})
  if(url.includes('/eligibility'))return response({canReceiveTips:true})
  if(url.includes('/quote')){
   const args=new URLSearchParams(url.split('?')[1]),amount=Number(args.get('amountMinor')),covered=args.get('feeCovered')==='true'
   const values:Record<number,[number,number,number]>={200:[10,31,32],500:[25,41,42],50000:[2500,1600,1652]}
   const [commission,uncovered,surcharge]=values[amount]??[0,0,0]
   return response({tipAmountMinor:amount,serviceFeeMinor:covered?surcharge:0,totalAmountMinor:amount+(covered?surcharge:0),recipientAmountMinor:amount-commission-(covered?0:uncovered),platformFeeMinor:commission,platformFeePercent:5,pricingVersion:7,estimatedStripeFeeMinor:covered?surcharge:uncovered})
  }
  if(url.includes('/checkout'))return response({transactionId:'fake-test'})
  throw Error('Unexpected local fake route')
 });vi.stubGlobal('fetch',fetch);render(<DemoPayment/>);return fetch
}
it('uses server limits and hides the one-euro preset for team payments',async()=>{
 mount();await waitFor(()=>expect(screen.getByRole('button',{name:/Weiter/})).toBeEnabled(),{timeout:5000})
 expect(screen.getByText(/Team-Trinkgeld:.*2,00.*500,00/)).toBeInTheDocument()
 expect(screen.queryByRole('button',{name:/^1,00/})).not.toBeInTheDocument()
 expect(screen.getByLabelText(/Anderer Betrag/)).toHaveAttribute('min','2')
 expect(screen.getByLabelText(/Anderer Betrag/)).toHaveAttribute('max','500')
})
for(const invalid of ['1,99','500,01','2,001','-2','keine Zahl'])it(`blocks an invalid pool amount ${invalid}`,async()=>{
 const fetch=mount();await waitFor(()=>expect(screen.getByRole('button',{name:/Weiter/})).toBeEnabled(),{timeout:5000})
 fireEvent.change(screen.getByLabelText(/Anderer Betrag/),{target:{value:invalid}})
 expect(screen.getByRole('button',{name:/Weiter/})).toBeDisabled()
 expect(screen.getByLabelText(/Anderer Betrag/)).toHaveAttribute('aria-invalid','true')
 expect(screen.getByRole('alert')).toHaveTextContent(/2,00.*500,00/)
 expect(fetch.mock.calls.some(([url])=>url.includes('/checkout'))).toBe(false)
})
it('accepts both endpoints and lets a Stripe surcharge exceed the pure tip maximum',async()=>{
 const fetch=mount();await waitFor(()=>expect(screen.getByRole('button',{name:/Weiter/})).toBeEnabled(),{timeout:5000})
 for(const input of ['2,00','500,00']){
  fireEvent.change(screen.getByLabelText(/Anderer Betrag/),{target:{value:input}})
  await waitFor(()=>expect(screen.getByRole('button',{name:/Weiter/})).toBeEnabled(),{timeout:5000})
 }
 fireEvent.click(screen.getByRole('button',{name:/Weiter/}))
 fireEvent.click(screen.getByRole('checkbox',{name:'Zahlungsgebühren freiwillig übernehmen'}))
 await waitFor(()=>expect(screen.getByRole('button',{name:/Weiter zur Zusammenfassung/})).toBeEnabled())
 fireEvent.click(screen.getByRole('button',{name:/Weiter zur Zusammenfassung/}))
 expect(screen.getAllByText(/516,52/).length).toBeGreaterThan(0)
 fireEvent.click(screen.getByRole('button',{name:/Jetzt sicher bezahlen/}))
 await waitFor(()=>expect(fetch.mock.calls.some(([url])=>url.includes('/checkout'))).toBe(true))
 const sent=fetch.mock.calls.find(([url])=>url.includes('/checkout'))
 expect(JSON.parse(String(sent?.[1]?.body))).toMatchObject({tipAmountMinor:50000,feeCovered:true,expectedTotalAmountMinor:51652,expectedRecipientAmountMinor:47500})
})
it('fails closed when the backend limit configuration is unavailable or invalid',async()=>{
 const fetch=mount({minAmountMinor:1,maxAmountMinor:1000000});await screen.findByText(/Zahlungsgrenzen konnten nicht geladen/)
 expect(screen.getByRole('button',{name:/Weiter/})).toBeDisabled()
 expect(fetch.mock.calls.some(([url])=>url.includes('/quote')||url.includes('/checkout'))).toBe(false)
})
