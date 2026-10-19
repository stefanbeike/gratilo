import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { StripeTestEnrollment } from './StripeTestEnrollment'
const session = { accessToken: 'signed-test-jwt', userId: '11111111-1111-1111-1111-111111111111', email: 'tester@example.invalid' }
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('activates the signed-in account without asking for a UUID and clears the private code', async () => {
  const fetch = vi.fn(async (_url: string, options?: RequestInit) => ({ ok: true, json: async () => ({ restricted: true, enabled: options?.method === 'POST', canActivate: true }) }))
  vi.stubGlobal('fetch', fetch)
  const onChange = vi.fn()
  render(<StripeTestEnrollment session={session} onChange={onChange} />)
  const input = await screen.findByLabelText('Privater Test-Freigabecode')
  expect(input).toHaveAttribute('type', 'password')
  fireEvent.change(input, { target: { value: 'private-fixture-code' } })
  fireEvent.click(screen.getByRole('button', { name: 'Mein Konto für den Test freigeben' }))
  await screen.findByText(/Dein Konto ist für den Stripe-Test freigegeben/)
  const request = fetch.mock.calls.find(([, options]) => options?.method === 'POST')![1]!
  expect(JSON.parse(String(request.body))).toEqual({ code: 'private-fixture-code' })
  expect(request.headers).toEqual({ Authorization: 'Bearer signed-test-jwt', 'Content-Type': 'application/json' })
  expect(onChange).toHaveBeenLastCalledWith(true)
  expect(localStorage.getItem('private-fixture-code')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Testfreigabe widerrufen' }))
  expect(await screen.findByLabelText('Privater Test-Freigabecode')).toHaveValue('')
  expect(onChange).toHaveBeenLastCalledWith(false)
})

it('keeps the account blocked after a wrong code and removes the input value', async () => {
  const fetch = vi.fn(async (_url: string, options?: RequestInit) => options?.method === 'POST'
    ? { ok: false, status: 403, json: async () => ({ message: 'Testfreigabe nicht möglich.' }) }
    : { ok: true, json: async () => ({ restricted: true, enabled: false, canActivate: true }) })
  vi.stubGlobal('fetch', fetch)
  const onChange = vi.fn()
  render(<StripeTestEnrollment session={session} onChange={onChange} />)
  const input = await screen.findByLabelText('Privater Test-Freigabecode')
  fireEvent.change(input, { target: { value: 'wrong' } })
  fireEvent.click(screen.getByRole('button', { name: 'Mein Konto für den Test freigeben' }))
  await screen.findByRole('alert')
  await waitFor(() => expect(input).toHaveValue(''))
  expect(onChange).not.toHaveBeenCalledWith(true)
})
