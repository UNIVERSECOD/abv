import { ReactNode, useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuthStore } from '../store/authStore.ts'
import { t, type TranslationKey } from '../i18n/index.ts'
import { roleLabel } from '../i18n/labels.ts'
import AttendraBrand from './AttendraBrand.tsx'

const HR_ROLES = ['HEAD_OFFICE_HR', 'OFFICE_HR', 'DEPARTMENT_HR']

type NavItem = {
  path: string
  labelKey: TranslationKey
  icon: ReactNode
  headOfficeOnly?: boolean
}

const navItems: NavItem[] = [
  {
    path: '/',
    labelKey: 'layout.navDashboard',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
      </svg>
    ),
  },
  {
    path: '/employees',
    labelKey: 'layout.navEmployees',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
      </svg>
    ),
  },
  {
    path: '/branches',
    labelKey: 'layout.navBranches',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 21h18M5 21V7l8-4 6 3v15M9 9h.01M9 13h.01M9 17h.01M15 10h.01M15 14h.01M15 18h.01" />
      </svg>
    ),
  },
  {
    path: '/departments',
    labelKey: 'layout.navDepartments',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0h2m-2 0h-5m-9 0H3m2 0h5M9 7h1m-1 4h1m4-4h1m-1 4h1m-5 10v-5a1 1 0 011-1h2a1 1 0 011 1v5m-4 0h4" />
      </svg>
    ),
  },
  {
    path: '/positions',
    labelKey: 'layout.navPositions',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 13.255A23.931 23.931 0 0112 15c-3.183 0-6.22-.62-9-1.745M16 6V4a2 2 0 00-2-2h-4a2 2 0 00-2 2v2m4 6h.01M5 20h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
      </svg>
    ),
  },
  {
    path: '/attendance',
    labelKey: 'layout.navAttendance',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
      </svg>
    ),
  },
  {
    path: '/open-sessions',
    labelKey: 'layout.navOpenSessions',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 6v6l4 2m5-2a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    ),
  },
  {
    path: '/reports',
    labelKey: 'layout.navReports',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 17v-6m3 6V7m3 10v-4m4 7H5a2 2 0 01-2-2V6a2 2 0 012-2h7l5 5v9a2 2 0 01-2 2z" />
      </svg>
    ),
  },
  {
    path: '/tabel',
    labelKey: 'layout.navTabel',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 7h18M3 12h18M3 17h18M7 3v18M12 3v18M17 3v18" />
      </svg>
    ),
  },
  {
    path: '/work-schedule',
    labelKey: 'layout.navWorkSchedule',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M8 7V3m8 4V3M5 11h14M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
      </svg>
    ),
  },
  {
    path: '/permissions',
    labelKey: 'layout.navPermissions',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12.75 11.25 15 15 9.75M6.75 3.75h10.5A2.25 2.25 0 0 1 19.5 6v12a2.25 2.25 0 0 1-2.25 2.25H6.75A2.25 2.25 0 0 1 4.5 18V6a2.25 2.25 0 0 1 2.25-2.25Z" />
      </svg>
    ),
  },
  {
    path: '/devices',
    labelKey: 'layout.navDevices',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z" />
      </svg>
    ),
  },
  {
    path: '/setup/import-employees',
    labelKey: 'layout.navImport',
    headOfficeOnly: true,
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
      </svg>
    ),
  },
  {
    path: '/device-log-search',
    labelKey: 'layout.navDeviceSearch',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 10l4.553-2.069A1 1 0 0121 8.867V15.1a1 1 0 01-1.447.894L15 14M3 8a2 2 0 012-2h8a2 2 0 012 2v8a2 2 0 01-2 2H5a2 2 0 01-2-2V8z" />
        <circle cx="9" cy="12" r="1" strokeWidth={0} fill="currentColor" />
      </svg>
    ),
  },
  {
    path: '/access-logs',
    labelKey: 'layout.navAccessLogs',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" />
      </svg>
    ),
  },
  {
    path: '/audit-logs',
    labelKey: 'layout.navAuditLogs',
    headOfficeOnly: true,
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 7h6m-6 4h4" />
      </svg>
    ),
  },
  {
    path: '/settings',
    labelKey: 'layout.navSettings',
    icon: (
      <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
      </svg>
    ),
  },
]

