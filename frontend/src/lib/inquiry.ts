// 문의·피드백과 업데이트 노트 (T17). 사용자 피드백 → 관리자 답변(+ GitHub Issue) → 업데이트 노트
import { api } from './api'

export type InquiryType = 'BUG' | 'FEATURE' | 'ETC'
export type InquiryStatus = 'OPEN' | 'ANSWERED'

export const INQUIRY_TYPE_LABELS: Record<InquiryType, string> = {
  BUG: '버그',
  FEATURE: '기능 제안',
  ETC: '기타',
}

export const INQUIRY_TITLE_MAX = 100
export const INQUIRY_CONTENT_MAX = 2000

export type Inquiry = {
  id: number
  type: InquiryType
  title: string
  content: string
  pagePath: string | null // 피드백 버튼으로 남긴 화면 (T20)
  status: InquiryStatus
  answer: string | null
  issueUrl: string | null
  createdAt: string
  answeredAt: string | null
}

export type AdminInquiry = Inquiry & { authorNickname: string }

export type Notice = { id: number; title: string; content: string; createdAt: string }

export function createInquiry(body: { type: InquiryType; title: string; content: string; pagePath?: string }) {
  return api<Inquiry>('/api/inquiries', { method: 'POST', body })
}

export function fetchMyInquiries() {
  return api<Inquiry[]>('/api/inquiries/me')
}

export function fetchNotices() {
  return api<Notice[]>('/api/notices')
}

// ───── 관리자 ─────

export function fetchAdminInquiries(status?: InquiryStatus) {
  return api<AdminInquiry[]>(status ? `/api/admin/inquiries?status=${status}` : '/api/admin/inquiries')
}

export function answerInquiry(id: number, answer: string, issueUrl: string) {
  return api<Inquiry>(`/api/admin/inquiries/${id}/answer`, {
    method: 'POST',
    body: { answer, issueUrl: issueUrl.trim() || null },
  })
}

export function createNotice(title: string, content: string) {
  return api<Notice>('/api/admin/notices', { method: 'POST', body: { title, content } })
}
