import type { InputHTMLAttributes } from 'react'

type Props = InputHTMLAttributes<HTMLInputElement> & {
  label: string
  error?: string
}

export function TextField({ label, error, id, ...inputProps }: Props) {
  const inputId = id ?? inputProps.name
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={inputId} className="text-sm font-medium text-gray-700">
        {label}
      </label>
      <input
        id={inputId}
        className={`rounded-lg border px-3 py-2 outline-none focus:border-orange-500 ${
          error ? 'border-red-400' : 'border-gray-300'
        }`}
        aria-invalid={error ? true : undefined}
        {...inputProps}
      />
      {error ? <p className="text-sm text-red-600">{error}</p> : null}
    </div>
  )
}
