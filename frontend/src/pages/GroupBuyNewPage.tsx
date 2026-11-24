import { useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAuth } from '../auth/context'
import { ImagePicker, type PickedImage } from '../components/ImagePicker'
import { TextField } from '../components/TextField'
import { api, ApiError } from '../lib/api'
import { startPayment } from '../lib/payment'
import { parseSharedProduct } from '../lib/productPaste'
import {
  CATEGORY_LABELS,
  previewPrice,
  toLocalInput,
  won,
  type Category,
  type GroupBuyDetail,
  type PriceType,
} from '../lib/groupBuy'

type PickupDraft = { pickupAt: string; placeName: string }

function defaultDeadline() {
  const d = new Date()
  d.setDate(d.getDate() + 3)
  d.setHours(20, 0, 0, 0)
  return d
}

function plusDays(local: string, days: number) {
  const d = new Date(local)
  d.setDate(d.getDate() + days)
  d.setHours(19, 0, 0, 0)
  return toLocalInput(d)
}

export function GroupBuyNewPage() {
  const { me } = useAuth()
  const navigate = useNavigate()

  const [productUrl, setProductUrl] = useState('')
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [category, setCategory] = useState<Category>('LIVING')
  const [images, setImages] = useState<PickedImage[]>([])
  const [priceType, setPriceType] = useState<PriceType>('EQUAL')
  const [totalPrice, setTotalPrice] = useState('')
  const [totalQuantity, setTotalQuantity] = useState('')
  const [unitLabel, setUnitLabel] = useState('개')
  const [people, setPeople] = useState('3')
  const [minUnits, setMinUnits] = useState('1')
  const [hostUnits, setHostUnits] = useState('')
  const [deadlineAt, setDeadlineAt] = useState(toLocalInput(defaultDeadline()))
  const [pickups, setPickups] = useState<PickupDraft[]>([
    { pickupAt: plusDays(toLocalInput(defaultDeadline()), 1), placeName: '' },
  ])
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [shared, setShared] = useState('')
  // 붙여넣기로 채운 칸 이름 (T19). 사용자가 확인하도록 안내만 하고, 칸은 언제든 직접 고칠 수 있다
  const [autoFilled, setAutoFilled] = useState<string[]>([])

  if (me && !me.neighborhoodName) {
    return (
      <section className="mx-auto max-w-sm text-center">
        <h1 className="mb-3 text-2xl font-bold">공구 열기</h1>
        <p className="mb-6 text-gray-600">공구는 내 동네 이웃에게 보여요. 먼저 동네를 정해 주세요.</p>
        <Link to="/me/location" className="rounded-lg bg-orange-500 px-5 py-2.5 font-medium text-white">
          동네 설정하기
        </Link>
      </section>
    )
  }

  const price = Number(totalPrice)
  const capacity = priceType === 'EQUAL' ? Number(people) : Number(totalQuantity)
  const myUnits = priceType === 'EQUAL' ? 1 : Number(hostUnits)
  const preview = previewPrice(price, capacity, myUnits || 0)

  // 쇼핑 앱 "공유 → 복사" 글을 붙여 넣으면 링크·상품명·총수량·단위를 채운다 (T19, 읽은 것만)
  const handleShared = (text: string) => {
    setShared(text)
    const parsed = parseSharedProduct(text)
    const filled: string[] = []
    if (parsed.url) {
      setProductUrl(parsed.url)
      filled.push('상품 링크')
    }
    if (parsed.title) {
      setTitle(parsed.title)
      filled.push('상품명')
    }
    if (parsed.quantity && parsed.unit) {
      setTotalQuantity(String(parsed.quantity))
      setUnitLabel(parsed.unit)
      filled.push(`총수량 ${parsed.quantity}${parsed.unit}`)
    }
    setAutoFilled(filled)
  }

  const updatePickup = (index: number, patch: Partial<PickupDraft>) =>
    setPickups((prev) => prev.map((p, i) => (i === index ? { ...p, ...patch } : p)))

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    setFieldErrors({})
    setSubmitting(true)
    try {
      const created = await api<GroupBuyDetail>('/api/group-buys', {
        method: 'POST',
        body: {
          productUrl,
          title,
          description,
          category,
          priceType,
          totalPrice: price,
          totalQuantity: Number(totalQuantity),
          unitLabel,
          people: priceType === 'EQUAL' ? Number(people) : null,
          minUnitsPerPerson: priceType === 'BY_QUANTITY' ? Number(minUnits) : null,
          hostUnits: priceType === 'BY_QUANTITY' ? Number(hostUnits) : null,
          deadlineAt,
          imageKeys: images.map((image) => image.key),
          pickupOptions: pickups.map((p) => ({ pickupAt: p.pickupAt, placeName: p.placeName })),
        },
      })
      // 개설 직후 바로 내 몫 결제로. 결제를 시작하지 못하면 상세에서 다시 결제할 수 있다
      try {
        const order = await startPayment(created.id, created.myParticipation?.units ?? 1, true)
        navigate(`/payments/checkout?orderId=${encodeURIComponent(order.orderId)}`, { replace: true })
      } catch {
        navigate(`/group-buys/${created.id}`, { replace: true })
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setFieldErrors(Object.fromEntries(err.fieldErrors.map((f) => [f.field, f.message])))
        setError(err.message)
      } else {
        setError('잠시 후 다시 시도해 주세요')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-lg">
      <h1 className="mb-1 text-2xl font-bold">공구 열기</h1>
      <p className="mb-6 text-sm text-gray-600">
        {me?.neighborhoodName} 이웃에게 보여요. 내 몫을 결제하면 공구가 공개돼요.
      </p>

      <form onSubmit={handleSubmit} className="flex flex-col gap-6" noValidate>
        <Section title="상품">
          <div className="flex flex-col gap-1 rounded-xl bg-orange-50 p-3">
            <label htmlFor="shared" className="text-sm font-medium text-gray-800">
              빠르게 채우기 (선택)
            </label>
            <p className="text-xs text-gray-600">
              쇼핑 앱에서 상품의 <strong>공유 → 복사</strong>를 누르고 여기에 붙여 넣으면 링크·상품명·수량을 채워요.
            </p>
            <textarea
              id="shared"
              rows={2}
              value={shared}
              onChange={(e) => handleShared(e.target.value)}
              placeholder={'예: 탐사 3겹 데코 화장지 30m, 30롤, 1팩\nhttps://link.coupang.com/a/…'}
              className="rounded-lg border border-orange-200 bg-white px-3 py-2 text-sm outline-none focus:border-orange-500"
            />
            {shared.trim() ? (
              <p role="status" className="text-xs text-orange-800">
                {autoFilled.length > 0
                  ? `자동으로 채웠어요: ${autoFilled.join(', ')}. 맞는지 꼭 확인해 주세요.`
                  : '읽을 수 있는 정보가 없어요. 아래 칸에 직접 적어 주세요.'}
                {autoFilled.includes('상품명') && !autoFilled.some((f) => f.startsWith('총수량'))
                  ? ' 총수량은 상품명에서 찾지 못해 직접 적어 주세요.'
                  : ''}
              </p>
            ) : null}
          </div>
          <TextField
            label="상품 링크 (필수)"
            name="productUrl"
            type="url"
            placeholder="https://"
            value={productUrl}
            onChange={(e) => setProductUrl(e.target.value)}
            error={fieldErrors.productUrl}
          />
          <TextField
            label="상품명"
            name="title"
            placeholder="예: 휴지 30롤"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            error={fieldErrors.title}
          />
          <div className="flex flex-col gap-1">
            <label htmlFor="description" className="text-sm font-medium text-gray-700">
              설명
            </label>
            <textarea
              id="description"
              rows={3}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="나누는 방법, 상품 정보 등"
              className="rounded-lg border border-gray-300 px-3 py-2 outline-none focus:border-orange-500"
            />
          </div>
          <ChipGroup
            label="카테고리"
            value={category}
            options={Object.entries(CATEGORY_LABELS).map(([value, label]) => ({ value: value as Category, label }))}
            onChange={setCategory}
          />
          <ImagePicker images={images} onChange={setImages} />
        </Section>

        <Section title="금액과 인원">
          <ChipGroup
            label="금액 방식"
            value={priceType}
            options={[
              { value: 'EQUAL', label: '똑같이 나누기' },
              { value: 'BY_QUANTITY', label: '수량만큼 내기' },
            ]}
            onChange={setPriceType}
          />
          <div className="grid grid-cols-3 gap-2">
            <TextField
              label="총가 (원)"
              name="totalPrice"
              type="number"
              inputMode="numeric"
              value={totalPrice}
              onChange={(e) => setTotalPrice(e.target.value)}
              error={fieldErrors.totalPrice}
            />
            <TextField
              label="총수량"
              name="totalQuantity"
              type="number"
              inputMode="numeric"
              value={totalQuantity}
              onChange={(e) => setTotalQuantity(e.target.value)}
              error={fieldErrors.totalQuantity}
            />
            <TextField
              label="단위"
              name="unitLabel"
              placeholder="롤, 개, g"
              value={unitLabel}
              onChange={(e) => setUnitLabel(e.target.value)}
              error={fieldErrors.unitLabel}
            />
          </div>
          {priceType === 'EQUAL' ? (
            <TextField
              label="모집 인원 (나 포함, 2~20명)"
              name="people"
              type="number"
              inputMode="numeric"
              value={people}
              onChange={(e) => setPeople(e.target.value)}
            />
          ) : (
            <div className="grid grid-cols-2 gap-2">
              <TextField
                label={`1인 최소 수량 (${unitLabel})`}
                name="minUnitsPerPerson"
                type="number"
                inputMode="numeric"
                value={minUnits}
                onChange={(e) => setMinUnits(e.target.value)}
              />
              <TextField
                label={`내가 가져갈 수량 (${unitLabel})`}
                name="hostUnits"
                type="number"
                inputMode="numeric"
                value={hostUnits}
                onChange={(e) => setHostUnits(e.target.value)}
              />
            </div>
          )}
          {preview ? (
            <div className="rounded-lg bg-orange-50 px-4 py-3 text-sm">
              {priceType === 'EQUAL' ? (
                <p>
                  이웃 1명당 <strong>{won(preview.unitPrice)}</strong>
                </p>
              ) : (
                <p>
                  {unitLabel} 1개당 <strong>{won(preview.unitPrice)}</strong>
                </p>
              )}
              <p>
                내가 결제할 금액 <strong>{won(preview.hostAmount)}</strong>
                {preview.remainder > 0 ? (
                  <span className="text-gray-500"> (나누고 남는 {won(preview.remainder)} 포함)</span>
                ) : null}
              </p>
            </div>
          ) : null}
        </Section>

        <Section title="마감과 수령">
          <TextField
            label="모집 마감 (1시간 뒤 ~ 14일 안)"
            name="deadlineAt"
            type="datetime-local"
            value={deadlineAt}
            onChange={(e) => setDeadlineAt(e.target.value)}
            error={fieldErrors.deadlineAt}
          />
          <div className="flex flex-col gap-3">
            <span className="text-sm font-medium text-gray-700">수령 후보 (마감 이후, 1~3개)</span>
            {pickups.map((pickup, index) => (
              <div key={index} className="flex flex-col gap-2 rounded-lg border border-gray-200 p-3">
                <input
                  type="datetime-local"
                  aria-label={`수령 후보 ${index + 1} 시각`}
                  value={pickup.pickupAt}
                  onChange={(e) => updatePickup(index, { pickupAt: e.target.value })}
                  className="rounded-lg border border-gray-300 px-3 py-2"
                />
                <div className="flex gap-2">
                  <input
                    aria-label={`수령 후보 ${index + 1} 장소`}
                    placeholder="예: OO편의점 앞"
                    value={pickup.placeName}
                    onChange={(e) => updatePickup(index, { placeName: e.target.value })}
                    className="min-w-0 flex-1 rounded-lg border border-gray-300 px-3 py-2"
                  />
                  {pickups.length > 1 ? (
                    <button
                      type="button"
                      onClick={() => setPickups((prev) => prev.filter((_, i) => i !== index))}
                      className="px-2 text-sm text-gray-500"
                    >
                      삭제
                    </button>
                  ) : null}
                </div>
              </div>
            ))}
            {pickups.length < 3 ? (
              <button
                type="button"
                onClick={() => setPickups((prev) => [...prev, { pickupAt: plusDays(deadlineAt, prev.length + 1), placeName: '' }])}
                className="rounded-lg border border-dashed border-gray-300 py-2 text-sm text-gray-600"
              >
                + 수령 후보 추가
              </button>
            ) : null}
          </div>
        </Section>

        {error ? <p className="text-sm text-red-600">{error}</p> : null}
        <button
          type="submit"
          disabled={submitting}
          className="rounded-lg bg-orange-500 py-3 font-medium text-white disabled:opacity-50"
        >
          {submitting ? '여는 중…' : '공구 열기'}
        </button>
      </form>
    </section>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <fieldset className="flex flex-col gap-4">
      <legend className="mb-2 text-lg font-semibold">{title}</legend>
      {children}
    </fieldset>
  )
}

function ChipGroup<T extends string>({
  label,
  value,
  options,
  onChange,
}: {
  label: string
  value: T
  options: { value: T; label: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-medium text-gray-700">{label}</span>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            aria-pressed={value === option.value}
            onClick={() => onChange(option.value)}
            className={`rounded-full border px-3 py-1.5 text-sm ${
              value === option.value ? 'border-orange-500 bg-orange-500 text-white' : 'border-gray-300 text-gray-700'
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
}
