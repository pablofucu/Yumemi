// Layered sprite of Yumemi (port of ui/sprite/YumemiSprite.kt). Every layer image of a slot is
// stacked and faded with CSS, so mood changes crossfade like the Android Crossfade.
import { useEffect, useState } from 'react'
import type { CSSProperties } from 'react'
import type { YumemiMood } from '../domain'

/** Frame of every sprite layer, in px. */
const FRAME_W = 528
const FRAME_H = 576

type Bust = { cx: number; cy: number; side: number }
const BUST_STAND: Bust = { cx: 352, cy: 175, side: 300 }
const BUST_SHY: Bust = { cx: 380, cy: 230, side: 300 } // the shy pose leans, so her head sits lower
/** Tighter crops: just her head, hat to chin. */
const HEAD_STAND: Bust = { cx: 360, cy: 118, side: 270 }
const HEAD_SHY: Bust = { cx: 388, cy: 173, side: 270 }

type Layers = { body: string; arms: string | null; face: string; ribbon: string; canTalk: boolean }

export function layersFor(mood: YumemiMood): Layers {
  switch (mood) {
    case 'idle':
      return { body: 'body_stand', arms: 'arms_rest', face: 'face_smile', ribbon: 'ribbon_stand_lilac', canTalk: true }
    case 'happy':
      return { body: 'body_stand', arms: 'arms_rest', face: 'face_wink', ribbon: 'ribbon_stand_gold', canTalk: true }
    case 'thinking':
      return { body: 'body_stand', arms: 'arms_think', face: 'face_ponder', ribbon: 'ribbon_stand_violet', canTalk: false }
    case 'working':
      return { body: 'body_stand', arms: 'arms_wrench', face: 'face_focus', ribbon: 'ribbon_stand_teal', canTalk: false }
    case 'surprised':
      return { body: 'body_stand', arms: 'arms_rest', face: 'face_surprised', ribbon: 'ribbon_stand_green', canTalk: true }
    case 'error':
      return { body: 'body_stand', arms: 'arms_rest', face: 'face_worried', ribbon: 'ribbon_stand_pink', canTalk: false }
    case 'sleeping':
      return { body: 'body_shy', arms: null, face: 'face_sleep', ribbon: 'ribbon_shy_violet', canTalk: false }
  }
}

const BODIES = ['body_stand', 'body_shy']
const ARMS = ['arms_rest', 'arms_think', 'arms_wrench']
const FACES = ['face_smile', 'face_wink', 'face_ponder', 'face_focus', 'face_surprised', 'face_worried', 'face_sleep']
const RIBBONS = [
  'ribbon_stand_lilac',
  'ribbon_stand_gold',
  'ribbon_stand_violet',
  'ribbon_stand_teal',
  'ribbon_stand_green',
  'ribbon_stand_pink',
  'ribbon_shy_violet',
]

const src = (id: string) => `/sprites/yu_${id}.webp`

function Group({ ids, active, className }: { ids: string[]; active: string | null; className?: string }) {
  return (
    <div className={`sprite-group ${className ?? ''}`}>
      {ids.map((id) => (
        <img key={id} className={`sprite-layer ${id === active ? 'on' : ''}`} src={src(id)} alt="" draggable={false} />
      ))}
    </div>
  )
}

const SPARKLES: [string, number, number][] = [
  ['♥', 0.12, 0.0],
  ['✦', 0.78, 0.2],
  ['♥', 0.88, 0.45],
  ['✦', 0.05, 0.62],
  ['★', 0.7, 0.8],
]
const ANIM_FLOAT_MS = 1800

function Sparkles() {
  return (
    <div className="sprite-sparkles" aria-hidden="true">
      {SPARKLES.map(([glyph, fx, phase], i) => (
        <span
          key={i}
          className={glyph === '♥' ? 'heart' : 'star'}
          style={{
            left: `${fx * 100}%`,
            fontSize: i % 2 === 0 ? 16 : 13,
            animationDelay: `${-phase * ANIM_FLOAT_MS}ms`,
          }}
        >
          {glyph}
        </span>
      ))}
    </div>
  )
}

export type SpriteProps = {
  mood: YumemiMood
  talking?: boolean
  /** Square avatar crop of her head and shoulders (just her head with [headOnly]). */
  face?: boolean
  headOnly?: boolean
  className?: string
  style?: CSSProperties
}

export function Sprite({ mood, talking = false, face = false, headOnly = false, className = '', style }: SpriteProps) {
  const happy = mood === 'happy'
  // Happy alternates the wink with a bright smile so the face keeps moving.
  const [winkOn, setWinkOn] = useState(true)
  useEffect(() => {
    setWinkOn(true)
    if (!happy) return
    const t = setInterval(() => setWinkOn((v) => !v), 650)
    return () => clearInterval(t)
  }, [happy])

  const base = layersFor(mood)
  const layers = happy && !winkOn ? { ...base, face: 'face_smile' } : base

  // Mouth flapping while she "speaks".
  const speaking = talking && layers.canTalk
  const [mouthOpen, setMouthOpen] = useState(false)
  useEffect(() => {
    if (!speaking) {
      setMouthOpen(false)
      return
    }
    let stop = false
    let t: ReturnType<typeof setTimeout>
    const tick = () => {
      if (stop) return
      setMouthOpen((v) => !v)
      t = setTimeout(tick, 160 + Math.random() * 60)
    }
    tick()
    return () => {
      stop = true
      clearTimeout(t)
    }
  }, [speaking])

  const faceActive = mouthOpen ? 'face_talk' : layers.face
  const swayClass = happy ? 'sway sway-happy' : 'sway'

  const stack = (
    <>
      <Group ids={BODIES} active={layers.body} className="slot-body" />
      <Group ids={ARMS} active={layers.arms} className="slot-arms" />
      <Group ids={[...FACES, 'face_talk']} active={faceActive} className={`slot-face ${speaking ? 'flap' : ''}`} />
      <Group ids={RIBBONS} active={layers.ribbon} className={`slot-ribbon ${swayClass}`} />
    </>
  )

  if (face) {
    const stand = layers.body === 'body_stand'
    const bust = headOnly ? (stand ? HEAD_STAND : HEAD_SHY) : stand ? BUST_STAND : BUST_SHY
    const w = (FRAME_W / bust.side) * 100
    const h = (FRAME_H / bust.side) * 100
    const left = (0.5 - bust.cx / bust.side) * 100
    const top = (0.5 - bust.cy / bust.side) * 100
    return (
      <div className={`sprite sprite-face ${happy ? 'happy' : ''} ${className}`} style={style}>
        <div className="sprite-hop">
          <div className="sprite-stack" style={{ width: `${w}%`, height: `${h}%`, left: `${left}%`, top: `${top}%` }}>
            {stack}
          </div>
        </div>
        {happy && <Sparkles />}
      </div>
    )
  }
  return (
    <div className={`sprite sprite-portrait ${happy ? 'happy' : ''} ${className}`} style={style}>
      <div className="sprite-bob">
        <div className="sprite-stack sprite-stack-fill">{stack}</div>
      </div>
      {happy && <Sparkles />}
    </div>
  )
}
