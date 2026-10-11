// Makes a Content-Security-Policy violation fail a browser gate.
//
// A blocked resource is a `securitypolicyviolation` event on the document, plus a console error
// that some gates filter out as noise. Recording the event itself means no gate can lose a block
// to its own error filter. Call `recordCspViolations(page)` before navigating and
// `readCspViolations(page)` before the browser closes.

/** Starts collecting violations on every document the page loads. */
export async function recordCspViolations(page) {
  await page.addInitScript(() => {
    window.__cspViolations = []
    document.addEventListener('securitypolicyviolation', (e) => {
      window.__cspViolations.push({ violatedDirective: e.violatedDirective, blockedURI: e.blockedURI })
    })
  })
}

/** The violations seen so far, as printable one-line descriptions. */
export async function readCspViolations(page) {
  const violations = await page.evaluate(() => window.__cspViolations ?? [])
  return violations.map((v) => `CSP violation: ${v.violatedDirective} blocked ${v.blockedURI || '(inline)'}`)
}
