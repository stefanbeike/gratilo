import { cleanup, fireEvent, render, screen, waitFor, within, act } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./stripe', () => ({ paymentsEnabled: true }))
vi.mock('./features', () => ({ useTipPoolsEnabled: () => true }))
import { Dashboard } from './Dashboard'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState({}, '', '/account') })
const session = { userId: 'me', email: 'me@example.test', accessToken: 'test-token' }
const member = (id: string, percent: number) => ({ id, userId: 'me', name: 'Alex', email: session.email, active: true, status: 'ACTIVE', sharePercent: percent, effectiveSharePercent: percent })
const response = (data: unknown) => ({ ok: true, json: async () => data })
function mount({ failure, legacyCounts = false, deferRename = false }: { failure?: 'personal' | 'pool'; legacyCounts?: boolean; deferRename?: boolean } = {}) {
  const own = (id: string, name: string) => ({ id, name, ownerId: 'me', paymentUrl: `/pay/${id}`, membersComplete: true, memberCount: 1, activeMemberCount: 1, members: [member(`member-${id}`, 100)] })
  const foreign = (id: string, name: string) => ({ id, name, ownerId: `owner-${id}`, paymentUrl: `/pay/${id}`, membersComplete: false, memberCount: legacyCounts ? undefined : 4, activeMemberCount: legacyCounts ? undefined : 4, members: [member(`member-${id}`, 25)] })
  const state = {
    pools: [own('own-a', 'Eigener Pool A'), own('own-b', 'Eigener Pool B'), foreign('foreign-a', 'Fremder Pool A'), foreign('foreign-b', 'Fremder Pool B'), foreign('foreign-c', 'Fremder Pool C')],
    releaseRename: null as null | (() => void),
  }
  const fetch = vi.fn(async (url: string, options?: RequestInit) => {
    if (url === '/payment-service/test-access') return response({ enabled: true })
    if (url === '/payment-service/connect-account' || url === '/payment-service/connect-account/sync') return response({ stripeAccountId: 'acct_own', stripeStatus: 'READY', statusCheckedAt: new Date().toISOString(), onboardingStatus: 'COMPLETE', chargesEnabled: true, transfersEnabled: true, payoutsEnabled: true, detailsSubmitted: true })
    if (url === '/payment-service/connect-account/balance') return response({ currency: 'EUR', availableMinor: 12345, pendingMinor: 0, checkedAt: new Date().toISOString() })
    if (url === '/payment-service/transactions') {
      if (failure === 'personal') throw Error('offline')
      return response([{ id: 'personal', type: 'TIP', amountMinor: 1000, recipientAmountMinor: 950, status: 'BOOKED', createdAt: new Date().toISOString() }])
    }
    if (url === '/payment-service/pool-transfers') {
      if (failure === 'pool') throw Error('offline')
      return response(['a', 'b', 'c'].map(id => ({ id, poolId: `foreign-${id}`, poolName: `Fremder Pool ${id.toUpperCase()}`, amountMinor: 250, reversedAmountMinor: 0, status: 'TRANSFERRED', historicalSharePercent: 25, createdAt: new Date().toISOString() })))
    }
    if (url === '/user-service/pools/all') return response(state.pools)
    if (url.endsWith('/eligibility')) return response({ canReceiveTips: true })
    if (url.startsWith('/payment-service/connect-account/pools/')) return response([])
    if (url === '/user-service/users/profile') return response({ email: session.email, paymentEnabled: JSON.parse(String(options?.body)).paymentEnabled })
    if (url.startsWith('/user-service/pools/memberships/') && options?.method === 'DELETE') {
      const membershipId = url.split('/').pop()
      state.pools = state.pools.filter(pool => !pool.members.some(member => member.id === membershipId))
      return response({})
    }
    if (url === '/user-service/pools/own-a' && options?.method === 'PUT') {
      const updated = { ...state.pools[0], name: JSON.parse(String(options.body)).name }
      state.pools = state.pools.map(pool => pool.id === updated.id ? updated : pool)
      if (deferRename) return new Promise<ReturnType<typeof response>>(resolve => { state.releaseRename = () => resolve(response(updated)) })
      return response(updated)
    }
    return response([])
  })
  vi.stubGlobal('fetch', fetch)
  render(<Dashboard session={session} profile={{ email: session.email, firstName: 'Alex', paymentEnabled: true, paymentUrl: '/pay/personal' }} onProfile={vi.fn()} onLogout={vi.fn()} />)
  return { state, fetch }
}
async function openPools() {
  fireEvent.click(screen.getByRole('button', { name: 'Meine Pools' }))
  await screen.findByRole('button', { name: /Eigener Pool A/ })
}

