import { useEffect, useState, type FormEvent } from 'react'
import { api, type Session } from './api'

type Access = { restricted: boolean; enabled: boolean; canActivate: boolean }

export function StripeTestEnrollment({ session, onChange }: { session: Session; onChange: (enabled: boolean) => void }) {
  const [access, setAccess] = useState<Access | null>(null)
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let cancelled = false
    onChange(false); setCode(''); setAccess(null); setError('')
    api<Access>('/payment-service/test-access', undefined, session.accessToken).then(value => {
      if (!cancelled) { setAccess(value); onChange(value.enabled === true) }
    }).catch(() => { if (!cancelled) setError('Die Testfreigabe konnte nicht geladen werden.') })
    return () => { cancelled = true }
  }, [session.accessToken, onChange])
  async function activate(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('')
    try {
      const value = await api<Access>('/payment-service/test-access', { code }, session.accessToken)
      setAccess(value); onChange(value.enabled === true)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Testfreigabe nicht möglich.') }
    finally { setCode(''); setBusy(false) }
  }
  async function revoke() {
    setBusy(true); setError('')
    try {
      const value = await api<Access>('/payment-service/test-access', undefined, session.accessToken, 'DELETE')
      setAccess(value); onChange(value.enabled === true)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Testfreigabe konnte nicht widerrufen werden.') }
    finally { setBusy(false) }
  }
  if (access && !access.restricted) return null
  return <section className="account-card"><h2>Stripe-Test freigeben</h2>
    <p>Die Freigabe gilt für dein angemeldetes Konto. Danach kannst du das Stripe-Test-Onboarding starten und deinen QR-Code ohne Anmeldung im privaten Fenster testen.</p>
    {error && <p className="message error" role="alert">{error}</p>}
    {!access ? <p>Testfreigabe wird geprüft …</p> : access.enabled ? <><p role="status">Dein Konto ist für den Stripe-Test freigegeben. Trinkgelder werden erst nach Freischaltung durch Stripe möglich.</p><button className="outline-button" disabled={busy} onClick={revoke}>Testfreigabe widerrufen</button></> : access.canActivate ?
      <form onSubmit={activate}><label htmlFor="test-activation-code">Privater Test-Freigabecode</label><input id="test-activation-code" type="password" autoComplete="off" maxLength={256} value={code} onChange={event => setCode(event.target.value)} /><button className="solid-button" disabled={busy || !code}>Mein Konto für den Test freigeben</button></form> :
      <p>Der private Test-Freigabecode muss zunächst vom Betreiber eingerichtet werden.</p>}
    <p>Nur Stripe-Testzahlungen. Es werden keine echten Zahlungen oder Bankauszahlungen ausgeführt. Teile den Freigabecode nicht; er gehört nicht in den QR-Link.</p>
  </section>
}
