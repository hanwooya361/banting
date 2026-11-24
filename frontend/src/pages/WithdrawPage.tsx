import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAuth } from '../auth/context'
import { api, ApiError } from '../lib/api'

// 회원 탈퇴 (T22). 지워지는 것과 남는 것을 먼저 보여 주고, 비밀번호를 한 번 더 받는다
export function WithdrawPage() {
  const { me, logout } = useAuth()
  // 간편 가입 회원(T23)은 비밀번호가 없어 "탈퇴"를 직접 입력해 확인한다
  const usesPassword = me?.hasPassword ?? true
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [blocked, setBlocked] = useState(false)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    setBlocked(false)
    try {
      await api<void>('/api/users/me/withdrawal', {
        method: 'POST',
        body: usesPassword ? { password } : { confirmText: password },
      })
      // 먼저 이동하고 나서 로그아웃한다. 반대로 하면 이 화면의 RequireAuth가 먼저 로그인 화면으로 보내 안내가 사라진다.
      // /intro는 로그인 상태여도 목록으로 넘기지 않는 표지라 안내가 그대로 보인다
      navigate('/intro', { replace: true, state: { notice: '탈퇴했어요. 그동안 반띵을 써 주셔서 고마워요.' } })
      await logout().catch(() => {})   // 서버에서 토큰은 이미 폐기됨, 화면 상태만 비움
    } catch (err) {
      if (err instanceof ApiError && err.code === 'WITHDRAWAL_BLOCKED') setBlocked(true)
      setError(err instanceof ApiError ? (err.fieldErrors[0]?.message ?? err.message) : '잠시 후 다시 시도해 주세요')
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto flex max-w-sm flex-col gap-5">
      <h1 className="text-2xl font-bold">탈퇴하기</h1>

      <div className="rounded-xl border border-gray-200 p-4 text-sm leading-relaxed">
        <p className="font-semibold">지워지는 것</p>
        <ul className="mt-1 list-disc pl-5 text-gray-700">
          <li>이메일, 닉네임, 비밀번호, 동네 정보</li>
          <li>알림</li>
        </ul>
        <p className="mt-3 font-semibold">남는 것</p>
        <ul className="mt-1 list-disc pl-5 text-gray-700">
          <li>
            참여·결제·정산 기록은 거래 이력이라 <strong>누구인지 알 수 없게</strong> 남아요 (상대에겐 "탈퇴회원"으로 보여요)
          </li>
          <li>남긴 문의 내용</li>
        </ul>
        <p className="mt-3 text-gray-600">
          진행 중인 공구(결제 대기·모집 중·확정·수령 대기)나 환불이 있으면 끝난 뒤에 탈퇴할 수 있어요. 같은 이메일로 다시
          가입할 수 있어요.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-3">
        <label className="flex flex-col gap-1 text-sm font-medium text-gray-700">
          {usesPassword ? '비밀번호 확인' : "확인을 위해 '탈퇴'라고 입력해 주세요"}
          <input
            type={usesPassword ? 'password' : 'text'}
            autoComplete={usesPassword ? 'current-password' : 'off'}
            placeholder={usesPassword ? undefined : '탈퇴'}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            className="rounded-lg border border-gray-300 px-3 py-2 font-normal outline-none focus:border-orange-500"
          />
        </label>
        <label className="flex items-start gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
            className="mt-0.5 h-4 w-4 accent-red-600"
          />
          위 내용을 확인했고, 탈퇴하면 되돌릴 수 없다는 걸 알아요.
        </label>
        {error ? (
          <p className="text-sm text-red-600">
            {error}
            {blocked ? (
              <>
                {' '}
                <Link to="/me/group-buys" className="underline">
                  내 공구 보기
                </Link>
              </>
            ) : null}
          </p>
        ) : null}
        <button
          type="submit"
          disabled={submitting || !confirmed || !password}
          className="rounded-lg bg-red-600 py-2.5 font-semibold text-white disabled:opacity-40"
        >
          {submitting ? '처리 중…' : '탈퇴하기'}
        </button>
        <Link to="/me" className="text-center text-sm text-gray-500 underline">
          그만두기
        </Link>
      </form>
    </section>
  )
}
