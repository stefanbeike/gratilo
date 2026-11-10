import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { FeatureFlagsProvider } from './features'
import { Dashboard } from './Dashboard'
import { App } from './App'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); history.replaceState({}, '', '/') })

function mount(enabled?: boolean) {
  const fetchMock = vi.fn(async (url: string) => {
    if (url === '/user-service/features') {
      if (enabled === undefined) throw new Error('offline')
      return { ok: true, json: async () => ({ tipPoolsEnabled: enabled }) }
    }
    if (url === '/user-service/pools/all') return { ok: true, json: async () => [] }
    return { ok: true, json: async () => [] }
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<FeatureFlagsProvider><Dashboard
    session={{ accessToken: 'token', userId: '123', email: 'alex@example.com' }}
    profile={{ email: 'alex@example.com' }} onProfile={vi.fn()} onLogout={vi.fn()}
  /></FeatureFlagsProvider>)
  return fetchMock
}

it.each([false, undefined])('hides pools and skips pool requests when disabled or unavailable (%s)', async enabled => {
  const fetchMock = mount(enabled)
  await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/user-service/features', expect.anything()))
  expect(screen.queryByRole('heading', { name: 'Dein Trinkgeld-Pool' })).not.toBeInTheDocument()
  expect(fetchMock.mock.calls.some(([url]) => url.startsWith('/user-service/pools'))).toBe(false)
})

it('restores pool management and requests when enabled by the backend', async () => {
  const fetchMock = mount(true)
  fireEvent.click(screen.getByRole('button',{name:'Meine Pools'}))
  expect(await screen.findByText('Du bist noch keinem Pool beigetreten.')).toBeInTheDocument()
  await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url === '/user-service/pools/all')).toBe(true))
})

it('blocks invitation links while disabled', () => {
  history.replaceState({}, '', '/pool/invitations/test-token')
  render(<App />)
  expect(screen.getByRole('heading', { name: 'Diese Funktion ist derzeit nicht verfügbar.' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Annehmen/ })).not.toBeInTheDocument()
})


it('distinguishes unknown configuration and recovers without a page reload', async () => {
  const fetchMock = mount(undefined)
  fireEvent.click(screen.getByRole('button',{name:'Meine Pools'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('Pool-Konfiguration')
  expect(screen.queryByText('Team-Trinkgelder sind derzeit deaktiviert.')).not.toBeInTheDocument()
  fetchMock.mockImplementation(async url => {
    if(url==='/user-service/features')return {ok:true,json:async()=>({tipPoolsEnabled:true})}
    return {ok:true,json:async()=>[]}
  })
  fireEvent.click(screen.getByRole('button',{name:'Pool-Konfiguration erneut laden'}))
  expect(await screen.findByText('Du bist noch keinem Pool beigetreten.')).toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('retains confirmed pool access after a feature refresh fails', async () => {
  const fetchMock = mount(true)
  fireEvent.click(screen.getByRole('button',{name:'Meine Pools'}))
  await screen.findByText('Du bist noch keinem Pool beigetreten.')
  fetchMock.mockImplementation(async url => {
    if(url==='/user-service/features')throw Error('offline')
    return {ok:true,json:async()=>[]}
  })
  window.dispatchEvent(new Event('focus'))
  expect(await screen.findByRole('alert')).toHaveTextContent('bestätigte Einstellungen bleiben erhalten')
  expect(screen.getByText('Du bist noch keinem Pool beigetreten.')).toBeInTheDocument()
})
