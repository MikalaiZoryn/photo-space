import { useRef, type KeyboardEvent, type PointerEvent } from 'react'
import type { Crop } from '../api/content.ts'
import { centredCrop, moveCrop, RATIO, zoomCrop } from './crop.ts'

/** The profile grid's 3:4 thumbnail, as a share of the 4:5 crop's width. */
const GRID_SHARE = 3 / 4 / RATIO
const MIN_ZOOM = 0.3

type Props = {
  src: string
  alt: string
  /** The photo's upright size in pixels. */
  width: number
  height: number
  crop: Crop
  onChange: (crop: Crop) => void
}

/**
 * The photo with a draggable 4:5 frame: drag it, or use the arrow keys, to move it; the slider zooms it. The
 * dashed lines show the 3:4 part the profile grid shows. The original is never changed; the post kit renders the
 * crop as a separate file.
 */
export function CropEditor({ src, alt, width, height, crop, onChange }: Props) {
  const frame = useRef<HTMLDivElement>(null)
  const drag = useRef<{ x: number; y: number; crop: Crop } | null>(null)
  const zoom = crop.width / centredCrop(width, height).width

  function onPointerDown(event: PointerEvent<HTMLDivElement>) {
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = { x: event.clientX, y: event.clientY, crop }
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    const start = drag.current
    const box = frame.current?.getBoundingClientRect()
    if (!start || !box || box.width === 0 || box.height === 0) return
    onChange(moveCrop(start.crop, (event.clientX - start.x) / box.width, (event.clientY - start.y) / box.height))
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 0.05 : 0.01
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-step, 0],
      ArrowRight: [step, 0],
      ArrowUp: [0, -step],
      ArrowDown: [0, step],
    }
    const move = moves[event.key]
    if (!move) return
    event.preventDefault()
    onChange(moveCrop(crop, move[0], move[1]))
  }

  return (
    <div className="space-y-3">
      <div ref={frame} className="relative w-full overflow-hidden rounded-lg bg-muted" style={{ aspectRatio: `${width} / ${height}` }}>
        <img src={src} alt={alt} className="absolute inset-0 size-full object-cover" draggable={false} />
        <div
          role="slider"
          tabIndex={0}
          aria-label={`Crop position for ${alt}`}
          aria-valuetext={`${Math.round(crop.x * 100)}% from the left, ${Math.round(crop.y * 100)}% from the top`}
          className="absolute cursor-move touch-none border-2 border-white shadow-[0_0_0_9999px_rgba(0,0,0,0.45)] outline-none focus-visible:border-brand"
          style={{
            left: `${crop.x * 100}%`,
            top: `${crop.y * 100}%`,
            width: `${crop.width * 100}%`,
            height: `${crop.height * 100}%`,
          }}
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={() => (drag.current = null)}
          onPointerCancel={() => (drag.current = null)}
          onKeyDown={onKeyDown}
        >
          <div
            aria-hidden="true"
            className="absolute inset-y-0 border-x border-dashed border-white/80"
            style={{ left: `${((1 - GRID_SHARE) / 2) * 100}%`, right: `${((1 - GRID_SHARE) / 2) * 100}%` }}
          />
        </div>
      </div>
      <label className="flex items-center gap-3 text-sm">
        Zoom
        <input
          type="range"
          min={MIN_ZOOM}
          max={1}
          step={0.01}
          value={zoom}
          onChange={(e) => onChange(zoomCrop(crop, Number(e.target.value), width, height))}
          className="flex-1 accent-brand"
        />
      </label>
      <p className="text-xs text-muted-foreground">
        Drag the frame or use the arrow keys. The dashed lines show what the profile grid shows (3:4).
      </p>
    </div>
  )
}
