import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ANONYMOUS, loadTossPayments, type TossPaymentsWidgets } from '@tosspayments/tosspayments-sdk'
import { loadOrder, minutesLeft, TOSS_CLIENT_KEY } from '../lib/payment'
import { won } from '../lib/groupBuy'

// S05 결제: 토스 결제위젯(결제수단 + 약관)을 그리고 결제를 요청한다.
// 결제가 끝나면 토스가 successUrl/failUrl로 보내고, 실제 승인은 성공 페이지에서 서버가 한다.
export function CheckoutPage() {
  const [params] = useSearchParams()
  const orderId = params.get('orderId') ?? ''
  const [order] = useState(() => loadOrder(orderId))
  const widgetsRef = useRef<TossPaymentsWidgets | null>(null)
  const [ready, setReady] = useState(false)
  const [requesting, setRequesting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!order) return
    let active = true
    ;(async () => {
      try {
        const toss = await loadTossPayments(TOSS_CLIENT_KEY)
        // 비회원 결제처럼 구매자 식별 없이 진행 (토스 쪽에 우리 회원 정보를 넘기지 않음)
        const widgets = toss.widgets({ customerKey: ANONYMOUS })
        await widgets.setAmount({ currency: 'KRW', value: order.amount })
        if (!active) return
        await Promise.all([
          widgets.renderPaymentMethods({ selector: '#payment-method', variantKey: 'DEFAULT' }),
          widgets.renderAgreement({ selector: '#agreement', variantKey: 'AGREEMENT' }),
        ])
        if (!active) return
        widgetsRef.current = widgets
        setReady(true)
      } catch {
        if (active) setError('결제창을 불러오지 못했어요. 새로고침해 주세요.')
      }
    })()
    return () => {
      active = false
    }
  }, [order])

  if (!order) {
    return (
      <div className="py-16 text-center">
        <p className="text-gray-600">결제 정보를 찾을 수 없어요.</p>
        <Link to="/group-buys" className="mt-4 inline-block text-orange-600 underline">
          공구 목록으로
        </Link>
      </div>
    )
  }

  const handlePay = async () => {
    const widgets = widgetsRef.current
    if (!widgets) return
    setRequesting(true)
    setError(null)
    const back = `groupBuyId=${order.groupBuyId}`
    try {
      await widgets.requestPayment({
        orderId: order.orderId,
        orderName: order.orderName,
        successUrl: `${window.location.origin}/payments/success?${back}`,
        failUrl: `${window.location.origin}/payments/fail?${back}`,
      })
    } catch {
      // 결제창을 닫는 등 요청 단계에서 끝나면 여기로 온다. 결제 대기 시간 안에는 다시 누를 수 있다
      setRequesting(false)
    }
  }

  return (
    <div className="mx-auto flex max-w-lg flex-col gap-4">
      <h1 className="text-xl font-bold">결제하기</h1>
      <section className="rounded-xl border border-gray-200 p-4">
        <p className="text-sm text-gray-500">{order.orderName}</p>
        <p className="mt-1 text-2xl font-bold">{won(order.amount)}</p>
        <p className="mt-1 text-xs text-gray-500">
          {minutesLeft(order.expiresAt)}분 안에 결제하면 자리가 확정돼요. 인원이 다 모이지 않으면 전액 자동 환불돼요.
        </p>
      </section>

      <p className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800">
        테스트 결제예요. 실제로 돈이 나가지 않아요.
      </p>

      <div id="payment-method" />
      <div id="agreement" />

      {error ? <p className="text-sm text-red-600">{error}</p> : null}

      <button
        type="button"
        onClick={handlePay}
        disabled={!ready || requesting}
        className="rounded-lg bg-orange-500 py-3 font-semibold text-white disabled:opacity-50"
      >
        {ready ? `${won(order.amount)} 결제하기` : '결제창 불러오는 중…'}
      </button>
      <Link to={`/group-buys/${order.groupBuyId}`} className="text-center text-sm text-gray-500 underline">
        공구로 돌아가기
      </Link>
    </div>
  )
}
