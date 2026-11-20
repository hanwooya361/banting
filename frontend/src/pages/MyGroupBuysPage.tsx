import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../lib/api'
import { formatDateTime, STATUS_BADGES, won } from '../lib/groupBuy'
import { fetchMyGroupBuys, type MyGroupBuyItem, type MyGroupBuys } from '../lib/myGroupBuys'
import { receive } from '../lib/pickup'

type Tab = keyof MyGroupBuys

const TABS: { key: Tab; label: string; empty: string }[] = [
  { key: 'active', label: '참여 중', empty: '참여 중인 공구가 없어요. 동네 공구를 둘러보세요.' },
  { key: 'hosting', label: '개설한', empty: '연 공구가 없어요. 혼자 사기 많은 물건이 있다면 공구를 열어 보세요.' },
  { key: 'past', label: '지난 공구', empty: '끝난 공구가 여기에 모여요.' },
]

const TODO_COPY = {
  PAY: '결제를 마무리해 주세요',
  PURCHASE: '다 모였어요. 상품을 사고 구매했어요를 눌러 주세요',
  RECEIVE: '받으면 아래 버튼을 눌러 주세요',
} as const

// 내 공구 (S07): 참여 중 / 개설한 / 지난 공구, 지금 할 일이 있는 공구가 맨 위
export function MyGroupBuysPage() {
  const [data, setData] = useState<MyGroupBuys | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('active')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let active = true
    fetchMyGroupBuys()
      .then((d) => {
        if (active) setData(d)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
      })
    return () => {
      active = false
    }
  }, [reloadKey])

  if (error) return <p className="py-16 text-center text-gray-600">{error}</p>
  if (!data) return <p className="py-16 text-center text-gray-500">불러오는 중…</p>

  const items = data[tab]
  const current = TABS.find((t) => t.key === tab)!

  return (
    <section className="mx-auto flex max-w-lg flex-col gap-4">
      <h1 className="text-2xl font-bold">내 공구</h1>
      <div role="tablist" className="flex gap-1 rounded-xl bg-gray-100 p-1">
        {TABS.map((t) => {
          const todoCount = data[t.key].filter((i) => i.todo).length
          return (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={tab === t.key}
              onClick={() => setTab(t.key)}
              className={`flex flex-1 items-center justify-center gap-1 rounded-lg py-2 text-sm font-medium ${
                tab === t.key ? 'bg-white shadow-sm' : 'text-gray-500'
              }`}
            >
              {t.label} {data[t.key].length}
              {todoCount > 0 ? (
                <span className="h-1.5 w-1.5 rounded-full bg-orange-500" aria-label={`할 일 ${todoCount}개`} />
              ) : null}
            </button>
          )
        })}
      </div>

      {items.length === 0 ? (
        <div className="py-12 text-center text-sm text-gray-500">
          <p>{current.empty}</p>
          {tab === 'active' ? (
            <Link to="/group-buys" className="mt-3 inline-block font-medium text-orange-600 underline">
              동네 공구 보기
            </Link>
          ) : null}
        </div>
      ) : (
        <ul role="tabpanel" className="flex flex-col gap-3">
          {items.map((item) => (
            <MyGroupBuyCard key={item.participationId} item={item} onChanged={() => setReloadKey((k) => k + 1)} />
          ))}
        </ul>
      )}
    </section>
  )
}

function MyGroupBuyCard({ item, onChanged }: { item: MyGroupBuyItem; onChanged: () => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const badge = STATUS_BADGES[item.status]

  const handleReceive = async () => {
    setBusy(true)
    setError(null)
    try {
      await receive(item.participationId)
      onChanged()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
      setBusy(false)
    }
  }

  return (
    <li
      className={`overflow-hidden rounded-xl border ${item.todo ? 'border-orange-300 bg-orange-50/40' : 'border-gray-200'}`}
    >
      <Link to={`/group-buys/${item.groupBuyId}`} className="flex gap-3 p-3">
        {item.thumbnailUrl ? (
          <img src={item.thumbnailUrl} alt="" className="h-16 w-16 flex-none rounded-lg object-cover" />
        ) : (
          <div className="h-16 w-16 flex-none rounded-lg bg-gray-100" aria-hidden="true" />
        )}
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-1.5 text-xs">
            <span className={`rounded-full px-2 py-0.5 font-medium ${badge.className}`}>{badge.label}</span>
            {item.host ? <span className="text-gray-500">내가 연 공구</span> : null}
          </div>
          <p className="mt-1 truncate font-semibold">{item.title}</p>
          <p className="mt-0.5 text-xs text-gray-500">{subline(item)}</p>
        </div>
      </Link>
      {item.todo ? (
        <div className="border-t border-orange-200 px-3 py-2.5 text-sm">
          <p className="font-medium text-orange-800">{TODO_COPY[item.todo]}</p>
          {item.todo === 'RECEIVE' ? (
            <button
              type="button"
              onClick={handleReceive}
              disabled={busy}
              className="mt-2 w-full rounded-lg bg-orange-500 py-2 font-semibold text-white disabled:opacity-50"
            >
              {busy ? '처리 중…' : '받았어요'}
            </button>
          ) : null}
          {error ? <p className="mt-1 text-red-600">{error}</p> : null}
        </div>
      ) : null}
    </li>
  )
}

// 카드 둘째 줄: 상태에 맞춰 가장 궁금한 정보 하나
function subline(item: MyGroupBuyItem) {
  const paid = `${won(item.amount)} 결제`
  if (item.participationStatus === 'REFUND_PENDING') return `${won(item.amount)} 환불 처리 중`
  if (item.participationStatus === 'REFUNDED') return `${won(item.amount)} 환불됨`
  if (item.status === 'FAILED') return item.failReason === 'HOST_CANCELED' ? '개설자가 취소했어요' : '인원이 모이지 않았어요'
  if (item.participationStatus === 'PENDING_PAYMENT') return `${won(item.amount)} 결제 대기`
  if (item.pickupAt && (item.status === 'CONFIRMED' || item.status === 'PICKUP')) {
    return `수령 ${formatDateTime(item.pickupAt)} · ${item.pickupPlace}`
  }
  if (item.status === 'COMPLETED') return item.participationStatus === 'RECEIVED' ? `${paid} · 수령 확인함` : `${paid} · 완료`
  return `${paid} · ${formatDateTime(item.deadlineAt)} 마감`
}