export default function Layout({ children }: { children: ReactNode }) {
  const location = useLocation()
  const navigate = useNavigate()
  const { user, logout } = useAuthStore()
  const isHr = user?.userType ? HR_ROLES.includes(user.userType) : false
  const isHeadOfficeHr = user?.userType === 'HEAD_OFFICE_HR'
  const [collapsed, setCollapsed] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)

  const visibleNavItems = navItems.filter((item) => !item.headOfficeOnly || isHeadOfficeHr)

  const handleLogout = () => {
    logout()
    navigate('/login')
  }

  const isSignupActive = location.pathname === '/signup' || location.pathname === '/register'

  const navLinkStyle = (isActive: boolean) => ({
    color: isActive ? '#fff' : '#a5b4fc',
    background: isActive ? '#a855f7' : 'transparent',
  })

  const handleNavMouseEnter = (e: React.MouseEvent<HTMLAnchorElement | HTMLButtonElement>, isActive: boolean) => {
    if (!isActive) {
      ;(e.currentTarget as HTMLElement).style.background = 'rgba(168,85,247,0.15)'
      ;(e.currentTarget as HTMLElement).style.color = '#fff'
    }
  }

  const handleNavMouseLeave = (e: React.MouseEvent<HTMLAnchorElement | HTMLButtonElement>, isActive: boolean) => {
    if (!isActive) {
      ;(e.currentTarget as HTMLElement).style.background = 'transparent'
      ;(e.currentTarget as HTMLElement).style.color = '#a5b4fc'
    }
  }

  const sidebarContent = (
    <>
      <div className={`flex-shrink-0 border-b border-white/10 ${collapsed ? 'px-2 py-4' : 'px-4 py-5'}`}>
        {collapsed ? (
          <AttendraBrand
            size="compact"
            showWordmark={false}
            className="mx-auto"
            onClick={() => setCollapsed(false)}
            title={t('layout.expandSidebar')}
          />
        ) : (
          <AttendraBrand
            size="sidebar"
            showWordmark
            showTagline
            onDark
            className="w-full"
            onClick={() => setCollapsed(true)}
            title={t('layout.collapseSidebar')}
          />
        )}
      </div>

      <nav className="flex-1 px-2 pb-4 pt-3 space-y-0.5 overflow-y-auto">
        {visibleNavItems.map((item) => {
          const isActive = location.pathname === item.path
          const label = t(item.labelKey)
          return (
            <Link
              key={item.path}
              to={item.path}
              onClick={() => setMobileOpen(false)}
              className={`flex items-center rounded-lg transition-all text-sm font-medium ${collapsed ? 'justify-center px-2 py-2.5' : 'gap-3 px-3 py-2.5'}`}
              style={navLinkStyle(isActive)}
              title={collapsed ? label : undefined}
              onMouseEnter={(e) => handleNavMouseEnter(e, isActive)}
              onMouseLeave={(e) => handleNavMouseLeave(e, isActive)}
            >
              {item.icon}
              {!collapsed && <span className="truncate">{label}</span>}
            </Link>
          )
        })}

        {isHr && (
          <Link
            to="/signup"
            onClick={() => setMobileOpen(false)}
            className={`flex items-center rounded-lg transition-all text-sm font-medium ${collapsed ? 'justify-center px-2 py-2.5' : 'gap-3 px-3 py-2.5'}`}
            style={navLinkStyle(isSignupActive)}
            title={collapsed ? t('layout.createUser') : undefined}
            onMouseEnter={(e) => handleNavMouseEnter(e, isSignupActive)}
            onMouseLeave={(e) => handleNavMouseLeave(e, isSignupActive)}
          >
            <svg className="w-5 h-5 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
            </svg>
            {!collapsed && <span className="truncate">{t('layout.createUser')}</span>}
          </Link>
        )}
      </nav>

      <div className="px-2 pb-3 pt-2 border-t flex-shrink-0" style={{ borderColor: 'rgba(255,255,255,0.1)' }}>
        {!collapsed && (
          <div className="flex items-center gap-3 mb-2 px-1">
            <div className="w-8 h-8 rounded-full flex items-center justify-center text-white text-sm font-bold flex-shrink-0" style={{ background: '#a855f7' }}>
              {user?.username?.charAt(0).toUpperCase() ?? 'A'}
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-white text-sm font-medium truncate">{user?.username}</p>
              <p className="text-xs truncate" style={{ color: '#a5b4fc' }}>{roleLabel(user?.userType)}</p>
            </div>
          </div>
        )}
        <button
          onClick={handleLogout}
          className={`w-full flex items-center rounded-lg text-sm transition-all py-2 ${collapsed ? 'justify-center px-2' : 'gap-2 px-3'}`}
          style={{ color: '#a5b4fc' }}
          title={collapsed ? t('layout.logout') : undefined}
          onMouseEnter={(e) => {
            ;(e.currentTarget as HTMLButtonElement).style.background = 'rgba(168,85,247,0.15)'
            ;(e.currentTarget as HTMLButtonElement).style.color = '#fff'
          }}
          onMouseLeave={(e) => {
            ;(e.currentTarget as HTMLButtonElement).style.background = 'transparent'
            ;(e.currentTarget as HTMLButtonElement).style.color = '#a5b4fc'
          }}
        >
          <svg className="w-4 h-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
          </svg>
          {!collapsed && t('layout.logout')}
        </button>
      </div>
    </>
  )

  return (
    <div className="flex h-screen overflow-hidden" style={{ background: '#f1f5f9' }}>
      {mobileOpen && (
        <div
          className="fixed inset-0 z-40 bg-black bg-opacity-50 lg:hidden"
          onClick={() => setMobileOpen(false)}
        />
      )}

      <aside
        className={`flex flex-col flex-shrink-0 transition-all duration-200 z-50
          ${collapsed ? 'w-16' : 'w-72'}
          fixed lg:relative inset-y-0 left-0
          ${mobileOpen ? 'translate-x-0' : '-translate-x-full lg:translate-x-0'}`}
        style={{ background: '#1e1b4b' }}
      >
        {sidebarContent}
      </aside>

      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Mobile + desktop top brand bar */}
        <div className="flex items-center gap-3 px-4 sm:px-6 py-3 bg-white border-b border-gray-100 flex-shrink-0">
          <button
            onClick={() => setMobileOpen(true)}
            className="lg:hidden p-2 rounded-lg text-gray-600 hover:bg-gray-100 transition-colors"
            aria-label={t('layout.openMenu')}
          >
            <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <AttendraBrand size="header" showWordmark showTagline className="min-w-0" />
        </div>

        <main className="flex-1 overflow-auto min-w-0">
          {children}
        </main>
      </div>
    </div>
  )
}
