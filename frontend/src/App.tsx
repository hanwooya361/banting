import { useEffect, useState } from 'react'

type HealthStatus = 'checking' | 'UP' | 'DOWN'

function App() {
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
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-white px-4">
      <h1 className="text-4xl font-bold text-gray-900">반띵</h1>
      <p className="text-gray-600">동네 이웃과 대용량 상품을 반띵하는 공동구매</p>
      <span className={`rounded-full px-3 py-1 text-sm font-medium ${badge}`}>
        서버 상태: {health === 'checking' ? '확인 중' : health}
      </span>
    </main>
  )
}

export default App
