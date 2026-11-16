import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/context'

type HealthStatus = 'checking' | 'UP' | 'DOWN'

export function HomePage() {
  const { me } = useAuth()
  const [health, setHealth] = useState<HealthStatus>('checking')

  useEffect(() => {
    fetch('/api/health')
      .then((res) => (res.ok ? res.json() : Promise.reject(res.status)))
      .then((data: { status: string }) => setHealth(data.status === 'UP' ? 'UP' : 'DOWN'))
      .catch(() => setHealth('DOWN'))
  }, [])

  const badge = {
    checking: 'bg-gray-100 text-gray-600',
    UP: 'bg-green-100 text-green-700',
    DOWN: 'bg-red-100 text-red-700',
  }[health]

  return (
    <section className="flex flex-col items-center gap-4 py-16 text-center">
      <h1 className="text-4xl font-bold">반띵</h1>
      <p className="text-gray-600">동네 이웃과 대용량 상품을 반띵하는 공동구매</p>
      {me ? (
        <p className="text-gray-800">{me.nickname}님, 반가워요!</p>
      ) : (
        <Link to="/signup" className="rounded-full bg-orange-500 px-5 py-2 font-medium text-white">
          시작하기
        </Link>
      )}
      <span className={`rounded-full px-3 py-1 text-xs font-medium ${badge}`}>
        서버 상태: {health === 'checking' ? '확인 중' : health}
      </span>
    </section>
  )
}
