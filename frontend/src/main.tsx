import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '@/lib/queryClient'
import './index.css'
import App from './App.tsx'

// sockjs-client expects Node-like global variable in browser.
// Keep this before app bootstraps to avoid runtime ReferenceError.
if (typeof window !== 'undefined' && !(window as Window & { global?: Window }).global) {
  ;(window as Window & { global?: Window }).global = window
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </StrictMode>
)
