import { useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import {
  fetchNotifications,
  readAllNotifications,
  readNotification,
  type NotificationItem,
  type NotificationList,
} from '../lib/notification'
import { formatDateTime } from '../lib/groupBuy'

// 헤더의 알림 종 (T15). 실시간 연결 없이 페이지를 옮길 때마다 다시 불러온다 (서버 1대·적은 사용자 기준이면 충분)
export function NotificationBell() {
  const { pathname } = useLocation()
  const navigate = useNavigate()
  const [data, setData] = useState<NotificationList | null>(null)
  const [open, setOpen] = useState(false)
  const boxRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let active = true
    fetchNotifications()
      .then((d) => {
        if (active) setData(d)
      })
      .catch(() => {
        // 알림을 못 불러와도 화면은 그대로 쓸 수 있게 조용히 넘어간다
      })
    return () => {
      active = false
    }
  }, [pathname])

  // 바깥을 누르면 닫기
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  const handleClick = (item: NotificationItem) => {
    setOpen(false)
    if (!item.read) {
      setData((d) =>
        d && {
          unreadCount: Math.max(0, d.unreadCount - 1),
          items: d.items.map((n) => (n.id === item.id ? { ...n, read: true } : n)),
        },
      )
      readNotification(item.id).catch(() => {})
    }
    navigate(`/group-buys/${item.groupBuyId}`)
  }

  const handleReadAll = () => {
    setData((d) => d && { unreadCount: 0, items: d.items.map((n) => ({ ...n, read: true })) })
    readAllNotifications().catch(() => {})
  }

  const unread = data?.unreadCount ?? 0

  return (
    <div ref={boxRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
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
        <div className="fixed inset-x-4 top-14 z-30 overflow-hidden rounded-xl border border-gray-200 bg-white shadow-lg sm:absolute sm:inset-x-auto sm:right-0 sm:top-11 sm:w-80">
          <div className="flex items-center justify-between border-b border-gray-100 px-4 py-2.5">
            <p className="font-semibold">알림</p>
            {unread > 0 ? (
              <button type="button" onClick={handleReadAll} className="text-xs text-gray-500 hover:text-gray-900">
                모두 읽음
              </button>
            ) : null}
          </div>
          {data && data.items.length > 0 ? (
            <ul className="max-h-96 overflow-y-auto">
              {data.items.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => handleClick(item)}
                    className={`w-full px-4 py-3 text-left text-sm hover:bg-gray-50 ${item.read ? 'text-gray-500' : 'bg-orange-50/50 text-gray-900'}`}
                  >
                    <p>{item.message}</p>
                    <p className="mt-1 text-xs text-gray-400">{formatDateTime(item.createdAt)}</p>
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="px-4 py-8 text-center text-sm text-gray-500">아직 알림이 없어요</p>
          )}
        </div>
      ) : null}
    </div>
  )
}
