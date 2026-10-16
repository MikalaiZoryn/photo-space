/** Starts a file download from a URL whose response is an attachment, so the page itself stays open. */
export function startDownload(url: string) {
  window.location.assign(url)
}
