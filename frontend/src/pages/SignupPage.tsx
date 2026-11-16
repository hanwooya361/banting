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

  const update = (field: Field) => (e: ChangeEvent<HTMLInputElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }))

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setFieldErrors({})
    setSubmitting(true)
    try {
      await signup(form.email, form.password, form.nickname)
      navigate('/', { replace: true })
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
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <button
          type="submit"
          disabled={submitting}
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
