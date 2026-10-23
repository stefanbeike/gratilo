import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './design-tokens.css'
import './styles.css'
import './auth-brand.css'
import './product-brand.css'
import { FeatureFlagsProvider } from './features'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <FeatureFlagsProvider><App /></FeatureFlagsProvider>
  </StrictMode>,
)
