import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { useAuth } from '../auth/context'
import { ApiError } from '../lib/api'
import {
  answerInquiry,
  createNotice,
  fetchAdminInquiries,
  INQUIRY_CONTENT_MAX,
  type AdminInquiry,
  type InquiryStatus,
} from '../lib/inquiry'
import { InquiryCard } from './InquiriesPage'

const message = (err: unknown) =>
  err instanceof ApiError ? (err.fieldErrors[0]?.message ?? err.message) : '잠시 후 다시 시도해 주세요'

// S13 관리자 (T17): 문의 답변 + 업데이트 노트 작성. 서버가 /api/admin/**을 ADMIN만 통과시키고, 화면은 안내만
export function AdminPage() {
  const { me } = useAuth()
  const [filter, setFilter] = useState<InquiryStatus | undefined>('OPEN')
  const [items, setItems] = useState<AdminInquiry[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback((status: InquiryStatus | undefined) => {
    fetchAdminInquiries(status)
      .then((list) => {
        setItems(list)
        setError(null)
      })
      .catch((err) => setError(message(err)))
  }, [])

  useEffect(() => {
    if (me?.role === 'ADMIN') load(filter)
  }, [me, filter, load])

  if (me?.role !== 'ADMIN') {
    return <p className="py-16 text-center text-gray-600">관리자만 볼 수 있어요</p>
  }

  return (
    <div className="flex flex-col gap-8">
      <section className="flex flex-col gap-3">
        <div className="flex items-center justify-between">
          <h1 className="text-xl font-bold">문의 관리</h1>
          <div className="flex gap-1 rounded-lg bg-gray-100 p-1 text-sm">
            {([['OPEN', '답변 대기'], [undefined, '전체']] as const).map(([value, label]) => (
              <button
                key={label}
                type="button"
                onClick={() => setFilter(value)}
                className={`rounded-md px-3 py-1 ${filter === value ? 'bg-white font-medium shadow-sm' : 'text-gray-500'}`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        {items?.length === 0 ? (
          <p className="rounded-xl border border-gray-200 py-8 text-center text-sm text-gray-500">
            {filter === 'OPEN' ? '답변할 문의가 없어요' : '문의가 없어요'}
          </p>
        ) : null}
        <ul className="flex flex-col gap-3">
          {items?.map((item) => (
            <li key={item.id} className="flex flex-col gap-2">
              <InquiryCard item={item} author={item.authorNickname} />
              <AnswerForm item={item} onDone={() => load(filter)} />
            </li>
          ))}
        </ul>
      </section>

      <NoticeForm />
    </div>
  )
}

function AnswerForm({ item, onDone }: { item: AdminInquiry; onDone: () => void }) {
  const [answer, setAnswer] = useState(item.answer ?? '')
  const [issueUrl, setIssueUrl] = useState(item.issueUrl ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await answerInquiry(item.id, answer.trim(), issueUrl)
      onDone()
    } catch (err) {
      setError(message(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2 rounded-xl border border-dashed border-gray-300 p-3 text-sm">
      <textarea
        value={answer}
        onChange={(e) => setAnswer(e.target.value)}
        maxLength={INQUIRY_CONTENT_MAX}
        rows={3}
        required
        aria-label={`'${item.title}' 답변`}
        placeholder="답변 (처음 답변하면 문의한 사람에게 알림이 가요)"
        className="rounded-lg border border-gray-300 px-3 py-2 outline-none focus:border-orange-500"
      />
      <input
        value={issueUrl}
        onChange={(e) => setIssueUrl(e.target.value)}
        aria-label={`'${item.title}' GitHub Issue 주소`}
        placeholder="GitHub Issue 주소 (선택) https://github.com/…/issues/12"
        className="rounded-lg border border-gray-300 px-3 py-2 outline-none focus:border-orange-500"
      />
      {error ? <p className="text-red-600">{error}</p> : null}
      <button
        type="submit"
        disabled={saving || !answer.trim()}
        className="self-end rounded-lg bg-gray-900 px-4 py-2 font-medium text-white disabled:opacity-50"
      >
        {saving ? '저장 중…' : item.status === 'ANSWERED' ? '답변 고치기' : '답변하기'}
      </button>
    </form>
  )
}

function NoticeForm() {
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [saving, setSaving] = useState(false)
  const [result, setResult] = useState<string | null>(null)

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setSaving(true)
    setResult(null)
    try {
      await createNotice(title.trim(), content.trim())
      setTitle('')
      setContent('')
      setResult('업데이트 노트를 올렸어요')
    } catch (err) {
      setResult(message(err))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="flex flex-col gap-3">
      <h2 className="text-xl font-bold">업데이트 노트 쓰기</h2>
      <form onSubmit={handleSubmit} className="flex flex-col gap-2 rounded-xl border border-gray-200 p-4 text-sm">
        <input
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          maxLength={100}
          required
          aria-label="노트 제목"
          placeholder="제목 (예: 11/24 알림 줄바꿈 수정)"
          className="rounded-lg border border-gray-300 px-3 py-2 outline-none focus:border-orange-500"
        />
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          rows={5}
          required
          aria-label="노트 내용"
          placeholder={'받은 피드백 → 반영 내용\n예: 알림 문장이 잘려요 → 줄바꿈되게 고쳤어요 (#40)'}
          className="rounded-lg border border-gray-300 px-3 py-2 outline-none focus:border-orange-500"
        />
        {result ? <p role="status">{result}</p> : null}
        <button
          type="submit"
          disabled={saving || !title.trim() || !content.trim()}
          className="self-end rounded-lg bg-orange-500 px-4 py-2 font-semibold text-white disabled:opacity-50"
        >
          {saving ? '올리는 중…' : '노트 올리기'}
        </button>
      </form>
    </section>
  )
}
