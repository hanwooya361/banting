import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import {
  fetchNotifications,
  NOTIFICATION_POLL_MS,
  NOTIFICATIONS_CHANGED,
  notifyNotificationsChanged,
  readAllNotifications,
  notificationLink,
  readNotification,
  type NotificationItem,
  type NotificationList,
} from '../lib/notification'
import { NotificationRow } from './NotificationRow'

// 드롭다운에는 최근 몇 개만, 나머지는 전체 알림 화면(/notifications)에서 더 보기로
const PREVIEW_COUNT = 5

// 헤더의 알림 종 (T15). 실시간 연결 없이 다시 불러온다: 페이지 이동·종 열기·30초마다(탭이 보일 때만)·다른 화면에서 읽음 처리
// (서버 1대·적은 사용자 기준이면 충분. 체험처럼 한 화면에 머무는 동안 생긴 알림도 30초 안에 뜬다)
export function NotificationBell() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const [data, setData] = useState<NotificationList | null>(null)
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  const refresh = useCallback(() => {
    fetchNotifications()
      .then(setData)
      .catch(() => {
        // 알림을 못 불러와도 화면은 그대로 쓸 수 있게 조용히 넘어간다
      })
  }, [])

  useEffect(() => {
    refresh()
  }, [pathname, refresh])

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') refresh()
    }, NOTIFICATION_POLL_MS)
    window.addEventListener(NOTIFICATIONS_CHANGED, refresh)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener(NOTIFICATIONS_CHANGED, refresh)
    }
  }, [refresh])

  // 바깥을 누르거나 Esc면 닫기
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    const esc = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', esc)
    return () => {
      document.removeEventListener('mousedown', close)
      document.removeEventListener('keydown', esc)
    }
  }, [open])

  const toggle = () => {
    if (!open) refresh()   // 열 때 최신으로
    setOpen((o) => !o)
  }

  const handleClick = (item: NotificationItem) => {
    setOpen(false)
    if (!item.read) {
      setData((d) =>
        d && {
          ...d,
          unreadCount: Math.max(0, d.unreadCount - 1),
          items: d.items.map((n) => (n.id === item.id ? { ...n, read: true } : n)),
        },
      )
      readNotification(item.id)
        .then(notifyNotificationsChanged)
        .catch(() => {})
    }
    navigate(notificationLink(item))
  }

  const handleReadAll = () => {
    setData((d) => d && { ...d, unreadCount: 0, items: d.items.map((n) => ({ ...n, read: true })) })
    readAllNotifications()
      .then(notifyNotificationsChanged)
      .catch(() => {})
  }

  const unread = data?.unreadCount ?? 0
  const preview = data?.items.slice(0, PREVIEW_COUNT) ?? []
  const more = (data?.items.length ?? 0) > PREVIEW_COUNT || data?.hasNext

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        onClick={toggle}
        aria-label={unread > 0 ? `알림 ${unread}개 안 읽음` : '알림'}
        aria-expanded={open}
        className="relative flex h-9 w-9 items-center justify-center rounded-full text-gray-600 hover:bg-gray-100"
      >
        <svg viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        {unread > 0 ? (
          <span className="absolute right-0.5 top-0.5 min-w-4 rounded-full bg-orange-500 px-1 text-center text-[10px] font-bold leading-4 text-white">
            {unread > 9 ? '9+' : unread}
          </span>
        ) : null}
      </button>

      {open ? (
        // 휴대폰: 화면 양옆 16px에 붙여 고정 (종 기준으로 펼치면 왼쪽으로 넘침), 넓은 화면: 종 아래로
        // 높이는 화면의 70%까지, 넘치면 목록만 스크롤 (머리글과 전체 보기는 항상 보이게)
        // 헤더 nav의 whitespace-nowrap을 물려받으면 알림 문장이 한 줄로 넘치므로 whitespace-normal로 되돌린다
        <div className="fixed inset-x-4 top-14 z-30 flex max-h-[70vh] flex-col overflow-hidden whitespace-normal rounded-xl border border-gray-200 bg-white shadow-lg sm:absolute sm:inset-x-auto sm:right-0 sm:top-11 sm:w-96">
          <div className="flex flex-none items-center justify-between border-b border-gray-100 px-4 py-2.5">
            <p className="font-semibold">
              알림{unread > 0 ? <span className="ml-1 text-sm font-normal text-orange-600">{unread}개 안 읽음</span> : null}
            </p>
            {unread > 0 ? (
              <button type="button" onClick={handleReadAll} className="text-xs text-gray-500 hover:text-gray-900">
                모두 읽음
              </button>
            ) : null}
          </div>
          {preview.length > 0 ? (
            <ul className="min-h-0 flex-1 divide-y divide-gray-100 overflow-y-auto">
              {preview.map((item) => (
                <li key={item.id}>
                  <NotificationRow item={item} onClick={handleClick} />
                </li>
              ))}
            </ul>
          ) : (
            <p className="px-4 py-8 text-center text-sm text-gray-500">아직 알림이 없어요</p>
          )}
          <Link
            to="/notifications"
            onClick={() => setOpen(false)}
            className="flex-none border-t border-gray-100 py-2.5 text-center text-sm font-medium text-gray-700 hover:bg-gray-50"
          >
            {more ? '알림 전체 보기' : '알림 화면으로'}
          </Link>
        </div>
      ) : null}
    </div>
  )
}
