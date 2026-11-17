import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './styles.css'
import './auth-brand.css'
import './design-tokens.css'
import './responsive.css'
import { FeatureFlagsProvider } from './features'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <FeatureFlagsProvider><App /></FeatureFlagsProvider>
  </StrictMode>,
)
