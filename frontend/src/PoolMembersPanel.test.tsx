import { cleanup,fireEvent,render,screen,within,waitFor } from '@testing-library/react'
import { afterEach,expect,it,vi } from 'vitest'
import { PoolMembersPanel,type PoolParticipant } from './PoolMembersPanel'
import type { MemberStripeStatus } from './poolStripeStatus'
afterEach(cleanup)
const members:PoolParticipant[]=[{id:'a',userId:'a',email:'a@example.test',name:'Alex',sharePercent:30,effectiveSharePercent:30,status:'ACTIVE',active:true},{id:'b',userId:'b',email:'b@example.test',name:'Sam',sharePercent:70,effectiveSharePercent:70,status:'ACTIVE',active:true},{id:'c',userId:'c',email:'c@example.test',name:'Kim',sharePercent:0,effectiveSharePercent:0,status:'ACTIVE',active:false}]
const ready=(userId:string):MemberStripeStatus=>({userId,status:'READY',onboardingStatus:'COMPLETE',transfersEnabled:true,payoutsEnabled:true,checkedAt:new Date().toISOString()})
const props=()=>({members,ownerId:'a',stripe:{a:ready('a'),b:{...ready('b'),payoutsEnabled:false}} as Record<string,MemberStripeStatus>,drafts:{},onDraft:vi.fn(),onToggle:vi.fn().mockResolvedValue(undefined),onRemove:vi.fn().mockResolvedValue(undefined),onResend:vi.fn().mockResolvedValue(undefined),onDistribute:vi.fn(),onSave:vi.fn().mockResolvedValue(undefined)})
it('shows stored weights separately from effective shares and keeps payout capability separate',()=>{
 render(<PoolMembersPanel {...props()}/>);expect(screen.getByText('100,00 %')).toBeInTheDocument();expect(screen.getByText('★ Poolinhaber')).toBeInTheDocument();expect(screen.getByText('30,00 %')).toBeInTheDocument();expect(screen.getByText('70,00 %')).toBeInTheDocument();expect(screen.getByText('0,00 %')).toBeInTheDocument()
 expect(within(screen.getByRole('list',{name:'Aktive Teilnehmer'})).getAllByRole('listitem')).toHaveLength(2)
 expect(within(screen.getByRole('list',{name:'Inaktive Teilnehmer und Einladungen'})).getAllByRole('listitem')).toHaveLength(1)
 expect(within(screen.getByRole('list',{name:'Aktive Teilnehmer'})).getByText(/Stripe-Payout-Fähigkeit: Nicht bestätigt/)).toBeInTheDocument();expect(screen.getByLabelText('Prozentgewicht für Sam')).toHaveValue('70')
 expect(screen.getByText('2 von 2 aktiven Mitgliedern mit Anteil sind bestätigt transferbereit.')).toBeInTheDocument()
})
it('requires confirmation for deactivation and restores focus on cancel',async()=>{
 const p=props();render(<PoolMembersPanel {...p}/>);const trigger=screen.getByRole('button',{name:'Deaktivieren'});trigger.focus();fireEvent.click(trigger)
 expect(screen.getByRole('alertdialog')).toHaveAccessibleName('Mitglied deaktivieren?');expect(p.onToggle).not.toHaveBeenCalled()
 fireEvent.click(screen.getByRole('button',{name:'Abbrechen'}));await waitFor(()=>expect(trigger).toHaveFocus());expect(p.onToggle).not.toHaveBeenCalled()
 fireEvent.click(trigger);fireEvent.click(screen.getByRole('button',{name:'Bestätigen'}));await waitFor(()=>expect(p.onToggle).toHaveBeenCalledWith(members[1],false))
})
it('supports keyboard cancellation and requires confirmation before removal or saving weights',async()=>{
 const p=props();render(<PoolMembersPanel {...p}/>);fireEvent.click(screen.getAllByRole('button',{name:'Entfernen'})[0]);fireEvent.keyDown(screen.getByRole('alertdialog'),{key:'Escape'});expect(p.onRemove).not.toHaveBeenCalled();expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
 fireEvent.click(screen.getByRole('button',{name:'Alle Anteile speichern'}));expect(p.onSave).not.toHaveBeenCalled();fireEvent.click(screen.getByRole('button',{name:'Bestätigen'}));await waitFor(()=>expect(p.onSave).toHaveBeenCalledOnce())
})
it('shows missing transfer permission without redistributing the displayed share',()=>{
 const p=props();p.stripe.b={...ready('b'),status:'INCOMPLETE',transfersEnabled:false};render(<PoolMembersPanel {...p}/>);expect(screen.getByText('70,00 %')).toBeInTheDocument();expect(screen.getByText(/Bestätigte Stripe-Einrichtung erforderlich/)).toBeInTheDocument()
})
it('does not describe an unknown Stripe lookup as a confirmed block',()=>{
 const p=props();p.stripe.b={userId:'b',status:'UNAVAILABLE',onboardingStatus:'COMPLETE',transfersEnabled:null,payoutsEnabled:null,checkedAt:null};render(<PoolMembersPanel {...p}/>);
 expect(screen.getAllByText('Stripe-Status derzeit nicht überprüfbar')).toHaveLength(1);expect(within(screen.getByRole('list',{name:'Aktive Teilnehmer'})).getAllByText(/Transferempfang: Status unbekannt/)).toHaveLength(1);expect(screen.getByText(/bei einem aktiven Mitglied derzeit nicht überprüfbar/)).toBeInTheDocument();expect(screen.queryByText(/Bestätigte Stripe-Einrichtung erforderlich/)).not.toBeInTheDocument()
})
it('offers an explicit status refresh for pool transfer readiness',()=>{
 const p={...props(),onRefreshStripe:vi.fn()};render(<PoolMembersPanel {...p}/>);fireEvent.click(screen.getByRole('button',{name:'Stripe-Status erneut prüfen'}));expect(p.onRefreshStripe).toHaveBeenCalledOnce()
})
it('renders reactivated members using returned server shares and never overwrites saved weights',()=>{
 const p=props();const view=render(<PoolMembersPanel {...p} members={[{...members[0],effectiveSharePercent:100},{...members[1],active:false,effectiveSharePercent:0}]}/>);expect(screen.getAllByText('100,00 %')).toHaveLength(2)
 view.rerender(<PoolMembersPanel {...p}/>);expect(screen.getByText('70,00 %')).toBeInTheDocument();expect(screen.getByLabelText('Prozentgewicht für Sam')).toHaveValue('70')
})
it('keeps keyboard focus on the matching action after a participant moves between groups',async()=>{
 const p=props();const view=render(<PoolMembersPanel {...p}/>);
 p.onToggle.mockImplementation(async()=>{view.rerender(<PoolMembersPanel {...p} members={[{...members[0],effectiveSharePercent:100},{...members[1],active:false,effectiveSharePercent:0},members[2]]}/>)});
 const trigger=screen.getByRole('button',{name:'Deaktivieren'});trigger.focus();fireEvent.click(trigger);fireEvent.click(screen.getByRole('button',{name:'Bestätigen'}));
 await waitFor(()=>expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
 await waitFor(()=>expect(within(screen.getByRole('group',{name:'Aktionen für Sam'})).getByRole('button',{name:'Reaktivieren'})).toHaveFocus());
})

it('shows draft totals without inventing effective shares and blocks invalid precision',()=>{
 const p=props();const view=render(<PoolMembersPanel {...p} drafts={{a:'50',b:'30'}}/>);
 expect(screen.getByText(/Gespeicherte Prozentgewichte der aktiven Mitglieder: 80,00 %/)).toBeInTheDocument();
 expect(screen.getByText('70,00 %')).toBeInTheDocument();
 expect(screen.getByRole('button',{name:'Alle Anteile speichern'})).toBeEnabled();
 view.rerender(<PoolMembersPanel {...p} drafts={{a:'50.001',b:'30'}}/>);
 expect(screen.getByLabelText('Prozentgewicht für Alex')).toHaveAttribute('aria-invalid','true');
 expect(screen.getByRole('button',{name:'Alle Anteile speichern'})).toBeDisabled();
 view.rerender(<PoolMembersPanel {...p} drafts={{a:'0',b:'0',c:'100'}}/>);
 expect(screen.getByRole('button',{name:'Alle Anteile speichern'})).toBeDisabled();
 expect(screen.getByRole('alert')).toHaveTextContent('Mindestens ein aktives Mitglied');
})
