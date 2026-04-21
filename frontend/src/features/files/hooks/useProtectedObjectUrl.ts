import { useEffect, useState } from 'react'
import api from '@/lib/api'

/**
 * Load protected file URL through axios (with Bearer token),
 * then expose a browser object URL for <img>/<a>.
 *
 * Cleanup on unmount: revokes blob URL + aborts in-flight request.
 * Returns null while loading or on error (consumer shows placeholder).
 */
export function useProtectedObjectUrl(path: string | null | undefined): string | null {
  const [objectUrl, setObjectUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!path) {
      setObjectUrl(null)
      return
    }

    const controller = new AbortController()
    let currentUrl: string | null = null

    void api
      .get<Blob>(path, { responseType: 'blob', signal: controller.signal })
      .then((res) => {
        currentUrl = URL.createObjectURL(res.data)
        setObjectUrl(currentUrl)
      })
      .catch((err: unknown) => {
        // Ignore abort errors (unmount / path changed) — not a real error
        if (
          (err as { name?: string })?.name === 'CanceledError' ||
          (err as { name?: string })?.name === 'AbortError'
        ) {
          return
        }
        setObjectUrl(null)
      })

    return () => {
      controller.abort()
      if (currentUrl) URL.revokeObjectURL(currentUrl)
    }
  }, [path])

  return objectUrl
}
