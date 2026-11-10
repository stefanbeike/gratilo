import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api } from './api'

// Unknown configuration is distinct from a confirmed disabled feature.
const TipPoolsContext = createContext<boolean | null>(null)

export function FeatureFlagsProvider({ children }: { children: ReactNode }) {
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [failed, setFailed] = useState(false)
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const retry = () => setRevision(value => value + 1)
    window.addEventListener('focus', retry)
    return () => window.removeEventListener('focus', retry)
  }, [])
  useEffect(() => {
    let cancelled = false
    api<{ tipPoolsEnabled: boolean }>('/features')
      .then(flags => {
        if (typeof flags?.tipPoolsEnabled !== 'boolean') throw new Error('Invalid feature configuration')
        if (!cancelled) { setEnabled(flags.tipPoolsEnabled); setFailed(false) }
      })
      .catch(() => { if (!cancelled) setFailed(true) })
    return () => { cancelled = true }
  }, [revision])
  return <TipPoolsContext.Provider value={enabled}>
    {failed && <div className="message error" role="alert">Die Pool-Konfiguration konnte nicht aktualisiert werden. Bereits bestätigte Einstellungen bleiben erhalten.
      <button className="text-button" onClick={() => setRevision(value => value + 1)}>Pool-Konfiguration erneut laden</button>
    </div>}
    {children}
  </TipPoolsContext.Provider>
}

export function useTipPoolsEnabled() {
  return useContext(TipPoolsContext)
}
