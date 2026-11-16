import { api } from './api'

type PresignedUpload = { url: string; key: string; contentType: string }

const MAX_SIDE = 1600
const MAX_BYTES = 5 * 1024 * 1024

/**
 * 휴대폰 사진은 수 MB라, 올리기 전에 긴 변 1600px·webp로 줄인다 (데이터 절약, 5MB 제한 통과).
 * 브라우저가 변환을 못 하면 원본을 그대로 쓴다.
 */
async function compress(file: File): Promise<Blob> {
  try {
    const bitmap = await createImageBitmap(file)
    const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(bitmap.width * scale)
    canvas.height = Math.round(bitmap.height * scale)
    canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    bitmap.close()
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/webp', 0.85))
    if (blob && blob.type === 'image/webp' && blob.size < file.size) return blob
  } catch {
    // 변환 실패 시 원본 사용
  }
  return file
}

/**
 * 1) 서버에 업로드 URL 발급 요청 (파일 형식·크기가 서명에 들어감)
 * 2) 그 URL로 S3에 직접 PUT — 우리 서버를 거치지 않는다
 * 반환한 key를 공구 저장 요청에 넣으면 서버가 공개 위치로 옮긴다.
 */
export async function uploadImage(file: File): Promise<string> {
  const blob = await compress(file)
  const contentType = blob.type || file.type
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(contentType) || blob.size > MAX_BYTES) {
    throw new Error('jpg·png·webp 사진, 5MB 이하만 올릴 수 있어요')
  }
  const presigned = await api<PresignedUpload>('/api/uploads/presigned', {
    method: 'POST',
    body: { contentType, contentLength: blob.size },
  })
  const res = await fetch(presigned.url, { method: 'PUT', headers: { 'Content-Type': contentType }, body: blob })
  if (!res.ok) {
    throw new Error('사진을 올리지 못했어요. 다시 시도해 주세요')
  }
  return presigned.key
}
