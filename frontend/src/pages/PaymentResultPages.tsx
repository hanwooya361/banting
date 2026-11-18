import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { ApiError } from '../lib/api'
import { confirmPayment, failMessage, loadOrder, type ConfirmResult } from '../lib/payment'
import { won } from '../lib/groupBuy'

// S06 결제 결과. 토스가 successUrl에 paymentKey·orderId·amount를 붙여 보내면 서버에 승인을 요청한다.
// 승인 전까지는 돈이 확정되지 않는다 (서버가 금액·만료를 다시 확인하고 토스 승인 API를 부름)
export function PaymentSuccessPage() {
  const [params] = useSearchParams()
  const paymentKey = params.get('paymentKey') ?? ''
  const orderId = params.get('orderId') ?? ''
  const amount = Number(params.get('amount'))
  const groupBuyId = params.get('groupBuyId')
  const valid = Boolean(paymentKey && orderId && Number.isFinite(amount) && amount > 0)
  const [result, setResult] = useState<ConfirmResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!valid) return
    let active = true
    confirmPayment(paymentKey, orderId, amount)
      .then((r) => active && setResult(r))
      .catch((err) => active && setError(err instanceof ApiError ? err.message : '결제를 확인하지 못했어요'))
    return () => {
      active = false
    }
  }, [valid, paymentKey, orderId, amount])

  const detailPath = `/group-buys/${result?.groupBuyId ?? groupBuyId ?? ''}`

  if (!valid || error) {
    return (
      <ResultCard tone="error" title="결제를 마치지 못했어요" body={error ?? '결제 정보가 올바르지 않아요.'}>
        <Link to={detailPath} className="rounded-lg bg-orange-500 px-5 py-2.5 font-medium text-white">
          공구로 돌아가기
        </Link>
      </ResultCard>
    )
  }
  if (!result) {
    return <p className="py-16 text-center text-gray-500">결제를 확인하고 있어요…</p>
  }

  const { title, body } = successCopy(result, loadOrder(orderId)?.host ?? false)
  return (
    <ResultCard tone="success" title={title} body={body}>
      <p className="text-sm text-gray-500">결제 금액 {won(result.amount)}</p>
      <Link to={detailPath} className="rounded-lg bg-orange-500 px-5 py-2.5 font-medium text-white">
        공구 보러 가기
      </Link>
    </ResultCard>
  )
}

function successCopy(r: ConfirmResult, host: boolean) {
  if (r.groupBuyStatus === 'CONFIRMED') {
    return { title: '다 모였어요! 반띵 확정', body: '수령 일정이 정해지면 알려드릴게요.' }
  }
  if (host) {
    return { title: '공구가 열렸어요!', body: '이제 동네 이웃에게 보여요. 인원이 다 모이면 자동으로 확정돼요.' }
  }
  return { title: '참여 완료!', body: '이웃이 다 모이면 자동으로 확정돼요. 못 모이면 전액 자동 환불돼요.' }
}

export function PaymentFailPage() {
  const [params] = useSearchParams()
  const groupBuyId = params.get('groupBuyId')
  return (
    <ResultCard tone="error" title="결제를 마치지 못했어요" body={failMessage(params.get('code'), params.get('message'))}>
      <p className="text-sm text-gray-500">자리는 10분 동안 잡아 둬요. 그 안에 다시 결제할 수 있어요.</p>
      <Link
        to={groupBuyId ? `/group-buys/${groupBuyId}` : '/group-buys'}
        className="rounded-lg bg-orange-500 px-5 py-2.5 font-medium text-white"
      >
        공구로 돌아가기
      </Link>
    </ResultCard>
  )
}

function ResultCard({
  tone,
  title,
  body,
  children,
}: {
  tone: 'success' | 'error'
  title: string
  body: string
  children: React.ReactNode
}) {
  return (
    <div className="mx-auto flex max-w-sm flex-col items-center gap-3 py-12 text-center">
      <span
        aria-hidden
        className={`flex h-14 w-14 items-center justify-center rounded-full text-2xl ${
          tone === 'success' ? 'bg-orange-100 text-orange-600' : 'bg-gray-100 text-gray-500'
        }`}
      >
        {tone === 'success' ? '✓' : '!'}
      </span>
      <h1 className="text-xl font-bold">{title}</h1>
      <p className="text-gray-600">{body}</p>
      {children}
    </div>
  )
}
