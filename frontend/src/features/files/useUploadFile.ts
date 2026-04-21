// ---------------------------------------------------------------------------
// useUploadFile — hook for managing file uploads with progress tracking
//
// Features:
//   - Multiple concurrent uploads, each with its own AbortController
//   - Progress tracking via axios onUploadProgress
//   - Cancel individual uploads (abort + filter out)
//   - Remove completed/failed uploads (revoke blob URL + filter)
//   - Clear all (revoke all blob URLs + reset)
//   - Cleanup on unmount: revoke all blob URLs
//
// AbortController note: axios v1+ uses signal (AbortController), not CancelToken.
// ---------------------------------------------------------------------------

import { useState, useCallback, useEffect, useRef } from 'react'
import axios from 'axios'
import api from '@/lib/api'
import type { AttachmentDto } from '@/types/message'

export interface PendingUpload {
  localId: string
  file: File
  progress: number
  status: 'uploading' | 'done' | 'error'
  error?: string
  previewUrl: string       // URL.createObjectURL(file)
  result?: AttachmentDto   // set when status='done'
  abortController?: AbortController
}

export function useUploadFile() {
  const [pending, setPending] = useState<PendingUpload[]>([])
  // Keep a ref to current pending for cleanup on unmount
  const pendingRef = useRef<PendingUpload[]>(pending)
  pendingRef.current = pending

  // Cleanup blob URLs on unmount
  useEffect(() => {
    return () => {
      for (const item of pendingRef.current) {
        URL.revokeObjectURL(item.previewUrl)
      }
    }
  }, [])

  const upload = useCallback(async (files: File[]): Promise<void> => {
    for (const file of files) {
      const localId = crypto.randomUUID()
      const previewUrl = URL.createObjectURL(file)
      const controller = new AbortController()

      // Add to pending immediately
      setPending((prev) => [
        ...prev,
        {
          localId,
          file,
          progress: 0,
          status: 'uploading',
          previewUrl,
          abortController: controller,
        },
      ])

      const formData = new FormData()
      formData.append('file', file)

      try {
        const response = await api.post<AttachmentDto>('/api/files/upload', formData, {
          // Do NOT set Content-Type manually — browser auto-sets multipart/form-data
          // with correct boundary when axios detects FormData payload.
          // Setting it manually skips the boundary → BE cannot parse parts.
          headers: { 'Content-Type': undefined },
          signal: controller.signal,
          onUploadProgress: (progressEvent) => {
            if (progressEvent.total) {
              const pct = Math.round((progressEvent.loaded / progressEvent.total) * 100)
              setPending((prev) =>
                prev.map((item) =>
                  item.localId === localId ? { ...item, progress: pct } : item,
                ),
              )
            }
          },
        })

        setPending((prev) =>
          prev.map((item) =>
            item.localId === localId
              ? { ...item, status: 'done', progress: 100, result: response.data, abortController: undefined }
              : item,
          ),
        )
      } catch (err) {
        // Cancelled by user — filter out silently
        if (axios.isCancel(err)) {
          setPending((prev) => {
            const item = prev.find((p) => p.localId === localId)
            if (item) URL.revokeObjectURL(item.previewUrl)
            return prev.filter((p) => p.localId !== localId)
          })
          continue
        }

        // Real error
        const errorMsg =
          (err as { response?: { data?: { message?: string } } }).response?.data?.message ??
          'Upload thất bại'

        setPending((prev) =>
          prev.map((item) =>
            item.localId === localId
              ? { ...item, status: 'error', error: errorMsg, abortController: undefined }
              : item,
          ),
        )
      }
    }
  }, [])

  const cancel = useCallback((localId: string): void => {
    setPending((prev) => {
      const item = prev.find((p) => p.localId === localId)
      if (!item) return prev
      item.abortController?.abort()
      // Item will be filtered out in the catch block above when abort fires.
      // But if abort fires synchronously before filter: filter here too.
      URL.revokeObjectURL(item.previewUrl)
      return prev.filter((p) => p.localId !== localId)
    })
  }, [])

  const remove = useCallback((localId: string): void => {
    setPending((prev) => {
      const item = prev.find((p) => p.localId === localId)
      if (item) URL.revokeObjectURL(item.previewUrl)
      return prev.filter((p) => p.localId !== localId)
    })
  }, [])

  const clear = useCallback((): void => {
    setPending((prev) => {
      for (const item of prev) {
        URL.revokeObjectURL(item.previewUrl)
      }
      return []
    })
  }, [])

  return { pending, upload, cancel, remove, clear }
}
