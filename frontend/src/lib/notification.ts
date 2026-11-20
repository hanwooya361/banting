// 인앱 알림 (T15). 확정·무산·개설자 취소 때 서버가 상태 변경과 같은 트랜잭션에서 만든다
import { api } from './api'

export type NotificationItem = {
  id: number
  type: 'GROUP_BUY_CONFIRMED' | 'GROUP_BUY_FAILED' | 'GROUP_BUY_CANCELED'
  groupBuyId: number
  message: string
  read: boolean
  createdAt: string
}

export type NotificationList = { unreadCount: number; items: NotificationItem[] }

export function fetchNotifications() {
  return api<NotificationList>('/api/notifications')
}

export function readNotification(id: number) {
  return api<void>(`/api/notifications/${id}/read`, { method: 'POST' })
}

export function readAllNotifications() {
  return api<void>('/api/notifications/read-all', { method: 'POST' })
}

