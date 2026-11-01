import attendraMark from '../assets/attendra-mark.png'

type BrandSize = 'hero' | 'auth' | 'sidebar' | 'header' | 'loading' | 'compact'

const MARK_CLASS: Record<BrandSize, string> = {
  hero: 'h-20 w-20 sm:h-24 sm:w-24',
  auth: 'h-16 w-16 sm:h-20 sm:w-20',
  sidebar: 'h-11 w-11',
  header: 'h-10 w-10 sm:h-11 sm:w-11',
  loading: 'h-20 w-20',
  compact: 'h-9 w-9',
}

const TITLE_CLASS: Record<BrandSize, string> = {
  hero: 'text-4xl sm:text-5xl font-extrabold tracking-tight',
  auth: 'text-3xl sm:text-4xl font-extrabold tracking-tight',
  sidebar: 'text-xl font-bold tracking-tight',
  header: 'text-2xl font-bold tracking-tight',
  loading: 'text-3xl font-extrabold tracking-tight',
  compact: 'text-lg font-bold',
}

const TAGLINE_CLASS: Record<BrandSize, string> = {
  hero: 'text-sm sm:text-base',
  auth: 'text-sm',
  sidebar: 'text-[11px] leading-tight',
  header: 'text-xs sm:text-sm',
  loading: 'text-sm',
  compact: 'text-[10px]',
}

type Props = {
  size?: BrandSize
  /** Show big Attendra wordmark next to the A mark */
  showWordmark?: boolean
  /** Show slogan under the wordmark */
  showTagline?: boolean
  /** Force light text (for dark navy sidebar) */
  onDark?: boolean
  className?: string
  onClick?: () => void
  title?: string
}

/**
 * Attendra A-mark only (transparent) + optional big wordmark text.
 */
export default function AttendraBrand({
  size = 'auth',
  showWordmark = true,
  showTagline = false,
  onDark = false,
  className = '',
  onClick,
  title = 'Attendra',
}: Props) {
  const titleColor = onDark ? '#ffffff' : '#0b1b4d'
  const taglineColor = onDark ? '#c7d2fe' : '#64748b'

  const content = (
    <div className={`inline-flex items-center gap-3 min-w-0 ${className}`}>
      <img
        src={attendraMark}
        alt=""
        aria-hidden
        className={`object-contain flex-shrink-0 ${MARK_CLASS[size]}`}
        draggable={false}
      />
      {showWordmark && (
        <div className="min-w-0 text-left">
          <p className={`${TITLE_CLASS[size]} leading-none truncate`} style={{ color: titleColor }}>
            Attendra
          </p>
          {showTagline && (
            <p className={`${TAGLINE_CLASS[size]} mt-1 truncate`} style={{ color: taglineColor }}>
              Davamlı qeydiyyat, dəqiq hesabat
            </p>
          )}
        </div>
      )}
    </div>
  )

  if (onClick) {
    return (
      <button
        type="button"
        onClick={onClick}
        title={title}
        className="inline-flex items-center bg-transparent border-0 p-0 cursor-pointer"
      >
        {content}
      </button>
    )
  }

  return content
}
