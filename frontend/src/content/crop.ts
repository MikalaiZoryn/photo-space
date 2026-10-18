import type { Crop } from '../api/content.ts'

/** Width over height of a 4:5 crop in pixels. */
export const RATIO = 4 / 5

/** The largest centred 4:5 crop of a photo of this size, as the server's default. */
export function centredCrop(width: number, height: number): Crop {
  const photoRatio = width / height
  if (photoRatio > RATIO) {
    const w = RATIO / photoRatio
    return { x: (1 - w) / 2, y: 0, width: w, height: 1 }
  }
  const h = photoRatio / RATIO
  return { x: 0, y: (1 - h) / 2, width: 1, height: h }
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value))
}

/** The crop moved by (dx, dy) fractions, kept inside the photo. */
export function moveCrop(crop: Crop, dx: number, dy: number): Crop {
  return { ...crop, x: clamp(crop.x + dx, 0, 1 - crop.width), y: clamp(crop.y + dy, 0, 1 - crop.height) }
}

/** The crop scaled around its centre to `zoom` of the largest one (1 = largest), kept 4:5 and inside the photo. */
export function zoomCrop(crop: Crop, zoom: number, width: number, height: number): Crop {
  const largest = centredCrop(width, height)
  const w = largest.width * zoom
  const h = largest.height * zoom
  const cx = crop.x + crop.width / 2
  const cy = crop.y + crop.height / 2
  return { x: clamp(cx - w / 2, 0, 1 - w), y: clamp(cy - h / 2, 0, 1 - h), width: w, height: h }
}
