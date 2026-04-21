// ---------------------------------------------------------------------------
// validateFiles — client-side file validation before upload
// Rules (v0.9.5):
//   Group A (images): all images, 1-5 total including existing pending
//   Group B (non-image): exactly 1 file, alone (no mix with Group A or other B)
// ---------------------------------------------------------------------------

const ALLOWED_MIMES = [
  // Group A — Images
  'image/jpeg', 'image/png', 'image/webp', 'image/gif',
  // Group B — Documents & archives
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/msword',
  'application/vnd.ms-excel',
  'application/vnd.ms-powerpoint',
  'text/plain',
  'application/zip',
  'application/x-7z-compressed',
]

const MAX_SIZE_BYTES = 20 * 1024 * 1024

function isImageMime(mime: string): boolean {
  return mime.startsWith('image/')
}

// Signature thay đổi: nhận toàn bộ pending array thay vì chỉ count
// để có thể check group mixing với pending hiện tại
export interface FileLike {
  type: string
}

export function validateFiles(
  newFiles: File[],
  currentPendingCount: number,
  currentPendingMimes?: string[],  // MIME của pending hiện tại (để check mixing)
): { ok: File[]; errors: string[] } {
  if (newFiles.length === 0) return { ok: [], errors: [] }

  // Per-file checks (MIME + size)
  const errors: string[] = []
  const sized: File[] = []
  for (const f of newFiles) {
    if (!ALLOWED_MIMES.includes(f.type)) {
      errors.push(`${f.name}: định dạng không hỗ trợ`)
      continue
    }
    if (f.size > MAX_SIZE_BYTES) {
      errors.push(`${f.name}: vượt quá 20MB`)
      continue
    }
    sized.push(f)
  }

  if (sized.length === 0) return { ok: [], errors }

  const allNewImages = sized.every(f => isImageMime(f.type))
  const existingImages = (currentPendingMimes ?? []).every(m => isImageMime(m))
  const existingCount = currentPendingCount

  // Case: all new files are images
  if (allNewImages) {
    // Check existing pending is also images (or empty)
    if (existingCount > 0 && !existingImages) {
      return { ok: [], errors: ['Không thể trộn ảnh với tệp khác'] }
    }
    // Check total ≤ 5
    if (existingCount + sized.length > 5) {
      return { ok: [], errors: ['Tối đa 5 ảnh / tin nhắn'] }
    }
    return { ok: sized, errors }
  }

  // Case: new files contain non-image
  // Only allow exactly 1 non-image file, alone (no existing pending)
  if (sized.length === 1 && existingCount === 0) {
    return { ok: sized, errors }
  }

  if (sized.length > 1) {
    return { ok: [], errors: ['Chỉ gửi 1 tệp (PDF, Word, Excel...) hoặc 1-5 ảnh / tin nhắn'] }
  }

  // sized.length === 1 but existingCount > 0
  return { ok: [], errors: ['Tệp này phải gửi một mình (không kết hợp với tệp khác)'] }
}
