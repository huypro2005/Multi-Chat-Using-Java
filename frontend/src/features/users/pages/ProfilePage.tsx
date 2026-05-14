import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ProfileInfoTab } from '../components/ProfileInfoTab'
import { ChangePasswordTab } from '../components/ChangePasswordTab'
import { AccountTab } from '../components/AccountTab'

type Tab = 'info' | 'password' | 'account'

export function ProfilePage() {
  const [activeTab, setActiveTab] = useState<Tab>('info')
  const navigate = useNavigate()

  const handleBack = () => {
    if (window.history.length > 1) {
      navigate(-1)
      return
    }
    navigate('/conversations')
  }

  return (
    <div className="max-w-3xl mx-auto p-6">
      <button
        type="button"
        onClick={handleBack}
        className="mb-4 text-sm text-gray-600 hover:text-indigo-600"
      >
        ← Quay lại
      </button>
      <h1 className="text-2xl font-bold mb-6">Hồ sơ của tôi</h1>
      <div className="flex border-b border-gray-200 mb-6">
        <TabButton active={activeTab === 'info'} onClick={() => setActiveTab('info')}>
          Thông tin
        </TabButton>
        <TabButton active={activeTab === 'password'} onClick={() => setActiveTab('password')}>
          Mật khẩu
        </TabButton>
        <TabButton active={activeTab === 'account'} onClick={() => setActiveTab('account')}>
          Tài khoản
        </TabButton>
      </div>

      {activeTab === 'info' && <ProfileInfoTab />}
      {activeTab === 'password' && <ChangePasswordTab />}
      {activeTab === 'account' && <AccountTab />}
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
