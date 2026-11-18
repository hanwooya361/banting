import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/context'
import { api, ApiError } from '../lib/api'
import {
  CATEGORY_LABELS,
  listPath,
  RADIUS_OPTIONS,
  remainingCopy,
  timeLeft,
  won,
  type Category,
  type GroupBuyListItem,
  type GroupBuyListResponse,
  type ListQuery,
} from '../lib/groupBuy'

const CATEGORIES = Object.keys(CATEGORY_LABELS) as Category[]

// S01 홈: 내 동네 반경 안의 모집 중 공구
export function HomePage() {
  const { me, loading: authLoading, reloadMe } = useAuth()
  const [query, setQuery] = useState<ListQuery>({
    radius: null,
    category: null,
    keyword: '',
    sort: 'DEADLINE',
    demoArea: false,
  })
  const [keywordInput, setKeywordInput] = useState('')
  const [page, setPage] = useState(0)
  const [result, setResult] = useState<GroupBuyListResponse | null>(null)
  const [items, setItems] = useState<GroupBuyListItem[]>([])
  // 마지막으로 끝난 요청의 키. 지금 요청 키와 다르면 "불러오는 중" (로딩 상태를 따로 두지 않고 계산)
  const [settled, setSettled] = useState<{ key: string; error: string | null } | null>(null)
  // 로그인·로그아웃하면 기준 동네가 바뀌므로 회원 id도 요청 키에 넣는다
  const requestKey = `${me?.id ?? 'guest'} ${listPath(query, page)}`
  const loading = authLoading || settled?.key !== requestKey
  const error = settled?.key === requestKey ? settled.error : null

  useEffect(() => {
    // 로그인 복원이 끝나야 "내 동네" 기준으로 받을 수 있다
    if (authLoading) return
    let active = true // 조건을 빠르게 바꿨을 때 늦게 도착한 이전 응답은 버린다
    api<GroupBuyListResponse>(listPath(query, page))
      .then((res) => {
        if (!active) return
        setResult(res)
        setItems((prev) => (page === 0 ? res.items : [...prev, ...res.items]))
        setSettled({ key: requestKey, error: null })
      })
      .catch((err) => {
        if (active) {
          setSettled({ key: requestKey, error: err instanceof ApiError ? err.message : '목록을 불러오지 못했어요' })
        }
      })
    return () => {
      active = false
    }
  }, [query, page, authLoading, requestKey])

  // 조건이 바뀌면 첫 페이지부터
  const changeQuery = (patch: Partial<ListQuery>) => {
    setQuery((q) => ({ ...q, ...patch }))
    setPage(0)
  }

  const changeRadius = (radius: number) => {
    changeQuery({ radius })
    // 내 동네를 보는 중이면 반경도 저장해 다음 방문·다른 기기에서도 유지 (실패해도 이번 검색은 그대로)
    if (me?.neighborhoodName && !query.demoArea) {
      api('/api/users/me/location', { method: 'PUT', body: { radiusM: radius } })
        .then(reloadMe)
        .catch(() => {})
    }
  }

  const submitSearch = (e: FormEvent) => {
    e.preventDefault()
    changeQuery({ keyword: keywordInput.trim() })
  }

  const radius = query.radius ?? result?.radiusM ?? me?.searchRadiusM ?? 1000

  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-2">
        <h1 className="text-lg font-bold">
          {result ? `${result.neighborhoodName} 근처` : '내 동네'} 공구
        </h1>
        {me ? (
          <Link to="/me/location" className="text-sm text-gray-500 hover:underline">
            동네 바꾸기
          </Link>
        ) : null}
      </div>

      {query.demoArea ? (
        <div className="flex items-center justify-between gap-3 rounded-lg bg-orange-50 px-4 py-3 text-sm">
          <span className="text-gray-700">체험 동네(서울시청)의 예시 공구를 보고 있어요.</span>
          <button
            type="button"
            onClick={() => changeQuery({ demoArea: false, radius: null })}
            className="shrink-0 font-medium text-orange-600 hover:underline"
          >
            내 동네로 돌아가기
          </button>
        </div>
      ) : result?.defaultLocation ? (
        <div className="flex items-center justify-between gap-3 rounded-lg bg-orange-50 px-4 py-3 text-sm">
          <span className="text-gray-700">지금은 서울시청 근처 공구를 보여드리고 있어요.</span>
          <Link
            to={me ? '/me/location' : '/login'}
            className="shrink-0 font-medium text-orange-600 hover:underline"
          >
            {me ? '내 동네 설정하기' : '로그인하고 내 동네 보기'}
          </Link>
        </div>
      ) : null}

      <div className="flex gap-2" role="group" aria-label="검색 반경">
        {RADIUS_OPTIONS.map((option) => (
          <button
            key={option.value}
            type="button"
            aria-pressed={radius === option.value}
            onClick={() => changeRadius(option.value)}
            className={`rounded-full border px-3 py-1 text-sm ${
              radius === option.value ? 'border-orange-500 bg-orange-500 text-white' : 'border-gray-300 text-gray-700'
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>

      <form onSubmit={submitSearch} className="flex gap-2">
        <input
          type="search"
          value={keywordInput}
          onChange={(e) => setKeywordInput(e.target.value)}
          maxLength={50}
          placeholder="상품명으로 찾기 (예: 휴지, 생수)"
          aria-label="상품명 검색"
          className="min-w-0 flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm"
        />
        <button type="submit" className="rounded-lg bg-gray-900 px-4 text-sm font-medium text-white">
          검색
        </button>
      </form>

      <div className="flex items-center justify-between gap-2">
        <div className="flex gap-1 overflow-x-auto" role="tablist" aria-label="카테고리">
          {[null, ...CATEGORIES].map((category) => (
            <button
              key={category ?? 'ALL'}
              type="button"
              role="tab"
              aria-selected={query.category === category}
              onClick={() => changeQuery({ category })}
              className={`shrink-0 rounded-full px-3 py-1 text-sm ${
                query.category === category ? 'bg-gray-900 text-white' : 'text-gray-600 hover:bg-gray-100'
              }`}
            >
              {category ? CATEGORY_LABELS[category] : '전체'}
            </button>
          ))}
        </div>
        <select
          value={query.sort}
          onChange={(e) => changeQuery({ sort: e.target.value as ListQuery['sort'] })}
          aria-label="정렬"
          className="shrink-0 rounded-lg border border-gray-300 px-2 py-1 text-sm"
        >
          <option value="DEADLINE">마감임박순</option>
          <option value="LATEST">최신순</option>
        </select>
      </div>

      {query.keyword ? (
        <p className="text-sm text-gray-600">
          “{query.keyword}” 검색 결과{' '}
          <button
            type="button"
            onClick={() => {
              setKeywordInput('')
              changeQuery({ keyword: '' })
            }}
            className="text-gray-400 underline"
          >
            지우기
          </button>
        </p>
      ) : null}

      {error ? <p className="py-8 text-center text-sm text-red-600">{error}</p> : null}

      {!error && !loading && items.length === 0 ? (
        <div className="flex flex-col items-center gap-3 py-16 text-center text-gray-600">
          <p>{query.keyword || query.category ? '조건에 맞는 공구가 없어요.' : '근처 공구가 아직 없어요.'}</p>
          {result && !result.defaultLocation && !query.keyword && !query.category ? (
            <button
              type="button"
              onClick={() => changeQuery({ demoArea: true, radius: null })}
              className="rounded-full border border-orange-500 px-4 py-2 text-sm font-medium text-orange-600"
            >
              체험 동네 공구 보기
            </button>
          ) : null}
          {me ? (
            <Link to="/group-buys/new" className="rounded-full bg-orange-500 px-4 py-2 text-sm font-medium text-white">
              첫 공구 열기
            </Link>
          ) : null}
        </div>
      ) : null}

      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        {items.map((g) => (
          <li key={g.id} className="min-w-0">
            <GroupBuyCard g={g} />
          </li>
        ))}
      </ul>

      {loading ? <p className="py-4 text-center text-sm text-gray-500">불러오는 중…</p> : null}

      {!loading && result?.hasNext ? (
        <button
          type="button"
          onClick={() => setPage((p) => p + 1)}
          className="rounded-lg border border-gray-300 py-2 text-sm font-medium text-gray-700"
        >
          더 보기
        </button>
      ) : null}
    </section>
  )
}

function GroupBuyCard({ g }: { g: GroupBuyListItem }) {
  const progress = Math.min(100, Math.round((g.reservedUnits / g.capacityUnits) * 100))
  const price = g.priceType === 'EQUAL' ? `1인 ${won(g.unitPrice)}` : `${g.unitLabel}당 ${won(g.unitPrice)}`

  return (
    <Link
      to={`/group-buys/${g.id}`}
      className="flex gap-3 rounded-xl border border-gray-100 p-3 hover:border-orange-200 hover:bg-orange-50/40"
    >
      {g.thumbnailUrl ? (
        <img src={g.thumbnailUrl} alt="" loading="lazy" className="h-24 w-24 shrink-0 rounded-lg object-cover" />
      ) : (
        <div className="flex h-24 w-24 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-xs text-gray-400">
          사진 없음
        </div>
      )}
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <p className="flex min-w-0 items-center gap-1.5">
          {g.demo ? (
            <span className="shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-xs font-medium text-gray-600">예시</span>
          ) : null}
          <span className="truncate font-medium">{g.title}</span>
        </p>
        <p className="text-xs text-gray-500">
          {g.neighborhoodName} · {CATEGORY_LABELS[g.category]} · <span className="text-orange-600">{timeLeft(g.deadlineAt)}</span>
        </p>
        <p className="font-bold">{price}</p>
        <div className="mt-auto">
          <div className="h-1.5 overflow-hidden rounded-full bg-gray-100">
            <div className="h-full bg-orange-500" style={{ width: `${progress}%` }} />
          </div>
          <p className="mt-1 text-xs text-gray-600">{remainingCopy(g)}</p>
        </div>
      </div>
    </Link>
  )
}
