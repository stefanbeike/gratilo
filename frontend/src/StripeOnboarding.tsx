import { useEffect, useRef, useState } from 'react'
import { loadConnectAndInitialize } from '@stripe/connect-js'
import { api, type Session } from './api'
import { stripePublishableKey } from './stripe'

const loadError = 'Stripe-Onboarding konnte nicht geladen werden. Bitte versuche es erneut. Wenn der Fehler bestehen bleibt, müssen die Stripe-Testschlüssel des Betreibers geprüft werden.'

export function StripeOnboarding({ session }: { session: Session }) {
  const container = useRef<HTMLDivElement>(null)
  const [attempt, setAttempt] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    let element: HTMLElement | undefined
    setLoading(true); setError('')
    const fail = () => { if (!cancelled) { setLoading(false); setError(loadError) } }
    const fetchClientSecret = async () => {
      try {
        if (cancelled) throw new Error(loadError)
        // Connect.js also calls this when a session expires. Never reuse a secret.
        const account = await api<{ onboardingClientSecret?: string }>(
          '/payment-service/connect-account/onboarding/start', {}, session.accessToken)
        if (cancelled || !account?.onboardingClientSecret?.trim()) throw new Error(loadError)
        return account.onboardingClientSecret
      } catch {
        fail()
        // SDK/API errors can contain credentials. Expose only our own message.
        throw new Error(loadError)
      }
    }
    try {
      if (!container.current) return
      const connect = loadConnectAndInitialize({ publishableKey: stripePublishableKey, fetchClientSecret })
      const onboarding = connect.create('account-onboarding')
      onboarding.setOnLoadError(fail)
      onboarding.setOnLoaderStart(() => { if (!cancelled) setLoading(false) })
      element = onboarding
      container.current.appendChild(onboarding)
    } catch { fail() }
    return () => { cancelled = true; element?.remove() }
  }, [session.accessToken, attempt])

  return <>
    {loading && <p role="status">Stripe-Onboarding wird geladen …</p>}
    {error && <div role="alert" className="message error"><p>{error}</p><button className="outline-button" onClick={() => setAttempt(value => value + 1)}>Erneut versuchen</button></div>}
    <div ref={container} className="stripe-onboarding-container" />
  </>
}
