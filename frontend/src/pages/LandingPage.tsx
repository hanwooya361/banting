import { useEffect, useState } from 'react'
import { Link, Navigate } from 'react-router'
import { useAuth } from '../auth/context'
import { api } from '../lib/api'
import { listPath, won, type GroupBuyListItem, type GroupBuyListResponse } from '../lib/groupBuy'

// S00 표지. 처음 온 사람에게 "무슨 서비스인지"를 5초 안에 보여 주고 목록으로 보낸다.
// 로그인한 사람은 이미 아는 서비스이므로 `/`에서는 바로 목록으로 (소개는 /intro에서 언제든 다시 볼 수 있음)

const LIST_PATH = '/group-buys'

// 체험 동네(서울시청) 목록 = 실제 공구 + 예시 공구. 표지 카드 묶음과 "모집 중 N개"에 쓴다
const PREVIEW_PATH = listPath({ radius: null, category: null, keyword: '', sort: 'DEADLINE', demoArea: true }, 0)

// API가 실패해도 표지는 비지 않게 하는 예비 그림 (예시 상품 일러스트)
const FALLBACK_IMAGES = ['/demo/tissue.webp', '/demo/water.webp', '/demo/detergent.webp']

const STEPS = [
  { title: '공구 찾기·열기', body: '동네 반경 안의 공구를 둘러보거나, 사고 싶은 대용량 상품 링크로 직접 열어요.' },
  { title: '내 몫만 결제', body: '참여하는 순간 내 몫만 결제해요. 개설자도 똑같이 자기 몫을 먼저 내요.' },
  { title: '다 모이면 확정', body: '인원이 차면 자동으로 확정되고, 투표로 정한 시간·장소에서 나눠 받아요.' },
]

const PROMISES = [
  { title: '못 모이면 전액 자동 환불', body: '마감까지 인원이 안 차면 모두에게 자동으로 돌려드려요.' },
  { title: '위치는 동네 이름만', body: '좌표나 거리는 화면에 보이지 않아요. "중구 명동"처럼 동네 이름만.' },
  { title: '1원 단위까지 공평하게', body: '나누고 남는 원은 개설자가 부담해요. 참여자는 딱 자기 몫만.' },
]

export function LandingPage({ alwaysShow = false }: { alwaysShow?: boolean }) {
  const { me, loading } = useAuth()

  if (!alwaysShow && !loading && me) {
    return <Navigate to={LIST_PATH} replace />
  }
  return <Landing />
}

