import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../lib/api'
import {
  fetchNotifications,
  notifyNotificationsChanged,
  readAllNotifications,
  NOTIFICATIONS_CHANGED,
  readNotification,
  type NotificationItem,
  type NotificationList,
} from '../lib/notification'
import { NotificationRow } from '../components/NotificationRow'

// 전체 알림 (T16.5와 함께). 20개씩 더 보기로 보관 기간(30일) 안의 알림을 모두 볼 수 있다
export function NotificationsPage() {
  const navigate = useNavigate()
  const [items, setItems] = useState<NotificationItem[]>([])
  const [unread, setUnread] = useState(0)
  const [hasNext, setHasNext] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const apply = useCallback((page: NotificationList, append: boolean) => {
    setItems((prev) => (append ? [...prev, ...page.items] : page.items))
    setUnread(page.unreadCount)
    setHasNext(page.hasNext)
  }, [])

  const fail = (err: unknown) => setError(err instanceof ApiError ? err.message : '알림을 불러오지 못했어요')

  // 처음 들어왔을 때 + 다른 곳(종)에서 읽음 처리했을 때 첫 페이지를 다시 받는다
  useEffect(() => {
    let active = true
    const reload = () =>
      fetchNotifications()
        .then((page) => active && apply(page, false))
        .catch((err) => active && fail(err))
        .finally(() => active && setLoading(false))
    reload()
    window.addEventListener(NOTIFICATIONS_CHANGED, reload)
    return () => {
      active = false
      window.removeEventListener(NOTIFICATIONS_CHANGED, reload)
    }
  }, [apply])

  const loadMore = async () => {
    setLoading(true)
    setError(null)
    try {
      apply(await fetchNotifications(items[items.length - 1]?.id), true)
    } catch (err) {
      fail(err)
    } finally {
      setLoading(false)
    }
  }

  const handleClick = (item: NotificationItem) => {
    if (!item.read) {
      readNotification(item.id)
        .then(notifyNotificationsChanged)
        .catch(() => {})
    }
    navigate(`/group-buys/${item.groupBuyId}`)
  }

  const handleReadAll = async () => {
    setItems((prev) => prev.map((n) => ({ ...n, read: true })))
    setUnread(0)
    await readAllNotifications().catch(() => {})
    notifyNotificationsChanged()
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-end justify-between">
        <div>
          <h1 className="text-xl font-bold">알림</h1>
          <p className="mt-1 text-sm text-gray-500">
            {unread > 0 ? `안 읽은 알림 ${unread}개 · ` : ''}최근 30일 알림을 보관해요
          </p>
        </div>
        {unread > 0 ? (
          <button type="button" onClick={handleReadAll} className="text-sm text-gray-600 hover:text-gray-900">
            모두 읽음
          </button>
        ) : null}
      </div>

      {items.length > 0 ? (
        <ul className="divide-y divide-gray-100 overflow-hidden rounded-xl border border-gray-200">
          {items.map((item) => (
            <li key={item.id}>
              <NotificationRow item={item} onClick={handleClick} />
            </li>
          ))}
        </ul>
      ) : !loading && !error ? (
        <p className="rounded-xl border border-gray-200 py-12 text-center text-sm text-gray-500">아직 알림이 없어요</p>
      ) : null}

      {error ? <p className="text-center text-sm text-red-600">{error}</p> : null}
      {loading ? <p className="text-center text-sm text-gray-500">불러오는 중…</p> : null}
      {hasNext && !loading ? (
        <button
          type="button"
          onClick={loadMore}
          className="rounded-lg border border-gray-300 py-2.5 font-medium"
        >
          이전 알림 더 보기
        </button>
      ) : null}
    </div>
  )
}
