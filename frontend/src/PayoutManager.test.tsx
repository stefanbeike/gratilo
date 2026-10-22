import { cleanup, render, screen, fireEvent, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
vi.mock('./api',()=>({api:vi.fn()}))
import { api } from './api'
import { PayoutManager, payoutAmount } from './PayoutManager'
const base={simulation:true,eligible:true,availableMinor:10000,pendingMinor:5000,simulationAvailableMinor:10000,currency:'EUR',bankLast4:'6789',bankName:'Testbank',schedule:'manual',message:''}
let eligibility={...base}
let history:unknown[]=[]
let result={id:'payout-1',amountMinor:-2500,status:'PENDING',provider:'STRIPE_SANDBOX',metadata:'{"payoutStatus":"pending"}'}
beforeEach(()=>{localStorage.clear();eligibility={...base};history=[];vi.mocked(api).mockReset();vi.mocked(api).mockImplementation(async(path,body)=>{
 if(path.endsWith('/eligibility'))return eligibility as never
 if(path.endsWith('/reconcile')){result={...result,status:'BOOKED',metadata:'{"payoutStatus":"paid"}'};history=[result];return result as never}
 if(body){history=[result];return result as never}
 return history as never
})})
afterEach(cleanup)
const mount=()=>render(<PayoutManager accessToken="own-token" userId="own-user" onChanged={vi.fn()}/>)
it.each([['25,00',2500],['2.1',210],['1',100],['0,01',1],['-1',null],['2,001',null],['1e3',null],['NaN',null],['',null],['99999999999999999999999',null]])('parses %s as safe integer cents', (input,expected)=>expect(payoutAmount(input)).toBe(expected))
it('shows own available and masked bank and confirms one standard payout',async()=>{
 mount();expect(await screen.findByText('Testmodus · lokale Auszahlungssimulation')).toBeInTheDocument();expect(screen.getByText(/6789/)).toBeInTheDocument();fireEvent.change(screen.getByLabelText('Auszahlungsbetrag in EUR'),{target:{value:'25,00'}});fireEvent.click(screen.getByRole('button',{name:'Auszahlen'}));expect(screen.getByRole('dialog')).toHaveTextContent('25,00');const button=screen.getByRole('button',{name:'Auszahlung bestätigen'});fireEvent.click(button);fireEvent.click(button)
 await screen.findByText(/Deine simulierte Auszahlung wurde angestoßen/);const posts=vi.mocked(api).mock.calls.filter(([path,body])=>path==='/payment-service/payouts'&&body);expect(posts).toHaveLength(1);expect(posts[0][1]).toMatchObject({amountMinor:2500});expect(posts[0][2]).toBe('own-token');expect((posts[0][1] as {requestId:string}).requestId).toMatch(/^[a-f0-9-]{36}$/);expect(screen.getByText('Wird verarbeitet')).toBeInTheDocument()
 fireEvent.click(screen.getByRole('button',{name:'Status abgleichen'}));expect(await screen.findByText('Simulation abgeschlossen')).toBeInTheDocument()
})
it('uses simulation spendable amount rather than inventing a second Stripe balance',async()=>{
 eligibility={...base,simulationAvailableMinor:1200};mount();await screen.findByText('12,00 €');fireEvent.click(screen.getByLabelText('Gesamtes verfügbares Guthaben'));fireEvent.click(screen.getByRole('button',{name:'Auszahlen'}));expect(screen.getByRole('dialog')).toHaveTextContent('12,00');fireEvent.click(screen.getByRole('button',{name:'Auszahlung bestätigen'}));await waitFor(()=>expect(vi.mocked(api).mock.calls.some(([,body])=>body&&'amountMinor' in (body as object)&&(body as {amountMinor:unknown}).amountMinor===null)).toBe(true))
})
it.each([0,99])('cannot pay pending-only or too-small balance (%s)',async available=>{
 eligibility={...base,eligible:false,availableMinor:available,simulationAvailableMinor:available,message:'Ausstehende Beträge können noch nicht ausgezahlt werden.'};mount();await screen.findByText(eligibility.message);fireEvent.change(screen.getByLabelText('Auszahlungsbetrag in EUR'),{target:{value:'100'}});expect(screen.getByRole('button',{name:'Auszahlen'})).toBeDisabled();expect(screen.getByRole('alert')).toBeInTheDocument()
})
it('keeps an uncertain request id on timeout and reuses it instead of creating another request',async()=>{
 vi.mocked(api).mockImplementation(async(path,body)=>{if(path.endsWith('eligibility'))return base as never;if(body)throw Error('Timeout');return [] as never});mount();await screen.findByText('100,00 €');fireEvent.change(screen.getByLabelText('Auszahlungsbetrag in EUR'),{target:{value:'25'}});fireEvent.click(screen.getByRole('button',{name:'Auszahlen'}));fireEvent.click(screen.getByRole('button',{name:'Auszahlung bestätigen'}));await screen.findByText('Timeout');fireEvent.click(screen.getByRole('button',{name:'Auszahlung bestätigen'}));await waitFor(()=>expect(vi.mocked(api).mock.calls.filter(([,b])=>b)).toHaveLength(2));const bodies=vi.mocked(api).mock.calls.filter(([,b])=>b).map(([,b])=>b);expect(bodies[0]).toEqual(bodies[1])
})
it('shows missing prerequisites and never provides a payout form',async()=>{
 vi.mocked(api).mockImplementation(async path=>{if(path.endsWith('eligibility'))throw Error('Bitte hinterlege eine EUR-Bankverbindung.');return [] as never});mount();expect(await screen.findByRole('alert')).toHaveTextContent('Bankverbindung');expect(screen.queryByLabelText('Auszahlungsbetrag in EUR')).not.toBeInTheDocument()
})
it('blocks new payouts when a previous result is unknown and never labels it paid',async()=>{
 history=[{...result,status:'AUTHORIZED',metadata:'{"payoutStatus":"unknown"}'}];mount();await screen.findByText('Ergebnis unklar – Statusabgleich erforderlich');fireEvent.change(screen.getByLabelText('Auszahlungsbetrag in EUR'),{target:{value:'25'}});expect(screen.getByRole('button',{name:'Auszahlen'})).toBeDisabled();expect(screen.queryByText('Ausgezahlt')).not.toBeInTheDocument()
})
it('shows history errors and blocks payouts instead of claiming no history',async()=>{
 vi.mocked(api).mockImplementation(async path=>{if(path.endsWith('eligibility'))return base as never;throw Error('offline')});mount();await screen.findByText('Die Auszahlungshistorie konnte nicht geladen werden.');fireEvent.change(screen.getByLabelText('Auszahlungsbetrag in EUR'),{target:{value:'25'}});expect(screen.getByRole('button',{name:'Auszahlen'})).toBeDisabled();expect(screen.queryByText('Noch keine Auszahlungen vorhanden.')).not.toBeInTheDocument()
})