function Landing() {
  const [preview, setPreview] = useState<GroupBuyListResponse | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    api<GroupBuyListResponse>(PREVIEW_PATH)
      .then((res) => active && setPreview(res))
      .catch(() => active && setFailed(true))
    return () => {
      active = false
    }
  }, [])

  const cards = preview?.items.slice(0, 3) ?? []
  const count = preview ? `${preview.items.length}${preview.hasNext ? '+' : ''}` : null
  const hasDemo = preview?.items.some((g) => g.demo) ?? false

  return (
    <div className="overflow-x-hidden">
      {/* ───── 히어로 ───── */}
      <section className="relative isolate">
        <div
          aria-hidden
          className="absolute inset-0 -z-10 bg-gradient-to-b from-orange-50 via-amber-50/60 to-white"
        />
        <div aria-hidden className="absolute -top-24 -right-24 -z-10 h-72 w-72 rounded-full bg-orange-200/50 blur-3xl" />
        <div aria-hidden className="absolute top-40 -left-28 -z-10 h-64 w-64 rounded-full bg-amber-200/40 blur-3xl" />

        <div className="mx-auto grid max-w-5xl items-center gap-12 px-4 pt-24 pb-16 md:grid-cols-2 md:pt-32 md:pb-24">
          <div>
            <p className="inline-flex items-center gap-2 rounded-full bg-white/80 px-3 py-1 text-xs font-medium text-orange-600 shadow-sm ring-1 ring-orange-100">
              <span className="h-1.5 w-1.5 rounded-full bg-orange-500" />
              동네 이웃과 하는 공동구매
            </p>
            <h1 className="mt-5 text-4xl leading-tight font-extrabold tracking-tight text-gray-900 sm:text-5xl">
              대용량, 혼자 사긴
              <br />
              많을 때는
              <br />
              <span className="relative inline-block text-orange-500">
                반띵
                <svg
                  aria-hidden
                  viewBox="0 0 120 12"
                  className="absolute -bottom-2 left-0 h-3 w-full text-orange-300"
                  preserveAspectRatio="none"
                >
                  <path d="M2 9 Q 30 2, 60 7 T 118 5" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" />
                </svg>
              </span>
              하세요
            </h1>
            <p className="mt-6 text-base leading-relaxed text-gray-600 sm:text-lg">
              휴지 30롤, 생수 24병, 세제 4개입.
              <br />
              필요한 만큼만 사고, 나머지는 동네 이웃과 나눠요.
            </p>

            <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center">
              <Link
                to={LIST_PATH}
                className="inline-flex items-center justify-center gap-2 rounded-full bg-orange-500 px-6 py-3.5 text-base font-semibold text-white shadow-lg shadow-orange-500/25 transition hover:bg-orange-600"
              >
                우리 동네 공구 보기
                <span aria-hidden>→</span>
              </Link>
              <Link
                to="/signup"
                className="inline-flex items-center justify-center rounded-full px-6 py-3.5 text-base font-medium text-gray-700 ring-1 ring-gray-200 transition hover:bg-white"
              >
                처음이에요, 가입하기
              </Link>
            </div>

            <dl className="mt-8 flex flex-wrap gap-x-6 gap-y-2 text-sm text-gray-500">
              <div className="flex items-center gap-1.5">
                <Check />
                <dt className="sr-only">결제</dt>
                <dd>참여 = 결제</dd>
              </div>
              <div className="flex items-center gap-1.5">
                <Check />
                <dt className="sr-only">확정</dt>
                <dd>다 모이면 자동 확정</dd>
              </div>
              <div className="flex items-center gap-1.5">
                <Check />
                <dt className="sr-only">환불</dt>
                <dd>못 모이면 전액 환불</dd>
              </div>
            </dl>
          </div>

          {/* 카드 묶음: 체험 동네의 실제 목록에서 3개 */}
          <div className="relative mx-auto w-full max-w-sm">
            <div className="relative h-[27rem]">
              {cards.length > 0
                ? cards.map((g, i) => <PreviewCard key={g.id} g={g} index={i} />)
                : FALLBACK_IMAGES.map((src, i) => <FallbackCard key={src} src={src} index={i} pulse={!failed} />)}
            </div>
            {count !== null && (
              <p className="mt-4 text-center text-sm text-gray-500">
                지금 <span className="font-semibold text-gray-800">{preview?.neighborhoodName}</span> 근처{' '}
                <span className="font-semibold text-orange-600">{count}개</span> 공구 모집 중
                {hasDemo && <span className="text-gray-400"> (예시 포함)</span>}
              </p>
            )}
          </div>
        </div>
      </section>

      {/* ───── 이렇게 반띵해요 ───── */}
      <section className="mx-auto max-w-5xl px-4 py-16">
        <h2 className="text-center text-2xl font-bold text-gray-900 sm:text-3xl">이렇게 반띵해요</h2>
        <p className="mt-2 text-center text-gray-500">세 단계면 끝나요</p>
        <ol className="mt-10 grid gap-4 md:grid-cols-3">
          {STEPS.map((step, i) => (
            <li key={step.title} className="relative rounded-2xl border border-gray-100 bg-white p-6 shadow-sm">
              <span className="flex h-10 w-10 items-center justify-center rounded-full bg-orange-500 text-lg font-bold text-white">
                {i + 1}
              </span>
              <h3 className="mt-4 text-lg font-semibold text-gray-900">{step.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-gray-600">{step.body}</p>
            </li>
          ))}
        </ol>
      </section>

      {/* ───── 안심 포인트 ───── */}
      <section className="bg-gray-50">
        <div className="mx-auto max-w-5xl px-4 py-16">
          <h2 className="text-center text-2xl font-bold text-gray-900 sm:text-3xl">돈 문제는 깔끔하게</h2>
          <p className="mt-2 text-center text-gray-500">이웃끼리 얼굴 붉힐 일 없게 반띵이 정리해요</p>
          <ul className="mt-10 grid gap-4 md:grid-cols-3">
            {PROMISES.map((p) => (
              <li key={p.title} className="rounded-2xl bg-white p-6 ring-1 ring-gray-100">
                <span className="flex h-9 w-9 items-center justify-center rounded-xl bg-orange-100 text-orange-600">
                  <Check />
                </span>
                <h3 className="mt-4 font-semibold text-gray-900">{p.title}</h3>
                <p className="mt-2 text-sm leading-relaxed text-gray-600">{p.body}</p>
              </li>
            ))}
          </ul>
          <p className="mt-6 text-center text-xs text-gray-400">결제는 토스페이먼츠 테스트 모드로 동작해요. 실제 돈은 나가지 않아요.</p>
        </div>
      </section>

      {/* ───── 마지막 행동 유도 ───── */}
      <section className="mx-auto max-w-5xl px-4 py-16">
        <div className="relative isolate overflow-hidden rounded-3xl bg-orange-500 px-6 py-12 text-center text-white">
          <div aria-hidden className="absolute -top-16 -left-16 -z-10 h-48 w-48 rounded-full bg-white/10" />
          <div aria-hidden className="absolute -right-10 -bottom-20 -z-10 h-56 w-56 rounded-full bg-amber-300/30" />
          <h2 className="text-2xl font-bold sm:text-3xl">우리 동네엔 어떤 공구가 있을까요?</h2>
          <p className="mt-2 text-orange-50">로그인 없이 바로 둘러볼 수 있어요</p>
          <Link
            to={LIST_PATH}
            className="mt-6 inline-flex items-center gap-2 rounded-full bg-white px-6 py-3 font-semibold text-orange-600 shadow-md transition hover:bg-orange-50"
          >
            공구 둘러보기 <span aria-hidden>→</span>
          </Link>
        </div>
      </section>
    </div>
  )
}

