import { useTipPoolsEnabled } from './features'
import { useEffect, useState, type FormEvent } from 'react'
import { api, type Session } from './api'
import { prepareProfileImage } from './profileImage'
import { Dashboard, DemoPayment, PoolInvitation, PoolInvitationLogin, type Profile } from './Dashboard'

type Page = 'login' | 'register' | 'forgot' | 'reset' | 'verify' | 'resend'
const pages: Record<string, Page> = { '/register': 'register', '/forgot-password': 'forgot', '/reset-password': 'reset', '/auth/verify': 'verify', '/resend-verification': 'resend' }
const titles = { login: 'Schön, dass du da bist.', register: 'Dein Danke beginnt hier.', forgot: 'Passwort vergessen?', reset: 'Ein neuer Anfang.', verify: 'E-Mail bestätigen.', resend: 'Bestätigung erneut senden.' }
const descriptions = { login: 'Melde dich an und mach Wertschätzung einfach.', register: 'Erstelle dein Konto und werde Teil von Gratilo.', forgot: 'Kein Problem. Wir schicken dir einen Link zum Zurücksetzen.', reset: 'Wähle ein neues, sicheres Passwort für dein Konto.', verify: 'Bestätige deine E-Mail-Adresse, um dein Konto zu aktivieren.', resend: 'Gib die E-Mail-Adresse und das Passwort deines noch unbestätigten Kontos ein.' }
const passwordRule = /^(?=.*\p{Ll})(?=.*\p{Lu})(?=.*[^\p{L}\p{N}\s])\S{8,}$/u

