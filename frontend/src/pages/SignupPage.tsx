import { useState, type ChangeEvent, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAuth } from '../auth/context'
import { TextField } from '../components/TextField'
import { ApiError } from '../lib/api'

type Field = 'email' | 'password' | 'nickname'

export function SignupPage() {
  const { signup } = useAuth()
  const navigate = useNavigate()

  const [form, setForm] = useState<Record<Field, string>>({ email: '', password: '', nickname: '' })
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({})
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  // 개인정보처리방침 동의 (T18 화면, T21부터 서버가 동의 여부를 검사하고 동의 시각을 저장)
  const [agreed, setAgreed] = useState(false)

  const update = (field: Field) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }))

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setFieldErrors({})
    setSubmitting(true)
    try {
      await signup(form.email, form.password, form.nickname)
      // 가입 직후 동네 설정으로 (화면 흐름 1: 로그인 → 동네 설정 → 홈)
      navigate('/me/location', { replace: true, state: { onboarding: true } })
    } catch (err) {
      if (!(err instanceof ApiError)) {
        setError('잠시 후 다시 시도해 주세요')
      } else if (err.code === 'EMAIL_DUPLICATED') {
        setFieldErrors({ email: err.message })
      } else if (err.code === 'NICKNAME_DUPLICATED') {
        setFieldErrors({ nickname: err.message })
      } else if (err.fieldErrors.length > 0) {
        // 서버 검증 오류를 칸별로 표시
        setFieldErrors(
          Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message])) as Partial<Record<Field, string>>,
        )
      } else {
        setError(err.message)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-sm">
      <h1 className="mb-6 text-2xl font-bold">가입하기</h1>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
        <TextField
          label="이메일"
          name="email"
          type="email"
          autoComplete="email"
          value={form.email}
          onChange={update('email')}
          error={fieldErrors.email}
        />
        <TextField
          label="비밀번호"
          name="password"
          type="password"
          autoComplete="new-password"
          placeholder="영문과 숫자를 섞어 8자 이상"
          value={form.password}
          onChange={update('password')}
          error={fieldErrors.password}
        />
        <TextField
          label="닉네임"
          name="nickname"
          autoComplete="nickname"
          placeholder="한글·영문·숫자 2~20자"
          value={form.nickname}
          onChange={update('nickname')}
          error={fieldErrors.nickname}
        />
        <label className="flex items-start gap-2 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={agreed}
            onChange={(e) => setAgreed(e.target.checked)}
            className="mt-0.5 h-4 w-4 accent-orange-500"
          />
          <span>
            <Link to="/privacy" target="_blank" className="font-medium underline">
              개인정보처리방침
            </Link>
            을 읽었고, 이메일·닉네임·동네 정보를 모으는 데 동의해요. (필수)
          </span>
        </label>
        <p className="-mt-2 text-xs text-gray-500">반띵은 베타예요. 결제는 테스트라 실제 돈이 나가지 않아요.</p>
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <button
          type="submit"
          disabled={submitting || !agreed}
          className="rounded-lg bg-orange-500 py-2.5 font-medium text-white disabled:opacity-50"
        >
          {submitting ? '가입 중…' : '가입하기'}
        </button>
      </form>
      <p className="mt-6 text-center text-sm text-gray-600">
        이미 계정이 있나요?{' '}
        <Link to="/login" className="font-medium text-orange-600 hover:underline">
          로그인
        </Link>
      </p>
    </section>
  )
}
