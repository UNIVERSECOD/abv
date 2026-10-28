import { useEffect, useState } from 'react'
import { isAxiosError } from 'axios'
import { useNavigate } from 'react-router-dom'
import { authApi } from '../api/authApi.ts'
import AttendraBrand from '../components/AttendraBrand.tsx'
import { t } from '../i18n/index.ts'
import { useAuthStore } from '../store/authStore.ts'
import { getApiErrorMessage } from '../utils/apiError.ts'

const PAGE_BACKGROUND = '#111827'
const ACCENT_BAR_GRADIENT = 'linear-gradient(90deg, #7c3aed, #a855f7, #22c55e)'
const BUTTON_GRADIENT = 'linear-gradient(135deg, #7c3aed, #9333ea)'
const INPUT_CLS =
  'w-full px-4 py-3 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-purple-500 focus:border-transparent text-gray-800 placeholder-gray-400 bg-gray-50 transition-all disabled:text-gray-500 disabled:bg-gray-100'

export default function InitialSetupPage() {
  const [username, setUsername] = useState('admin')
  const [setupToken, setSetupToken] = useState('')
  const [tokenError, setTokenError] = useState('')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')
  const [checkingSetup, setCheckingSetup] = useState(true)
  const [submitting, setSubmitting] = useState(false)
  const [retryCount, setRetryCount] = useState(0)
  const navigate = useNavigate()
  const { setAuth } = useAuthStore()

  useEffect(() => {
    let active = true
    setCheckingSetup(true)
    setError('')

    authApi.getInitialSetupStatus()
      .then(({ data }) => {
        if (!active) return
        if (!data.setupRequired) {
          navigate('/login', { replace: true })
          return
        }
        setUsername(data.username)
      })
      .catch((requestError: unknown) => {
        if (active) {
          setError(getApiErrorMessage(requestError, t('initialSetup.loadFailed')))
        }
      })
      .finally(() => {
        if (active) setCheckingSetup(false)
      })

    return () => {
      active = false
    }
  }, [navigate, retryCount])

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    setError('')

    if (password.length < 8) {
      setError(t('signup.passwordMinLength'))
      return
    }

    if (!setupToken.trim()) {
      setTokenError('Quraşdırma kodunu daxil edin')
      return
    }
    setTokenError('')
    if (!/\d/.test(password)) {
      setError(t('signup.passwordNeedsDigit'))
      return
    }
    if (password !== confirmPassword) {
      setError(t('signup.passwordMismatch'))
      return
    }

    setSubmitting(true)
    try {
      const { data } = await authApi.completeInitialSetup(password, confirmPassword, setupToken.trim())
      setAuth(data.token, data.user)
      navigate('/', { replace: true })
    } catch (requestError: unknown) {
      const message = getApiErrorMessage(requestError, t('initialSetup.setupFailed'))
      if (isAxiosError(requestError) && requestError.response?.status === 401) {
        setTokenError(message)
      } else {
        setError(message)
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      className="min-h-screen flex items-center justify-center p-4"
      style={{ background: PAGE_BACKGROUND }}
    >
      <main className="bg-white rounded-2xl shadow-2xl w-full max-w-lg overflow-hidden">
        <div className="h-1.5 w-full" style={{ background: ACCENT_BAR_GRADIENT }} />

        <div className="p-8">
          <AttendraBrand size="hero" showWordmark showTagline className="justify-center mb-8" />

          <div className="mb-6 text-center">
            <h1 className="text-2xl font-bold text-gray-900">{t('initialSetup.title')}</h1>
            <p className="mt-2 text-sm text-gray-500">{t('initialSetup.subtitle')}</p>
          </div>

          {error && (
            <div className="bg-red-50 border border-red-200 text-red-700 px-4 py-3 rounded-xl mb-5 text-sm">
              {error}
            </div>
          )}

          {checkingSetup ? (
            <div className="flex items-center justify-center gap-3 py-12 text-sm text-gray-500">
              <span className="h-5 w-5 rounded-full border-2 border-gray-200 border-t-purple-600 animate-spin" />
              {t('app.loading')}
            </div>
          ) : error && !password && !confirmPassword ? (
            <button
              type="button"
              onClick={() => setRetryCount((value) => value + 1)}
              className="w-full border border-gray-300 py-3 px-4 rounded-xl font-semibold text-sm text-gray-700 transition-colors hover:bg-gray-50"
            >
              {t('initialSetup.retry')}
            </button>
          ) : (
            <form onSubmit={handleSubmit} className="space-y-5">
              <div>
                <label htmlFor="setup-token" className="block text-sm font-semibold text-gray-700 mb-1.5">Quraşdırma kodu</label>
                <input id="setup-token" type="password" value={setupToken}
                  onChange={(event) => { setSetupToken(event.target.value); setTokenError('') }}
                  autoComplete="off" maxLength={256} required className={INPUT_CLS} />
                <p className="text-xs text-gray-500 mt-1.5">Kodu proqramı quraşdıran sistem administratorundan alın.</p>
                {tokenError && <p className="text-xs text-red-600 mt-1" role="alert">{tokenError}</p>}
              </div>
              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-1.5">
                  {t('initialSetup.username')}
                </label>
                <input
                  type="text"
                  value={username}
                  autoComplete="username"
                  className={INPUT_CLS}
                  readOnly
                  disabled
                />
              </div>

              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-1.5">
                  {t('initialSetup.newPassword')}
                </label>
                <input
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  autoComplete="new-password"
                  className={INPUT_CLS}
                  placeholder="••••••••"
                  minLength={8}
                  required
                  autoFocus
                />
                <p className="text-xs text-gray-500 mt-1.5">{t('initialSetup.passwordHint')}</p>
              </div>

              <div>
                <label className="block text-sm font-semibold text-gray-700 mb-1.5">
                  {t('initialSetup.confirmPassword')}
                </label>
                <input
                  type="password"
                  value={confirmPassword}
                  onChange={(event) => setConfirmPassword(event.target.value)}
                  autoComplete="new-password"
                  className={INPUT_CLS}
                  placeholder="••••••••"
                  minLength={8}
                  required
                />
              </div>

              <button
                type="submit"
                disabled={submitting}
                className="w-full text-white py-3 px-4 rounded-xl font-semibold text-sm transition-all disabled:opacity-50 disabled:cursor-not-allowed hover:shadow-lg active:scale-95"
                style={{ background: BUTTON_GRADIENT }}
              >
                {submitting ? t('initialSetup.submitting') : t('initialSetup.submit')}
              </button>
            </form>
          )}
        </div>
      </main>
    </div>
  )
}