export function App() {
  const tipPoolsEnabled = useTipPoolsEnabled()
  const [page, setPage] = useState<Page>(() => pages[location.pathname] || 'login')
  const [profileImage, setProfileImage] = useState<string | null>(null)
  const [imageBusy, setImageBusy] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [session, setSession] = useState<Session | null>(() => { try { const value = JSON.parse(localStorage.getItem('gratilo.session') || 'null'); const payload = value?.accessToken?.split('.')[1]; const exp = payload ? JSON.parse(atob(payload.replace(/-/g, '+').replace(/_/g, '/'))).exp : 0; return exp * 1000 > Date.now() ? value : null } catch { return null } })
  const [profile, setProfile] = useState<Profile | null>(() => { try { return JSON.parse(localStorage.getItem('gratilo.profile') || 'null') } catch { return null } })
  const [sessionExpired, setSessionExpired] = useState(false)
  useEffect(() => { if (session) localStorage.setItem('gratilo.session', JSON.stringify(session)); else localStorage.removeItem('gratilo.session') }, [session])
  useEffect(() => { if (profile) localStorage.setItem('gratilo.profile', JSON.stringify(profile)); else localStorage.removeItem('gratilo.profile') }, [profile])
  useEffect(() => { if (session && !profile) api<Profile>('/users/me', undefined, session.accessToken).then(setProfile).catch(() => { setSession(null); setSessionExpired(true) }) }, [session, profile])
  useEffect(() => {
    const onPop = () => { setPage(pages[location.pathname] || 'login'); setError(''); setSuccess('') }
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])
  function navigate(path: string) {
    history.pushState({}, '', path); setPage(pages[path] || 'login'); setError(''); setSuccess(''); setShowPassword(false)
  }
  function link(path: string, label: string) {
    return <a href={path} onClick={event => { if (!event.ctrlKey && !event.metaKey && !busy) { event.preventDefault(); navigate(path) } else if (busy) event.preventDefault() }}>{label}</a>
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(''); setSuccess('')
    const data = new FormData(event.currentTarget)
    const email = String(data.get('email') || '').trim()
    const password = String(data.get('password') || '')
    if ((page === 'register' || page === 'reset') && !passwordRule.test(password)) { setError('Das Passwort benötigt mindestens 8 Zeichen, Groß- und Kleinbuchstaben sowie ein Sonderzeichen, ohne Leerzeichen.'); return }
    if ((page === 'register' || page === 'reset') && password !== data.get('confirmPassword')) { setError('Die Passwörter stimmen nicht überein.'); return }
    setBusy(true)
    try {
      if (page === 'login') {
        const result = await api<Session>('/auth/login', { email, password })
        const userProfile = await api<Profile>('/users/me', undefined, result.accessToken)
        setProfile(userProfile)
        const invitationPath = sessionStorage.getItem('gratilo.invitationPath')
        if (invitationPath) { sessionStorage.removeItem('gratilo.invitationPath'); navigate(invitationPath) } else navigate('/account')
        setSessionExpired(false)
        setSession(result)
      } else if (page === 'register') {
        await api('/users/register', { email, password, confirmPassword: data.get('confirmPassword'), firstName: data.get('firstName'), lastName: data.get('lastName'), newsletter: data.get('newsletter') === 'on', agbFileName: 'agb-demo-v1', privacyPolicy: 'privacy-demo-v1', profileImage })
        setSuccess('Fast geschafft! Öffne die E-Mail von Gratilo und bestätige deine E-Mail-Adresse. Danach kannst du dich anmelden.')
      } else if (page === 'forgot') {
        const result = await api('/auth/password-reset/request', { email }); setSuccess(result.message)
      } else if (page === 'resend') {
        const result = await api('/auth/verification/resend', { email, password }); setSuccess(result.message)
      } else {
        const token = new URLSearchParams(location.search).get('token')
        if (!token) throw new Error('In diesem Link fehlt der Token. Bitte öffne den vollständigen Link aus deiner E-Mail.')
        const result = page === 'verify' ? await api(`/auth/verify?token=${encodeURIComponent(token)}`) : await api('/auth/password-reset/confirm', { token, newPassword: password })
        setSuccess(result.message)
        history.replaceState({}, '', location.pathname)
      }
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Bitte versuche es erneut.') }
    finally { setBusy(false) }
  }
  if (location.pathname.startsWith('/pay/')) return <DemoPayment />
  if (location.pathname.startsWith('/pool/invitations/') && !tipPoolsEnabled) return <main className="shell"><section className="account-card"><h1>Diese Funktion ist derzeit nicht verfügbar.</h1><a href="/account">Zum Konto</a></section></main>
  if (location.pathname.startsWith('/pool/invitations/') && !session) return <PoolInvitationLogin onLogin={() => { sessionStorage.setItem('gratilo.invitationPath', location.pathname); window.location.assign('/login') }} onRegister={() => { sessionStorage.setItem('gratilo.invitationPath', location.pathname); window.location.assign('/register') }} />
  if (location.pathname.startsWith('/pool/invitations/') && session) return <PoolInvitation session={session} onSwitchUser={() => { setSession(null); setProfile(null); setSessionExpired(false) }} />
  if (session && profile) return <Dashboard session={session} profile={profile} onProfile={setProfile} onLogout={(expired = false) => { setSession(null); setProfile(null); setSessionExpired(expired === true); navigate('/login') }} />
  if (sessionExpired) return <><main className="shell" /><div className="session-dialog-backdrop"><section className="session-dialog" role="dialog" aria-modal="true" aria-labelledby="session-expired-title"><span className="session-dialog-icon">◷</span><h2 id="session-expired-title">Deine Sitzung ist abgelaufen</h2><p>Aus Sicherheitsgründen wurdest du automatisch abgemeldet. Melde dich erneut an, um weiterzumachen.</p><button className="primary" onClick={() => setSessionExpired(false)}>Anmelden <span aria-hidden="true">→</span></button></section></div></>
  return <main className="shell auth-shell">
    <aside className="story">
      <a className="auth-brand" href="/login" onClick={event => { event.preventDefault(); if (!busy) navigate('/login') }} aria-label="Gratilo Startseite"><img src="/gratilo-logo.svg" alt="Gratilo – Good people deserve more" width="340" height="152" /></a>
      <div className="story-content"><span className="eyebrow">EIN KLEINES DANKE. EIN GROSSES GEFÜHL.</span><h1>Trinkgeld <br />geht jetzt <span className="serif">einfach.</span></h1>
        <ul className="auth-values">
          <li><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21 3.5 12.5C-2 7 5 0 12 7c7-7 14 0 8.5 5.5Z" /></svg>Wertschätzung zeigen.</li>
          <li><svg viewBox="0 0 24 24" aria-hidden="true"><rect x="6" y="2" width="12" height="20" rx="2" /><path d="M9 5h6M10 19h4" /></svg>Einfach scannen.</li>
          <li><svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="7" r="3" /><path d="M2 21v-4a7 7 0 0 1 14 0v4ZM17 4a3 3 0 0 1 0 6M18 14a5 5 0 0 1 4 5v2h-3" /></svg>Direkt unterstützen.</li>
        </ul>
        <div className="auth-art"><p className="auth-thanks"><span>Danke</span>FÜR GROSSARTIGE<br />MOMENTE!</p><img src="/gratilo-smile.svg" alt="" width="200" height="200" /></div>
      </div><p className="story-footer">EINFACH. SCHNELL. DIREKT.</p>
    </aside>
    <section className="form-side" aria-label="Dein Gratilo-Konto"><div className="top-note">{page === 'register' ? <>Schon dabei? {link('/login', 'Anmelden')}</> : <>Neu bei Gratilo? {link('/register', 'Konto erstellen ↗')}</>}</div>
      <div className="form-content" key={page}>
        <span className="step-label">DEIN GRATILO-KONTO</span>
        <h2>{session ? 'Du bist angemeldet.' : titles[page]}</h2><p className="intro">{session ? `Willkommen bei Gratilo, ${session.email}.` : descriptions[page]}</p>
        {error && <div className="message error" role="alert">{error}</div>}
        {success && <div className="message success" role="status">{success}</div>}
        {session ? <button className="primary" onClick={() => { setSession(null); navigate('/login') }}>Abmelden</button> : success ? <div className="success-actions">{link('/login', 'Zur Anmeldung →')}{page === 'forgot' && <button className="text-button" onClick={() => setSuccess('')}>Andere E-Mail-Adresse verwenden</button>}</div> : <form onSubmit={submit}>
          <fieldset disabled={busy || imageBusy}>
            {page === 'register' && <div className="profile-image-picker"><div className="profile-image-preview">{profileImage ? <img src={profileImage} alt="Vorschau deines Profilbilds" /> : <span aria-hidden="true">♙</span>}</div><div><label htmlFor="profile-image">Profilbild <span>(optional)</span></label><input id="profile-image" type="file" accept="image/jpeg,image/png,image/webp" onChange={async event => { const file = event.target.files?.[0]; event.target.value = ''; if (!file) return; setImageBusy(true); setError(''); try { setProfileImage(await prepareProfileImage(file)) } catch (cause) { setError(cause instanceof Error ? cause.message : 'Das Bild konnte nicht geladen werden.') } finally { setImageBusy(false) } }} /><p className="hint">{imageBusy ? 'Bild wird vorbereitet …' : 'JPG, PNG oder WebP · bis 5 MB · quadratischer Ausschnitt'}</p>{profileImage && <button type="button" className="text-button" onClick={() => setProfileImage(null)}>Bild entfernen</button>}</div></div>}
            {page === 'register' && <div className="name-fields"><label>Vorname <span>(optional)</span><input name="firstName" autoComplete="given-name" placeholder="Alex" /></label><label>Nachname <span>(optional)</span><input name="lastName" autoComplete="family-name" placeholder="Muster" /></label></div>}
            {['login', 'register', 'forgot', 'resend'].includes(page) && <label>E-Mail-Adresse<input name="email" type="email" autoComplete="email" placeholder="du@beispiel.de" required maxLength={254} /></label>}
            {['login', 'register', 'reset', 'resend'].includes(page) && <><label htmlFor="password">{page === 'reset' ? 'Neues Passwort' : 'Passwort'}</label><div className="password-field"><input id="password" name="password" type={showPassword ? 'text' : 'password'} autoComplete={page === 'login' || page === 'resend' ? 'current-password' : 'new-password'} required aria-describedby={page === 'register' || page === 'reset' ? 'password-hint' : undefined} placeholder="Dein sicheres Passwort" /><button className="reveal" type="button" aria-pressed={showPassword} onClick={() => setShowPassword(!showPassword)}>{showPassword ? 'Verbergen' : 'Anzeigen'}</button></div></>}
            {(page === 'register' || page === 'reset') && <><p id="password-hint" className="hint">Mindestens 8 Zeichen, Groß- und Kleinbuchstaben und ein Sonderzeichen. Keine Leerzeichen.</p><label>Passwort wiederholen<input name="confirmPassword" type={showPassword ? 'text' : 'password'} autoComplete="new-password" required placeholder="Noch einmal zur Sicherheit" /></label></>}
            {page === 'login' && <div className="forgot-link">{link('/forgot-password', 'Passwort vergessen?')}</div>}
            {page === 'login' && <div className="forgot-link">{link('/resend-verification', 'Bestätigungs-E-Mail erneut anfordern')}</div>}
            {page === 'register' && <><details className="legal"><summary>Test-AGB & Datenschutzhinweis lesen</summary><p>Diese lokale Demo dient ausschließlich zum Testen der Kontofunktionen. E-Mail-Adresse, Passwort (als Hash) und freiwillige Profildaten werden im User-Service gespeichert. E-Mails werden in der lokalen Testumgebung über MailHog bereitgestellt. Keine echten Zahlungsfunktionen. Diese Testtexte müssen vor einem öffentlichen Betrieb ersetzt werden.</p></details><label className="checkbox"><input type="checkbox" required /> <span>Ich akzeptiere die Test-AGB und habe den Datenschutzhinweis gelesen.</span></label><label className="checkbox"><input type="checkbox" name="newsletter" /><span>Ich möchte Neuigkeiten von Gratilo erhalten. <span>(optional)</span></span></label></>}
            <button className="primary" type="submit">{busy ? 'Einen Moment …' : { login: 'Anmelden', register: 'Konto erstellen', forgot: 'Link anfordern', reset: 'Passwort speichern', verify: 'E-Mail jetzt bestätigen', resend: 'Bestätigungs-E-Mail senden' }[page]}{!busy && <span aria-hidden="true">→</span>}</button>
          </fieldset>
        </form>}
        {!session && page !== 'login' && !success && <div className="back-link">{link('/login', '← Zurück zur Anmeldung')}</div>}
        {!session && page === 'login' && <p className="signup-note">Dein erstes Mal hier? {link('/register', 'Jetzt registrieren')}</p>}
      </div><footer>© {new Date().getFullYear()} Gratilo <span>Mit einem Danke fängt es an.</span></footer>
    </section>
  </main>
}
