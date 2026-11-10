import { useEffect, useRef, useState } from 'react'
import { dissolvePool } from './api'

export function PoolDissolveDialog({ pool, token, onClose, onDissolved, onClosing }: {
  pool: { id: string; name: string }; token: string; onClose: () => void;
  onDissolved: (id: string) => void; onClosing: (id: string) => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    const element = dialog.current!
    const trigger = document.activeElement as HTMLElement | null
    element.showModal()
    return () => { element.close(); trigger?.focus() }
  }, [])
  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (busy || confirmation !== pool.name) return
    setBusy(true); setError('')
    try { await dissolvePool(pool.id, confirmation, token); onDissolved(pool.id) }
    catch (cause) {
      if (cause instanceof Error && /POOL_FINANCE_(PENDING|UNAVAILABLE)/.test((cause as Error & { code?: string }).code || '')) onClosing(pool.id)
      setError(cause instanceof Error ? cause.message : 'Der Pool konnte nicht aufgelöst werden.')
    } finally { setBusy(false) }
  }
  return <dialog ref={dialog} className="pool-dissolve-dialog" aria-labelledby="pool-dissolve-heading" onCancel={event => { event.preventDefault(); if (!busy) onClose() }}>
    <form onSubmit={submit}>
      <h2 id="pool-dissolve-heading">Pool wirklich auflösen?</h2>
      <p>Wenn du den Pool „{pool.name}“ auflöst:</p>
      <ul>
        <li>werden alle Mitgliedschaften dieses Pools beendet,</li>
        <li>werden offene Einladungen ungültig,</li>
        <li>werden die Mitglieder per E-Mail informiert,</li>
        <li>können über diesen Pool keine neuen Trinkgelder mehr empfangen werden,</li>
        <li>bleiben historische Zahlungsdaten erhalten.</li>
      </ul>
      <p>Diese Aktion kann nicht rückgängig gemacht werden. Andere Pools und dein persönliches Konto bleiben unverändert.</p>
      <p>Bei offenen Finanzvorgängen bleibt der Pool für neue Zahlungen gesperrt. Du kannst die Auflösung nach deren Abschluss erneut versuchen.</p>
      <label htmlFor="pool-dissolve-confirmation">Gib zur Bestätigung den Poolnamen ein: {pool.name}</label>
      <input autoFocus id="pool-dissolve-confirmation" value={confirmation} disabled={busy} autoComplete="off" onChange={event => setConfirmation(event.target.value)} />
      {error && <p role="alert" className="message error">{error}</p>}
      <div className="pool-dissolve-actions">
        <button type="button" className="outline-button" disabled={busy} onClick={onClose}>Abbrechen</button>
        <button type="submit" className="solid-button" disabled={busy || confirmation !== pool.name}>{busy ? 'Pool wird aufgelöst …' : 'Pool endgültig auflösen'}</button>
      </div>
    </form>
  </dialog>
}
