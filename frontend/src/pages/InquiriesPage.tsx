import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../lib/api'
import { formatDateTime } from '../lib/groupBuy'
import {
  createInquiry,
  fetchMyInquiries,
  INQUIRY_CONTENT_MAX,
  INQUIRY_TITLE_MAX,
  INQUIRY_TYPE_LABELS,
  type Inquiry,
  type InquiryType,
} from '../lib/inquiry'

// S11 문의·피드백 (T17). 남긴 피드백은 관리자가 답변하고, 반영되면 업데이트 노트에 올라간다
export function InquiriesPage() {
  const [items, setItems] = useState<Inquiry[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [type, setType] = useState<InquiryType>('BUG')
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  useEffect(() => {
    let active = true
    fetchMyInquiries()
      .then((list) => active && setItems(list))
      .catch((err) => active && setLoadError(err instanceof ApiError ? err.message : '문의를 불러오지 못했어요'))
    return () => {
      active = false
    }
  }, [])

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    setDone(false)
    try {
      const created = await createInquiry({ type, title: title.trim(), content: content.trim() })
      setItems((prev) => [created, ...(prev ?? [])])
      setTitle('')
      setContent('')
      setDone(true)
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors[0]?.message ?? err.message) : '잠시 후 다시 시도해 주세요')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-bold">문의·피드백</h1>
        <p className="mt-1 text-sm text-gray-500">
          불편한 점이나 바라는 기능을 남겨 주세요. 답변이 달리면 알림으로 알려 드리고, 반영한 내용은{' '}
          <Link to="/notices" className="underline">
            업데이트 노트
          </Link>
          에 올려요.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="flex flex-col gap-3 rounded-xl border border-gray-200 p-4">
        <fieldset className="flex gap-2">
          <legend className="mb-2 text-sm font-medium text-gray-700">유형</legend>
          {(Object.keys(INQUIRY_TYPE_LABELS) as InquiryType[]).map((t) => (
            <label
              key={t}
              className={`cursor-pointer rounded-full border px-3 py-1.5 text-sm ${type === t ? 'border-orange-500 bg-orange-50 font-medium text-orange-700' : 'border-gray-300 text-gray-600'}`}
            >
              <input type="radio" name="type" value={t} checked={type === t} onChange={() => setType(t)} className="sr-only" />
              {INQUIRY_TYPE_LABELS[t]}
            </label>
          ))}
        </fieldset>
        <label className="flex flex-col gap-1 text-sm font-medium text-gray-700">
          제목
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={INQUIRY_TITLE_MAX}
            required
            placeholder="예: 결제 후 화면이 멈춰요"
            className="rounded-lg border border-gray-300 px-3 py-2 font-normal outline-none focus:border-orange-500"
          />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-gray-700">
          내용
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            maxLength={INQUIRY_CONTENT_MAX}
            required
            rows={5}
            placeholder="어떤 화면에서 무엇을 했는지 적어 주시면 빨리 고칠 수 있어요"
            className="rounded-lg border border-gray-300 px-3 py-2 font-normal outline-none focus:border-orange-500"
          />
          <span className="self-end text-xs font-normal text-gray-400">
            {content.length.toLocaleString()} / {INQUIRY_CONTENT_MAX.toLocaleString()}
          </span>
        </label>
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        {done ? (
          <p role="status" className="text-sm text-green-700">
            고마워요! 문의를 남겼어요.
          </p>
        ) : null}
        <button
          type="submit"
          disabled={submitting || !title.trim() || !content.trim()}
          className="rounded-lg bg-orange-500 py-2.5 font-semibold text-white disabled:opacity-50"
        >
          {submitting ? '보내는 중…' : '문의 남기기'}
        </button>
      </form>

      <section>
        <h2 className="mb-3 font-semibold">내 문의</h2>
        {loadError ? <p className="text-sm text-red-600">{loadError}</p> : null}
        {items === null && !loadError ? <p className="text-sm text-gray-500">불러오는 중…</p> : null}
        {items?.length === 0 ? (
          <p className="rounded-xl border border-gray-200 py-8 text-center text-sm text-gray-500">아직 남긴 문의가 없어요</p>
        ) : null}
        <ul className="flex flex-col gap-3">
          {items?.map((item) => (
            <li key={item.id}>
              <InquiryCard item={item} />
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

export function InquiryCard({ item, author }: { item: Inquiry; author?: string }) {
  return (
    <article className="rounded-xl border border-gray-200 p-4 text-sm">
      <div className="flex flex-wrap items-center gap-1.5 text-xs">
        <span className="rounded-full bg-gray-100 px-2 py-0.5 font-medium text-gray-700">{INQUIRY_TYPE_LABELS[item.type]}</span>
        <span
          className={`rounded-full px-2 py-0.5 font-medium ${item.status === 'ANSWERED' ? 'bg-green-100 text-green-800' : 'bg-orange-100 text-orange-800'}`}
        >
          {item.status === 'ANSWERED' ? '답변 완료' : '답변 대기'}
        </span>
        <span className="text-gray-400">
          {author ? `${author} · ` : ''}
          {formatDateTime(item.createdAt)}
        </span>
      </div>
      <p className="mt-2 font-semibold">{item.title}</p>
      <p className="mt-1 whitespace-pre-line break-keep text-gray-700 wrap-anywhere">{item.content}</p>
      {item.answer ? (
        <div className="mt-3 rounded-lg bg-gray-50 px-3 py-2">
          <p className="text-xs font-medium text-gray-500">
            반띵 답변{item.answeredAt ? ` · ${formatDateTime(item.answeredAt)}` : ''}
          </p>
          <p className="mt-1 whitespace-pre-line break-keep text-gray-800 wrap-anywhere">{item.answer}</p>
          {item.issueUrl ? (
            <a
              href={item.issueUrl}
              target="_blank"
              rel="noopener noreferrer nofollow"
              className="mt-1 inline-block text-xs text-blue-700 underline"
            >
              작업 기록(GitHub Issue) 보기 ↗
            </a>
          ) : null}
        </div>
      ) : null}
    </article>
  )
}
