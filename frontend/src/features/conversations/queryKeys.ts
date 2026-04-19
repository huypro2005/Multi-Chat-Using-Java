export const conversationKeys = {
  all: ['conversations'] as const,
  lists: () => [...conversationKeys.all, 'list'] as const,
  list: (page: number, size: number) => [...conversationKeys.lists(), { page, size }] as const,
  detail: (id: string) => [...conversationKeys.all, 'detail', id] as const,
}

export const userKeys = {
  search: (q: string) => ['users', 'search', q] as const,
}
