import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, ApiError } from '../lib/api'
import { beforeDeadline, CATEGORY_LABELS, formatDateTime, STATUS_BADGES, won, type GroupBuyDetail } from '../lib/groupBuy'
import { useAuth } from '../auth/context'
import { ParticipateSheet } from '../components/ParticipateSheet'
import { cancelGroupBuy, cancelParticipation, minutesLeft, startPayment } from '../lib/payment'
import { markPurchased, receive, votePickup } from '../lib/pickup'

export function GroupBuyDetailPage() {
  const { id } = useParams()
  const { me, loading: authLoading } = useAuth()
  const navigate = useNavigate()
  const [groupBuy, setGroupBuy] = useState<GroupBuyDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [paying, setPaying] = useState(false)
  const [payError, setPayError] = useState<string | null>(null)
  // 취소 후 상세를 다시 불러오기 위한 값 (바뀌면 effect가 다시 돈다)
  const [reloadKey, setReloadKey] = useState(0)
  const [notice, setNotice] = useState<string | null>(null)

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
  }, [id, authLoading, reloadKey])

  if (error) return <p className="py-16 text-center text-gray-600">{error}</p>
  if (!groupBuy) return <p className="py-16 text-center text-gray-500">불러오는 중…</p>

  const g = groupBuy
  const badge = STATUS_BADGES[g.status]
  const unitWord = g.priceType === 'EQUAL' ? '명' : g.unitLabel
  const progress = Math.min(100, Math.round((g.reservedUnits / g.capacityUnits) * 100))
  const editable = g.mine && (g.status === 'DRAFT' || g.status === 'OPEN')

  // 결제 대기 중인 내 참여(개설자 몫 포함)를 같은 수량으로 이어서 결제
  const resumePayment = async (units: number, host: boolean) => {
    setPaying(true)
    setPayError(null)
    try {
      const order = await startPayment(g.id, units, host)
      navigate(`/payments/checkout?orderId=${encodeURIComponent(order.orderId)}`)
    } catch (err) {
      setPayError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
      setPaying(false)
    }
  }

  // 참여 취소: 결제 전이면 바로, 결제 후면 전액 환불 (환불이 늦어지면 다시 눌러 재시도)
  const handleCancelParticipation = async () => {
    const mp = g.myParticipation
    if (!mp) return
    const message =
      mp.status === 'PENDING_PAYMENT'
        ? '결제를 취소하고 잡아 둔 자리를 풀까요?'
        : `참여를 취소할까요? ${won(mp.amount)}이 전액 환불돼요. 같은 공구에는 다시 참여할 수 없어요.`
    if (mp.status !== 'REFUND_PENDING' && !window.confirm(message)) return
    setPaying(true)
    setPayError(null)
    try {
      const result = await cancelParticipation(mp.id)
      setNotice(
        result.status === 'REFUND_PENDING'
          ? '환불을 접수했어요. 결제사 응답이 늦어 처리 중이에요. 잠시 후 다시 확인해 주세요.'
          : result.status === 'REFUNDED'
            ? `${won(result.refundAmount)} 환불했어요.`
            : '결제 대기를 취소했어요.',
      )
      setReloadKey((k) => k + 1)
    } catch (err) {
      setPayError(err instanceof ApiError ? err.message : '취소하지 못했어요')
    } finally {
      setPaying(false)
    }
  }

  // 개설자의 공구 취소: 무산 처리하고 참여자 전원(개설자 포함) 환불
  const handleCancelGroupBuy = async () => {
    if (!window.confirm('공구를 취소할까요? 참여한 이웃과 내 몫까지 모두 전액 환불되고, 되돌릴 수 없어요.')) return
    setDeleting(true)
    setError(null)
    try {
      const result = await cancelGroupBuy(g.id)
      setNotice(
        result.pendingRefunds > 0
          ? `${result.refunded}건 환불, ${result.pendingRefunds}건은 처리 중이에요. 잠시 후 '남은 환불 다시 시도'를 눌러 주세요.`
          : `공구를 취소하고 ${result.refunded}건 모두 환불했어요.`,
      )
      setReloadKey((k) => k + 1)
    } catch (err) {
      setNotice(err instanceof ApiError ? err.message : '취소하지 못했어요')
    } finally {
      setDeleting(false)
    }
  }

  // 개설자 "구매했어요" (T16)
  const handlePurchased = async () => {
    if (!window.confirm('상품을 샀나요? 참여자에게 약속 장소에서 만나자고 알려요.')) return
    setPaying(true)
    setPayError(null)
    try {
      await markPurchased(g.id)
      setNotice('참여자에게 알렸어요. 약속 장소에서 만나요.')
      setReloadKey((k) => k + 1)
    } catch (err) {
      setPayError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
    } finally {
      setPaying(false)
    }
  }

  // 참여자 "받았어요" (T16)
  const handleReceive = async () => {
    const mp = g.myParticipation
    if (!mp) return
    setPaying(true)
    setPayError(null)
    try {
      const result = await receive(mp.id)
      setNotice(result.groupBuyCompleted ? '모두 받아서 공구가 완료됐어요. 반띵 성공!' : '수령을 확인했어요.')
      setReloadKey((k) => k + 1)
    } catch (err) {
      setPayError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
    } finally {
      setPaying(false)
    }
  }

  const openSheet = () => {
    if (!me) {
      navigate('/login', { state: { from: `/group-buys/${g.id}` } })
      return
    }
    setSheetOpen(true)
  }

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

      {g.status === 'FAILED' ? (
        <div className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
          <p className="font-medium">무산된 공구예요</p>
          <p className="mt-1">
            {g.failReason === 'HOST_CANCELED'
              ? '개설자가 공구를 취소해서 결제한 금액을 전액 환불했어요.'
              : '인원이 모이지 않아 결제한 금액을 전액 환불했어요.'}
          </p>
        </div>
      ) : null}

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
            결제할 금액 <strong>{won(g.myAmount ?? 0)}</strong> · 결제 전까지는 나에게만 보여요.
          </p>
          {g.myParticipation?.status === 'PENDING_PAYMENT' ? (
            <button
              type="button"
              onClick={() => resumePayment(g.myParticipation!.units, true)}
              disabled={paying}
              className="mt-3 w-full rounded-lg bg-orange-500 py-2.5 font-semibold text-white disabled:opacity-50"
            >
              {paying ? '준비 중…' : `내 몫 ${won(g.myAmount ?? 0)} 결제하기`}
            </button>
          ) : null}
          {payError ? <p className="mt-2 text-red-600">{payError}</p> : null}
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

      <PickupSection groupBuy={g} onVoted={() => setReloadKey((k) => k + 1)} />

      {notice ? (
        <p role="status" className="rounded-lg bg-gray-900 px-4 py-3 text-sm text-white">
          {notice}
        </p>
      ) : null}

      {/* 페이지 맨 아래에 두어 스크롤하는 동안 화면 아래에 붙어 있게 (sticky) */}
      {!g.mine && !g.demo ? (
        <ParticipationBox
          groupBuy={g}
          paying={paying}
          payError={payError}
          onJoin={openSheet}
          onResume={resumePayment}
          onCancel={handleCancelParticipation}
          onReceive={handleReceive}
        />
      ) : null}

      {g.mine && g.status === 'CONFIRMED' ? (
        <div className="rounded-xl border border-green-200 bg-green-50 px-4 py-3 text-sm">
          <p className="font-semibold text-green-800">다 모였어요. 이제 상품을 사 주세요</p>
          <p className="mt-1 text-green-700">사고 나서 버튼을 누르면 참여자에게 약속 장소에서 만나자고 알려요.</p>
          <button
            type="button"
            onClick={handlePurchased}
            disabled={paying}
            className="mt-3 w-full rounded-lg bg-green-600 py-2.5 font-semibold text-white disabled:opacity-50"
          >
            {paying ? '처리 중…' : '구매했어요'}
          </button>
          {payError ? <p className="mt-2 text-red-600">{payError}</p> : null}
        </div>
      ) : null}
      {g.mine && g.status === 'PICKUP' ? (
        <p className="rounded-xl bg-blue-50 px-4 py-3 text-sm text-blue-800">
          나눔 준비 중이에요. 참여자가 모두 받았어요를 누르거나 수령 일정 3일 뒤에 공구가 완료되고 정산돼요.
        </p>
      ) : null}
      {g.mine && g.settlement ? (
        <div className="rounded-xl border border-gray-200 px-4 py-3 text-sm">
          <p className="font-semibold">정산 예정 {won(g.settlement.netAmount)}</p>
          <p className="mt-1 text-gray-600">
            결제 총액 {won(g.settlement.grossAmount)} − 수수료 {won(g.settlement.feeAmount)}
          </p>
        </div>
      ) : null}

      {g.mine && g.status === 'OPEN' ? (
        <button
          type="button"
          onClick={handleCancelGroupBuy}
          disabled={deleting}
          className="rounded-lg border border-red-300 py-2.5 font-medium text-red-600 disabled:opacity-50"
        >
          {deleting ? '취소하는 중…' : '공구 취소 (전원 환불)'}
        </button>
      ) : null}
      {g.mine && g.failReason === 'HOST_CANCELED' ? (
        <button
          type="button"
          onClick={handleCancelGroupBuy}
          disabled={deleting}
          className="text-sm text-gray-500 underline disabled:opacity-50"
        >
          남은 환불 다시 시도
        </button>
      ) : null}

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

      {sheetOpen ? <ParticipateSheet groupBuy={g} onClose={() => setSheetOpen(false)} /> : null}
    </article>
  )
}

