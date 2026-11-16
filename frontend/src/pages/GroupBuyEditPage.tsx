import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useAuth } from '../auth/context'
import { ImagePicker, type PickedImage } from '../components/ImagePicker'
import { TextField } from '../components/TextField'
import { api, ApiError } from '../lib/api'
import { CATEGORY_LABELS, toLocalInput, type Category, type GroupBuyDetail } from '../lib/groupBuy'

// 바꿀 수 있는 항목만: 상품명, 설명, 카테고리, 사진, 마감 연장 (금액·수량·인원·링크는 결제 금액이 바뀌어 불가)
export function GroupBuyEditPage() {
  const { id } = useParams()
  const { loading: authLoading } = useAuth()
  const navigate = useNavigate()

  const [original, setOriginal] = useState<GroupBuyDetail | null>(null)
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<Category>('LIVING')
  const [deadlineAt, setDeadlineAt] = useState('')
  const [images, setImages] = useState<PickedImage[]>([])
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (authLoading) return
    // 늦게 도착한 이전 요청의 응답이 사용자가 입력 중인 값을 덮어쓰지 않게, 정리된 effect의 응답은 버린다
    // (개발 모드 StrictMode는 effect를 두 번 실행해서 실제로 겪은 문제)
    let active = true
    api<GroupBuyDetail>(`/api/group-buys/${id}`)
      .then((g) => {
        if (!active) return
        setOriginal(g)
        setTitle(g.title)
        setDescription(g.description)
        setCategory(g.category)
        setDeadlineAt(toLocalInput(new Date(g.deadlineAt)))
        setImages(g.images.map((image) => ({ key: image.key, previewUrl: image.url })))
      })
      .catch((err) => {
        if (active) setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
      })
    return () => {
      active = false
    }
  }, [id, authLoading])

  if (!original) {
    return <p className="py-16 text-center text-gray-500">{error ?? '불러오는 중…'}</p>
  }

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setSubmitting(true)
    const deadlineChanged = deadlineAt !== toLocalInput(new Date(original.deadlineAt))
    try {
      await api(`/api/group-buys/${original.id}`, {
        method: 'PATCH',
        body: {
          title,
          description,
          category,
          deadlineAt: deadlineChanged ? deadlineAt : null,
          imageKeys: images.map((image) => image.key),
        },
      })
      navigate(`/group-buys/${original.id}`, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '잠시 후 다시 시도해 주세요')
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-lg">
      <h1 className="mb-1 text-2xl font-bold">공구 수정</h1>
      <p className="mb-6 text-sm text-gray-600">금액·수량·인원·링크는 바꿀 수 없어요. 바꾸려면 삭제하고 다시 열어 주세요.</p>
      <form onSubmit={handleSubmit} className="flex flex-col gap-4">
        <TextField label="상품명" name="title" value={title} onChange={(e) => setTitle(e.target.value)} />
        <div className="flex flex-col gap-1">
          <label htmlFor="description" className="text-sm font-medium text-gray-700">
            설명
          </label>
          <textarea
            id="description"
            rows={4}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="rounded-lg border border-gray-300 px-3 py-2 outline-none focus:border-orange-500"
          />
        </div>
        <div className="flex flex-wrap gap-2">
          {(Object.keys(CATEGORY_LABELS) as Category[]).map((value) => (
            <button
              key={value}
              type="button"
              aria-pressed={category === value}
              onClick={() => setCategory(value)}
              className={`rounded-full border px-3 py-1.5 text-sm ${
                category === value ? 'border-orange-500 bg-orange-500 text-white' : 'border-gray-300 text-gray-700'
              }`}
            >
              {CATEGORY_LABELS[value]}
            </button>
          ))}
        </div>
        <ImagePicker images={images} onChange={setImages} />
        <TextField
          label="모집 마감 (늦추기만 가능)"
          name="deadlineAt"
          type="datetime-local"
          value={deadlineAt}
          onChange={(e) => setDeadlineAt(e.target.value)}
        />
        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <button
          type="submit"
          disabled={submitting}
          className="rounded-lg bg-orange-500 py-3 font-medium text-white disabled:opacity-50"
        >
          {submitting ? '저장 중…' : '저장'}
        </button>
      </form>
    </section>
  )
}