it('supports two owned pools and three foreign memberships alongside the personal account', async () => {
  mount()
  await screen.findByRole('button', { name: 'Transaktion personal ansehen' })
  const account = screen.getByRole('region', { name: 'Mein Konto und meine Auszahlungen' })
  await openPools()
  for (const name of ['Eigener Pool A', 'Eigener Pool B', 'Fremder Pool A', 'Fremder Pool B', 'Fremder Pool C']) expect(screen.getByRole('button', { name: new RegExp(name) })).toBeInTheDocument()
  expect(screen.getByRole('region', { name: 'Mein Konto und meine Auszahlungen' })).toBe(account)
  fireEvent.click(screen.getByRole('button', { name: /Eigener Pool B/ }))
  expect(await screen.findByLabelText('Poolname bearbeiten')).toHaveValue('Eigener Pool B')
  fireEvent.click(screen.getByRole('button', { name: /Fremder Pool A/ }))
  expect(screen.queryByText('Pool-Einstellungen und Mitglieder verwalten')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Pool verlassen' })).toBeEnabled()
  fireEvent.click(screen.getByRole('button', { name: 'Meine Trinkgelder' }))
  expect(screen.getByRole('button', { name: 'Transaktion personal ansehen' })).toBeInTheDocument()
  expect(screen.getByLabelText('Dein persönlicher Link')).toHaveValue('/pay/personal')
})

it('shows a confirmed 25-percent member share and the anonymous server member count', async () => {
  mount(); await openPools()
  const card = screen.getByRole('button', { name: /Fremder Pool A/ })
  expect(card).toHaveTextContent('4 aktive Mitglieder')
  fireEvent.click(card)
  const chart = screen.getByRole('region', { name: 'Dein Pool-Anteil' })
  expect(chart).toHaveTextContent('25,00 %')
  expect(chart).not.toHaveTextContent('100 %')
  expect(within(chart).getAllByRole('listitem')).toHaveLength(1)
})

it('does not infer a foreign member count from an older member-only response', async () => {
  mount({ legacyCounts: true }); await openPools()
  expect(screen.getByRole('button', { name: /Fremder Pool A/ })).toHaveTextContent('Mitgliederzahl nicht verfügbar')
})

it('keeps aggregate history free of pool-specific member or admin actions', async () => {
  mount(); await openPools()
  fireEvent.change(screen.getByLabelText('Teamansicht und Historie'), { target: { value: 'all' } })
  expect(screen.queryByText('Pool-Einstellungen und Mitglieder verwalten')).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Pool verlassen' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Mitgliedschaft pausieren/ })).not.toBeInTheDocument()
  for (const id of ['a', 'b', 'c']) expect(await screen.findByRole('button', { name: `Transaktion pool-transfer-${id} ansehen` })).toBeInTheDocument()
  fireEvent.change(screen.getByLabelText('Teamansicht und Historie'), { target: { value: 'own-b' } })
  expect(await screen.findByLabelText('Poolname bearbeiten')).toHaveValue('Eigener Pool B')
})

it.each(['personal', 'pool'] as const)('loads the other transaction history when %s history fails', async failure => {
  mount({ failure })
  if (failure === 'pool') {
    expect(await screen.findByRole('button', { name: 'Transaktion personal ansehen' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await openPools()
    expect(await screen.findByRole('alert')).toHaveTextContent('Pool-Transaktionen')
  } else {
    expect(await screen.findByRole('alert')).toHaveTextContent('persönlichen Transaktionen')
    await openPools()
    fireEvent.click(screen.getByRole('button', { name: /Fremder Pool A/ }))
    expect(await screen.findByRole('button', { name: 'Transaktion pool-transfer-a ansehen' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  }
})

it('leaves only the selected foreign membership and preserves the other pools', async () => {
  const { fetch } = mount(); vi.stubGlobal('confirm', () => true); await openPools()
  fireEvent.click(screen.getByRole('button', { name: /Fremder Pool B/ }))
  fireEvent.click(screen.getByRole('button', { name: 'Pool verlassen' }))
  await waitFor(() => expect(screen.queryByRole('button', { name: /Fremder Pool B/ })).not.toBeInTheDocument())
  for (const name of ['Eigener Pool A', 'Eigener Pool B', 'Fremder Pool A', 'Fremder Pool C']) expect(screen.getByRole('button', { name: new RegExp(name) })).toBeInTheDocument()
  expect(fetch.mock.calls.filter(([, options]) => options?.method === 'DELETE').map(([url]) => url)).toEqual(['/user-service/pools/memberships/member-foreign-b'])
})

it('keeps a delayed owner mutation from switching back from another pool', async () => {
  const { state } = mount({ deferRename: true }); await openPools()
  fireEvent.change(screen.getByLabelText('Poolname bearbeiten'), { target: { value: 'Umbenannter Pool A' } })
  fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))
  await waitFor(() => expect(state.releaseRename).not.toBeNull())
  fireEvent.click(screen.getByRole('button', { name: /Fremder Pool C/ }))
  await act(async () => state.releaseRename!())
  expect(screen.getByLabelText('Teamansicht und Historie')).toHaveValue('foreign-c')
  expect(screen.queryByText('Pool-Einstellungen und Mitglieder verwalten')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Umbenannter Pool A/ })).toBeInTheDocument()
})

it('pauses only the personal payment link while the selected pool stays available', async () => {
  mount()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Zahlungslink kopieren' })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: /Deaktivieren/ }))
  await screen.findByText('Deine Zahlungsseite wurde deaktiviert.')
  expect(screen.getByRole('button', { name: 'Zahlungslink kopieren' })).toBeDisabled()
  await openPools()
  expect(await screen.findByTitle('Team-Trinkgeld QR-Code')).toBeInTheDocument()
})
