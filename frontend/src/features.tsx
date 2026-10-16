import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { api } from './api'

const TipPoolsContext = createContext(false)

export function FeatureFlagsProvider({ children }: { children: ReactNode }) {
  const [enabled, setEnabled] = useState(false)
  useEffect(() => {
    let cancelled = false
    api<{ tipPoolsEnabled: boolean }>('/features')
      .then(flags => { if (!cancelled) setEnabled(flags?.tipPoolsEnabled === true) })
      .catch(() => { /* Features remain disabled when configuration cannot be loaded. */ })
    return () => { cancelled = true }
  }, [])
  return <TipPoolsContext.Provider value={enabled}>{children}</TipPoolsContext.Provider>
}

export function useTipPoolsEnabled() {
  return useContext(TipPoolsContext)
}
