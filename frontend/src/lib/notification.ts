// 인앱 알림 (T15). 확정·무산·취소·구매·완료 때 서버가 상태 변경과 같은 트랜잭션에서 만든다. 보관 30일
import { api } from './api'

export type NotificationType =
  | 'GROUP_BUY_CONFIRMED'
  | 'GROUP_BUY_FAILED'
  | 'GROUP_BUY_CANCELED'
  | 'GROUP_BUY_PURCHASED'
  | 'GROUP_BUY_COMPLETED'
  | 'INQUIRY_ANSWERED'

export type NotificationItem = {
  id: number
  type: NotificationType
  groupBuyId: number | null // 문의 답변 알림(T17)은 공구가 없어 null
  message: string
  read: boolean
  createdAt: string
}

// hasNext면 마지막 항목 id를 before로 넘겨 더 오래된 20개를 받는다
export type NotificationList = { unreadCount: number; items: NotificationItem[]; hasNext: boolean }

export const NOTIFICATION_LABELS: Record<NotificationType, { label: string; className: string }> = {
  GROUP_BUY_CONFIRMED: { label: '확정', className: 'bg-green-100 text-green-800' },
  GROUP_BUY_PURCHASED: { label: '구매 완료', className: 'bg-blue-100 text-blue-800' },
  GROUP_BUY_COMPLETED: { label: '공구 완료', className: 'bg-violet-100 text-violet-800' },
  GROUP_BUY_FAILED: { label: '무산·환불', className: 'bg-gray-200 text-gray-700' },
  GROUP_BUY_CANCELED: { label: '취소·환불', className: 'bg-gray-200 text-gray-700' },
  INQUIRY_ANSWERED: { label: '문의 답변', className: 'bg-orange-100 text-orange-800' },
}

// 알림을 누르면 갈 곳: 공구 알림은 그 공구, 문의 답변은 내 문의
export function notificationLink(item: NotificationItem) {
  return item.groupBuyId === null ? '/inquiries' : `/group-buys/${item.groupBuyId}`
}

// 종 아이콘이 새 알림을 확인하는 간격
export const NOTIFICATION_POLL_MS = 30_000

export function fetchNotifications(before?: number) {
  return api<NotificationList>(before ? `/api/notifications?before=${before}` : '/api/notifications')
}

export function readNotification(id: number) {
  return api<void>(`/api/notifications/${id}/read`, { method: 'POST' })
}

export function readAllNotifications() {
  return api<void>('/api/notifications/read-all', { method: 'POST' })
}

// 다른 화면(전체 알림 페이지 ↔ 종)에서 읽음 처리했을 때 서로 새로 고치게 알리는 이벤트
export const NOTIFICATIONS_CHANGED = 'banting:notifications-changed'
export function notifyNotificationsChanged() {
  window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED))
}
