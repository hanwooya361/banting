import { formatDateTime } from '../lib/groupBuy'
import { NOTIFICATION_LABELS, type NotificationItem } from '../lib/notification'

// 알림 한 줄. 종 드롭다운과 전체 알림 화면이 같이 쓴다. 문장은 자르지 않고 줄바꿈해서 다 보여 준다
export function NotificationRow({ item, onClick }: { item: NotificationItem; onClick: (item: NotificationItem) => void }) {
  const tag = NOTIFICATION_LABELS[item.type] ?? { label: '알림', className: 'bg-gray-100 text-gray-700' }
  return (
    <button
      type="button"
      onClick={() => onClick(item)}
      className={`flex w-full gap-3 px-4 py-3 text-left text-sm hover:bg-gray-50 ${item.read ? 'text-gray-500' : 'bg-orange-50/50 text-gray-900'}`}
    >
      <span
        className={`mt-1.5 h-2 w-2 flex-none rounded-full ${item.read ? 'bg-transparent' : 'bg-orange-500'}`}
        aria-label={item.read ? undefined : '안 읽음'}
      />
      <span className="min-w-0 flex-1">
        <span className={`inline-block rounded-full px-2 py-0.5 text-[11px] font-medium ${tag.className}`}>{tag.label}</span>
        <span className="mt-1 block break-keep wrap-anywhere">{item.message}</span>
        <span className="mt-1 block text-xs text-gray-400">{formatDateTime(item.createdAt)}</span>
      </span>
    </button>
  )
}