// 결제한 참여자에게 공구 상태별로 보여 줄 문구 (화면 설계 3장)
const PAID_STATUS_COPY: Partial<Record<GroupBuyDetail['status'], string>> = {
  OPEN: '다 모이면 자동으로 확정돼요.',
  CONFIRMED: '다 모여서 확정됐어요! 개설자가 상품을 사면 알려 드려요.',
  PICKUP: '개설자가 상품을 샀어요. 약속 장소에서 만나요.',
  COMPLETED: '완료된 공구예요.',
}

// 참여자 쪽 상태별 안내와 버튼
function ParticipationBox({
  groupBuy: g,
  paying,
  payError,
  onJoin,
  onResume,
  onCancel,
  onReceive,
}: {
  groupBuy: GroupBuyDetail
  paying: boolean
  payError: string | null
  onJoin: () => void
  onResume: (units: number, host: boolean) => void
  onCancel: () => void
  onReceive: () => void
}) {
  const mp = g.myParticipation
  const unitWord = g.priceType === 'EQUAL' ? '명' : g.unitLabel

  if (mp && (mp.status === 'PAID' || mp.status === 'RECEIVED')) {
    return (
      <div className="rounded-xl border border-orange-200 bg-orange-50 px-4 py-3 text-sm">
        <p className="font-semibold text-orange-800">참여 완료</p>
        <p className="mt-1 text-orange-700">
          {g.priceType === 'EQUAL' ? '1몫' : `${mp.units}${g.unitLabel}`} · {won(mp.amount)} 결제함.{' '}
          {PAID_STATUS_COPY[g.status] ?? ''}
        </p>
        {mp.status === 'PAID' && (g.status === 'PICKUP' || g.status === 'COMPLETED') ? (
          <button
            type="button"
            onClick={onReceive}
            disabled={paying}
            className="mt-3 w-full rounded-lg bg-orange-500 py-2.5 font-semibold text-white disabled:opacity-50"
          >
            {paying ? '처리 중…' : '받았어요'}
          </button>
        ) : null}
        {mp.status === 'RECEIVED' ? <p className="mt-2 font-medium text-orange-800">수령 확인 완료</p> : null}
        {mp.status === 'PAID' && g.status === 'OPEN' && beforeDeadline(g.deadlineAt) ? (
          <button
            type="button"
            onClick={onCancel}
            disabled={paying}
            className="mt-2 text-xs text-gray-500 underline disabled:opacity-50"
          >
            참여 취소하고 환불받기
          </button>
        ) : null}
        {payError ? <p className="mt-2 text-red-600">{payError}</p> : null}
      </div>
    )
  }
  if (mp?.status === 'REFUND_PENDING') {
    return (
      <div className="rounded-xl bg-gray-50 px-4 py-3 text-sm text-gray-700">
        <p className="font-medium">환불 처리 중이에요</p>
        <p className="mt-1">결제사 응답이 늦어지고 있어요. 잠시 후 아래 버튼으로 다시 요청해 주세요.</p>
        <button
          type="button"
          onClick={onCancel}
          disabled={paying}
          className="mt-2 text-xs text-gray-600 underline disabled:opacity-50"
        >
          환불 다시 요청
        </button>
        {payError ? <p className="mt-2 text-red-600">{payError}</p> : null}
      </div>
    )
  }
  if (mp?.status === 'REFUNDED') {
    return <p className="rounded-xl bg-gray-50 px-4 py-3 text-sm text-gray-600">취소하고 환불받은 공구예요.</p>
  }
  if (g.status !== 'OPEN') return null

  if (mp?.status === 'PENDING_PAYMENT' && minutesLeft(mp.expiresAt) > 0) {
    return (
      <div className="rounded-xl border border-orange-200 bg-orange-50 px-4 py-3 text-sm">
        <p className="font-semibold text-orange-800">자리를 잡아 뒀어요. 결제를 마무리해 주세요</p>
        <p className="mt-1 text-orange-700">{minutesLeft(mp.expiresAt)}분 안에 결제하지 않으면 자리가 풀려요.</p>
        <button
          type="button"
          onClick={() => onResume(mp.units, false)}
          disabled={paying}
          className="mt-3 w-full rounded-lg bg-orange-500 py-2.5 font-semibold text-white disabled:opacity-50"
        >
          {paying ? '준비 중…' : `${won(mp.amount)} 결제 이어 하기`}
        </button>
        <button
          type="button"
          onClick={onCancel}
          disabled={paying}
          className="mt-2 w-full text-xs text-gray-500 underline disabled:opacity-50"
        >
          결제 대기 취소
        </button>
        {payError ? <p className="mt-2 text-red-600">{payError}</p> : null}
      </div>
    )
  }

  const full = g.reservedUnits >= g.capacityUnits
  return (
    <div className="sticky bottom-3 z-10">
      <button
        type="button"
        onClick={onJoin}
        disabled={full}
        className="w-full rounded-xl bg-orange-500 py-3.5 text-lg font-semibold text-white shadow-lg shadow-orange-500/20 disabled:bg-gray-300 disabled:shadow-none"
      >
        {full ? `${unitWord === '명' ? '자리' : unitWord}가 다 찼어요` : `참여하기 · ${won(g.unitPrice)}${g.priceType === 'EQUAL' ? '' : `/${g.unitLabel}`}`}
      </button>
    </div>
  )
}

