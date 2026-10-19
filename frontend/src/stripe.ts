/** Public Stripe configuration. Never put the secret key in frontend code. */
declare global { interface ImportMeta { readonly env: Record<string, string | undefined> } }
export const paymentsEnabled = import.meta.env.VITE_PAYMENTS_ENABLED === 'true'