// 겹쳐 놓은 카드의 위치·기울기·쌓는 순서. 글자(상품명·금액)는 서로 가리지 않고 사진끼리만 겹치게 배치
const STACK = [
  { className: 'top-0 left-0 -rotate-6 motion-safe:animate-float-slow', z: 1 },
  { className: 'top-[4.5rem] right-0 rotate-3 motion-safe:animate-float', z: 0 },
  { className: 'top-[15.5rem] left-[10%] -rotate-2 motion-safe:animate-float-slow', z: 2 },
]

function PreviewCard({ g, index }: { g: GroupBuyListItem; index: number }) {
  const progress = Math.min(100, Math.round((g.reservedUnits / g.capacityUnits) * 100))
  return (
    <Link
      to={`/group-buys/${g.id}`}
      className={`absolute w-56 rounded-2xl bg-white p-3 shadow-xl ring-1 shadow-orange-900/10 ring-gray-100 transition hover:z-10 hover:scale-105 ${STACK[index].className}`}
      style={{ zIndex: STACK[index].z }}
    >
      {g.thumbnailUrl ? (
        <img src={g.thumbnailUrl} alt="" className="h-24 w-full rounded-xl object-cover" />
      ) : (
        <div className="h-24 w-full rounded-xl bg-orange-50" />
      )}
      <div className="mt-2.5 flex items-center gap-1.5">
        <p className="truncate text-sm font-semibold text-gray-900">{g.title}</p>
        {g.demo && <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-500">예시</span>}
      </div>
      <p className="mt-0.5 text-sm">
        <span className="font-bold text-orange-600">{won(g.unitPrice)}</span>
        <span className="text-gray-400"> / {g.priceType === 'EQUAL' ? '1명' : `1${g.unitLabel}`}</span>
      </p>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-gray-100">
        <div className="h-full rounded-full bg-orange-500" style={{ width: `${progress}%` }} />
      </div>
    </Link>
  )
}

function FallbackCard({ src, index, pulse }: { src: string; index: number; pulse: boolean }) {
  return (
    <div
      className={`absolute w-56 rounded-2xl bg-white p-3 shadow-xl ring-1 shadow-orange-900/10 ring-gray-100 ${STACK[index].className}`}
      style={{ zIndex: STACK[index].z }}
    >
      <img src={src} alt="" className="h-24 w-full rounded-xl object-cover" />
      <div className={`mt-3 h-3 w-3/4 rounded bg-gray-100 ${pulse ? 'animate-pulse' : ''}`} />
      <div className={`mt-2 h-3 w-1/3 rounded bg-orange-100 ${pulse ? 'animate-pulse' : ''}`} />
      <div className="mt-3 h-1.5 rounded-full bg-gray-100" />
    </div>
  )
}

function Check() {
  return (
    <svg aria-hidden viewBox="0 0 20 20" className="h-4 w-4 shrink-0 text-orange-500" fill="currentColor">
      <path
        fillRule="evenodd"
        d="M16.7 5.3a1 1 0 0 1 0 1.4l-8 8a1 1 0 0 1-1.4 0l-4-4a1 1 0 1 1 1.4-1.4L8 12.6l7.3-7.3a1 1 0 0 1 1.4 0Z"
        clipRule="evenodd"
      />
    </svg>
  )
}
