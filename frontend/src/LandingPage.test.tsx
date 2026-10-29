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

it('prepares a correctly addressed enquiry without claiming delivery or sending data', () => {
  const fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  render(<LandingContact />)
  fireEvent.change(screen.getByLabelText('Dein Name'), { target: { value: 'Alex' } })
  fireEvent.change(screen.getByLabelText('Deine E-Mail-Adresse'), { target: { value: 'alex@example.com' } })
  fireEvent.change(screen.getByLabelText('Dein Anliegen'), { target: { value: 'Pool für unser Team' } })
  fireEvent.change(screen.getByLabelText('Deine Nachricht'), { target: { value: 'Wir interessieren uns für Gratilo.' } })
  fireEvent.click(screen.getByRole('button', { name: 'E-Mail vorbereiten ↗' }))
  expect(screen.getByRole('status')).toHaveTextContent('noch nicht versendet')
  const link = screen.getByRole('link', { name: 'Im E-Mail-Programm öffnen ↗' })
  expect(link.getAttribute('href')).toMatch(/^mailto:info@gratilo.eu\?/)
  expect(decodeURIComponent(link.getAttribute('href')!)).toContain('Pool für unser Team')
  expect(fetchMock).not.toHaveBeenCalled()
  fireEvent.change(screen.getByLabelText('Dein Name'), { target: { value: 'Sam' } })
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})
