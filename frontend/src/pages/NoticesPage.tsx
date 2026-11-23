import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../lib/api'
import { formatDateTime } from '../lib/groupBuy'
import { fetchNotices, type Notice } from '../lib/inquiry'

// S12 업데이트 노트 (T17): 받은 피드백 → 반영 내용. 로그인 없이 볼 수 있다
export function NoticesPage() {
  const [items, setItems] = useState<Notice[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    fetchNotices()
      .then((list) => active && setItems(list))
      .catch((err) => active && setError(err instanceof ApiError ? err.message : '노트를 불러오지 못했어요'))
    return () => {
      active = false
    }
  }, [])

  return (
    <div className="flex flex-col gap-4">
      <div>
        <h1 className="text-xl font-bold">업데이트 노트</h1>
        <p className="mt-1 text-sm text-gray-500">
          받은 피드백으로 무엇을 고쳤는지 기록해요. 의견은{' '}
          <Link to="/inquiries" className="underline">
            문의·피드백
          </Link>
          에 남겨 주세요.
        </p>
      </div>
      {error ? <p className="text-sm text-red-600">{error}</p> : null}
      {items === null && !error ? <p className="text-sm text-gray-500">불러오는 중…</p> : null}
      {items?.length === 0 ? (
        <p className="rounded-xl border border-gray-200 py-8 text-center text-sm text-gray-500">아직 노트가 없어요</p>
      ) : null}
      <ul className="flex flex-col gap-3">
        {items?.map((n) => (
          <li key={n.id} className="rounded-xl border border-gray-200 p-4">
            <p className="text-xs text-gray-400">{formatDateTime(n.createdAt)}</p>
            <h2 className="mt-1 font-semibold">{n.title}</h2>
            <p className="mt-2 whitespace-pre-line break-keep text-sm text-gray-700 wrap-anywhere">{n.content}</p>
          </li>
        ))}
      </ul>
    </div>
  )
}
