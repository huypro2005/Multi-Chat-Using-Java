import { useEffect, useState } from 'react'
import api from '@/lib/api'

/**
 * Load protected file URL through axios (with Bearer token),
 * then expose a browser object URL for <img>/<a>.
 *
 * Cleanup on unmount: revokes blob URL + aborts in-flight request.
 * Returns null while loading or on error (consumer shows placeholder).
 *
 * Pattern: stores { url, forPath } — derive returns null when path changes,
 * avoiding set-state-in-effect lint rule.
 */
export function useProtectedObjectUrl(path: string | null | undefined): string | null {
  // Store { url, forPath } to avoid set-state-in-effect pattern
  // Derive: only return url when forPath matches current path
  const [state, setState] = useState<{ url: string; forPath: string } | null>(null)

  useEffect(() => {
    if (!path) return

    const controller = new AbortController()
    let currentUrl: string | null = null

    void api
      .get<Blob>(path, { responseType: 'blob', signal: controller.signal })
      .then((res) => {
        currentUrl = URL.createObjectURL(res.data)
        setState({ url: currentUrl, forPath: path })
      })
      .catch((err: unknown) => {
        // Ignore abort errors (unmount / path changed) — not a real error
        if (
          (err as { name?: string })?.name === 'CanceledError' ||
          (err as { name?: string })?.name === 'AbortError'
        ) {
          return
        }
        // On real error: clear state for this path
        setState((prev) => (prev?.forPath === path ? null : prev))
      })

    return () => {
      controller.abort()
      if (currentUrl) URL.revokeObjectURL(currentUrl)
    }
  }, [path])

  // Derive: only valid when the stored url was fetched for the current path
  return state?.forPath === path && !!path ? state.url : null
}
