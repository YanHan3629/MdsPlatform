// Browser regression. Requires Playwright (resolve it using NODE_PATH if bundled).
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const settings = Object.fromEntries(fs.readFileSync(path.join(__dirname, '../.env.qa-test'), 'utf8')
  .split(/\r?\n/).filter(s => s.includes('=')).map(s => s.split('=')));
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1050 } });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    await page.goto('http://localhost:8888/data-space/index.html#qa');
    await page.locator('#qaLogin [name=userName]').fill(settings.QA_TEST_USERNAME);
    await page.locator('#qaLogin [name=password]').fill(settings.QA_TEST_PASSWORD);
    await page.locator('#qaLogin button').click();
    await page.waitForFunction(() => document.querySelector('#qaDataset').options.length > 1);
    assert.ok(await page.locator('#qaLogin').isHidden());
    await page.locator('#qaInputMode').selectOption('question_only');
    assert.ok(await page.locator('#qaImages').isDisabled());
    await page.locator('#qaRetrievalType').selectOption('text_to_image');
    await page.locator('#qaForm [name=topK]').fill('1');
    await page.locator('#qaForm [name=question]').fill('用一句话描述检索到的图片内容。');
    await page.locator('#qaSubmit').click();
    await page.waitForFunction(() => !document.querySelector('#qaSubmit').disabled, { }, { timeout: 360000 });
    assert.equal(await page.locator('#qaProgress').textContent(), '回答完成');
    assert.ok((await page.locator('#qaAnswer').textContent()).length > 0);
    assert.ok(await page.locator('[data-qa-asset]').count());
    const downloadEvent = page.waitForEvent('download');
    await page.locator('[data-qa-asset]').first().click();
    const download = await downloadEvent;
    assert.equal(await download.failure(), null);
    await page.locator('#qaLogout').click();
    assert.ok(await page.locator('#qaLogin').isVisible());
    assert.equal(await page.locator('#qaSources').textContent(), '');
    assert.deepEqual(errors, []);
    console.log('UI_OK: login, dataset selection, real RAG, source download, logout; no page errors');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
