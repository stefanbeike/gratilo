import { cleanup, render, screen, within, fireEvent } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: false }))
vi.mock('./features', () => ({ useTipPoolsEnabled: () => true }))
import { Dashboard } from './Dashboard'

const session = { userId: 'member', accessToken: 'token', email: 'member@example.test' }
afterEach(() => { cleanup(); vi.unstubAllGlobals();window.history.replaceState({},'','/account') })
function install(historical: unknown = 50, distribution: number | null | undefined = 2500, selected = 2500) {
  let currentShare = 50
  const transfer = { id: 'allocation', transactionId: 'payment', poolName: 'testpool', amountMinor: 1250, reversedAmountMinor: 0, status: 'TRANSFERRED', createdAt: '2026-10-20T13:42:31Z', historicalSharePercent: historical, poolDistributionAmountMinor: distribution as number | null | undefined, selectedTipAmountMinor: selected }
  const fetch = vi.fn(async (url: string, options?: RequestInit) => ({ ok: true, json: async () => {
    if (url === '/payment-service/transactions') return []
    if (url === '/payment-service/pool-transfers') return [transfer]
    if (url === '/user-service/pools/memberships') return [{ id: 'pool', name: 'testpool', ownerId: 'owner', members: [{ id: 'membership', userId: session.userId, name: 'Alex', email: session.email, sharePercent: currentShare, effectiveSharePercent: currentShare, status: 'ACTIVE', active: true }] }]
    if (url.startsWith('/user-service/pools/memberships/membership?')) {
      currentShare = 70
      return { id: 'pool', name: 'testpool', ownerId: 'owner', members: [{ id: 'membership', userId: session.userId, name: 'Alex', email: session.email, sharePercent: currentShare, effectiveSharePercent: currentShare, status: 'ACTIVE', active: false }] }
    }
    if (url === '/user-service/pools') return null
    return []
  } }))
  vi.stubGlobal('fetch', fetch)
  render(<Dashboard session={session} profile={{ email: session.email, firstName: 'Alex' }} onProfile={vi.fn()} onLogout={vi.fn()} />)
  fireEvent.click(screen.getByRole('button',{name:'Mein Team'}))
  return { transfer, fetch }
}
it('shows the frozen fifty-percent share and the whole distribution rather than the personal transfer', async () => {
  install()
  const history = await screen.findByText(/50 % eigener Anteil/)
  expect(history).toHaveTextContent(/Pool-Verteilungsbetrag 25,00/)
  const row = history.closest('tr')!
  expect(within(row).getByText(/12,50/)).toBeInTheDocument()
  expect(within(row).getByText('Gebucht')).toBeInTheDocument()
  expect(screen.getByText('50 % effektiver Anteil')).toBeInTheDocument()
  expect(screen.queryByText(/0% eigener Anteil/)).not.toBeInTheDocument()
  expect(screen.queryByText(/von 12,50/)).not.toBeInTheDocument()
})
it('keeps the historical share after changing the current membership and distribution key', async () => {
  const f = install(30); f.transfer.amountMinor = 750
  await screen.findByText(/30 % eigener Anteil/)
  fireEvent.click(screen.getByRole('button', { name: 'Mitgliedschaft pausieren' }))
  await screen.findByText('70 % effektiver Anteil');expect(screen.getByText('Inaktiv')).toBeInTheDocument()
  expect(screen.getByText(/30 % eigener Anteil/)).toHaveTextContent(/Pool-Verteilungsbetrag 25,00/)
})
for (const missing of [null, undefined, 0, '50', NaN, Infinity, -1, 101]) it(`does not turn missing or invalid historical percentages (${String(missing)}) into zero`, async () => {
  // Explicitly remove the field as an older server would; no fallback to the current 50% membership.
  const f = install(missing)
  f.transfer.historicalSharePercent = missing
  expect(await screen.findByText(/Historischer Anteil nicht verfügbar/)).toHaveTextContent(/Pool-Verteilungsbetrag 25,00/)
  expect(screen.queryByText(/(?:0|50)\s*% eigener Anteil/)).not.toBeInTheDocument()
})
it('distinguishes the historical net distribution from the guest-selected amount', async () => {
  const f = install(50, 2396, 2500); f.transfer.amountMinor = 1198
  const history = await screen.findByText(/50 % eigener Anteil/)
  expect(history).toHaveTextContent(/Pool-Verteilungsbetrag 23,96/)
  expect(history).toHaveTextContent(/Gewähltes Trinkgeld 25,00/)
  expect(within(history.closest('tr')!).getByText(/11,98/)).toBeInTheDocument()
})
it('does not label the personal transfer as the total when the server omits the historical budget', async () => {
  const f = install(null, null); f.transfer.poolDistributionAmountMinor = undefined
  expect(await screen.findByText(/Historischer Anteil nicht verfügbar/)).toHaveTextContent('Pool-Verteilungsbetrag nicht verfügbar')
  expect(screen.queryByText(/Pool-Verteilungsbetrag 12,50/)).not.toBeInTheDocument()
})
it('formats fractional historical percentages as percentages without multiplying them again', async () => {
  install(33.3333)
  expect(await screen.findByText(/33,3333 % eigener Anteil/)).toBeInTheDocument()
})
