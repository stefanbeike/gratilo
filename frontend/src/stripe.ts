/** Public Stripe configuration. Never put the secret key in frontend code. */
declare global { interface ImportMeta { readonly env: Record<string, string | undefined> } }
export const paymentsEnabled = import.meta.env.VITE_PAYMENTS_ENABLED === 'true'
export const stripePublishableKey: string = paymentsEnabled ? import.meta.env.VITE_STRIPE_PUBLISHABLE_KEY || '' : ''

if (paymentsEnabled && !stripePublishableKey.startsWith('pk_test_')) {
  throw new Error('Für Zahlungen ist ein öffentlicher Stripe-Testschlüssel erforderlich.')
}
