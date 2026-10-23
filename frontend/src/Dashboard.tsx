import { useTipPoolsEnabled } from './features'
import { useEffect, useMemo, useState, useRef, type FormEvent } from 'react'
import { QRCodeSVG } from 'qrcode.react'
import { api, type Session } from './api'
import { prepareProfileImage } from './profileImage'
import { percentageBasisPoints } from './poolPercentages'
import { paymentsEnabled } from './stripe'
import { StripeTestEnrollment } from './StripeTestEnrollment'
import { StripeOnboarding } from './StripeOnboarding'
import { PublicTipCheckout } from './PublicTipCheckout'
import { memberTransferReady, type MemberStripeStatus } from './poolStripeStatus'
import { PoolMembersPanel } from './PoolMembersPanel'
import { stripeDashboardStatus } from './StripeStatusCard'
import { TeamAvatar, TeamImageEditor } from './TeamImageEditor'
import { PersonalAccountPanel, type StripeBalance } from './PersonalAccountPanel'
import './dashboard.css'

export interface Profile { personalLinkPaused?: boolean; paymentUrl?: string | null; paymentEnabled?: boolean; stripeOnboardingCompleted?: boolean; profileImage?: string | null; email: string; firstName?: string; lastName?: string; organisation?: string; street?: string; city?: string; phone?: string; country?: string; newsletter?: boolean }
interface ConnectedAccount { transfersEnabled?: boolean; stripeStatus?: string; statusCheckedAt?: string; stripeAccountId?: string; onboardingStatus: string; chargesEnabled: boolean; payoutsEnabled: boolean; detailsSubmitted: boolean; payoutScheduleInterval: string }
interface PoolMember { deactivationReason?: string | null; id: string; userId: string; email: string; name: string; sharePercent: number; effectiveSharePercent?: number; status: string; active: boolean; invitationExpiresAt?: string | null; invitationToken?: string | null }
interface Pool { id: string; name: string; ownerId: string; members: PoolMember[]; paymentUrl?: string | null; profileImage?: string | null }
const normalizePool = (value: Pool | null | undefined): Pool | null => value ? { ...value, members: Array.isArray(value.members) ? value.members : [] } : null
type PoolAllocation = { poolId?: string; poolName: string; sharePercent?: number | null; allocatedAmountMinor?: number; distributionAmountMinor?: number | null; selectedTipAmountMinor?: number | null; members?: Array<{ name?: string; email?: string; sharePercent?: number; amountMinor?: number; status?: string }> }
type Transaction = { netEstimated?: boolean; stripeCollected?: boolean; actualFeeMinor?: number; id: string; date: string; name: string; note: string; amount: number; status: 'Gebucht' | 'Ausstehend' | 'Fehlgeschlagen' | 'Erstattet'; type: 'Trinkgeld' | 'Auszahlung' | 'Erstattung'; paymentMethod?: string | null; selectedMinor?: number; netMinor?: number; grossMinor?: number | null; feeMinor?: number | null; commissionMinor?: number | null; stripeEstimateMinor?: number | null; feeCovered?: boolean | null; guestStatus?: string | null; transferStatus?: string; settlementReason?: string; pool?: PoolAllocation }
type ApiTransaction = { id: string; amountMinor: number; recipientAmountMinor?: number; actualNetKnown?: boolean; actualFeeMinor?: number; type: string; status: string; description?: string | null; reference?: string | null; paymentMethod?: string | null; createdAt?: string; metadata?: string | null }
type ApiPoolTransfer = { poolId?: string | null; id: string; transactionId: string; poolName: string; amountMinor: number; reversedAmountMinor: number; status: string; createdAt: string; settlementReason?: string; fundsAvailableAt?: string; historicalSharePercent?: number | null; poolDistributionAmountMinor?: number | null; selectedTipAmountMinor?: number | null; personalGrossAmountMinor?: number | null; personalFeeAmountMinor?: number | null; guestPaymentStatus?: string | null; platformFeeMinor?: number | null; estimatedStripeFeeMinor?: number | null; feeCovered?: boolean | null }
const pricingDetails = (metadata?: string | null) => {
  try {
    const saved = JSON.parse(metadata || '{}')
    if (![4, 5, 6, 7, 8, 9, 10, 11].includes(saved.pricingVersion)) return {}
    return { netEstimated: [8,11].includes(saved.pricingVersion), stripeCollected: [8,11].includes(saved.pricingVersion), commissionMinor: Number.isSafeInteger(saved.platformFeeMinor) ? saved.platformFeeMinor as number : undefined,
      stripeEstimateMinor: Number.isSafeInteger(saved.estimatedStripeFeeMinor) ? saved.estimatedStripeFeeMinor as number : undefined,
      feeCovered: typeof saved.feeCovered === 'boolean' ? saved.feeCovered as boolean : undefined }
  } catch { return {} }
}
const poolMetadata = (value?: string | null): PoolAllocation | undefined => { try { const parsed = value ? JSON.parse(value) : null; return parsed?.poolName ? parsed as PoolAllocation : undefined } catch { return undefined } }
export const transactions: Transaction[] = [
  { id: 'DEMO-1008', date: '2026-09-13', name: 'Sophie M.', note: 'Danke für den tollen Service! ♡', amount: 12, status: 'Gebucht', type: 'Trinkgeld' },
  { id: 'DEMO-1007', date: '2026-09-13', name: 'Ein zufriedener Gast', note: 'Ein kleines Dankeschön', amount: 5, status: 'Gebucht', type: 'Trinkgeld' },
  { id: 'DEMO-1006', date: '2026-09-12', name: 'Jonas K.', note: 'Wir kommen gerne wieder.', amount: 8.5, status: 'Ausstehend', type: 'Trinkgeld' },
  { id: 'DEMO-1005', date: '2026-09-12', name: 'Auszahlung auf dein Bankkonto', note: 'Deine gesammelten Trinkgelder', amount: -75, status: 'Gebucht', type: 'Auszahlung' },
  { id: 'DEMO-1004', date: '2026-09-11', name: 'Marie & Paul', note: 'Für einen schönen Abend', amount: 20, status: 'Gebucht', type: 'Trinkgeld' },
  { id: 'DEMO-1003', date: '2026-09-10', name: 'Ein zufriedener Gast', note: 'Ein kleines Dankeschön', amount: 7.5, status: 'Gebucht', type: 'Trinkgeld' },
  { id: 'DEMO-1002', date: '2026-09-09', name: 'Lena B.', note: 'Du hast unseren Tag schöner gemacht.', amount: 15, status: 'Gebucht', type: 'Trinkgeld' },
  { id: 'DEMO-1001', date: '2026-09-08', name: 'Tisch 12', note: 'Vielen Dank vom ganzen Team!', amount: 50, status: 'Gebucht', type: 'Trinkgeld' },
]
const money = (value: number) => new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(value)
const validHistoricalPercent = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= 100
const historicalShareLabel = (value: unknown) => validHistoricalPercent(value) ? `${new Intl.NumberFormat('de-DE', { maximumFractionDigits: 4 }).format(value)} %` : 'Historischer Anteil nicht verfügbar'
const validHistoricalAmount = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value > 0
function historicalPoolSummary(pool: PoolAllocation) {
  const parts = [validHistoricalPercent(pool.sharePercent) ? `${historicalShareLabel(pool.sharePercent)} eigener Anteil` : 'Historischer Anteil nicht verfügbar']
  parts.push(validHistoricalAmount(pool.distributionAmountMinor) ? `Pool-Verteilungsbetrag ${money(pool.distributionAmountMinor / 100)}` : 'Pool-Verteilungsbetrag nicht verfügbar')
  if (validHistoricalAmount(pool.selectedTipAmountMinor) && validHistoricalAmount(pool.distributionAmountMinor) && pool.selectedTipAmountMinor !== pool.distributionAmountMinor)
    parts.push(`Gewähltes Trinkgeld ${money(pool.selectedTipAmountMinor / 100)}`)
  return parts.join(' · ')
}
function transferLabel(t: Transaction) {
  if(t.transferStatus==='REVERSED')return 'Rückabgewickelt'
  if(t.transferStatus==='REVERSAL_PENDING')return 'Rückabwicklung läuft'
  if(t.transferStatus==='TRANSFERRED')return 'Transfer erfolgreich'
  if(t.transferStatus==='FAILED'||['RECONCILIATION_REQUIRED','REFUND_RECONCILIATION_REQUIRED','ATTEMPT_AMOUNT_CHANGED','TRANSFER_AMOUNT_MISMATCH'].includes(t.settlementReason||''))return 'Handlungsbedarf'
  if(t.guestStatus==='FAILED'||t.guestStatus==='CANCELLED')return 'Gastzahlung fehlgeschlagen'
  if(t.settlementReason==='WAITING_CONFIRMED_PAYMENT')return 'Gastzahlung wird verarbeitet'
  if(t.settlementReason==='APPROVAL_REQUIRED')return 'Freigabe erforderlich'
  if(t.settlementReason==='RECIPIENT_NOT_READY')return 'Stripe-Freigabe erforderlich'
  if(['WAITING_SOURCE_FUNDS','WAITING_PLATFORM_BALANCE','STRIPE_INSUFFICIENT_FUNDS','SETTLEMENT_RETRY_REQUIRED'].includes(t.settlementReason||''))return 'Transfer verzögert'
  return 'Pool-Verteilung läuft'
}
const transactionDate = (value: string) => {
  if(!value || !Number.isFinite(Date.parse(value)))return 'Datum nicht verfügbar'
  const hasTime = value.includes('T')
  const parsed = new Date(hasTime ? value : value + 'T12:00:00')
  const formatted = parsed.toLocaleDateString('de-DE', { day: '2-digit', month: 'short', year: 'numeric' })
  return hasTime ? `${formatted}, ${parsed.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })}` : formatted
}
const guestStatusLabel = (value?:string|null) => value==='BOOKED'||value==='COMPLETED'?'Zahlung erfolgreich':value==='REFUNDED'?'Erstattet':value==='FAILED'||value==='CANCELLED'?'Zahlung fehlgeschlagen':value==='DISPUTED'||value==='CHARGEBACK'?'Zahlung in Klärung':value==='PENDING'||value==='AUTHORIZED'?'Gastzahlung wird verarbeitet':'Nicht verfügbar'
const transactionDay = (value: string) => new Date(value.includes('T') ? value : value + 'T12:00:00').toISOString().slice(0, 10)
const paymentMethodLabel = (method?: string | null) => method === 'paypal' ? 'PayPal' : method === 'apple_pay' ? 'Apple Pay' : method === 'google_pay' ? 'Google Pay' : method === 'card' ? 'Karte' : method ? method : 'Zahlungsart unbekannt'
const paymentMethodIcon = (method?: string | null) => method === 'paypal' ? 'P' : method === 'apple_pay' ? '' : method === 'google_pay' ? 'G' : '▣'
const redirectToStripe = (url: string) => window.location.assign(url)
export function csvContent(rows: Transaction[]) {
  return '\uFEFF' + [['Referenz', 'Datum', 'Name', 'Typ', 'Status', 'Betrag EUR'], ...rows.map(t => [t.id, t.date, t.name, t.type, t.status, t.amount.toFixed(2).replace('.', ',')])].map(row => row.map(cell => `"${cell.replaceAll('"', '""')}"`).join(';')).join('\r\n')
}
function downloadQr(title:string,filename:string) { const svg=Array.from(document.querySelectorAll('svg')).find(node=>node.querySelector('title')?.textContent===title);if(svg)download(new Blob([new XMLSerializer().serializeToString(svg)],{type:'image/svg+xml'}),filename) }
function download(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = filename; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export function Dashboard({ session, profile, onProfile, onLogout }: { session: Session; profile: Profile; onProfile: (profile: Profile) => void; onLogout: (...args: any[]) => void }) {
  const tipPoolsEnabled = useTipPoolsEnabled()
  const [testAccessReady, setTestAccessReady] = useState(false)
  const [active, setActive] = useState(paymentsEnabled && profile.paymentEnabled !== false)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState('Alle')
  const [view, setView] = useState<'profile' | 'team'>(() => location.hash === '#team' ? 'team' : 'profile')
  const [selectedTransaction, setSelectedTransaction] = useState<Transaction | null>(null)
  const transactionTrigger=useRef<HTMLButtonElement|null>(null)
  function closeTransaction(){setSelectedTransaction(null);window.requestAnimationFrame(()=>transactionTrigger.current?.focus())}
  const [transactionsLoading, setTransactionsLoading] = useState(true)
  const [transactionsError, setTransactionsError] = useState('')
  const [teamEligibility, setTeamEligibility] = useState(false)
  useEffect(() => { const changed = () => {setView(location.hash === '#team' ? 'team' : 'profile');setEditing(location.hash === '#profile')};window.addEventListener('hashchange',changed);return()=>window.removeEventListener('hashchange',changed)},[])
  function navigate(next: 'profile' | 'team') {setView(next);setEditing(false);setPage(1);setFilter('Alle');setQuery('');window.history.replaceState({},'',next==='team'?'#team':'#overview')}
  const [period, setPeriod] = useState('Alle')
  const [pageSize, setPageSize] = useState(50)
  const [page, setPage] = useState(1)
  const [editing, setEditing] = useState(()=>location.hash==='#profile')
  const [deleteDialog, setDeleteDialog] = useState(false)
  const [deletingAccount, setDeletingAccount] = useState(false)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [poolInfoDialog, setPoolInfoDialog] = useState(false)
  const [saving, setSaving] = useState(false)
  const [profileImage, setProfileImage] = useState<string | null | undefined>(profile.profileImage)
  const [stripeBalance,setStripeBalance]=useState<StripeBalance|null>(null)
  const [balanceLoading,setBalanceLoading]=useState(false)
  const [balanceError,setBalanceError]=useState('')
  const [selectedTeamId,setSelectedTeamId]=useState<string|null>(null)
  const [connectedAccount, setConnectedAccount] = useState<ConnectedAccount | null>(null)
  const [accountTransactions, setAccountTransactions] = useState<Transaction[]>([])
  const [pool, setPool] = useState<Pool | null>(null)
  const [poolLoaded, setPoolLoaded] = useState(false)
  const [membershipsLoaded,setMembershipsLoaded]=useState(false)
  const [teamLoadError,setTeamLoadError]=useState(false)
  const [memberStripe, setMemberStripe] = useState<Record<string, MemberStripeStatus>>({})
  const [poolMemberships, setPoolMemberships] = useState<Pool[]>([])
  const [poolShareDrafts, setPoolShareDrafts] = useState<Record<string, string>>({})
  const [poolName, setPoolName] = useState('')
  const [inviteEmail, setInviteEmail] = useState('')
  const [transactionsVersion, setTransactionsVersion] = useState(0)
  useEffect(()=>{setAccountTransactions([]);setTransactionsLoading(true)},[session.accessToken])
  const [onboardingOpen, setOnboardingOpen] = useState(false)
  const sessionExpiresAt = useMemo(() => { try { const payload = session.accessToken.split('.')[1]; const exp = JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))).exp; return Number.isFinite(exp) ? exp * 1000 : Date.now() + 3600000 } catch { return Date.now() + 3600000 } }, [session.accessToken])
  const [sessionRemaining, setSessionRemaining] = useState(() => Math.max(0, sessionExpiresAt - Date.now()))
  useEffect(() => {
    let cancelled = false
    if (!paymentsEnabled || !testAccessReady) { setConnectedAccount(null); return }
    const refresh = async () => {
      try {
        let account = await api<ConnectedAccount>('/payment-service/connect-account', undefined, session.accessToken)
        if (account.stripeAccountId && !account.stripeAccountId.startsWith('acct_demo_'))
          account = await api<ConnectedAccount>('/payment-service/connect-account/sync', {}, session.accessToken)
        if (!cancelled) {
          setConnectedAccount(account)
          if (stripeDashboardStatus(account).title === 'Vollständig aktiv') setOnboardingOpen(false)
        }
      } catch { if (!cancelled) setConnectedAccount(null) }
    }
    void refresh()
    const timer = window.setInterval(refresh, onboardingOpen ? 5000 : 30000)
    window.addEventListener('focus', refresh)
    return () => { cancelled = true; window.clearInterval(timer); window.removeEventListener('focus', refresh) }
  }, [session.accessToken, testAccessReady, onboardingOpen])
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    const stripeState = params.get('stripe_onboarding')
    if (stripeState !== 'refresh' && stripeState !== 'return') return
    window.history.replaceState({}, '', '/account')
    if (stripeState === 'refresh') {
      setNotice('Der Stripe-Link war abgelaufen. Wir starten das Onboarding erneut.')
      setOnboardingOpen(true)
    } else {
      setNotice('Du bist von Stripe zurück. Der Kontostatus wird aktualisiert.')
    }
  }, [])
  useEffect(() => {
    let cancelled = false
    const loadTransactions = () => {
      api<ApiTransaction[]>('/payment-service/transactions', undefined, session.accessToken)
        .then(async items => {
          if (cancelled) return
          const transfers = tipPoolsEnabled ? await api<ApiPoolTransfer[]>('/payment-service/pool-transfers', undefined, session.accessToken) : []
          if(cancelled)return
          const withFees = await Promise.all(items.map(async item => {
            if (!pricingDetails(item.metadata).netEstimated || !['BOOKED','COMPLETED'].includes(item.status)) return item;
            try {
              const fees = await api<{actualRecipientAmountMinor?:number|null;actualFeeMinor?:number|null}>(`/payment-service/transactions/${item.id}/fees`,undefined,session.accessToken);
              if (Number.isSafeInteger(fees.actualRecipientAmountMinor) && Number.isSafeInteger(fees.actualFeeMinor))
                return {...item,recipientAmountMinor:fees.actualRecipientAmountMinor!,actualNetKnown:true,actualFeeMinor:fees.actualFeeMinor!};
            } catch { /* Keep the explicitly marked forecast when financial evidence is unavailable. */ }
            return item;
          }));
          if (cancelled) return;
          const own: Transaction[] = withFees.filter(item => !poolMetadata(item.metadata)).map(item => ({
            id: item.id, ...pricingDetails(item.metadata), netEstimated:pricingDetails(item.metadata).netEstimated && !item.actualNetKnown, actualFeeMinor:item.actualFeeMinor,
            date: item.createdAt || '',
            name: item.type === 'PAYOUT' ? 'Auszahlung auf dein Bankkonto' : item.type === 'REFUND' ? 'Erstattung' : 'Trinkgeld',
            note: item.description || item.reference || '',
            amount: (item.recipientAmountMinor ?? item.amountMinor) / 100,
            status: (item.status === 'BOOKED' || item.status === 'COMPLETED' ? 'Gebucht' : item.status === 'REFUNDED' ? 'Erstattet' : item.status === 'FAILED' || item.status === 'CANCELLED' ? 'Fehlgeschlagen' : 'Ausstehend') as Transaction['status'],
            type: (item.type === 'PAYOUT' ? 'Auszahlung' : item.type === 'REFUND' ? 'Erstattung' : 'Trinkgeld') as Transaction['type'],
            paymentMethod: item.paymentMethod, selectedMinor: item.type==='TIP'?item.amountMinor:undefined, netMinor: item.recipientAmountMinor ?? item.amountMinor, guestStatus: item.status,
          }))
          const receivedPoolTransfers = transfers.map(transfer => ({
            id: `pool-transfer-${transfer.id}`, date: transfer.createdAt, name: `Pool · ${transfer.poolName}`,
            note: transfer.settlementReason === 'WAITING_SOURCE_FUNDS' ? `Stripe-Mittel noch nicht verfügbar${transfer.fundsAvailableAt ? ` · voraussichtlich ${new Date(transfer.fundsAvailableAt).toLocaleDateString('de-DE')}` : ''}` : transfer.settlementReason === 'APPROVAL_REQUIRED' ? 'Bestehende Verteilung wartet auf ausdrückliche Freigabe' : transfer.settlementReason === 'STRIPE_INSUFFICIENT_FUNDS' ? 'Stripe hat den Transfer wegen fehlender Mittel abgelehnt; Gratilo prüft erneut' : transfer.settlementReason === 'WAITING_PLATFORM_BALANCE' ? 'Verteilung wartet auf ausreichendes Plattformguthaben' : transfer.settlementReason === 'RECIPIENT_NOT_READY' ? 'Transfer wartet auf Stripe-Freigabe des Empfängers' : transfer.reversedAmountMinor > 0 ? `Stripe-Transfer · ${money(transfer.reversedAmountMinor / 100)} rückabgewickelt` : 'Stripe-Transfer nach bestätigter Zahlung',
            amount: (transfer.amountMinor - transfer.reversedAmountMinor) / 100,
            status: transfer.status === 'REVERSED' ? 'Erstattet' as const : transfer.status === 'TRANSFERRED' ? 'Gebucht' as const : transfer.status === 'FAILED' ? 'Fehlgeschlagen' as const : 'Ausstehend' as const,
            type: transfer.status === 'REVERSED' ? 'Erstattung' as const : 'Trinkgeld' as const,
            paymentMethod: null, commissionMinor: transfer.platformFeeMinor, stripeEstimateMinor: transfer.estimatedStripeFeeMinor, feeCovered: transfer.feeCovered, netMinor: transfer.amountMinor - transfer.reversedAmountMinor, grossMinor: transfer.personalGrossAmountMinor, feeMinor: transfer.personalFeeAmountMinor, guestStatus: transfer.guestPaymentStatus, transferStatus: transfer.status, settlementReason:transfer.settlementReason, pool: { poolId: transfer.poolId || undefined, poolName: transfer.poolName, allocatedAmountMinor: transfer.amountMinor, sharePercent: transfer.historicalSharePercent, distributionAmountMinor: transfer.poolDistributionAmountMinor, selectedTipAmountMinor: transfer.selectedTipAmountMinor },
          }))
          setAccountTransactions([...own, ...receivedPoolTransfers].sort((a,b)=>Date.parse(b.date)-Date.parse(a.date)));setTransactionsError('');setTransactionsLoading(false)
        })
        .catch(() => { if (!cancelled) {setTransactionsError('Die Transaktionen konnten nicht vollständig geladen werden. Bitte erneut versuchen.');setTransactionsLoading(false)} })
    }
    loadTransactions()
    const timer = window.setInterval(loadTransactions, paymentsEnabled ? 15000 : 120000)
    window.addEventListener('focus', loadTransactions)
    return () => { cancelled = true; window.clearInterval(timer); window.removeEventListener('focus', loadTransactions) }
  }, [session.accessToken, transactionsVersion, tipPoolsEnabled])
  useEffect(() => {
    if (!tipPoolsEnabled) { setPool(null); setPoolMemberships([]); return }
    let current = true
    const refresh = () => {
      api<Pool | null>('/pools', undefined, session.accessToken).then(normalizePool).then(value => {
        if (!current) return
        setPool(previous => JSON.stringify(previous) === JSON.stringify(value) ? previous : value)
        setTeamLoadError(false);setPoolLoaded(true); setPoolName(previous => previous || value?.name || '')
      }).catch(() => { if (current) {setPoolLoaded(false);setTeamLoadError(true)} })
      api<Pool[]>('/pools/memberships', undefined, session.accessToken).then(items => {
        if (current) {setPoolMemberships(Array.isArray(items) ? items.map(item => normalizePool(item)!).filter(Boolean) : []);setMembershipsLoaded(true)}
      }).catch(() => { if (current) {setMembershipsLoaded(false);setTeamLoadError(true)} })
    }
    refresh()
    const timer = window.setInterval(refresh, 30000)
    window.addEventListener('focus', refresh)
    return () => { current = false; window.clearInterval(timer); window.removeEventListener('focus', refresh) }
  }, [session.accessToken, tipPoolsEnabled])
  useEffect(() => { if (pool) setPoolShareDrafts(Object.fromEntries(pool.members.map(member => [member.id, String(member.sharePercent)]))) }, [pool?.id, pool?.members.length])
  useEffect(() => { const timer = window.setInterval(() => { const remaining = Math.max(0, sessionExpiresAt - Date.now()); setSessionRemaining(remaining); if (remaining <= 0) { window.clearInterval(timer); onLogout(true) } }, 1000); return () => window.clearInterval(timer) }, [sessionExpiresAt, onLogout])
  useEffect(()=>{
    let current=true;setStripeBalance(null);setBalanceError('');setBalanceLoading(false)
    if(!paymentsEnabled||!testAccessReady)return
    const load=()=>{setBalanceLoading(true);api<StripeBalance>('/payment-service/connect-account/balance',undefined,session.accessToken).then(value=>{
      if(value.currency!=='EUR'||!Number.isSafeInteger(value.availableMinor)||!Number.isSafeInteger(value.pendingMinor)||!Number.isFinite(Date.parse(value.checkedAt)))throw Error('Invalid balance')
      if(current){setStripeBalance(value);setBalanceError('')}
    }).catch(()=>{if(current){setStripeBalance(null);setBalanceError('Dein persönliches Stripe-Guthaben konnte nicht geladen werden.')}}).finally(()=>{if(current)setBalanceLoading(false)})}
    load();window.addEventListener('focus',load)
    return()=>{current=false;window.removeEventListener('focus',load)}
  },[session.accessToken,testAccessReady])
  const onboardingReady = paymentsEnabled && testAccessReady && stripeDashboardStatus(connectedAccount).title === 'Vollständig aktiv'
  const qrActive = active && Boolean(onboardingReady)
  const activeMemberships = poolMemberships.filter(p=>p.members.some(m=>m.userId===session.userId&&m.active&&m.status==='ACTIVE'))
  const knownTeams=Array.from(new Map([...poolMemberships.filter(p=>p.members.some(m=>m.userId===session.userId&&m.status==='ACTIVE')),...(pool?[pool]:[])].map(p=>[p.id,p])).values())
  const teamConflict = new Set([...(pool?[pool.id]:[]),...activeMemberships.map(p=>p.id)]).size > 1
  const baseTeam = pool || knownTeams[0] || null
  const historicalTeams=Array.from(new Map(accountTransactions.filter(t=>t.pool?.poolId&&!knownTeams.some(p=>p.id===t.pool!.poolId)).map(t=>[t.pool!.poolId!,{id:t.pool!.poolId!,name:t.pool!.poolName}])).values())
  const teamChoices=[...knownTeams,...historicalTeams]
  const effectiveTeamId=selectedTeamId==='all'||selectedTeamId==='unknown'||teamChoices.some(p=>p.id===selectedTeamId)?selectedTeamId!:baseTeam?.id||'all'
  const currentTeam = knownTeams.find(p=>p.id===effectiveTeamId) || (effectiveTeamId==='all'?baseTeam:null)
  const historicalTeamSelected=effectiveTeamId!=='all'&&!currentTeam
  const selectedTeamName=teamChoices.find(p=>p.id===effectiveTeamId)?.name
  const ownMembership = currentTeam?.members.find(m=>m.userId===session.userId)
  const ownerReactivationRequired = ownMembership?.active === false && ownMembership.deactivationReason !== 'SELF'
  useEffect(()=>{let current=true;setTeamEligibility(false);if(currentTeam&&paymentsEnabled&&testAccessReady) api<{canReceiveTips:boolean}>(`/payment-service/tips/pools/${encodeURIComponent(currentTeam.ownerId)}/eligibility`,undefined,session.accessToken).then(value=>{if(current)setTeamEligibility(value.canReceiveTips===true)}).catch(()=>{if(current)setTeamEligibility(false)});return()=>{current=false}},[currentTeam?.ownerId,session.accessToken,testAccessReady,memberStripe])
  const requiredMembers = pool?.members.filter(member => member.active && member.status === 'ACTIVE' && member.sharePercent > 0) || []
  const poolPaymentReady = paymentsEnabled && testAccessReady && requiredMembers.length > 0 && requiredMembers.every(member => memberTransferReady(memberStripe[member.userId]))
  useEffect(() => {
    if (!pool || !tipPoolsEnabled) { setMemberStripe({}); return }
    let current = true
    const refresh = async () => {
      try {
        const statuses = await api<MemberStripeStatus[]>(`/payment-service/connect-account/pools/${encodeURIComponent(session.userId)}/members`, undefined, session.accessToken)
        if (current) setMemberStripe(Object.fromEntries(statuses.map(status => [status.userId, status])))
      } catch { if (current) setMemberStripe({}) }
    }
    setMemberStripe({}); void refresh()
    const timer = window.setInterval(refresh, 30000)
    window.addEventListener('focus', refresh)
    return () => { current = false; window.clearInterval(timer); window.removeEventListener('focus', refresh) }
  }, [session.accessToken, session.userId, pool, tipPoolsEnabled])
  async function persistOnboardingStatus(completed: boolean) { try { const updated = await api<Profile>('/users/profile', { firstName: profile.firstName, lastName: profile.lastName, organisation: profile.organisation, street: profile.street, city: profile.city, phone: profile.phone, country: profile.country, newsletter: profile.newsletter, profileImage, paymentEnabled: active, stripeOnboardingCompleted: completed }, session.accessToken, 'PUT'); onProfile(updated) } catch { /* Payment status remains authoritative if the profile sync is temporarily unavailable. */ } }
  function startOnboarding() { setError(''); setOnboardingOpen(true); void persistOnboardingStatus(false) }
  async function deleteAccount() { setDeletingAccount(true); try { await api('/users/account', undefined, session.accessToken, 'DELETE'); setDeleteDialog(false); onLogout(); } catch (cause) { setError(cause instanceof Error ? cause.message : 'Konto konnte nicht gelöscht werden.') } finally { setDeletingAccount(false) } }
  async function renamePool() { if (!pool) return; try { const updated = await api<Pool>(`/pools/${pool.id}`, { name: poolName.trim() }, session.accessToken, 'PUT'); setPool(normalizePool(updated)); setNotice('Poolname wurde gespeichert.') } catch (cause) { setError(cause instanceof Error ? cause.message : 'Poolname konnte nicht gespeichert werden.') } }
  async function saveTeamImage(image: string | null) {
    if (!pool) return;
    const updated = await api<Pool>(`/pools/${pool.id}/image`, { profileImage: image }, session.accessToken, 'PUT');
    setPool(normalizePool(updated));
    setNotice(image ? 'Team-Profilbild wurde gespeichert.' : 'Team-Profilbild wurde entfernt.');
  }
  async function createPool() { try { const created = await api<Pool>('/pools', { name: poolName.trim() }, session.accessToken); setPool(normalizePool(created)); setTeamLoadError(false);setPoolLoaded(true); setPoolName(created.name); setNotice('Pool wurde angelegt.') } catch (cause) { setError(cause instanceof Error ? cause.message : 'Pool konnte nicht angelegt werden.') } }
  async function inviteToPool() { if (!pool) return; if (inviteEmail.trim().toLowerCase() === session.email.toLowerCase()) { setError('Du kannst dich nicht selbst in deinen Pool einladen.'); return } try { const updated = await api<Pool>(`/pools/${pool.id}/members`, { email: inviteEmail.trim() }, session.accessToken); setPool(normalizePool(updated)); setInviteEmail(''); setNotice('Einladung wurde versendet. Den Anteil legst du beim Mitglied fest.') } catch (cause) { const detail = cause instanceof Error ? cause.message : 'Einladung konnte nicht versendet werden.'; if (detail.includes('Kein Benutzer mit dieser E-Mail-Adresse')) { setPoolInfoDialog(true); setError('') } else setError(detail) } }
  async function savePoolShares() {
    if (!pool) return;
    const parsed = pool.members.map(member => ({ memberId: member.id, basis: percentageBasisPoints(poolShareDrafts[member.id] ?? String(member.sharePercent)) }));
    if (parsed.some(value => value.basis === null)) { setError('Jedes Prozentgewicht muss zwischen 0 und 100 % liegen und darf höchstens zwei Nachkommastellen haben.'); return }
    if (!parsed.some((value,index) => pool.members[index].active && pool.members[index].status === 'ACTIVE' && value.basis! > 0)) { setError('Mindestens ein aktives Mitglied benötigt ein positives Prozentgewicht.'); return }
    try { const updated = await api<Pool>(`/pools/${pool.id}/shares`, { values: parsed.map(value => ({ memberId: value.memberId, sharePercent: value.basis! / 100 })) }, session.accessToken, 'PUT'); setPool(normalizePool(updated)); setNotice('Prozentgewichte gespeichert. Die effektiven Anteile wurden serverseitig berechnet.') }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Anteile konnten nicht gespeichert werden.') }
  }
  function distributePoolShares() {
    if (!pool) return;
    const eligible = pool.members.filter(member => member.active && member.status === 'ACTIVE').sort((a,b) => a.userId.localeCompare(b.userId));
    if (!eligible.length) return;
    const base = Math.floor(10000 / eligible.length), remainder = 10000 % eligible.length;
    const drafts: Record<string,string> = {};
    eligible.forEach((member,index) => { const basis = base + (index < remainder ? 1 : 0); drafts[member.id] = `${Math.floor(basis/100)}.${String(basis%100).padStart(2,'0')}` });
    setPoolShareDrafts(previous => ({...previous,...drafts})); setNotice('100,00 % wurden für aktive Mitglieder vorbereitet. Inaktive Gewichte bleiben erhalten. Bitte anschließend speichern.');
  }
  async function changePoolMember(member: PoolMember, activeValue: boolean) { if (!pool) return; try { setPool(normalizePool(await api<Pool>(`/pools/${pool.id}/members/${member.id}?active=${activeValue}`, undefined, session.accessToken, 'PUT'))) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Mitglied konnte nicht aktualisiert werden.') } }
  async function removePoolMember(member: PoolMember) { if (!pool || !window.confirm(`${member.name || member.email} wirklich aus dem Team entfernen? Bestehende Zahlungen bleiben unverändert.`)) return; try { setPool(normalizePool(await api<Pool>(`/pools/${pool.id}/members/${member.id}`, undefined, session.accessToken, 'DELETE' as any))) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Mitglied konnte nicht entfernt werden.') } }
  async function resendPoolMember(member: PoolMember) { if (!pool) return; try { setPool(normalizePool(await api<Pool>(`/pools/${pool.id}/members/${member.id}/resend`, {}, session.accessToken))); setNotice('Einladung wurde erneut versendet.') } catch (cause) { setError(cause instanceof Error ? cause.message : 'Einladung konnte nicht erneut versendet werden.') } }
  async function deletePool() { if (!pool || !window.confirm('Team wirklich löschen? Bestehende Zahlungsansprüche bleiben erhalten.')) return; try { await api(`/pools/${pool.id}`, undefined, session.accessToken, 'DELETE' as any); setPool(null); setPoolLoaded(true); setNotice('Pool wurde gelöscht.') } catch (cause) { setError(cause instanceof Error ? cause.message : 'Pool konnte nicht gelöscht werden.') } }
  async function syncOnboarding() { try { const account = await api<ConnectedAccount>('/payment-service/connect-account/sync', {}, session.accessToken); setConnectedAccount(account); const complete = stripeDashboardStatus(account).title === 'Vollständig aktiv'; await persistOnboardingStatus(complete); if (complete) { setOnboardingOpen(false); setNotice('Stripe Connect ist vollständig eingerichtet. Trinkgelder sind aktiv.') } else setNotice('Der Stripe-Status wurde aktualisiert. Prüfe die einzelnen Freigaben in der Statuskarte.') } catch (cause) { setError(cause instanceof Error ? cause.message : 'Stripe-Status konnte nicht geprüft werden.') } }
  const paymentLink = profile.paymentUrl || `${location.origin}/pay/${encodeURIComponent(session.userId)}`
  const nowForFilter = new Date()
  const periodStart = new Date(nowForFilter)
  periodStart.setHours(0, 0, 0, 0)
  if (period === '7 Tage') periodStart.setDate(periodStart.getDate() - 6)
  if (period === 'Diese Woche') { const day = periodStart.getDay() || 7; periodStart.setDate(periodStart.getDate() - day + 1) }
  if (period === 'Diesen Monat') periodStart.setDate(1)
  if (period === '30 Tage' || period === 'Letzte 30 Tage') periodStart.setDate(periodStart.getDate() - 29)
  const scopedTransactions = accountTransactions.filter(t=>t.type!=='Auszahlung'&&(view==='team'?Boolean(t.pool)&&(effectiveTeamId==='all'||(effectiveTeamId==='unknown'?!t.pool?.poolId:t.pool?.poolId===effectiveTeamId)):!t.pool))
  const rows = scopedTransactions.filter(t => (filter === 'Alle' || t.type === filter) && (period === 'Alle' || new Date(t.date.includes('T') ? t.date : t.date + 'T12:00:00') >= periodStart) && `${t.name} ${t.note} ${t.id}`.toLowerCase().includes(query.toLowerCase()))
  const pageCount = Math.max(1, Math.ceil(rows.length / pageSize))
  const currentPage = Math.min(page, pageCount)
  const visibleRows = rows.slice((currentPage - 1) * pageSize, currentPage * pageSize)
  const firstVisible = rows.length === 0 ? 0 : (currentPage - 1) * pageSize + 1
  const lastVisible = Math.min(currentPage * pageSize, rows.length)
  const booked = scopedTransactions.filter(t => t.status === 'Gebucht' && t.type === 'Trinkgeld')
  const now = new Date(); const todayStart = new Date(now);todayStart.setHours(0,0,0,0);const weekStart = new Date(todayStart);weekStart.setDate(now.getDate()-6);const monthStart=new Date(todayStart);monthStart.setDate(now.getDate()-29)
  const todayTotal = booked.filter(t => new Date(t.date) >= todayStart).reduce((sum, t) => sum + t.amount, 0)
  const weekTotal = booked.filter(t => new Date(t.date) >= weekStart).reduce((sum, t) => sum + t.amount, 0)
  const monthTotal = booked.filter(t=>t.type==='Trinkgeld'&&new Date(t.date)>=monthStart).reduce((sum,t)=>sum+t.amount,0)
  async function exportPdf() {
    try {
      const { jsPDF } = await import('jspdf')
      const doc = new jsPDF(); doc.setFontSize(22); doc.text('gratilo. | Kontoauszug', 18, 24)
      doc.setFontSize(10); doc.text('ECHTE TRANSAKTIONEN | Beträge in EUR', 18, 35)
      rows.forEach((t, i) => { const y = 53 + i * 23; doc.setFontSize(11); doc.text(`${transactionDate(t.date)}  |  ${t.name}`, 18, y); doc.text(t.amount.toFixed(2), 190, y, { align: 'right' }); doc.setFontSize(9); doc.text(`${t.id}  /  ${t.type}  /  ${t.status}`, 18, y + 7); doc.setDrawColor(220); doc.line(18, y + 12, 192, y + 12) })
      doc.save('gratilo-transaktionen-demo.pdf'); setNotice('PDF wurde erstellt.')
    } catch { setError('Der PDF-Export ist fehlgeschlagen. Bitte versuche es erneut.') }
  }
  async function togglePaymentPage() {
    const next = !active
    setActive(next)
    try {
      const updated = await api<Profile>('/users/profile', { firstName: profile.firstName, lastName: profile.lastName, organisation: profile.organisation, street: profile.street, city: profile.city, phone: profile.phone, country: profile.country, newsletter: profile.newsletter, profileImage, paymentEnabled: next }, session.accessToken, 'PUT')
      onProfile(updated)
      setNotice(next ? 'Deine Zahlungsseite ist wieder aktiv.' : 'Deine Zahlungsseite wurde deaktiviert.')
    } catch (cause) {
      setActive(!next)
      setError(cause instanceof Error ? cause.message : 'Der Status der Zahlungsseite konnte nicht geändert werden.')
    }
  }
  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSaving(true); setError('')
    const data = new FormData(event.currentTarget)
    try {
      const updated = await api<Profile>('/users/profile', { ...profile, profileImage, paymentEnabled: active, firstName: data.get('firstName'), lastName: data.get('lastName'), organisation: data.get('organisation'), phone: data.get('phone') }, session.accessToken, 'PUT')
      onProfile(updated); setEditing(false); setNotice('Dein Profil wurde gespeichert.')
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Speichern fehlgeschlagen.') }
    finally { setSaving(false) }
  }
  return <div className="account-app">
    {onboardingOpen && <div className="onboarding-page"><section className="onboarding-page-content" role="main" aria-labelledby="onboarding-title"><button className="onboarding-close" onClick={() => setOnboardingOpen(false)} aria-label="Onboarding schließen">×</button><span className="session-dialog-icon">✓</span><span className="step-label">STRIPE CONNECT</span><h2 id="onboarding-title">Auszahlungen einrichten</h2><StripeOnboarding session={session} onRedirect={redirectToStripe} /><button className="solid-button" onClick={syncOnboarding}>Status bei Stripe prüfen</button><button className="text-button" onClick={() => setOnboardingOpen(false)}>Später fortsetzen</button></section></div>}
    <header className="account-header"><a href="/account" className="brand account-brand" aria-label="Gratilo Kontoübersicht" onClick={e => { e.preventDefault(); setEditing(false) }}><img src="/gratilo-logo-light.svg" alt="Gratilo – Good people deserve more" width="340" height="152" /></a><nav aria-label="Kontonavigation"><a href="#overview" onClick={e=>{e.preventDefault();navigate('profile')}}>Meine Trinkgelder</a><a href="#team" onClick={e=>{e.preventDefault();navigate('team')}}>Team</a><a href="#profile" onClick={() => { setView('profile');setEditing(true); setError('') }}>Profil bearbeiten</a></nav><div className="account-user"><button className="logout" onClick={onLogout}>Abmelden <span aria-hidden="true">↗</span></button></div></header>
    <main className="account-main" id="overview"><div className="account-heading"><div className="account-welcome"><img className="account-smile" src="/gratilo-smile.svg" alt="" aria-hidden="true" width="80" height="80" /><div><span className="step-label">DEIN GRATILO DASHBOARD</span><h1>Hallo{profile.firstName ? `, ${profile.firstName}` : ''}<span className="hello-dot">.</span></h1><p>Viele kleine Danke. Alles an einem Ort.</p></div></div><div className="heading-actions"><span className="demo-badge">✧ Stripe-Testmodus</span><div className="session-status"><div className="session-status-line"><span>Sitzung aktiv</span><strong>{Math.floor(sessionRemaining / 60000)}:{String(Math.floor(sessionRemaining / 1000) % 60).padStart(2, '0')}</strong></div><div className="session-track"><span style={{ width: `${Math.min(100, sessionRemaining / 3600000 * 100)}%` }} /></div><small>Automatische Abmeldung bei Ablauf</small></div></div></div>
      {notice && <div className="message success" role="status">{notice}</div>}{error && <div className="message error" role="alert">{error}</div>}{poolInfoDialog && <div className="account-dialog-backdrop"><section className="account-dialog" role="dialog" aria-modal="true" aria-labelledby="pool-email-info-title"><h2 id="pool-email-info-title">Nutzer nicht gefunden</h2><p>Zu dieser E-Mail-Adresse gibt es noch kein Gratilo-Konto. Nur bereits registrierte Nutzer können in deinen Pool eingeladen werden.</p><div className="profile-actions"><button className="solid-button" type="button" onClick={() => setPoolInfoDialog(false)}>Verstanden</button></div></section></div>}
      {paymentsEnabled && <StripeTestEnrollment session={session} onChange={setTestAccessReady} />}
      <PersonalAccountPanel accessToken={session.accessToken} userId={session.userId} account={connectedAccount} enabled={paymentsEnabled&&testAccessReady} balance={stripeBalance} balanceLoading={balanceLoading} balanceError={balanceError} payouts={accountTransactions.filter(t=>t.type==='Auszahlung')} onStart={startOnboarding} onRefresh={syncOnboarding}/>
      <nav className="dashboard-tabs" aria-label="Dashboard-Bereiche"><button aria-current={view==='profile'?'page':undefined} className={view==='profile'?'active':''} onClick={()=>navigate('profile')}>Meine Trinkgelder</button><button aria-current={view==='team'?'page':undefined} className={view==='team'?'active':''} onClick={()=>navigate('team')}>Team</button></nav>
      {editing ? <section className="account-card profile-card" id="profile"><span className="step-label">DEINE DATEN</span><h2>Profil bearbeiten</h2><p className="muted">Hier bearbeitest du dein echtes Benutzerprofil.</p><form onSubmit={saveProfile}><fieldset disabled={saving}><div className="profile-image-picker"><div className="profile-image-preview">{profileImage ? <img src={profileImage} alt="Vorschau deines Profilbilds" /> : <span aria-hidden="true">♙</span>}</div><div><label htmlFor="edit-profile-image">Profilbild <span>(optional)</span></label><input id="edit-profile-image" type="file" accept="image/jpeg,image/png,image/webp" onChange={async event => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; setError(''); try { setProfileImage(await prepareProfileImage(file)) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Das Bild konnte nicht geladen werden.') } }} /><p className="hint">JPG, PNG oder WebP · bis 5 MB · quadratischer Ausschnitt</p>{profileImage && <button type="button" className="text-button" onClick={() => setProfileImage(null)}>Bild entfernen</button>}</div></div><div className="name-fields"><label>Vorname<input name="firstName" defaultValue={profile.firstName || ''} autoComplete="given-name" /></label><label>Nachname<input name="lastName" defaultValue={profile.lastName || ''} autoComplete="family-name" /></label></div><label>Betrieb<input name="organisation" defaultValue={profile.organisation || ''} autoComplete="organization" /></label><label>Telefon<input name="phone" type="tel" defaultValue={profile.phone || ''} autoComplete="tel" /></label><p className="muted">E-Mail: {profile.email}</p><div className="profile-actions"><button className="outline-button" type="button" onClick={() => { setProfileImage(profile.profileImage); setEditing(false) }}>Abbrechen</button><button className="solid-button" type="submit">{saving ? 'Wird gespeichert …' : 'Änderungen speichern'}</button></div></fieldset></form><section className="account-danger-zone"><h3>Gratilo-Konto schließen</h3><div className="account-info-box"><strong>Was passiert mit deinen Daten und Beträgen?</strong><ul><li>Dein Konto wird als gelöscht markiert und du wirst sofort abgemeldet.</li><li>Deine Zahlungsseite und dein QR-Code werden deaktiviert.</li><li>Offene Stripe-Zahlungen und Auszahlungen bleiben bei Stripe bestehen und werden nach deren Status abgewickelt. Gratilo verwaltet diese Gelder nicht.</li>{tipPoolsEnabled && <><li>Deine Mitgliedschaften in anderen Pools werden beendet. Die Poolinhaber werden per E-Mail informiert.</li><li>Dein eigener Pool wird geschlossen. Die Mitglieder werden darüber per E-Mail informiert.</li></>}</ul></div><button className="danger-button" type="button" onClick={() => setDeleteDialog(true)}>Konto endgültig schließen</button></section>{deleteDialog && <div className="account-dialog-backdrop"><section className="account-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-account-title"><h2 id="delete-account-title">Konto wirklich löschen?</h2><p>Dein Konto wird deaktiviert und kann nicht mehr verwendet werden. Offene Stripe-Vorgänge werden nicht von Gratilo gelöscht.</p><div className="profile-actions"><button className="outline-button" type="button" disabled={deletingAccount} onClick={() => setDeleteDialog(false)}>Abbrechen</button><button className="danger-button" type="button" disabled={deletingAccount} onClick={deleteAccount}>{deletingAccount ? 'Konto wird geschlossen …' : 'Ja, Konto schließen'}</button></div></section></div>}</section> : <>
      <section className="dashboard-section-heading"><div><span className="step-label">{view==='profile'?'PERSÖNLICHE TRINKGELDER':'DEIN ANTEIL AM TEAM-TRINKGELD'}</span><h2>{view==='profile'?'Meine Trinkgelder':selectedTeamName?`Team · ${selectedTeamName}`:'Team'}</h2><p className="muted">{view==='profile'?'Deine persönlichen Einnahmen und dein öffentlicher Zahlungslink.':'Deine Team-Einnahmen bleiben auch nach einem Teamwechsel nachvollziehbar.'}</p></div>{view==='profile'&&<button className="outline-button" onClick={()=>setEditing(true)}>Profil bearbeiten</button>}</section>
      {view==='team'&&(teamChoices.length>0||accountTransactions.some(t=>t.pool))&&<div className="team-view-select"><label htmlFor="dashboard-team">Teamansicht und Historie</label><select id="dashboard-team" value={effectiveTeamId} onChange={event=>{setSelectedTeamId(event.target.value);setPage(1);setSelectedTransaction(null)}}><option value="all">Alle meine Teamanteile</option>{teamChoices.map(team=><option key={team.id} value={team.id}>{team.name}{historicalTeams.some(p=>p.id===team.id)?' · frühere Zahlungen':''}</option>)}{accountTransactions.some(t=>t.pool&&!t.pool.poolId)&&<option value="unknown">Historische Anteile ohne Team-ID</option>}</select><small>Die Auswahl verändert weder dein persönliches Stripe-Konto noch bestehende Mitgliedschaften.</small></div>}
      <div className="income-grid" aria-label={view==='profile'?'Persönliche Einnahmen':'Team-Einnahmen'}>{[['Heute erhalten',todayTotal],['Letzte 7 Tage',weekTotal],['Letzte 30 Tage',monthTotal]].map(([label,total])=><section className="account-card income-stat" key={label}><span>{label}</span><strong>{transactionsLoading?'…':transactionsError?'—':money(Number(total))}</strong><small>Erfolgreich zugeordnet</small></section>)}<section className="account-card income-stat"><span>{view==='profile'?'Persönliche Transaktionen':'Team-Transaktionen'}</span><strong>{transactionsLoading?'…':transactionsError?'—':scopedTransactions.filter(t=>t.type==='Trinkgeld').length}</strong><small>{scopedTransactions.filter(t=>t.status==='Ausstehend').length} noch ausstehend</small></section></div>
      {view==='profile'&&<div className="profile-overview"><section className="account-card profile-identity">{profile.profileImage?<img className="team-avatar" src={profile.profileImage} alt="Persönliches Profilbild"/>:<span className="team-avatar team-avatar-default" aria-hidden="true">♙</span>}<div><h3>{[profile.firstName,profile.lastName].filter(Boolean).join(' ')||'Dein Profil'}</h3><p>{profile.organisation}</p><button className="text-button" onClick={()=>setEditing(true)}>Persönliche Profileinstellungen</button></div></section>
      <aside className="account-card qr-panel"><div className="panel-title"><h2>Dein Danke-Code</h2><span className={`status-pill ${qrActive ? 'booked' : 'inactive'}`}>{qrActive ? '● Aktiv' : '○ Inaktiv'}</span></div><p>Scannen. Danke sagen. Freude machen.</p><div className={`qr-layout ${!qrActive ? 'qr-disabled' : ''}`}>{profile.profileImage && <div className="dashboard-profile-image"><img src={profile.profileImage} alt="Dein Profilbild" /></div>}<div className="qr-frame">{qrActive ? <QRCodeSVG value={paymentLink} size={132} marginSize={2} level="M" title="QR-Code zur Test-Trinkgeldseite" /> : <div className="paused-qr">Ⅱ<span>Code pausiert</span></div>}</div><div><strong>Ein kleines Danke<br />ist nur einen Scan entfernt.</strong><span>Zeige deinen Code oder teile deinen persönlichen Link.</span></div></div><label className="payment-link-label" htmlFor="payment-link">Dein persönlicher Link</label><div className="copy-field"><input id="payment-link" value={paymentLink} readOnly disabled={!qrActive} /><button disabled={!qrActive} aria-label="Zahlungslink kopieren" onClick={async () => { try { await navigator.clipboard.writeText(paymentLink); setNotice('Zahlungslink kopiert.') } catch { setError('Bitte markiere den Link und kopiere ihn manuell.') } }}>Kopieren</button></div><button className="outline-button qr-toggle" disabled={!paymentsEnabled || !testAccessReady || !onboardingReady} onClick={togglePaymentPage}>{qrActive ? 'Ⅱ  Deaktivieren' : '▷  Aktivieren'}</button><div className="qr-extra-actions"><button className="text-button" disabled={!qrActive} onClick={async()=>{try{if(navigator.share)await navigator.share({title:'Mein Gratilo-Trinkgeldlink',url:paymentLink});else{await navigator.clipboard.writeText(paymentLink);setNotice('Zahlungslink kopiert.')}}catch{setError('Der Link konnte nicht geteilt werden.')}}}>Link teilen</button><button className="text-button" disabled={!qrActive} onClick={()=>downloadQr('QR-Code zur Test-Trinkgeldseite','gratilo-persoenlicher-qr.svg')}>QR-Code herunterladen</button></div><p className="qr-footnote">{qrActive ? 'Deine Zahlungsseite ist für Trinkgelder geöffnet.' : 'Deine Zahlungsseite ist derzeit deaktiviert.'}</p></aside></div>}
      {view==='team'&&<>
      {teamConflict&&<div className="message error" role="alert">Für dein Konto bestehen mehrere aktive Team-Zuordnungen. Bitte lass diese prüfen. Es wurden keine Mitgliedschaften oder Zahlungsansprüche verändert.</div>}
      {!tipPoolsEnabled?<section className="account-card team-empty"><h3>Team-Trinkgelder sind derzeit deaktiviert.</h3></section>:!poolLoaded||!membershipsLoaded?<section className="account-card team-empty" role={teamLoadError?'alert':'status'}>{teamLoadError?'Dein Team konnte nicht vollständig geladen werden.':'Team wird geladen …'}{teamLoadError&&<button className="text-button" onClick={()=>window.dispatchEvent(new Event('focus'))}>Team erneut laden</button>}</section>:historicalTeamSelected?<section className="account-card team-empty"><h3>{selectedTeamName||'Historische Teamanteile'}</h3><p>Diese Ansicht zeigt ausschließlich deine gespeicherten Zahlungsanteile. Aktuelle Mitgliedschaft, QR-Code und Verwaltungsrechte werden daraus nicht abgeleitet.</p></section>:!currentTeam?<section className="account-card team-empty"><span className="team-avatar team-avatar-default" aria-hidden="true">♧</span><h3>Du bist noch keinem Team zugeordnet.</h3><p>Ein gemeinsamer Zahlungslink. Klare Anteile. Ein Danke für alle.</p><label htmlFor="new-team-name">Teamname</label><input id="new-team-name" aria-label="Poolname" placeholder="Zum Beispiel Café Sonnenseite" value={poolName} maxLength={120} onChange={e=>setPoolName(e.target.value)}/><button className="solid-button" disabled={!poolName.trim()} onClick={createPool}>Team erstellen</button><p className="muted">Einladungen erhältst du per E-Mail und kannst sie hier oder über den Einladungslink annehmen.</p></section>:<section className="account-card team-overview"><div className="team-identity"><TeamAvatar image={currentTeam.profileImage} name={currentTeam.name}/><div><h3>{currentTeam.name}</h3><span className="status-pill">{ownMembership?.deactivationReason==='OWNER'?'Vom Besitzer deaktiviert':ownMembership?.active?'Aktiv':'Inaktiv'}</span><p>{ownMembership?.effectiveSharePercent ?? 0} % effektiver Anteil</p><span className={`status-pill ${teamEligibility?'booked':'pending'}`}>{teamEligibility?'Team zahlungsbereit':'Noch nicht zahlungsbereit'}</span></div></div><div className="pool-team-qr">{teamEligibility?<QRCodeSVG value={(currentTeam.paymentUrl || `${location.origin}/pay/team/${encodeURIComponent(currentTeam.ownerId)}`)} size={112} title="Team-Trinkgeld QR-Code"/>:<p>Der Team-Code ist pausiert, bis alle vorgesehenen Mitglieder bereit sind.</p>}<div className="copy-field"><input aria-label="Team-Trinkgeldlink" value={(currentTeam.paymentUrl || `${location.origin}/pay/team/${encodeURIComponent(currentTeam.ownerId)}`)} readOnly/><button className="outline-button" onClick={async()=>{try{await navigator.clipboard.writeText((currentTeam.paymentUrl || `${location.origin}/pay/team/${encodeURIComponent(currentTeam.ownerId)}`));setNotice('Team-Link kopiert.')}catch{setError('Bitte kopiere den Link manuell.')}}}>Team-Link kopieren</button></div><button className="text-button" disabled={!teamEligibility} onClick={()=>downloadQr('Team-Trinkgeld QR-Code','gratilo-team-qr.svg')}>Team-QR herunterladen</button></div>{!pool&&<div className="team-member-actions">{ownerReactivationRequired&&<p role="status">{ownMembership?.deactivationReason==='OWNER'?'Du wurdest vom Pool-Besitzer deaktiviert. ':'Deine Mitgliedschaft ist deaktiviert. '}Nur der Pool-Besitzer kann dich wieder aktivieren.</p>}{ownMembership?.status==='ACTIVE'&&!ownerReactivationRequired&&<button className="outline-button" onClick={async()=>{if(!ownMembership)return;try{const updated=await api<Pool>(`/pools/memberships/${ownMembership.id}?active=${!ownMembership.active}`,undefined,session.accessToken,'PUT');setPoolMemberships(items=>items.map(item=>item.id===updated.id?normalizePool(updated)!:item))}catch(cause){setError(cause instanceof Error?cause.message:'Mitgliedschaft konnte nicht geändert werden.')}}}>{ownMembership?.active?'Mitgliedschaft pausieren':'Mitgliedschaft reaktivieren'}</button>}<button className="text-button" onClick={async()=>{if(!ownMembership||!window.confirm('Team wirklich verlassen? Bestehende Zahlungsansprüche bleiben erhalten.'))return;try{await api(`/pools/memberships/${ownMembership.id}`,undefined,session.accessToken,'DELETE');setPoolMemberships(items=>items.filter(item=>item.id!==currentTeam.id))}catch(cause){setError(cause instanceof Error?cause.message:'Austritt nicht möglich.')}}}>Team verlassen</button></div>}</section>}
{tipPoolsEnabled && view==='team' && pool && currentTeam?.id===pool.id && <details className="account-card pool-panel team-admin"><summary>Team-Einstellungen und Mitglieder verwalten</summary><section><div className="transactions-heading"><div><h2>Team verwalten</h2><p>Alle aktiven Mitglieder mit Trinkgeldanteil müssen für Stripe-Transfers freigegeben sein.</p></div>{pool && <button className="text-button pool-delete" onClick={deletePool}>Pool löschen</button>}</div>{pool && <><TeamImageEditor key={pool.id} image={pool.profileImage} name={pool.name} onSave={saveTeamImage} showPresets={false}/><div className="pool-edit-name"><label htmlFor="pool-name-edit">Poolname bearbeiten</label><input id="pool-name-edit" value={poolName} maxLength={120} onChange={event => setPoolName(event.target.value)} /><button className="outline-button" disabled={!poolName.trim() || poolName.trim() === pool.name} onClick={renamePool}>Speichern</button></div></>}{!pool ? <div className="pool-create"><div><strong>Gemeinsam Trinkgelder verteilen</strong><p className="muted">Lege einen Pool an und lade Personen per E-Mail ein. Du kannst die Anteile später ändern.</p></div><input aria-label="Poolname" placeholder="Name des Pools" value={poolName} onChange={e => setPoolName(e.target.value)} /><button className="solid-button" disabled={!poolName.trim()} onClick={createPool}>Pool anlegen</button></div> : <><div className="pool-created"><div><span className={`status-pill ${poolPaymentReady ? 'booked' : 'pending'}`}>{poolPaymentReady ? '● Pool zahlungsbereit' : 'Noch nicht zahlungsbereit'}</span><h3>{pool.name}</h3><p className="muted">{pool.members.length} Mitglieder · {pool.members.filter(member => member.status === 'ACTIVE' && member.active).reduce((sum, member) => sum + (member.effectiveSharePercent ?? 0), 0).toFixed(2).replace('.', ',')} % effektiv verteilt</p></div><span className="pool-share-total">{pool.members.filter(member => member.status === 'ACTIVE' && member.active).reduce((sum, member) => sum + (member.effectiveSharePercent ?? 0), 0).toFixed(2).replace('.', ',')} %</span></div><div className="pool-invite"><div className="pool-invite-copy"><strong>Person einladen</strong><small>Die Person erhält eine E-Mail und muss die Einladung bestätigen.</small></div><input aria-label="E-Mail des Mitglieds" type="email" placeholder="E-Mail-Adresse des Nutzers" value={inviteEmail} aria-invalid={inviteEmail.trim().toLowerCase() === session.email.toLowerCase()} onChange={e => setInviteEmail(e.target.value)} /><button className="solid-button" disabled={!inviteEmail.trim() || inviteEmail.trim().toLowerCase() === session.email.toLowerCase()} onClick={inviteToPool}>Einladen</button></div><PoolMembersPanel members={pool.members} ownerId={pool.ownerId} stripe={memberStripe} drafts={poolShareDrafts} onDraft={(id,value)=>setPoolShareDrafts(drafts=>({...drafts,[id]:value}))} onToggle={changePoolMember} onRemove={removePoolMember} onResend={resendPoolMember} onDistribute={distributePoolShares} onSave={savePoolShares} /></>}</section></details>}
      {tipPoolsEnabled&&poolMemberships.filter(p=>p.members.some(m=>m.userId===session.userId&&m.status==='INVITED')).map(invitation=>{const member=invitation.members.find(m=>m.userId===session.userId)!;return <section className="account-card team-invitation" key={invitation.id}><h3>Einladung: {invitation.name}</h3><p>Du kannst höchstens einem aktiven Team angehören.</p><button className="solid-button" disabled={Boolean(pool)||activeMemberships.length>0||!member.invitationToken} onClick={()=>location.assign(`/pool/invitations/${encodeURIComponent(member.invitationToken||'')}`)}>Einladung ansehen</button></section>})}
      </>}
      <section className="account-card transactions-panel"><div className="transactions-heading"><div><h2>{view==='profile'?'Persönliche Transaktionen':'Team-Transaktionen'}</h2><p>Jedes Danke auf einen Blick. Beträge mit „geschätzt“ sind Netto-Prognosen und kein verfügbares Guthaben.</p></div><div className="export-actions"><button onClick={exportPdf}>↓ PDF exportieren</button><button onClick={() => { download(new Blob([csvContent(rows)], { type: 'text/csv;charset=utf-8;' }), 'gratilo-transaktionen.csv'); setNotice('CSV wurde erstellt.') }}>↓ CSV exportieren</button></div></div><div className="transaction-tools"><div className="filter-tabs" role="group" aria-label="Transaktionen filtern">{['Alle', 'Trinkgeld', 'Erstattung'].map(value => <button key={value} aria-pressed={filter === value} className={filter === value ? 'active' : ''} onClick={() => { setFilter(value); setPage(1) }}>{value === 'Trinkgeld' ? 'Eingänge' : value === 'Erstattung' ? 'Erstattungen' : 'Alle'}</button>)}</div><div className="period-tabs" role="group" aria-label="Zeitraum filtern">{['Heute', '7 Tage', '30 Tage', 'Alle'].map(value => <button key={value} aria-pressed={period === value} className={period === value ? 'active' : ''} onClick={() => { setPeriod(value); setPage(1) }}>{value}</button>)}</div><input type="search" aria-label="Transaktionen suchen" placeholder="Name oder Referenz suchen …" value={query} onChange={e => { setQuery(e.target.value); setPage(1) }} /><label className="page-size-label">Anzeigen<select aria-label="Anzahl Transaktionen pro Seite" value={pageSize} onChange={e => { setPageSize(Number(e.target.value)); setPage(1) }}><option value="50">50</option><option value="100">100</option><option value="200">200</option></select></label></div><div className="table-scroll"><table><thead><tr><th>Transaktion</th><th>Datum</th><th>Status</th><th className="amount-col">Betrag</th></tr></thead><tbody>{visibleRows.map(t => <tr key={t.id}><td><div className="transaction-person"><span className={`transaction-icon ${t.amount < 0 ? 'outgoing' : ''}`}>{t.amount < 0 ? '↗' : '↙'}</span><div><button className="transaction-open" onClick={event=>{transactionTrigger.current=event.currentTarget;setSelectedTransaction(t)}} aria-label={`Transaktion ${t.id} ansehen`}>{t.name} <span aria-hidden="true">↗</span></button><small>{t.note}</small>{t.pool && <><span className="pool-transaction-meta" title="Anteil und Verteilungsbetrag zum Zahlungszeitpunkt">{historicalPoolSummary(t.pool)}</span>{t.pool.members && <div className="pool-transaction-breakdown">{t.pool.members.map((member, index) => <span key={`${t.id}-${index}`}>{member.name || member.email || 'Mitglied'} · {historicalShareLabel(member.sharePercent)} · {member.amountMinor != null ? money(member.amountMinor / 100) : '—'}</span>)}</div>}</>}{t.amount > 0 && <span className="payment-method"><span className="payment-method-icon" aria-hidden="true">{paymentMethodIcon(t.paymentMethod)}</span>{paymentMethodLabel(t.paymentMethod)}</span>}</div></div></td><td className="date-cell">{transactionDate(t.date)}</td><td><span className={`status-pill ${t.status === 'Gebucht' ? 'booked' : 'pending'}`}>{t.pool&&t.status==='Ausstehend'?transferLabel(t):t.status}</span></td><td className={`amount-col ${t.amount > 0 ? 'positive' : ''}`}>{t.amount > 0 ? '+' : '−'} {money(Math.abs(t.amount))}{t.netEstimated?' (geschätzt)':''}</td></tr>)}</tbody></table>{transactionsLoading&&<p className="empty-transactions" role="status">Transaktionen werden geladen …</p>}{transactionsError&&<div className="message error" role="alert">{transactionsError}<button className="text-button" onClick={()=>{setTransactionsLoading(true);setTransactionsVersion(v=>v+1)}}>Erneut laden</button></div>}{!transactionsLoading&&!transactionsError&&rows.length === 0 && <div className="empty-transactions">Keine passenden Transaktionen gefunden.<button className="text-button" onClick={() => { setQuery(''); setFilter('Alle'); setPage(1) }}>Filter zurücksetzen</button></div>}</div>{pageCount > 1 && <nav className="pagination" aria-label="Transaktionsseiten">{Array.from({ length: pageCount }, (_, index) => index + 1).map(number => <button key={number} className={number === currentPage ? "active" : ""} aria-current={number === currentPage ? "page" : undefined} onClick={() => setPage(number)}>{number}</button>)}</nav>}<div className="table-footer"><span>{firstVisible}–{lastVisible} von {rows.length} Transaktionen angezeigt</span><span>Exporte enthalten die gefilterte Ansicht.</span></div></section><p className="demo-note">Aktuelle Daten aus deinem Gratilo-Konto.</p></>}
      {selectedTransaction&&<div className="account-dialog-backdrop" onClick={closeTransaction}><section className="account-dialog transaction-detail" role="dialog" aria-modal="true" aria-labelledby="transaction-detail-title" tabIndex={-1} onClick={e=>e.stopPropagation()} onKeyDown={e=>{if(e.key==='Escape')closeTransaction();if(e.key==='Tab'){const nodes=Array.from(e.currentTarget.querySelectorAll<HTMLElement>('button,a,input'));const first=nodes[0],last=nodes[nodes.length-1];if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus()}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus()}}}}><button autoFocus className="text-button" onClick={closeTransaction}>Details schließen ×</button><h2 id="transaction-detail-title">{selectedTransaction.type==='Erstattung'?'Erstattung':selectedTransaction.pool?'Dein Team-Trinkgeld':'Dein persönliches Trinkgeld'}</h2><strong className="detail-amount">{money(selectedTransaction.amount)}</strong><dl><dt>Datum und Uhrzeit</dt><dd>{transactionDate(selectedTransaction.date)}</dd><dt>Referenz</dt><dd>{selectedTransaction.id}</dd>{selectedTransaction.pool?<><dt>Ursprüngliches Gesamttrinkgeld</dt><dd>{validHistoricalAmount(selectedTransaction.pool.selectedTipAmountMinor)?money(selectedTransaction.pool.selectedTipAmountMinor/100):'Nicht verfügbar'}</dd><dt>Historischer persönlicher Anteil</dt><dd>{historicalShareLabel(selectedTransaction.pool.sharePercent)}</dd><dt>Einmalige Gratilo-Provision des Pools</dt><dd>{selectedTransaction.commissionMinor!=null?money(selectedTransaction.commissionMinor/100):'Historisch nicht ausgewiesen'}</dd><dt>Stripe-Kostenschätzung der Gesamtzahlung</dt><dd>{selectedTransaction.stripeEstimateMinor!=null?money(selectedTransaction.stripeEstimateMinor/100):'Historisch nicht ausgewiesen'}</dd><dt>Pool-Verteilungsbetrag</dt><dd>{validHistoricalAmount(selectedTransaction.pool.distributionAmountMinor)?money(selectedTransaction.pool.distributionAmountMinor/100):'Nicht verfügbar'}</dd><dt>Persönlicher Bruttoanteil</dt><dd>{selectedTransaction.grossMinor!=null?money(selectedTransaction.grossMinor/100):'Nicht verfügbar'}</dd><dt>Zugeordnete Gesamtabzüge (Provision und geschätzte Stripe-Kosten)</dt><dd>{selectedTransaction.feeMinor!=null?money(selectedTransaction.feeMinor/100):'Nicht verfügbar'}</dd></>:<><dt>Ausgewähltes Trinkgeld</dt><dd>{selectedTransaction.selectedMinor!=null?money(selectedTransaction.selectedMinor/100):'Nicht verfügbar'}</dd><dt>Gratilo-Provision</dt><dd>{selectedTransaction.commissionMinor!=null?money(selectedTransaction.commissionMinor/100):'Historisch nicht separat ausgewiesen'}</dd><dt>{selectedTransaction.actualFeeMinor!=null?'Tatsächliche Stripe-Zahlungsgebühren':'Geschätzter Stripe-Kostenabzug'}</dt><dd>{selectedTransaction.actualFeeMinor!=null?money(selectedTransaction.actualFeeMinor/100):selectedTransaction.stripeEstimateMinor!=null?money((!selectedTransaction.stripeCollected && selectedTransaction.feeCovered?0:selectedTransaction.stripeEstimateMinor)/100):'Historisch nicht separat ausgewiesen'}</dd></>}<dt>{selectedTransaction.netEstimated?'Voraussichtlicher persönlicher Nettobetrag':'Persönlich zugeordneter Nettobetrag'}</dt><dd>{selectedTransaction.netMinor!=null?money(selectedTransaction.netMinor/100):'Nicht verfügbar'}</dd><dt>Gastzahlungsstatus</dt><dd>{guestStatusLabel(selectedTransaction.guestStatus)}</dd>{selectedTransaction.pool&&<><dt>Eigener Stripe-Transferstatus</dt><dd>{transferLabel(selectedTransaction)}</dd></>}</dl><p className="muted">{selectedTransaction.note}</p>{selectedTransaction.netEstimated&&<p className="muted">Netto-Prognose: Stripe zieht die tatsächlichen Zahlungsgebühren auf deinem Konto ein. Die Istkosten werden separat abgeglichen; die Prognose ist keine feste Auszahlung.</p>}<p className="muted">Die Beträge stammen aus dieser Zahlung. Aktuelle Team-Anteile verändern keine historischen Ansprüche. Tatsächliche Stripe-Gebühren können von der Schätzung abweichen.</p></section></div>}
      <footer className="account-footer"><span>© {new Date().getFullYear()} Gratilo</span><span>Mit einem Danke fängt es an.</span></footer>
    </main></div>
}
export function DemoPayment({ session }: { session?: Session | null }) {
  return paymentsEnabled
    ? <PublicTipCheckout session={session} />
    : <main className="tip-page"><section className="tip-card"><h1>Zahlungen sind derzeit deaktiviert.</h1><p>Aktuell sind keine Zahlungen oder Auszahlungen möglich.</p></section></main>
}

export function PoolInvitation({ session, onSwitchUser }: { session: Session; onSwitchUser: () => void }) {
  const token = location.pathname.split('/').pop() || ''
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  async function respond(action: 'accept' | 'decline') { setBusy(true); try { await api(`/pools/invitations/${encodeURIComponent(token)}/${action}`, {}, session.accessToken); setMessage(action === 'accept' ? 'Die Einladung wurde angenommen.' : 'Die Einladung wurde abgelehnt.'); window.setTimeout(() => window.location.assign('/account'), 700) } catch (cause) { const detail = cause instanceof Error ? cause.message : 'Die Einladung konnte nicht verarbeitet werden.'; setMessage(detail.includes('anderen Benutzer') ? 'Bitte melde dich mit dem Konto an, an dessen E-Mail-Adresse die Einladung gesendet wurde.' : detail) } finally { setBusy(false) } }
  const wrongAccount = message.startsWith('Bitte melde dich')
  return <main className="tip-page"><div className="tip-brand"><span className="brand-mark">g</span>gratilo<span className="brand-dot">.</span></div><section className="tip-card"><span className="step-label">TRINKGELD-POOL</span><h1>Du wurdest eingeladen.</h1><p className="tip-intro">Prüfe die Einladung und entscheide, ob du dem Pool beitreten möchtest.</p>{message && <div className={wrongAccount ? 'message error' : 'message success'} role="status">{message}</div>}{wrongAccount ? <button className="solid-button" onClick={onSwitchUser}>Abmelden und anderes Konto anmelden</button> : !message && <div className="pool-invitation-actions"><button className="solid-button" disabled={busy} onClick={() => respond('accept')}>Einladung annehmen</button><button className="outline-button" disabled={busy} onClick={() => respond('decline')}>Ablehnen</button></div>}</section></main>
}

export function PoolInvitationLogin({ onLogin, onRegister }: { onLogin: () => void; onRegister: () => void }) {
  return <main className="tip-page"><div className="tip-brand"><span className="brand-mark">g</span>gratilo<span className="brand-dot">.</span></div><section className="tip-card"><span className="step-label">TRINKGELD-POOL</span><h1>Du wurdest eingeladen.</h1><p className="tip-intro">Melde dich mit der E-Mail-Adresse aus der Einladung an. Danach kannst du die Einladung annehmen oder ablehnen.</p><div className="pool-invitation-actions"><button className="solid-button" onClick={onLogin}>Jetzt anmelden</button><button className="outline-button" onClick={onRegister}>Konto erstellen</button></div></section></main>
}
