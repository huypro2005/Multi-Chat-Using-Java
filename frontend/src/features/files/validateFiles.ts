// ---------------------------------------------------------------------------
// validateFiles — client-side file validation before upload
// Rules:
//   - Either all images (up to 5 total) OR exactly 1 PDF (alone)
//   - Allowed MIME types: JPEG, PNG, WebP, GIF, PDF
//   - Max size per file: 20 MB
// ---------------------------------------------------------------------------

const ALLOWED_MIMES = [
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/gif',
  'application/pdf',
]
const MAX_SIZE_BYTES = 20 * 1024 * 1024 // 20 MB

export function validateFiles(
  newFiles: File[],
  currentPendingCount: number,
): { ok: File[]; errors: string[] } {
  const errors: string[] = []

  if (newFiles.length === 0) return { ok: [], errors }

  const allImages = newFiles.every((f) => f.type.startsWith('image/'))
  const onePdf = newFiles.length === 1 && newFiles[0].type === 'application/pdf'

  if (!allImages && !onePdf) {
    return { ok: [], errors: ['Chỉ gửi 1 PDF hoặc 1-5 ảnh trong 1 tin nhắn'] }
  }

  if (allImages && currentPendingCount + newFiles.length > 5) {
    return { ok: [], errors: ['Tối đa 5 ảnh'] }
  }

  if (onePdf && currentPendingCount > 0) {
    return { ok: [], errors: ['PDF phải gửi một mình'] }
  }

  const ok: File[] = []
  for (const file of newFiles) {
    if (!ALLOWED_MIMES.includes(file.type)) {
      errors.push(`${file.name}: định dạng không hỗ trợ`)
      continue
    }
    if (file.size > MAX_SIZE_BYTES) {
      errors.push(`${file.name}: vượt quá 20MB`)
      continue
    }
    ok.push(file)
  }

  return { ok, errors }
}
