import { useRef, useState, type ChangeEvent } from 'react'
import { uploadImage } from '../lib/upload'

export type PickedImage = {
  // 서버에 보낼 키: 기존 사진이면 공개 키, 새 사진이면 tmp 키
  key: string
  previewUrl: string
}

const MAX_IMAGES = 5

export function ImagePicker({
  images,
  onChange,
}: {
  images: PickedImage[]
  onChange: (images: PickedImage[]) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const handleFiles = async (e: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []).slice(0, MAX_IMAGES - images.length)
    e.target.value = ''
    if (files.length === 0) return
    setError(null)
    setUploading((n) => n + files.length)
    let current = images
    for (const file of files) {
      try {
        const key = await uploadImage(file)
        current = [...current, { key, previewUrl: URL.createObjectURL(file) }]
        onChange(current)
      } catch (err) {
        setError(err instanceof Error ? err.message : '사진을 올리지 못했어요')
      } finally {
        setUploading((n) => n - 1)
      }
    }
  }

  const remove = (key: string) => onChange(images.filter((image) => image.key !== key))

  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-medium text-gray-700">
        사진 <span className="text-gray-400">({images.length}/{MAX_IMAGES})</span>
      </span>
      <div className="flex flex-wrap gap-2">
        {images.map((image, index) => (
          <div key={image.key} className="relative h-20 w-20 overflow-hidden rounded-lg border border-gray-200">
            <img src={image.previewUrl} alt={`사진 ${index + 1}`} className="h-full w-full object-cover" />
            <button
              type="button"
              onClick={() => remove(image.key)}
              aria-label={`사진 ${index + 1} 삭제`}
              className="absolute top-1 right-1 rounded-full bg-black/60 px-1.5 text-xs text-white"
            >
              ✕
            </button>
          </div>
        ))}
        {images.length + uploading < MAX_IMAGES ? (
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            className="flex h-20 w-20 items-center justify-center rounded-lg border border-dashed border-gray-300 text-2xl text-gray-400"
            aria-label="사진 추가"
          >
            {uploading > 0 ? '…' : '+'}
          </button>
        ) : null}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept="image/jpeg,image/png,image/webp"
        multiple
        hidden
        onChange={handleFiles}
      />
      {uploading > 0 ? <p className="text-xs text-gray-500">사진 올리는 중…</p> : null}
      {error ? <p className="text-sm text-red-600">{error}</p> : null}
    </div>
  )
}
