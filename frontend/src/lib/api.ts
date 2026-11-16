// API 호출 공통 모듈
// - access 토큰은 이 모듈의 변수(메모리)에만 둔다. localStorage에 두지 않아 XSS로 훔쳐 갈 수 없다
// - refresh 토큰은 HttpOnly 쿠키라 JS가 만지지 않는다. 브라우저가 /api/auth/* 요청에 자동으로 붙인다
// - 401이 오면 refresh로 access를 새로 받고 원래 요청을 한 번 다시 보낸다

export type FieldError = { field: string; message: string }

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: FieldError[]

  constructor(status: number, code: string, message: string, fieldErrors: FieldError[] = []) {
    super(message)
    this.status = status
    this.code = code
    this.fieldErrors = fieldErrors
  }
}

type TokenResponse = { accessToken: string; expiresIn: number }

let accessToken: string | null = null

export function setAccessToken(token: string | null) {
  accessToken = token
}

// 동시에 여러 곳에서 refresh가 필요해도 요청은 한 번만 보낸다.
// 서버는 refresh 토큰을 쓸 때마다 폐기하고, 폐기된 토큰이 다시 오면 탈취로 보고 전부 로그아웃시킨다.
// 두 번 보내면 두 번째가 "재사용"으로 걸리므로 진행 중인 요청을 공유한다 (React StrictMode의 이중 실행도 여기서 막힘)
let refreshing: Promise<boolean> | null = null

export function refreshAccessToken(): Promise<boolean> {
  if (!refreshing) {
    refreshing = fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' })
      .then(async (res) => {
        if (!res.ok) {
          accessToken = null
          return false
        }
        const data = (await res.json()) as TokenResponse
        accessToken = data.accessToken
        return true
      })
      .catch(() => {
        accessToken = null
        return false
      })
      .finally(() => {
        refreshing = null
      })
  }
  return refreshing
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
}

export async function api<T>(path: string, { method = 'GET', body }: RequestOptions = {}): Promise<T> {
  const send = () =>
    fetch(path, {
      method,
      credentials: 'same-origin',
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    })

  let res = await send()
  // 인증 API 자체의 401(로그인 실패 등)은 재시도하지 않는다
  if (res.status === 401 && !path.startsWith('/api/auth/') && (await refreshAccessToken())) {
    res = await send()
  }

  if (!res.ok) {
    const error = (await res.json().catch(() => null)) as
      | { code?: string; message?: string; fieldErrors?: FieldError[] }
      | null
    throw new ApiError(
      res.status,
      error?.code ?? 'UNKNOWN',
      error?.message ?? '잠시 후 다시 시도해 주세요',
      error?.fieldErrors ?? [],
    )
  }
  if (res.status === 204) {
    return undefined as T
  }
  return (await res.json()) as T
}

export async function loginRequest(email: string, password: string) {
  const data = await api<TokenResponse>('/api/auth/login', { method: 'POST', body: { email, password } })
  accessToken = data.accessToken
}

export async function logoutRequest() {
  try {
    await api<void>('/api/auth/logout', { method: 'POST' })
  } finally {
    accessToken = null
  }
}
