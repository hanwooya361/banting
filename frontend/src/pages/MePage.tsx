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
    </section>
  )
}
