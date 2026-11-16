import { createContext, useContext } from 'react'

export type Me = {
  id: number
  email: string
  nickname: string
  role: 'USER' | 'ADMIN'
  neighborhoodName: string | null
  searchRadiusM: number
}

export type AuthState = {
  me: Me | null
  // 첫 로딩에서 refresh 쿠키로 로그인 상태를 복원하는 중
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  signup: (email: string, password: string, nickname: string) => Promise<void>
  logout: () => Promise<void>
  // 내 정보가 바뀐 뒤(동네 설정 등) 다시 불러오기
  reloadMe: () => Promise<void>
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth는 AuthProvider 안에서만 쓸 수 있어요')
  }
  return value
}
