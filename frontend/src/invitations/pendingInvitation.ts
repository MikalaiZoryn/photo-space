// Keeps an invitation link's token for this browser tab while its owner signs in: Google sign-in is a full-page
// round trip that comes back to `/`, which drops the `#/invite/…` fragment. Session storage lasts only for the
// tab, and the token is forgotten once the invitation is accepted or set aside.
const KEY = 'photospace.pendingInvitation'

export function rememberInvitation(token: string) {
  try {
    window.sessionStorage.setItem(KEY, token)
  } catch {
    // Storage can be unavailable; the invitation then needs its link opened again after sign-in.
  }
}

export function recallInvitation(): string | null {
  try {
    return window.sessionStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function forgetInvitation() {
  try {
    window.sessionStorage.removeItem(KEY)
  } catch {
    // Nothing to forget.
  }
}
