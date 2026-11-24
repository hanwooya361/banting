import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { safeNext } from '../lib/oauth'
import { useAuth } from '../auth/context'
import { api, ApiError } from '../lib/api'

type Place = { name: string; address: string; latitude: number; longitude: number }
// 고른 위치 후보. 좌표는 저장 요청에만 쓰고 화면에는 동네 이름만 보여준다
type Candidate = { latitude: number; longitude: number; neighborhoodName: string }

const RADIUS_OPTIONS = [
  { value: 500, label: '500m' },
  { value: 1000, label: '1km' },
  { value: 3000, label: '3km' },
]

function errorMessage(err: unknown) {
  return err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요'
}

export function LocationPage() {
  const { me, reloadMe } = useAuth()
  const navigate = useNavigate()
  // 가입 직후: 이메일 가입은 state, 간편 가입(T23)은 새로 불러온 페이지라 주소(?onboarding=1&next=)로 알려 준다
  const [params] = useSearchParams()
  const onboarding =
    (useLocation().state as { onboarding?: boolean } | null)?.onboarding ?? params.get('onboarding') === '1'
  const afterOnboarding = safeNext(params.get('next'))

  const [candidate, setCandidate] = useState<Candidate | null>(null)
  const [radiusM, setRadiusM] = useState(me?.searchRadiusM ?? 1000)
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Place[] | null>(null)
  const [busy, setBusy] = useState<'locating' | 'searching' | 'resolving' | 'saving' | null>(null)
  const [error, setError] = useState<string | null>(null)

  const resolve = async (latitude: number, longitude: number) => {
    setBusy('resolving')
    setError(null)
    try {
      const params = new URLSearchParams({ latitude: String(latitude), longitude: String(longitude) })
      const { neighborhoodName } = await api<{ neighborhoodName: string }>(`/api/locations/region?${params}`)
      setCandidate({ latitude, longitude, neighborhoodName })
      setResults(null)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const useCurrentPosition = () => {
    if (!navigator.geolocation) {
      setError('이 브라우저는 위치 찾기를 지원하지 않아요. 주소로 찾아 주세요')
      return
    }
    setBusy('locating')
    setError(null)
    navigator.geolocation.getCurrentPosition(
      (pos) => void resolve(pos.coords.latitude, pos.coords.longitude),
      (err) => {
        setBusy(null)
        setError(
          err.code === err.PERMISSION_DENIED
            ? '위치 권한이 꺼져 있어요. 브라우저 설정에서 허용하거나 주소로 찾아 주세요'
            : '현재 위치를 찾지 못했어요. 주소로 찾아 주세요',
        )
      },
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 60_000 },
    )
  }

  const search = async (e: FormEvent) => {
    e.preventDefault()
    if (query.trim().length < 2) {
      setError('검색어를 2자 이상 입력해 주세요')
      return
    }
    setBusy('searching')
    setError(null)
    try {
      setResults(await api<Place[]>(`/api/locations/search?${new URLSearchParams({ query: query.trim() })}`))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(null)
    }
  }

  const save = async () => {
    setBusy('saving')
    setError(null)
    try {
      const body = candidate
        ? { latitude: candidate.latitude, longitude: candidate.longitude, radiusM }
        : { radiusM }
      await api('/api/users/me/location', { method: 'PUT', body })
      await reloadMe()
      navigate(onboarding ? afterOnboarding : '/me', { replace: true })
    } catch (err) {
      setError(errorMessage(err))
      setBusy(null)
    }
  }

  const shownName = candidate?.neighborhoodName ?? me?.neighborhoodName ?? null
  const canSave = busy === null && shownName !== null

  return (
    <section className="mx-auto flex max-w-sm flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">동네 설정</h1>
        <p className="mt-1 text-sm text-gray-600">
          {onboarding ? '내 동네 근처 공구를 보여드릴게요.' : '동네와 공구를 찾을 반경을 바꿀 수 있어요.'} 다른
          사람에게는 동네 이름만 보여요.
        </p>
      </div>

      <div className="rounded-xl bg-orange-50 px-4 py-3">
        <p className="text-xs text-orange-700">{candidate ? '새로 고른 동네' : '현재 동네'}</p>
        <p className="text-lg font-semibold">{shownName ?? '아직 정하지 않았어요'}</p>
      </div>

      <div className="flex flex-col gap-3">
        <button
          type="button"
          onClick={useCurrentPosition}
          disabled={busy !== null}
          className="rounded-lg border border-gray-300 py-2.5 font-medium disabled:opacity-50"
        >
          {busy === 'locating' || busy === 'resolving' ? '찾는 중…' : '📍 현재 위치로 찾기'}
        </button>

        <form onSubmit={search} className="flex gap-2">
          <input
            aria-label="주소나 장소 검색"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="동네, 도로명, 역 이름으로 검색"
            className="min-w-0 flex-1 rounded-lg border border-gray-300 px-3 py-2 outline-none focus:border-orange-500"
          />
          <button
            type="submit"
            disabled={busy !== null}
            className="rounded-lg bg-gray-900 px-4 font-medium text-white disabled:opacity-50"
          >
            검색
          </button>
        </form>

        {results ? (
          results.length === 0 ? (
            <p className="text-sm text-gray-500">검색 결과가 없어요</p>
          ) : (
            <ul className="divide-y divide-gray-100 rounded-lg border border-gray-200">
              {results.map((place) => (
                <li key={`${place.name}-${place.latitude}-${place.longitude}`}>
                  <button
                    type="button"
                    onClick={() => void resolve(place.latitude, place.longitude)}
                    className="w-full px-3 py-2 text-left hover:bg-gray-50"
                  >
                    <span className="block text-sm font-medium">{place.name}</span>
                    <span className="block text-xs text-gray-500">{place.address}</span>
                  </button>
                </li>
              ))}
            </ul>
          )
        ) : null}
      </div>

      <fieldset>
        <legend className="mb-2 text-sm font-medium text-gray-700">공구를 찾을 반경</legend>
        <div className="flex gap-2">
          {RADIUS_OPTIONS.map((option) => (
            <button
              key={option.value}
              type="button"
              aria-pressed={radiusM === option.value}
              onClick={() => setRadiusM(option.value)}
              className={`flex-1 rounded-full border py-2 text-sm font-medium ${
                radiusM === option.value
                  ? 'border-orange-500 bg-orange-500 text-white'
                  : 'border-gray-300 text-gray-700'
              }`}
            >
              {option.label}
            </button>
          ))}
        </div>
      </fieldset>

      {error ? <p className="text-sm text-red-600">{error}</p> : null}

      <button
        type="button"
        onClick={save}
        disabled={!canSave}
        className="rounded-lg bg-orange-500 py-3 font-medium text-white disabled:opacity-50"
      >
        {busy === 'saving' ? '저장 중…' : '이 동네로 저장'}
      </button>
    </section>
  )
}
