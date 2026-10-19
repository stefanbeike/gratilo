import { useEffect, useState } from 'react'
import { api, type Session } from './api'

const loadError = 'Stripe-Onboarding konnte nicht gestartet werden. Bitte versuche es erneut.'
const networkError = 'Stripe ist aus diesem Netzwerk nicht erreichbar. Bitte deaktiviere VPN-, DNS- oder Werbefilter oder nutze ein anderes Netzwerk und versuche es erneut.'

async function checkStripeConnectivity() {
  const controller = new AbortController()
  const timeout = window.setTimeout(() => controller.abort(), 5000)
  try {
    // A no-cors request cannot read Stripe's response, but it can distinguish
    // a reachable Stripe API from DNS, TLS, or network failures before we
    // create a single-use Account Link and leave Gratilo.
    await fetch('https://api.stripe.com/', {
      mode: 'no-cors',
      cache: 'no-store',
      signal: controller.signal,
    })
  } finally {
    window.clearTimeout(timeout)
  }
}

export function StripeOnboarding({ session, onRedirect }: { session: Session; onRedirect: (url: string) => void }) {
  const [attempt, setAttempt] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    setLoading(true); setError('')
    const start = async () => {
      try {
        await checkStripeConnectivity()
      } catch {
        if (!cancelled) { setLoading(false); setError(networkError) }
        return
      }
      try {
        const response = await api<{ onboardingUrl?: string }>(
          '/payment-service/connect-account/onboarding/start', {}, session.accessToken)
        const target = response?.onboardingUrl
        if (cancelled || !target) throw new Error(loadError)
        const url = new URL(target)
        if (url.protocol !== 'https:' || url.hostname !== 'connect.stripe.com') throw new Error(loadError)
        onRedirect(url.toString())
      } catch {
        if (!cancelled) { setLoading(false); setError(loadError) }
      }
    }
    void start()
    return () => { cancelled = true }
  }, [session.accessToken, attempt, onRedirect])

  return <>
    {loading && <p role="status">Du wirst sicher zu Stripe weitergeleitet …</p>}
    {error && <div role="alert" className="message error"><p>{error}</p><button className="outline-button" onClick={() => setAttempt(value => value + 1)}>Erneut versuchen</button></div>}
  </>
}
