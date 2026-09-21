import { useEffect, useMemo, useState, useTransition } from 'react'

const FALLBACK_RAILS = [
  {
    id: 'continue',
    title: 'Continue',
    items: [
      { id: '1', title: 'Night Harbor', subtitle: 'S2 · E4', poster: '' },
      { id: '2', title: 'Amber Circuit', subtitle: '72%', poster: '' },
      { id: '3', title: 'Glass Orchard', subtitle: 'S1 · E1', poster: '' },
    ],
  },
  {
    id: 'live',
    title: 'Live now',
    items: [
      { id: '4', title: 'ESPN', subtitle: 'Sports', poster: '' },
      { id: '5', title: 'Sky Sports', subtitle: 'Live', poster: '' },
      { id: '6', title: 'CNN', subtitle: 'News', poster: '' },
      { id: '7', title: 'DAZN', subtitle: 'Sports', poster: '' },
    ],
  },
  {
    id: 'trending',
    title: 'Trending',
    items: [
      { id: '8', title: 'Velvet Signal', subtitle: 'Movie', poster: '' },
      { id: '9', title: 'Northline', subtitle: 'Series', poster: '' },
      { id: '10', title: 'Ivory Drift', subtitle: 'Movie', poster: '' },
    ],
  },
]

function bridge() {
  return window.BetterStreamflixNative || null
}

function callNative(action, payload = {}) {
  const native = bridge()
  if (!native?.postMessage) return false
  try {
    native.postMessage(JSON.stringify({ action, ...payload }))
    return true
  } catch {
    return false
  }
}

function posterStyle(item) {
  if (item.poster) {
    return { backgroundImage: `url("${item.poster}")` }
  }
  const hue = Math.abs(hash(item.title || item.id)) % 360
  return {
    backgroundImage: `linear-gradient(160deg, hsl(${hue} 55% 42%), hsl(${(hue + 40) % 360} 40% 18%))`,
  }
}

function hash(value) {
  let h = 0
  for (let i = 0; i < value.length; i += 1) {
    h = (h << 5) - h + value.charCodeAt(i)
    h |= 0
  }
  return h
}

export default function App() {
  const [rails, setRails] = useState(FALLBACK_RAILS)
  const [profile, setProfile] = useState({ name: 'You', accent: '#E85A2A' })
  const [tab, setTab] = useState('home')
  const [status, setStatus] = useState('')
  const [, startTransition] = useTransition()

  useEffect(() => {
    document.documentElement.style.setProperty('--accent', profile.accent || '#E85A2A')
  }, [profile.accent])

  useEffect(() => {
    window.__luminaReceive = (raw) => {
      try {
        const data = typeof raw === 'string' ? JSON.parse(raw) : raw
        startTransition(() => {
          if (data.profile) setProfile((prev) => ({ ...prev, ...data.profile }))
          if (Array.isArray(data.rails) && data.rails.length) setRails(data.rails)
          if (typeof data.status === 'string') setStatus(data.status)
        })
      } catch (error) {
        console.warn('Lumina payload ignored', error)
      }
    }
    callNative('ready')
    return () => {
      delete window.__luminaReceive
    }
  }, [])

  const hero = useMemo(() => {
    const first = rails.flatMap((rail) => rail.items).find((item) => item.title)
    return first || { id: 'hero', title: 'Lumina', subtitle: 'Experimental shell' }
  }, [rails])

  const openItem = (item) => {
    if (!callNative('open', { id: item.id, title: item.title })) {
      setStatus(`Open ${item.title}`)
    }
  }

  const openNative = (dest) => {
    setTab(dest)
    if (!callNative('navigate', { dest })) {
      setStatus(`Navigate ${dest}`)
    }
  }

  return (
    <div className="shell">
      <header className="brand">
        <div className="brand-mark">
          Better<span>Streamflix</span>
        </div>
        <div className="brand-sub">Lumina · {profile.name}</div>
      </header>

      <section
        className="hero"
        style={{ '--hero-image': hero.poster ? `url("${hero.poster}")` : undefined }}
      >
        <div className="hero-art" />
        <div className="hero-copy">
          <h1>{hero.title}</h1>
          <p>
            {hero.subtitle ||
              'A cinematic React shell for catalogs, live TV, and profiles — wired to native playback.'}
          </p>
          <div className="cta-row">
            <button className="cta cta-primary" type="button" onClick={() => openItem(hero)}>
              Play
            </button>
            <button className="cta cta-ghost" type="button" onClick={() => openNative('search')}>
              Browse
            </button>
          </div>
        </div>
      </section>

      {status ? <div className="status">{status}</div> : null}

      {rails.map((rail) => (
        <section className="rail" key={rail.id}>
          <div className="rail-head">
            <h2>{rail.title}</h2>
            <span>{rail.items.length} titles</span>
          </div>
          <div className="rail-scroll">
            {rail.items.map((item) => (
              <button
                key={`${rail.id}-${item.id}`}
                className="poster"
                type="button"
                onClick={() => openItem(item)}
              >
                <div className="poster-art" style={posterStyle(item)}>
                  {item.poster ? <img src={item.poster} alt="" loading="lazy" /> : null}
                </div>
                <strong>{item.title}</strong>
                {item.subtitle ? <em>{item.subtitle}</em> : null}
              </button>
            ))}
          </div>
        </section>
      ))}

      <nav className="dock" aria-label="Primary">
        {[
          ['home', '⌂', 'Home'],
          ['search', '⌕', 'Search'],
          ['live', '●', 'Live'],
          ['settings', '⚙', 'Settings'],
        ].map(([id, icon, label]) => (
          <button
            key={id}
            type="button"
            className={tab === id ? 'active' : undefined}
            onClick={() => openNative(id)}
          >
            <span aria-hidden="true">{icon}</span>
            {label}
          </button>
        ))}
      </nav>
    </div>
  )
}
