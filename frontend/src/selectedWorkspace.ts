// Only remembers which workspace was last open in this browser. Access is always checked by the server.
const SELECTED_KEY = 'photospace.selectedWorkspace'

export function readSelected(): string | null {
  try {
    return window.localStorage.getItem(SELECTED_KEY)
  } catch {
    return null
  }
}

/** Makes `id` the workspace that opens next in this browser, such as one the user just joined. */
export function writeSelected(id: string) {
  try {
    window.localStorage.setItem(SELECTED_KEY, id)
  } catch {
    // Storage can be unavailable (private mode, blocked site data); selection then lasts for this page only.
  }
}
