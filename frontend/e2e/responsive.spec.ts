import { test, expect, type Page } from '@playwright/test'

async function fitsViewport(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1)
  const overflowing = await page.locator('main button:visible, main input:visible, [role=dialog]:visible, .account-header a:visible').evaluateAll(elements =>
    elements.filter(element => { const box = element.getBoundingClientRect(); return box.left < -1 || box.right > innerWidth + 1 }).map(element => element.textContent || element.getAttribute('aria-label')))
  expect(overflowing).toEqual([])
  const overlap = await page.locator('.account-header').evaluateAll(headers => headers.flatMap(header => [...header.querySelectorAll('a')].filter(link => link.getBoundingClientRect().bottom > header.getBoundingClientRect().bottom + 1).map(link => link.textContent)))
  expect(overlap).toEqual([])
}
async function backend(page: Page) {
  await page.route('**/user-service/**', async route => {
    const path = new URL(route.request().url()).pathname
    const profile = { userId: 'user', email: 'alex@example.test', firstName: 'Alex', paymentUrl: 'http://127.0.0.1:3000/pay/alex', paymentEnabled: true, stripeOnboardingCompleted: true }
    const pool = { id: 'pool', ownerId: 'user', name: 'Team mit einem sehr langen Namen für die mobile Darstellung', membersComplete: true, memberCount: 1, activeMemberCount: 1, status: 'ACTIVE', paymentUrl: 'http://127.0.0.1:3000/pay/team/pool', members: [{ id: 'membership', userId: 'user', name: 'Alex', email: 'alex@example.test', sharePercent: 100, effectiveSharePercent: 100, status: 'ACTIVE', active: true }] }
    await route.fulfill({ json: path.endsWith('/features') ? { tipPoolsEnabled: true } : path.endsWith('/pools/all') ? [pool] : profile })
  })
  await page.route('**/payment-service/**', async route => {
    const path = new URL(route.request().url()).pathname
    let value: unknown = []
    if (path.endsWith('/test-access')) value = { enabled: true, restricted: false, canActivate: false }
    else if (path.endsWith('/connect-account') || path.endsWith('/sync')) value = { stripeAccountId: 'acct_test', onboardingStatus: 'COMPLETE', stripeStatus: 'READY', statusCheckedAt: new Date().toISOString(), chargesEnabled: true, payoutsEnabled: true, transfersEnabled: true, detailsSubmitted: true }
    else if (path.endsWith('/balance')) value = { availableMinor: 2500, pendingMinor: 500, currency: 'EUR', checkedAt: new Date().toISOString() }
    else if (path.endsWith('/transactions')) value = [{ id: 'transaction-with-a-long-reference-for-mobile-layout', amountMinor: 500, recipientAmountMinor: 450, type: 'TIP', status: 'BOOKED', description: 'Ein großes Dankeschön für den freundlichen Service', createdAt: new Date().toISOString(), paymentMethod: 'card' }]
    else if (path.endsWith('/eligibility')) value = { canReceiveTips: true, simulation: true, eligible: true, availableMinor: 2500, simulationAvailableMinor: 2500, currency: 'EUR', bankLast4: '1234' }
    await route.fulfill({ json: value })
  })
}
for (const width of [320, 375, 768, 1440, 1920]) {
  test(`public pages fit ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await backend(page)
    for (const path of ['/', '/login', '/register', '/forgot-password', '/resend-verification', '/pay/example']) {
      await page.goto(path)
      await expect(page.locator('main')).toBeVisible()
      await fitsViewport(page)
    }
  })
  test(`dashboard, pools and transaction dialog fit ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await backend(page)
    await page.addInitScript(() => {
      const token = `header.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 }))}.signature`
      localStorage.setItem('gratilo.session', JSON.stringify({ userId: 'user', email: 'alex@example.test', accessToken: token }))
      localStorage.setItem('gratilo.profile', JSON.stringify({ email: 'alex@example.test', firstName: 'Alex', paymentUrl: 'http://127.0.0.1:3000/pay/alex', paymentEnabled: true, stripeOnboardingCompleted: true }))
    })
    await page.goto('/account')
    await expect(page.getByRole('button', { name: /Transaktion transaction-with/ })).toBeVisible()
    await fitsViewport(page)
    await expect(page.getByRole('navigation', { name: 'Kontonavigation' })).toBeVisible()
    await page.getByRole('button', { name: /Transaktion transaction-with/ }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await fitsViewport(page)
    await page.getByRole('button', { name: /Details schließen/ }).click()
    await page.getByRole('button', { name: 'Meine Pools', exact: true }).click()
    await expect(page.getByText('Team mit einem sehr langen Namen für die mobile Darstellung').first()).toBeVisible()
    await fitsViewport(page)
    await page.screenshot({ path: `test-results/pools-${width}.png`, fullPage: true })
  })
}
