// Applies the saved theme before the first paint, so the page does not flash the wrong colors. A classic script
// in <head>, because the CSP allows only same-origin scripts. Keep the key and values in step with src/theme.ts.
;(function () {
  var choice = null
  try {
    choice = window.localStorage.getItem('photospace.theme')
  } catch {
    // Storage can be unavailable; the system setting applies.
  }
  var dark = choice === 'dark' || (choice !== 'light' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
})()
