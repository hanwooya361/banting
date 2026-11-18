import { useState } from 'react'
import { useNavigate } from 'react-router'
import { ApiError } from '../lib/api'
import { won, type GroupBuyDetail } from '../lib/groupBuy'
import { startPayment } from '../lib/payment'

// S04 참여 수량 선택 (하단 시트). 수량별 공구는 최소 수량 ~ 남은 자리, 균등은 1자리.
// 여기 금액은 미리보기이고, 실제 결제 금액은 서버가 계산해 주문번호와 함께 준다
export function ParticipateSheet({ groupBuy: g, onClose }: { groupBuy: GroupBuyDetail; onClose: () => void }) {
  const navigate = useNavigate()
  const remaining = g.capacityUnits - g.reservedUnits
  const byQuantity = g.priceType === 'BY_QUANTITY'
  const min = byQuantity ? g.minUnitsPerPerson : 1
  const max = byQuantity ? remaining : 1
  const [units, setUnits] = useState(min)
  const [agreed, setAgreed] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handlePay = async () => {
    setSubmitting(true)
    setError(null)
    try {
      const order = await startPayment(g.id, units)
      navigate(`/payments/checkout?orderId=${encodeURIComponent(order.orderId)}`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 z-30 flex items-end justify-center bg-black/40 sm:items-center" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="participate-title"
        className="w-full max-w-lg rounded-t-2xl bg-white p-5 shadow-xl sm:rounded-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <h2 id="participate-title" className="text-lg font-bold">
            참여하기
          </h2>
          <button type="button" onClick={onClose} className="text-sm text-gray-500" aria-label="닫기">
            닫기
          </button>
        </div>

        {byQuantity ? (
          <div className="mt-4">
            <p className="text-sm text-gray-600">
              몇 {g.unitLabel} 가져갈까요? (최소 {min}
              {g.unitLabel}, 남은 {remaining}
              {g.unitLabel})
            </p>
            <div className="mt-2 flex items-center gap-3">
              <button
                type="button"
                onClick={() => setUnits((u) => Math.max(min, u - 1))}
                disabled={units <= min}
                className="h-10 w-10 rounded-full border border-gray-300 text-lg disabled:opacity-40"
                aria-label="하나 빼기"
              >
                −
              </button>
              <span className="min-w-16 text-center text-xl font-bold" aria-live="polite">
                {units}
                {g.unitLabel}
              </span>
              <button
                type="button"
                onClick={() => setUnits((u) => Math.min(max, u + 1))}
                disabled={units >= max}
                className="h-10 w-10 rounded-full border border-gray-300 text-lg disabled:opacity-40"
                aria-label="하나 더하기"
              >
                +
              </button>
            </div>
          </div>
        ) : (
          <p className="mt-4 text-sm text-gray-600">한 사람이 한 몫씩 나눠요.</p>
        )}

        <div className="mt-5 flex items-baseline justify-between border-t border-gray-100 pt-4">
          <span className="text-gray-600">내 결제 금액</span>
          <span className="text-2xl font-bold">{won(g.unitPrice * units)}</span>
        </div>

        <label className="mt-4 flex items-start gap-2 rounded-lg bg-gray-50 p-3 text-xs leading-relaxed text-gray-600">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            className="mt-0.5 h-4 w-4 accent-orange-500"
          />
          <span>
            반띵은 통신판매중개자로서 거래 당사자가 아니며, 상품 구매·전달에 대한 책임은 개설자와 참여자에게 있음을
            확인했어요. 마감까지 인원이 다 모이지 않으면 전액 자동 환불돼요.
          </span>
        </label>

        {error ? <p className="mt-3 text-sm text-red-600">{error}</p> : null}

        <button
          type="button"
          onClick={handlePay}
          disabled={!agreed || submitting}
          className="mt-4 w-full rounded-lg bg-orange-500 py-3 font-semibold text-white disabled:opacity-50"
        >
          {submitting ? '자리 잡는 중…' : `${won(g.unitPrice * units)} 결제하러 가기`}
        </button>
      </div>
    </div>
  )
}
