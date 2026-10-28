import { useEffect, useState } from 'react'
import Layout from '../components/Layout.tsx'
import { useAuthStore } from '../store/authStore.ts'
import { t } from '../i18n/index.ts'
import { roleLabel } from '../i18n/labels.ts'
import { authApi } from '../api/authApi.ts'
import { BackupSettings, settingsApi } from '../api/settingsApi.ts'
import { getApiErrorMessage } from '../utils/apiError.ts'
import { toast } from '../store/toastStore.ts'
import BackupOperationsPanel from '../components/BackupOperationsPanel.tsx'

const formatBytes = (bytes: number) => {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const unitIndex = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / (1024 ** unitIndex)
  return `${value.toLocaleString('az-AZ', { maximumFractionDigits: unitIndex === 0 ? 0 : 2 })} ${units[unitIndex]}`
}

const formatBackupTime = (value: string | null) => {
  if (!value) return 'Hələ backup edilməyib'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('az-AZ')
}

const BACKUP_FOLDER_PICKER_URL = 'http://127.0.0.1:18765/select-folder'

export default function SettingsPage() {
  const { user } = useAuthStore()
  const [activeTab, setActiveTab] = useState<'profile' | 'system' | 'backup' | 'security'>('profile')
  const [profileSaved, setProfileSaved] = useState(false)
  const [syncInterval, setSyncInterval] = useState(2)
  const [systemLoading, setSystemLoading] = useState(false)
  const [systemSaving, setSystemSaving] = useState(false)
  const [systemSaved, setSystemSaved] = useState(false)
  const [systemError, setSystemError] = useState('')
  const [backupSettings, setBackupSettings] = useState<BackupSettings | null>(null)
  const [backupFolderPath, setBackupFolderPath] = useState('C:\\AttendraBackups\\daily')
  const [backupEnabled, setBackupEnabled] = useState(true)
  const [backupLoading, setBackupLoading] = useState(false)
  const [backupSaving, setBackupSaving] = useState(false)
  const [backupFolderSelecting, setBackupFolderSelecting] = useState(false)
  const [backupError, setBackupError] = useState('')
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordSaving, setPasswordSaving] = useState(false)
  const [passwordSuccess, setPasswordSuccess] = useState('')
  const [passwordError, setPasswordError] = useState('')

  useEffect(() => {
    if (activeTab !== 'system' || user?.userType !== 'HEAD_OFFICE_HR') return

    let active = true
    setSystemLoading(true)
    setSystemSaved(false)
    setSystemError('')

    settingsApi.getSystemSettings()
      .then(({ data }) => {
        if (active) setSyncInterval(data.data.syncIntervalMinutes)
      })
      .catch((requestError: unknown) => {
        if (active) {
          setSystemError(getApiErrorMessage(requestError, t('settings.syncSettingsLoadFailed')))
        }
      })
      .finally(() => {
        if (active) setSystemLoading(false)
      })

    return () => {
      active = false
    }
  }, [activeTab, user?.userType])

  useEffect(() => {
    if (activeTab !== 'backup' || user?.userType !== 'HEAD_OFFICE_HR') return

    let active = true
    setBackupLoading(true)
    setBackupError('')

    settingsApi.getBackupSettings()
      .then(({ data }) => {
        if (!active) return
        setBackupSettings(data.data)
        setBackupFolderPath(data.data.folderPath)
        setBackupEnabled(data.data.enabled)
      })
      .catch((requestError: unknown) => {
        if (active) {
          const message = getApiErrorMessage(requestError, t('settings.backupLoadFailed'))
          setBackupError(message)
          toast.error(t('settings.backupLoadFailed'), message)
        }
      })
      .finally(() => {
        if (active) setBackupLoading(false)
      })

    return () => {
      active = false
    }
  }, [activeTab, user?.userType])

  const handleProfileSave = () => {
    setProfileSaved(true)
    toast.success('Profil yadda saxlanıldı')
    setTimeout(() => setProfileSaved(false), 2500)
  }

  const handleSystemSave = async () => {
    setSystemSaving(true)
    setSystemSaved(false)
    setSystemError('')
    try {
      const { data } = await settingsApi.updateSystemSettings(syncInterval)
      setSyncInterval(data.data.syncIntervalMinutes)
      setSystemSaved(true)
      toast.success('Sistem ayarları yadda saxlanıldı')
    } catch (requestError: unknown) {
      const message = getApiErrorMessage(requestError, t('settings.syncSettingsSaveFailed'))
      setSystemError(message)
      toast.error('Ayarlar saxlanılmadı', message)
    } finally {
      setSystemSaving(false)
    }
  }

  const handleBackupSave = async () => {
    setBackupSaving(true)
    setBackupError('')
    try {
      const { data } = await settingsApi.updateBackupSettings(backupEnabled, backupFolderPath)
      setBackupSettings(data.data)
      setBackupFolderPath(data.data.folderPath)
      setBackupEnabled(data.data.enabled)
      toast.success(t('settings.backupSaved'), t('settings.backupSavedDesc'))
    } catch (requestError: unknown) {
      const message = getApiErrorMessage(requestError, t('settings.backupSaveFailed'))
      setBackupError(message)
      toast.error(t('settings.backupSaveFailed'), message)
    } finally {
      setBackupSaving(false)
    }
  }

  const handleBackupFolderSelect = async () => {
    setBackupFolderSelecting(true)
    setBackupError('')
    try {
      const response = await fetch(BACKUP_FOLDER_PICKER_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPath: backupFolderPath }),
      })
      if (!response.ok) {
        throw new Error(`Folder picker returned ${response.status}`)
      }

      const result = await response.json() as { cancelled?: boolean; path?: string }
      if (result.cancelled) return
      if (!result.path) throw new Error('Folder picker returned an empty path')

      setBackupFolderPath(result.path)
      toast.success(t('settings.backupFolderSelected'), t('settings.backupFolderSelectedDesc'))
    } catch {
      const message = t('settings.backupFolderPickerUnavailableDesc')
      setBackupError(message)
      toast.error(t('settings.backupFolderPickerUnavailable'), message)
    } finally {
      setBackupFolderSelecting(false)
    }
  }

  const handlePasswordChange = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setPasswordError('')
    setPasswordSuccess('')

    if (!currentPassword) {
      setPasswordError(t('settings.currentPasswordRequired'))
      return
    }
    if (newPassword.length < 8) {
      setPasswordError(t('signup.passwordMinLength'))
      return
    }
    if (!/\d/.test(newPassword)) {
      setPasswordError(t('signup.passwordNeedsDigit'))
      return
    }
    if (newPassword !== confirmPassword) {
      setPasswordError(t('signup.passwordMismatch'))
      return
    }
    if (newPassword === currentPassword) {
      setPasswordError(t('settings.passwordMustChange'))
      return
    }

    setPasswordSaving(true)
    try {
      await authApi.changePassword(currentPassword, newPassword, confirmPassword)
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      setPasswordSuccess(t('settings.passwordUpdated'))
      toast.success('Şifrə dəyişdirildi. Yenidən daxil olun.')
      useAuthStore.getState().logout()
      window.location.assign('/login')
    } catch (requestError: unknown) {
      const message = getApiErrorMessage(requestError, t('settings.passwordUpdateFailed'))
      setPasswordError(message)
      toast.error(t('settings.passwordUpdateFailed'), message)
    } finally {
      setPasswordSaving(false)
    }
  }

  const tabs = ([
    { key: 'profile', label: t('settings.profileTab'), icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
      </svg>
    )},
    { key: 'system', label: t('settings.systemTab'), icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
      </svg>
    )},
    { key: 'backup', label: t('settings.backupTab'), icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 7c0-1.657 3.582-3 8-3s8 1.343 8 3-3.582 3-8 3-8-1.343-8-3zm0 0v5c0 1.657 3.582 3 8 3s8-1.343 8-3V7m-16 5v5c0 1.657 3.582 3 8 3s8-1.343 8-3v-5" />
      </svg>
    )},
    { key: 'security', label: t('settings.securityTab'), icon: (
      <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
      </svg>
    )},
  ] as const).filter((tab) =>
    (tab.key !== 'system' && tab.key !== 'backup') || user?.userType === 'HEAD_OFFICE_HR')

  return (
    <Layout>
      <div className="p-4 sm:p-8" style={{ background: '#f8fafc', minHeight: '100vh' }}>
        {/* Header */}
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-gray-900">{t('settings.title')}</h1>
          <p className="text-sm text-gray-500 mt-1">{t('settings.subtitle')}</p>
        </div>

        <div className="flex flex-col sm:flex-row gap-6">
          {/* Sidebar Tabs */}
          <div className="w-full sm:w-52 sm:flex-shrink-0">
            <div className="bg-white rounded-xl shadow-sm p-2 flex sm:flex-col gap-1 overflow-x-auto sm:overflow-visible">
              {tabs.map((tab) => (
                <button
                  key={tab.key}
                  onClick={() => setActiveTab(tab.key)}
                  className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-all text-left flex-shrink-0 sm:flex-shrink sm:w-full"
                  style={activeTab === tab.key
                    ? { background: '#a855f7', color: '#fff' }
                    : { color: '#6b7280' }}
                >
                  {tab.icon}
                  {tab.label}
                </button>
              ))}
            </div>
          </div>

          {/* Content */}
          <div className="flex-1 min-w-0">
            {activeTab === 'profile' && (
              <div className="bg-white rounded-xl shadow-sm p-6">
                <h2 className="text-base font-semibold text-gray-800 mb-5">{t('settings.userProfile')}</h2>

                {/* Avatar section */}
                <div className="flex items-center gap-4 mb-6 pb-6 border-b border-gray-100">
                  <div
                    className="w-16 h-16 rounded-full flex items-center justify-center text-white text-xl font-bold"
                    style={{ background: '#a855f7' }}
                  >
                    {user?.username?.charAt(0).toUpperCase() ?? 'A'}
                  </div>
                  <div>
                    <p className="font-semibold text-gray-900">{user?.username}</p>
                    <p className="text-sm text-gray-500">{user?.email}</p>
                    <span
                      className="inline-block mt-1 px-2 py-0.5 rounded-full text-xs font-medium"
                      style={{ background: '#f3e8ff', color: '#7c3aed' }}
                    >
                      {roleLabel(user?.userType)}
                    </span>
                  </div>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1.5">{t('settings.username')}</label>
                    <input
                      type="text"
                      defaultValue={user?.username}
                      className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 bg-gray-50"
                      readOnly
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1.5">{t('settings.email')}</label>
                    <input
                      type="email"
                      defaultValue={user?.email}
                      className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500"
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1.5">{t('settings.role')}</label>
                    <input
                      type="text"
                      value={roleLabel(user?.userType)}
                      className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm bg-gray-50 text-gray-500"
                      readOnly
                    />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1.5">{t('settings.language')}</label>
                    <select className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500">
                      <option>{t('settings.azerbaijani')}</option>
                    </select>
                  </div>
                </div>

                <div className="mt-5 flex justify-end">
                  <button
                    onClick={handleProfileSave}
                    className="flex items-center gap-2 px-5 py-2 text-sm font-medium text-white rounded-lg transition-colors"
                    style={{ background: '#a855f7' }}
                  >
                    {profileSaved ? (
                      <>
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                        {t('settings.saved')}
                      </>
                    ) : t('settings.saveChanges')}
                  </button>
                </div>
              </div>
            )}

            {activeTab === 'system' && (
              <div className="bg-white rounded-xl shadow-sm p-6">
                <h2 className="text-base font-semibold text-gray-800 mb-5">{t('settings.systemSettings')}</h2>

                <div className="space-y-5">
                  <div className="flex items-center justify-between py-4 border-b border-gray-100">
                    <div>
                      <p className="text-sm font-medium text-gray-800">{t('settings.autoSync')}</p>
                      <p className="text-xs text-gray-400 mt-0.5">{t('settings.autoSyncDesc')}</p>
                    </div>
                    <label className="relative inline-flex items-center cursor-pointer">
                      <input type="checkbox" className="sr-only peer" defaultChecked />
                      <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-purple-300 rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-500"></div>
                    </label>
                  </div>

                  <div className="flex items-center justify-between py-4 border-b border-gray-100">
                    <div>
                      <p className="text-sm font-medium text-gray-800">{t('settings.emailNotifications')}</p>
                      <p className="text-xs text-gray-400 mt-0.5">{t('settings.emailNotificationsDesc')}</p>
                    </div>
                    <label className="relative inline-flex items-center cursor-pointer">
                      <input type="checkbox" className="sr-only peer" />
                      <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-purple-300 rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-500"></div>
                    </label>
                  </div>

                  <div className="flex items-center justify-between py-4 border-b border-gray-100">
                    <div>
                      <p className="text-sm font-medium text-gray-800">{t('settings.auditLogging')}</p>
                      <p className="text-xs text-gray-400 mt-0.5">{t('settings.auditLoggingDesc')}</p>
                    </div>
                    <label className="relative inline-flex items-center cursor-pointer">
                      <input type="checkbox" className="sr-only peer" defaultChecked />
                      <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-purple-300 rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-500"></div>
                    </label>
                  </div>

                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1.5">{t('settings.syncInterval')}</label>
                    <select
                      value={syncInterval}
                      onChange={(event) => {
                        setSyncInterval(Number(event.target.value))
                        setSystemSaved(false)
                      }}
                      disabled={systemLoading || systemSaving}
                      className="w-full max-w-xs border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100 disabled:text-gray-500"
                    >
                      <option value={2}>2</option>
                      <option value={5}>5</option>
                      <option value={10}>10</option>
                      <option value={15}>15</option>
                      <option value={30}>30</option>
                      <option value={60}>60</option>
                    </select>
                  </div>
                </div>

                {systemError && (
                  <p className="mt-5 text-sm text-red-600" role="alert">{systemError}</p>
                )}

                <div className="mt-6 flex justify-end">
                  <button
                    onClick={handleSystemSave}
                    disabled={systemLoading || systemSaving}
                    className="flex items-center gap-2 px-5 py-2 text-sm font-medium text-white rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                    style={{ background: '#a855f7' }}
                  >
                    {systemSaved ? (
                      <>
                        <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                        </svg>
                        {t('settings.saved')}
                      </>
                    ) : systemSaving ? t('common.saving') : t('settings.saveChanges')}
                  </button>
                </div>
              </div>
            )}

            {activeTab === 'backup' && (
              <div className="bg-white rounded-xl shadow-sm p-6">
                <div className="flex flex-col gap-1 mb-5">
                  <h2 className="text-base font-semibold text-gray-800">{t('settings.backupSettings')}</h2>
                  <p className="text-sm text-gray-500">{t('settings.backupDescription')}</p>
                </div>

                {backupLoading ? (
                  <p className="text-sm text-gray-500">{t('common.loading')}</p>
                ) : (
                  <>
                    <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-3 mb-6">
                      <div className="rounded-xl border border-purple-100 bg-purple-50 p-4">
                        <p className="text-xs text-purple-600">{t('settings.currentDataSize')}</p>
                        <p className="text-xl font-semibold text-purple-900 mt-1">{formatBytes(backupSettings?.currentDataBytes ?? 0)}</p>
                      </div>
                      <div className="rounded-xl border border-gray-200 bg-gray-50 p-4">
                        <p className="text-xs text-gray-500">{t('settings.databaseSize')}</p>
                        <p className="text-xl font-semibold text-gray-900 mt-1">
                          {formatBytes((backupSettings?.backendDatabaseBytes ?? 0) + (backupSettings?.isapiDatabaseBytes ?? 0))}
                        </p>
                      </div>
                      <div className="rounded-xl border border-gray-200 bg-gray-50 p-4">
                        <p className="text-xs text-gray-500">{t('settings.faceImagesSize')}</p>
                        <p className="text-xl font-semibold text-gray-900 mt-1">{formatBytes(backupSettings?.faceImagesBytes ?? 0)}</p>
                      </div>
                      <div className="rounded-xl border border-gray-200 bg-gray-50 p-4">
                        <p className="text-xs text-gray-500">{t('settings.totalBackupSize')}</p>
                        <p className="text-xl font-semibold text-gray-900 mt-1">{formatBytes(backupSettings?.totalBackupBytes ?? 0)}</p>
                      </div>
                    </div>

                    <div className="space-y-5">
                      <div className="flex items-center justify-between py-4 border-y border-gray-100">
                        <div>
                          <p className="text-sm font-medium text-gray-800">{t('settings.automaticBackup')}</p>
                          <p className="text-xs text-gray-400 mt-0.5">{t('settings.automaticBackupDesc')}</p>
                        </div>
                        <label className="relative inline-flex items-center cursor-pointer">
                          <input
                            type="checkbox"
                            className="sr-only peer"
                            checked={backupEnabled}
                            onChange={(event) => setBackupEnabled(event.target.checked)}
                            disabled={backupSaving}
                          />
                          <div className="w-11 h-6 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-purple-300 rounded-full peer peer-checked:after:translate-x-full after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-500"></div>
                        </label>
                      </div>

                      <div>
                        <label className="block text-sm font-medium text-gray-700 mb-1.5">{t('settings.backupFolder')}</label>
                        <div className="flex flex-col sm:flex-row gap-2">
                          <input
                            type="text"
                            value={backupFolderPath}
                            onChange={(event) => setBackupFolderPath(event.target.value)}
                            disabled={backupSaving || backupFolderSelecting}
                            placeholder="D:\\AttendraBackups\\daily"
                            className="min-w-0 flex-1 border border-gray-200 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100"
                          />
                          <button
                            type="button"
                            onClick={handleBackupFolderSelect}
                            disabled={backupSaving || backupFolderSelecting}
                            className="inline-flex items-center justify-center gap-2 rounded-lg border border-purple-200 px-4 py-2 text-sm font-medium text-purple-700 transition-colors hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-50"
                          >
                            <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 7a2 2 0 012-2h4l2 2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V7z" />
                            </svg>
                            {backupFolderSelecting ? t('settings.backupFolderSelecting') : t('settings.selectBackupFolder')}
                          </button>
                        </div>
                        <p className="text-xs text-gray-400 mt-1.5">{t('settings.backupFolderHelp')}</p>
                      </div>

                      <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 rounded-xl border border-gray-100 p-4">
                        <div>
                          <p className="text-xs text-gray-400">{t('settings.retentionPeriod')}</p>
                          <p className="text-sm font-medium text-gray-800 mt-1">{backupSettings?.retentionDays ?? 183} {t('settings.days')}</p>
                        </div>
                        <div>
                          <p className="text-xs text-gray-400">{t('settings.lastBackup')}</p>
                          <p className="text-sm font-medium text-gray-800 mt-1">{formatBackupTime(backupSettings?.lastBackupAt ?? null)}</p>
                        </div>
                        <div>
                          <p className="text-xs text-gray-400">{t('settings.lastBackupSize')}</p>
                          <p className="text-sm font-medium text-gray-800 mt-1">{formatBytes(backupSettings?.lastBackupBytes ?? 0)}</p>
                        </div>
                      </div>

                      {backupSettings?.lastMessage && (
                        <p className={`text-sm ${backupSettings.lastStatus === 'ERROR' ? 'text-red-600' : 'text-gray-500'}`}>
                          {backupSettings.lastMessage}
                        </p>
                      )}
                    </div>

                    {backupError && (
                      <p className="mt-5 text-sm text-red-600" role="alert">{backupError}</p>
                    )}

                    <BackupOperationsPanel />
                    <div className="mt-6 flex justify-end">
                      <button
                        onClick={handleBackupSave}
                        disabled={backupSaving || !backupFolderPath.trim()}
                        className="px-5 py-2 text-sm font-medium text-white rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        style={{ background: '#a855f7' }}
                      >
                        {backupSaving ? t('common.saving') : t('settings.saveBackupSettings')}
                      </button>
                    </div>
                  </>
                )}
              </div>
            )}

            {activeTab === 'security' && (
              <div className="bg-white rounded-xl shadow-sm p-6">
                <h2 className="text-base font-semibold text-gray-800 mb-5">{t('settings.securitySettings')}</h2>

                <div className="space-y-5">
                  <div>
                    <h3 className="text-sm font-medium text-gray-700 mb-3">{t('settings.changePassword')}</h3>
                    <form onSubmit={handlePasswordChange} className="space-y-3 max-w-md">
                      {passwordError && (
                        <p className="text-sm text-red-600" role="alert">{passwordError}</p>
                      )}
                      {passwordSuccess && (
                        <p className="text-sm text-green-700" role="status">{passwordSuccess}</p>
                      )}
                      <div>
                        <label className="block text-xs text-gray-500 mb-1">{t('settings.currentPassword')}</label>
                        <input
                          type="password"
                          value={currentPassword}
                          onChange={(event) => setCurrentPassword(event.target.value)}
                          autoComplete="current-password"
                          maxLength={128}
                          required
                          disabled={passwordSaving}
                          className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100"
                          placeholder="••••••••"
                        />
                      </div>
                      <div>
                        <label className="block text-xs text-gray-500 mb-1">{t('settings.newPassword')}</label>
                        <input
                          type="password"
                          value={newPassword}
                          onChange={(event) => setNewPassword(event.target.value)}
                          autoComplete="new-password"
                          minLength={8}
                          maxLength={128}
                          required
                          disabled={passwordSaving}
                          className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100"
                          placeholder="••••••••"
                        />
                      </div>
                      <div>
                        <label className="block text-xs text-gray-500 mb-1">{t('settings.confirmNewPassword')}</label>
                        <input
                          type="password"
                          value={confirmPassword}
                          onChange={(event) => setConfirmPassword(event.target.value)}
                          autoComplete="new-password"
                          minLength={8}
                          maxLength={128}
                          required
                          disabled={passwordSaving}
                          className="w-full border border-gray-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-purple-500 disabled:bg-gray-100"
                          placeholder="••••••••"
                        />
                      </div>
                      <button
                        type="submit"
                        disabled={passwordSaving}
                        className="px-5 py-2 text-sm font-medium text-white rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
                        style={{ background: '#a855f7' }}
                      >
                        {passwordSaving ? t('common.saving') : t('settings.updatePassword')}
                      </button>
                    </form>
                  </div>

                  <div className="pt-5 border-t border-gray-100">
                    <h3 className="text-sm font-medium text-gray-700 mb-3">{t('settings.sessionManagement')}</h3>
                    <div className="space-y-3">
                      <div className="flex items-center justify-between py-3 px-4 rounded-lg" style={{ background: '#f8fafc' }}>
                        <div>
                          <p className="text-sm font-medium text-gray-700">{t('settings.currentSession')}</p>
                          <p className="text-xs text-gray-400">{t('settings.currentSessionDesc')}</p>
                        </div>
                        <span className="px-2 py-0.5 rounded-full text-xs font-medium" style={{ background: '#d1fae5', color: '#065f46' }}>
                          {t('common.active')}
                        </span>
                      </div>
                    </div>
                    <div className="mt-3">
                      <p className="text-xs text-gray-400 mb-1.5">{t('settings.tokenExpiration')}</p>
                      <p className="text-sm font-medium text-gray-700">{t('settings.tokenExpirationDesc')}</p>
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </div>
    </Layout>
  )
}