// 수령 후보와 투표 (T15). 결제를 마친 참여자·개설자는 모집 중에 투표, 확정되면 1위 후보가 수령 일정
function PickupSection({ groupBuy: g, onVoted }: { groupBuy: GroupBuyDetail; onVoted: () => void }) {
  const [voting, setVoting] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const selected = g.pickupOptions.find((o) => o.selected)
  const several = g.pickupOptions.length > 1
  const canVote =
    several && !g.demo && g.status === 'OPEN' && beforeDeadline(g.deadlineAt) && g.myParticipation?.status === 'PAID'

  const handleVote = async (optionId: number) => {
    setVoting(optionId)
    setError(null)
    try {
      await votePickup(g.id, optionId)
      onVoted()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '투표하지 못했어요')
    } finally {
      setVoting(null)
    }
  }

  return (
    <section className="flex flex-col gap-2">
      {selected ? (
        <div className="rounded-xl border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-800">
          <p className="font-semibold">다 모였어요</p>
          <p className="mt-1">
            수령 일정: <strong>{formatDateTime(selected.pickupAt)}</strong>, {selected.placeName}
          </p>
        </div>
      ) : null}
      <div className="flex items-baseline justify-between">
        <h2 className="font-semibold">수령 후보</h2>
        {canVote ? <p className="text-xs text-gray-500">편한 일정을 골라 주세요. 다 모이면 1위 일정으로 정해져요</p> : null}
      </div>
      <ul className="flex flex-col gap-2">
        {g.pickupOptions.map((option) => {
          const mine = g.myPickupVoteOptionId === option.id
          const label = (
            <>
              <span>
                {formatDateTime(option.pickupAt)} · {option.placeName}
              </span>
              {several ? (
                <span className={`shrink-0 text-xs ${mine ? 'font-semibold text-orange-600' : 'text-gray-500'}`}>
                  {mine ? '내 선택 · ' : ''}
                  {option.voteCount}표
                </span>
              ) : null}
            </>
          )
          const base = 'flex w-full items-center justify-between gap-2 rounded-lg px-3 py-2 text-left text-sm'
          const tone = option.selected
            ? 'bg-green-50 ring-1 ring-green-300'
            : mine
              ? 'bg-orange-50 ring-1 ring-orange-300'
              : 'bg-gray-50'
          return (
            <li key={option.id}>
              {canVote ? (
                <button
                  type="button"
                  onClick={() => handleVote(option.id)}
                  disabled={voting !== null || mine}
                  aria-pressed={mine}
                  className={`${base} ${tone} hover:ring-1 hover:ring-orange-300 disabled:cursor-default`}
                >
                  {voting === option.id ? <span>투표하는 중…</span> : label}
                </button>
              ) : (
                <div className={`${base} ${tone}`}>{label}</div>
              )}
            </li>
          )
        })}
      </ul>
      {error ? <p className="text-sm text-red-600">{error}</p> : null}
    </section>
  )
}
