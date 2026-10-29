import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { App } from './App'
import { LandingContact, LandingProducts } from './LandingProducts'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState({}, '', '/login') })

it('offers a public homepage and a payment-free demo without calling an API', () => {
  localStorage.clear()
  history.replaceState({}, '', '/')
  const fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  render(<App />)
  expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('Kein Bargeld?')
  expect(screen.getByRole('link', { name: 'Konto erstellen' })).toHaveAttribute('href', '/register')
  fireEvent.click(screen.getByRole('button', { name: '5 €' }))
  fireEvent.click(screen.getByRole('button', { name: 'Demo mit 5 € abschließen' }))
  expect(screen.getByRole('status')).toHaveTextContent('Es wurden keine 5 € bezahlt.')
  expect(fetchMock).not.toHaveBeenCalled()
})

it('explains a pool distribution and lets visitors compare shares', () => {
  render(<LandingProducts />)
  expect(screen.getAllByText('10 €')).toHaveLength(3)
  fireEvent.click(screen.getByRole('button', { name: 'Individuell' }))
  expect(screen.getByText('15 €')).toBeInTheDocument()
  expect(screen.getByText('9 €')).toBeInTheDocument()
  expect(screen.getByText('6 €')).toBeInTheDocument()
})

function fillContact() {
  fireEvent.change(screen.getByLabelText('Dein Name'), { target: { value: 'Alex' } })
  fireEvent.change(screen.getByLabelText('Deine E-Mail-Adresse'), { target: { value: 'alex@example.com' } })
  fireEvent.change(screen.getByLabelText('Dein Anliegen'), { target: { value: 'Pool für unser Team' } })
  fireEvent.change(screen.getByLabelText('Deine Nachricht'), { target: { value: 'Wir interessieren uns für Gratilo.' } })
}

it('posts the enquiry and waits for server confirmation before clearing fields', async () => {
  let resolve!: (value: unknown) => void
  const fetchMock = vi.fn().mockReturnValue(new Promise(r => { resolve = r }))
  vi.stubGlobal('fetch', fetchMock)
  render(<LandingContact />)
  fillContact()
  fireEvent.click(screen.getByRole('button', { name: 'Nachricht senden ↗' }))
  expect(screen.getByRole('button', { name: 'Wird gesendet …' })).toBeDisabled()
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(fetchMock).toHaveBeenCalledTimes(1)
  expect(fetchMock).toHaveBeenCalledWith('/user-service/contact', expect.objectContaining({ method: 'POST' }))
  expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ name: 'Alex', email: 'alex@example.com', topic: 'Pool für unser Team', message: 'Wir interessieren uns für Gratilo.', website: '' })
  resolve({ ok: true, json: async () => ({ message: 'Danke! Deine Nachricht wurde zum Versand angenommen.' }) })
  expect(await screen.findByRole('status')).toHaveTextContent('zum Versand angenommen')
  expect(screen.getByLabelText('Dein Name')).toHaveValue('')
})

it('preserves the enquiry on delivery failure and permits a retry', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce({ ok: false, status: 503, json: async () => ({ message: 'E-Mail-Versand nicht verfügbar.' }) }).mockResolvedValueOnce({ ok: true, json: async () => ({ message: 'Angenommen' }) })
  vi.stubGlobal('fetch', fetchMock)
  render(<LandingContact />)
  fillContact()
  fireEvent.click(screen.getByRole('button', { name: 'Nachricht senden ↗' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('E-Mail-Versand nicht verfügbar.')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
  expect(screen.getByLabelText('Dein Name')).toHaveValue('Alex')
  expect(screen.getByLabelText('Deine Nachricht')).toHaveValue('Wir interessieren uns für Gratilo.')
  fireEvent.click(screen.getByRole('button', { name: 'Nachricht senden ↗' }))
  expect(await screen.findByRole('status')).toHaveTextContent('zum Versand angenommen')
  expect(fetchMock).toHaveBeenCalledTimes(2)
})

it('does not claim delivery for an empty or HTML response', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => { throw new Error('HTML') } }))
  render(<LandingContact />)
  fillContact()
  fireEvent.click(screen.getByRole('button', { name: 'Nachricht senden ↗' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('Versand konnte nicht bestätigt werden')
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})
