import type { ReactNode } from 'react'

/** A photo card: the image (or a placeholder) above its file name. */
export function PhotoFigure({ caption, children }: { caption: string; children: ReactNode }) {
  return (
    <figure className="overflow-hidden rounded-xl border bg-card shadow-xs">
      {children}
      <figcaption className="truncate px-2.5 py-2 text-xs text-muted-foreground" title={caption}>
        {caption}
      </figcaption>
    </figure>
  )
}

export function PhotoImage({ src, alt }: { src: string; alt: string }) {
  return (
    <img
      src={src}
      alt={alt}
      loading="lazy"
      className="block aspect-square w-full bg-muted object-cover transition-transform duration-300 hover:scale-[1.02]"
    />
  )
}
