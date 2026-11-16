import { Link } from 'react-router'
import { useAuth } from '../auth/context'

export function MePage() {
  const { me } = useAuth()
  if (!me) return null

  const rows: [string, string][] = [
    ['이메일', me.email],
    ['닉네임', me.nickname],
    ['동네', me.neighborhoodName ?? '아직 설정하지 않았어요'],
    ['검색 반경', `${me.searchRadiusM.toLocaleString()}m`],
  ]

  return (
    <section className="mx-auto max-w-sm">
      <h1 className="mb-6 text-2xl font-bold">내 정보</h1>
      <dl className="divide-y divide-gray-100 rounded-xl border border-gray-200">
        {rows.map(([label, value]) => (
          <div key={label} className="flex justify-between px-4 py-3 text-sm">
            <dt className="text-gray-500">{label}</dt>
            <dd className="font-medium">{value}</dd>
          </div>
        ))}
      </dl>
      <Link
        to="/me/location"
        className="mt-4 block rounded-lg border border-orange-500 py-2.5 text-center font-medium text-orange-600"
      >
        {me.neighborhoodName ? '동네 바꾸기' : '동네 설정하기'}
      </Link>
    </section>
  )
}
