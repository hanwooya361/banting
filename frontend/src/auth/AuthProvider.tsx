import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, loginRequest, logoutRequest, refreshAccessToken } from '../lib/api'
import { AuthContext, type AuthState, type Me } from './context'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  // 새로고침하면 메모리의 access 토큰이 사라지므로, refresh 쿠키로 다시 받아 로그인 상태를 복원한다
  useEffect(() => {
    let active = true
    refreshAccessToken()
      .then((ok) => (ok ? api<Me>('/api/users/me') : null))
      .then((user) => {
        if (active) setMe(user)
      })
      .catch(() => {
        if (active) setMe(null)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    await loginRequest(email, password)
    setMe(await api<Me>('/api/users/me'))
  }, [])

  const signup = useCallback(
    async (email: string, password: string, nickname: string) => {
      await api('/api/auth/signup', { method: 'POST', body: { email, password, nickname } })
      // 가입 직후 바로 로그인시켜 한 번 더 입력하지 않게 한다
      await login(email, password)
    },
    [login],
  )

  const logout = useCallback(async () => {
    try {
      await logoutRequest()
    } finally {
      setMe(null)
    }
  }, [])

  const value = useMemo<AuthState>(
    () => ({ me, loading, login, signup, logout }),
    [me, loading, login, signup, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
