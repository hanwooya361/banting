import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, ApiError } from '../lib/api'
import { CATEGORY_LABELS, formatDateTime, STATUS_BADGES, won, type GroupBuyDetail } from '../lib/groupBuy'
import { useAuth } from '../auth/context'

export function GroupBuyDetailPage() {
  const { id } = useParams()
  const { loading: authLoading } = useAuth()
  const navigate = useNavigate()
  const [groupBuy, setGroupBuy] = useState<GroupBuyDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    // 로그인 복원(refresh)이 끝난 뒤 불러와야 내 공구 여부가 정확하다
    if (authLoading) return
    let active = true   // 다른 공구로 이동한 뒤 도착한 이전 응답은 버린다
    api<GroupBuyDetail>(`/api/group-buys/${id}`)
      .then((g) => {
        if (active) setGroupBuy(g)
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
      })
    return () => {
      active = false
    }
  }, [id, authLoading])

  if (error) return <p className="py-16 text-center text-gray-600">{error}</p>
  if (!groupBuy) return <p className="py-16 text-center text-gray-500">불러오는 중…</p>

  const g = groupBuy
  const badge = STATUS_BADGES[g.status]
  const unitWord = g.priceType === 'EQUAL' ? '명' : g.unitLabel
  const progress = Math.min(100, Math.round((g.reservedUnits / g.capacityUnits) * 100))
  const editable = g.mine && (g.status === 'DRAFT' || g.status === 'OPEN')

  const handleDelete = async () => {
    if (!window.confirm('이 공구를 삭제할까요? 되돌릴 수 없어요.')) return
    setDeleting(true)
    try {
      await api(`/api/group-buys/${g.id}`, { method: 'DELETE' })
      navigate('/group-buys', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '삭제하지 못했어요')
      setDeleting(false)
    }
  }

  return (
    <article className="mx-auto flex max-w-lg flex-col gap-5">
      {g.images.length > 0 ? (
        <div className="flex snap-x gap-2 overflow-x-auto">
          {g.images.map((image, index) => (
            <img
              key={image.key}
              src={image.url}
              alt={`${g.title} 사진 ${index + 1}`}
              className="aspect-square w-full flex-none snap-center rounded-xl object-cover sm:w-80"
            />
          ))}
        </div>
      ) : null}

      <header className="flex flex-col gap-2">
        <div className="flex items-center gap-2 text-sm">
          <span className={`rounded-full px-2.5 py-0.5 font-medium ${badge.className}`}>{badge.label}</span>
          <span className="text-gray-500">
            {CATEGORY_LABELS[g.category]} · {g.neighborhoodName} · {g.host.nickname}
          </span>
        </div>
        <h1 className="text-2xl font-bold">{g.title}</h1>
      </header>

      {g.demo ? (
        <div className="rounded-xl border border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-700">
          <p className="font-medium">체험 동네의 예시 공구예요</p>
          <p className="mt-1">실제로 참여하거나 결제할 수는 없어요. 반띵이 어떻게 보이는지 둘러보세요.</p>
        </div>
      ) : null}

      {g.status === 'DRAFT' && g.mine ? (
        <div className="rounded-xl border border-orange-200 bg-orange-50 px-4 py-3 text-sm">
          <p className="font-medium text-orange-800">내 몫을 결제하면 공구가 공개돼요</p>
          <p className="mt-1 text-orange-700">
            결제할 금액 <strong>{won(g.myAmount ?? 0)}</strong> · 결제 기능은 곧 열려요. 그 전까지는 나에게만 보여요.
          </p>
        </div>
      ) : null}

      <section className="rounded-xl border border-gray-200 p-4">
        <p className="text-sm text-gray-500">{g.priceType === 'EQUAL' ? '1명당' : `${g.unitLabel} 1개당`}</p>
        <p className="text-2xl font-bold">{won(g.unitPrice)}</p>
        <p className="mt-1 text-sm text-gray-500">
          총 {won(g.totalPrice)} · {g.totalQuantity.toLocaleString()}
          {g.unitLabel}
          {g.priceType === 'BY_QUANTITY' ? ` · 1인 최소 ${g.minUnitsPerPerson}${g.unitLabel}` : ''}
        </p>
        <div className="mt-4">
          <div className="h-2 overflow-hidden rounded-full bg-gray-100" role="progressbar" aria-valuenow={progress} aria-valuemin={0} aria-valuemax={100}>
            <div className="h-full bg-orange-500" style={{ width: `${progress}%` }} />
          </div>
          <p className="mt-1 text-sm text-gray-600">
            {g.capacityUnits}
            {unitWord} 중 {g.reservedUnits}
            {unitWord} 참여 · {formatDateTime(g.deadlineAt)} 마감
          </p>
        </div>
      </section>

      {/* 예시 공구의 링크는 example.com이라 숨긴다 */}
      {g.demo ? null : (
        <a
          href={g.productUrl}
          target="_blank"
          rel="noopener noreferrer nofollow"
          className="rounded-lg border border-gray-300 py-2.5 text-center font-medium"
        >
          상품 보러 가기 ↗
        </a>
      )}

      {g.description ? <p className="whitespace-pre-line text-gray-800">{g.description}</p> : null}

      <section>
        <h2 className="mb-2 font-semibold">수령 후보</h2>
        <ul className="flex flex-col gap-2">
          {g.pickupOptions.map((option) => (
            <li key={option.id} className="rounded-lg bg-gray-50 px-3 py-2 text-sm">
              {formatDateTime(option.pickupAt)} · {option.placeName}
            </li>
          ))}
        </ul>
      </section>

      {editable ? (
        <div className="flex gap-2">
          <Link
            to={`/group-buys/${g.id}/edit`}
            className="flex-1 rounded-lg border border-gray-300 py-2.5 text-center font-medium"
          >
            수정
          </Link>
          {g.status === 'DRAFT' ? (
            <button
              type="button"
              onClick={handleDelete}
              disabled={deleting}
              className="flex-1 rounded-lg border border-red-300 py-2.5 font-medium text-red-600 disabled:opacity-50"
            >
              삭제
            </button>
          ) : null}
        </div>
      ) : null}
    </article>
  )
}
