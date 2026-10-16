import { cleanup, render, screen, waitFor } from '@testing-library/react'
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
    if (url === '/user-service/pools') return { ok: true, json: async () => null }
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
  expect(await screen.findByRole('heading', { name: 'Dein Trinkgeld-Pool' })).toBeInTheDocument()
  await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url === '/user-service/pools/memberships')).toBe(true))
})

it('blocks invitation links while disabled', () => {
  history.replaceState({}, '', '/pool/invitations/test-token')
  render(<App />)
  expect(screen.getByRole('heading', { name: 'Diese Funktion ist derzeit nicht verfügbar.' })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /Annehmen/ })).not.toBeInTheDocument()
})
