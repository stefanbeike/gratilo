import { useEffect, useRef, useState, type FormEvent } from 'react'
import { api } from './api'

const topics = ['Allgemeine Anfrage', 'Einzelkonto', 'Pool für unser Team', 'NFC-Karte mit QR-Code', 'Tischaufsteller', 'Button mit QR-Code', 'QR-Aufkleber']

export function LandingContact() {
  const [topic, setTopic] = useState(topics[0])
  const [busy, setBusy] = useState(false)
  const inFlight = useRef(false)
  const [success, setSuccess] = useState('')
  const [error, setError] = useState('')
  useEffect(() => {
    const selectTopic = (event: Event) => {
      const value = (event as CustomEvent<string>).detail
      if (!inFlight.current && topics.includes(value)) { setTopic(value); setSuccess(''); setError('') }
    }
    window.addEventListener('gratilo:contact-topic', selectTopic)
    return () => window.removeEventListener('gratilo:contact-topic', selectTopic)
  }, [])

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (inFlight.current) return
    setSuccess(''); setError('')
    if (location.protocol === 'file:') {
      setError('Diese Datei ist eine Offline-Vorschau. Der direkte Versand funktioniert erst auf der veröffentlichten Website. Du erreichst uns unter info@gratilo.eu.')
      return
    }
    const form = event.currentTarget
    const data = new FormData(form)
    const payload = {
      name: String(data.get('name') || '').trim(), email: String(data.get('email') || '').trim(),
      topic, message: String(data.get('message') || '').trim(), website: String(data.get('website') || ''),
    }
    if (!payload.name || !payload.email || !payload.message) { setError('Bitte fülle alle Felder aus.'); return }
    inFlight.current = true; setBusy(true)
    try {
      const result = await api<{ message: string }>('/contact', payload)
      if (!result?.message) throw new Error('Der Versand konnte nicht bestätigt werden. Bitte versuche es erneut.')
      form.reset(); setTopic(topics[0]); setSuccess('Danke! Deine Nachricht wurde zum Versand angenommen. Wir melden uns an deiner E-Mail-Adresse.')
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Der Versand ist derzeit nicht möglich. Bitte versuche es später erneut.')
    } finally { inFlight.current = false; setBusy(false) }
  }

  return <section className="lp-section lp-contact" id="kontakt">
    <div><p className="lp-eyebrow">LASS UNS SPRECHEN</p><h2>Ein Danke beginnt<br />mit einem Hallo.</h2><p>Fragen zu Gratilo, ein Pool für euren Betrieb oder Interesse an unseren Artikeln? Schreib uns.</p><a href="mailto:info@gratilo.eu">info@gratilo.eu ↗</a><p className="lp-note">Deine Nachricht geht direkt an unser Gratilo-Team. Du brauchst dafür kein E-Mail-Programm.</p></div>
    <form onSubmit={send} aria-busy={busy} onChange={() => { if (!inFlight.current) { setSuccess(''); setError('') } }}>
      <fieldset disabled={busy} className="lp-contact-fields"><legend className="lp-visually-hidden">Kontaktanfrage</legend>
        <label htmlFor="contact-name">Dein Name</label><input id="contact-name" name="name" autoComplete="name" required maxLength={100} />
        <label htmlFor="contact-email">Deine E-Mail-Adresse</label><input id="contact-email" name="email" type="email" autoComplete="email" required maxLength={254} />
        <label htmlFor="contact-topic">Dein Anliegen</label><select id="contact-topic" value={topic} onChange={e => setTopic(e.target.value)}>{topics.map(value => <option key={value}>{value}</option>)}</select>
        <label htmlFor="contact-message">Deine Nachricht</label><textarea id="contact-message" name="message" rows={5} required maxLength={2000} />
        <div className="lp-contact-honeypot" aria-hidden="true"><label htmlFor="contact-website">Website bitte leer lassen</label><input id="contact-website" name="website" autoComplete="off" tabIndex={-1} /></div>
        <p className="lp-note">Wir verwenden deinen Namen, deine E-Mail-Adresse und Nachricht zur Bearbeitung deiner Anfrage. Bitte keine Zahlungsdaten oder Ausweisdokumente eintragen.</p>
        <button type="submit" className="lp-button">{busy ? 'Wird gesendet …' : 'Nachricht senden ↗'}</button>
      </fieldset>
      {success && <p className="lp-contact-result" role="status">{success}</p>}
      {error && <p className="lp-contact-error" role="alert">{error} Deine Eingaben bleiben für einen erneuten Versuch erhalten.</p>}
    </form>
  </section>
}
