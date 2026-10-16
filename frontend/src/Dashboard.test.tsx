import { cleanup, fireEvent, render, screen, within, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { Dashboard, DemoPayment, csvContent, transactions } from './Dashboard'
const session = { accessToken: 'token', email: 'alex@example.com', userId: '123' }
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
function mount() { return render(<Dashboard session={session} profile={{ email: session.email, firstName: 'Alex', city: 'Berlin' }} onProfile={vi.fn()} onLogout={vi.fn()} />) }
it('filters transactions and resets empty results', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => url === '/payment-service/transactions'
    ? transactions.map(item => ({ id: item.id, amountMinor: item.amount * 100, type: item.type === 'Auszahlung' ? 'PAYOUT' : 'TIP', status: 'BOOKED', description: item.note, createdAt: item.date }))
    : {} })))
  mount(); const table = screen.getByRole('table')
  await waitFor(() => expect(within(table).getAllByRole('row')).toHaveLength(9))
  fireEvent.click(screen.getByRole('button', { name: 'Auszahlungen' }))
  expect(within(table).getAllByRole('row')).toHaveLength(2)
  fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'Unbekannter Eintrag' } })
  expect(screen.getByText('Keine passenden Transaktionen gefunden.')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Filter zurücksetzen' }))
  expect(within(table).getAllByRole('row')).toHaveLength(9)
})
it('keeps payments, payouts and onboarding disabled without Stripe configuration', () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => [] }); vi.stubGlobal('fetch', fetch)
  mount()
  expect(screen.queryByTitle('QR-Code zum Demo-Zahlungslink')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Zahlungslink kopieren' })).toBeDisabled()
  expect(screen.getByRole('button', { name: /Aktivieren/ })).toBeDisabled()
  expect(screen.getByRole('button', { name: /Onboarding starten/ })).toBeDisabled()
  expect(fetch.mock.calls.some(([url]) => String(url).includes('connect-account'))).toBe(false)
})
it('saves the profile using PUT and preserves existing fields', async () => {
  const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ email: session.email, firstName: 'Ada' }) }); vi.stubGlobal('fetch', fetch)
  mount(); fireEvent.click(screen.getByRole('link', { name: 'Profil bearbeiten' }))
  fireEvent.change(screen.getByLabelText('Vorname'), { target: { value: 'Ada' } })
  fireEvent.click(screen.getByRole('button', { name: 'Änderungen speichern' }))
  expect(await screen.findByRole('status')).toHaveTextContent('Dein Profil wurde gespeichert.')
  expect(fetch).toHaveBeenCalledWith('/user-service/users/profile', expect.objectContaining({ method: 'PUT', headers: { Authorization: 'Bearer token', 'Content-Type': 'application/json' } }))
  const profileCall = fetch.mock.calls.find(([url, options]) => url === '/user-service/users/profile' && options?.method === 'PUT')
  expect(JSON.parse(profileCall![1].body)).toMatchObject({ firstName: 'Ada', city: 'Berlin' })
})
it('exports filtered transactions with German decimals', () => {
  const csv = csvContent(transactions.filter(t => t.type === 'Auszahlung'))
  expect(csv).toContain('"-75,00"'); expect(csv).toContain('DEMO-1005'); expect(csv).not.toContain('DEMO-1008')
})
it('shows the saved profile image next to logout', () => {
  render(<Dashboard session={session} profile={{ email: session.email, profileImage: 'data:image/jpeg;base64,test' }} onProfile={vi.fn()} onLogout={vi.fn()} />)
  expect(screen.getByRole('img', { name: 'Dein Profilbild' })).toHaveAttribute('src', 'data:image/jpeg;base64,test')
  expect(screen.getByRole('button', { name: /Abmelden/ })).toBeInTheDocument()
})

it('renders a disabled payment page without fetching or confirming anything', () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  render(<DemoPayment />)
  expect(screen.getByText('Zahlungen sind derzeit deaktiviert.')).toBeInTheDocument()
  expect(fetch).not.toHaveBeenCalled()
})
