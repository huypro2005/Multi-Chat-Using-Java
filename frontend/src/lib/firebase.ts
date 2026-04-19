// ---------------------------------------------------------------------------
// Firebase — lazy init để tránh crash khi env chưa có
//
// Lấy config: Firebase Console → Project Settings → Your apps → Web app → firebaseConfig
// Env vars cần set trong .env.local:
//   VITE_FIREBASE_API_KEY
//   VITE_FIREBASE_AUTH_DOMAIN
//   VITE_FIREBASE_PROJECT_ID
// ---------------------------------------------------------------------------

import { initializeApp, getApps } from 'firebase/app'
import { getAuth, GoogleAuthProvider, signInWithPopup } from 'firebase/auth'

const firebaseConfig = {
  apiKey: import.meta.env.VITE_FIREBASE_API_KEY,
  authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID,
}

// Lazy init — nếu app đã được khởi tạo (HMR, double render) thì dùng lại
const app = getApps().length === 0 ? initializeApp(firebaseConfig) : getApps()[0]
const auth = getAuth(app)
const googleProvider = new GoogleAuthProvider()

export { auth, googleProvider, signInWithPopup }
