export function MessageSkeleton() {
  return (
    <div className="flex gap-3 p-3 animate-pulse">
      <div className="w-10 h-10 rounded-full bg-gray-200" />
      <div className="flex-1 space-y-2">
        <div className="h-3 rounded bg-gray-200 w-1/4" />
        <div className="h-4 rounded bg-gray-200 w-3/4" />
      </div>
    </div>
  )
}

export function ConversationSkeleton() {
  return (
    <div className="flex items-center gap-3 p-3 animate-pulse">
      <div className="w-12 h-12 rounded-full bg-gray-200" />
      <div className="flex-1 space-y-2">
        <div className="h-3 rounded bg-gray-200 w-3/4" />
        <div className="h-3 rounded bg-gray-200 w-1/2" />
      </div>
    </div>
  )
}

export function MemberSkeleton() {
  return (
    <div className="flex items-center gap-2 p-2 animate-pulse">
      <div className="w-8 h-8 rounded-full bg-gray-200" />
      <div className="h-3 rounded bg-gray-200 w-24" />
    </div>
  )
}
