import { useState } from 'react'
import { BlockedUsersList } from '@/features/users/components/BlockedUsersList'
import { NotificationSettings } from '../components/NotificationSettings'

type Tab = 'blocked' | 'notifications'

export function SettingsPage() {
  const [activeTab, setActiveTab] = useState<Tab>('blocked')

  return (
    <div className="max-w-3xl mx-auto p-6">
      <h1 className="text-2xl font-bold mb-6">Cài đặt</h1>
      <div className="flex border-b border-gray-200 mb-6">
        <TabButton active={activeTab === 'blocked'} onClick={() => setActiveTab('blocked')}>
          Người bị chặn
        </TabButton>
        <TabButton active={activeTab === 'notifications'} onClick={() => setActiveTab('notifications')}>
          Thông báo
        </TabButton>
      </div>

      {activeTab === 'blocked' && <BlockedUsersList />}
      {activeTab === 'notifications' && <NotificationSettings />}
    </div>
  )
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean
  onClick: () => void
  children: React.ReactNode
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={`px-4 py-2 text-sm border-b-2 ${
        active ? 'border-indigo-600 text-indigo-600 font-semibold' : 'border-transparent text-gray-600'
      }`}
    >
      {children}
    </button>
  )
}
