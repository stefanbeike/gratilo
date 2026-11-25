import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { AdminPaymentReferences } from './AdminPaymentReferences'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('keeps colliding references visible until an admin selects a payment', async () => {
  const paypal = {
    transactionId: '30aa7460-c72e-453c-9762-915e93f034ab', publicReference: 'GT-Pool',
    paymentType: 'POOL', status: 'BOOKED', tipAmountMinor: 500, currency: 'EUR',
    attempts: [{
      chargeId: 'ch_1Test', paymentIntentId: 'pi_1Test', chargeCreatedAt: '2026-11-25T11:00:00Z', currency: 'eur',
      method: 'paypal', references: [{ source: 'Stripe Charge.payment_method_details.paypal.transaction_id', value: '4YW59914EF9464227' }],
    }],
  }
  const fetch = vi.fn().mockResolvedValue({ok: true, json: async () => [paypal, {
    ...paypal, transactionId: '03e1ac3b-cf8e-43cf-b4b7-ef242f57930c', publicReference: 'GT-Einzel', paymentType: 'SINGLE',
  }]})
  vi.stubGlobal('fetch', fetch)
  render(<AdminPaymentReferences session={{ accessToken: 'fixture', userId: '', email: '' }} />)
  fireEvent.change(screen.getByLabelText('Gratilo-, Stripe- oder Zahlungsanbieter-Referenz'), { target: { value: '4YW59914EF9464227' } })
  fireEvent.click(screen.getByRole('button', { name: 'Suchen' }))
  expect(await screen.findByText(/2 Zahlungen mit dieser Referenz/)).toBeInTheDocument()
  expect(screen.queryByRole('heading', {name: 'Transaktionsdetails'})).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', {name: /GT-Pool/}))
  expect(screen.getByText('Stripe Charge.payment_method_details.paypal.transaction_id')).toBeInTheDocument()
  expect(screen.getByText('4YW59914EF9464227')).toBeInTheDocument()
  expect(fetch.mock.calls[0][0]).toBe('/payment-service/admin/stripe-payment-audits/references/search')
  expect(fetch.mock.calls[0][1].body).toContain('"livemode":false')
})
