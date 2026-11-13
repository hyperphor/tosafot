// End-to-end check: load the built extension in Chrome for Testing, point it at a mock
// Anthropic SSE server, and drive select -> Ask -> streamed sidenote. Run via `bb e2e`.
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';

const extPath = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../ext');
const requests = [];
let aborted = false;

const PAGE = `<!DOCTYPE html><html><head><meta charset="utf-8"><title>Lean tutorial</title></head>
<body style="max-width:560px;margin:40px">
<h1>Propositions as types</h1>
<p id="intro">In Lean, a proof of a proposition is a term whose type is that proposition.</p>
<p id="target">The Curry-Howard correspondence says that implication corresponds to function types.</p>
<p>So a proof of p → q is a function taking proofs of p to proofs of q.</p>
</body></html>`;

const chunks = ['The **Curry-Howard** ', 'correspondence maps:\n\n', '- propositions to types\n', '- proofs to programs\n'];

function sse(res, type, data) {
  res.write(`event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`);
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET') {
    res.writeHead(200, { 'content-type': 'text/html' });
    return res.end(PAGE);
  }
  let body = '';
  req.on('data', d => (body += d));
  req.on('end', async () => {
    const parsed = JSON.parse(body);
    requests.push({ headers: req.headers, body: parsed });
    const q = parsed.messages[0].content;
    const slow = q.endsWith('slow');
    const refuse = q.endsWith('refuse');
    res.on('close', () => { if (!res.writableEnded) aborted = true; });
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    sse(res, 'message_start', { message: { model: parsed.model } });
    sse(res, 'content_block_start', { index: 0, content_block: { type: 'text', text: '' } });
    for (const text of slow ? Array(50).fill('tick ') : refuse ? ['Partial '] : chunks) {
      if (res.destroyed) return;
      sse(res, 'content_block_delta', { index: 0, delta: { type: 'text_delta', text } });
      await new Promise(r => setTimeout(r, slow ? 200 : 40));
    }
    sse(res, 'content_block_stop', { index: 0 });
    sse(res, 'message_delta', { delta: { stop_reason: refuse ? 'refusal' : 'end_turn' } });
    sse(res, 'message_stop', {});
    res.end();
  });
});

await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

let failures = 0;
function check(label, ok, detail = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${ok ? '' : '  ' + detail}`);
  if (!ok) failures++;
}

const browser = await puppeteer.launch({
  headless: true,
  enableExtensions: [extPath],
  args: ['--no-first-run'],
});

try {
  const swTarget = await browser.waitForTarget(t => t.type() === 'service_worker');
  const sw = await swTarget.worker();
  const extId = new URL(swTarget.url()).host;

  // Options page renders, and saving writes settings.
  const opts = await browser.newPage();
  await opts.goto(`chrome-extension://${extId}/options.html`);
  await opts.waitForSelector('fieldset input[type=password]');
  await opts.click('#save');
  await opts.waitForFunction(() => document.querySelector('.status')?.textContent === 'Saved.');
  const saved = await sw.evaluate(() => chrome.storage.local.get('snapdragon/settings'));
  check('options page saves settings', saved['snapdragon/settings']?.providers?.length === 1);
  await opts.close();

  const setProvider = key => sw.evaluate((base, key) => chrome.storage.local.set({
    'snapdragon/settings': {
      providers: [{ id: 'p1', name: 'Anthropic', api: 'anthropic', 'base-url': base,
                    'api-key': key, model: 'claude-opus-5-5', effort: 'low' }],
      active: 'p1', 'system-prompt': 'Be brief.', 'show-bubble?': true,
    },
  }), base, key);
  await setProvider('test-key');

  const page = await browser.newPage();
  page.on('console', m => { if (m.type() === 'error') console.log('  [page]', m.text()); });
  await page.setViewport({ width: 1280, height: 800 });
  await page.goto(`${base}/page`);
  await page.waitForFunction(() => window.getComputedStyle(document.body)); // content script idle

  const shadow = (sel) => page.evaluateHandle(
    s => document.querySelector('snapdragon-host')?.shadowRoot?.querySelector(s), sel);
  const select = () => page.evaluate(() => {
    const t = document.querySelector('#target').firstChild;
    const r = document.createRange();
    r.setStart(t, 4);                       // "Curry-Howard correspondence"
    r.setEnd(t, 31);
    getSelection().removeAllRanges();
    getSelection().addRange(r);
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });
  const askVia = async question => {
    await select();
    await page.waitForFunction(() =>
      document.querySelector('snapdragon-host')?.shadowRoot?.querySelector('.bubble'));
    const bubble = await shadow('.bubble');
    await bubble.click();
    await page.waitForFunction(() =>
      document.querySelector('snapdragon-host').shadowRoot.querySelector('.popover textarea'));
    await page.keyboard.type(question);
    await page.keyboard.press('Enter');
  };
  const noteState = () => page.evaluate(() => {
    const n = document.querySelector('snapdragon-host').shadowRoot.querySelector('.note');
    if (!n) return null;
    const st = n.querySelector('.status');
    return { who: n.querySelector('.who').textContent,
             html: n.querySelector('.answer').innerHTML,
             status: st.style.display === 'none' ? null : st.textContent };
  });

  // Happy path
  await askVia('What is this?');
  await page.waitForFunction(() => {
    const a = document.querySelector('snapdragon-host').shadowRoot.querySelector('.note .answer');
    return a && a.textContent.includes('proofs to programs');
  }, { timeout: 10000 }).catch(async e => {
    console.log('note state:', await noteState(), 'requests:', requests.length);
    throw e;
  });
  const st = await noteState();
  check('answer streamed and rendered as markdown', st.html.includes('<strong>Curry-Howard</strong>') && st.html.includes('<li>'), st.html);
  check('header shows provider and model', st.who === 'Anthropic · claude-opus-5-5', st.who);
  check('status cleared after completion', st.status === null, st.status);
  const placed = await page.evaluate(() => {
    const n = document.querySelector('snapdragon-host').shadowRoot.querySelector('.note');
    return n.getBoundingClientRect().left > document.querySelector('#target').getBoundingClientRect().right;
  });
  check('note placed in the right margin', placed);
  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });
  const hl = await page.evaluate(() => CSS.highlights.get('snapdragon')?.size);
  check('selection highlighted', hl === 1, String(hl));

  const req = requests.at(-1);
  check('sends API key and browser-access header',
        req.headers['x-api-key'] === 'test-key' && req.headers['anthropic-dangerous-direct-browser-access'] === 'true');
  check('requests streaming with effort and fallbacks',
        req.body.stream === true && req.body.output_config?.effort === 'low' && req.body.fallbacks === 'default');
  const content = req.body.messages[0].content;
  check('prompt has selection in context and question',
        content.includes('The <selection>Curry-Howard correspondence</selection> says') &&
        content.includes('proof of a proposition') && content.endsWith('What is this?'), content);
  check('extra system prompt appended', req.body.system.endsWith('Be brief.'));

  // Close removes note and highlight
  await (await shadow('.note-head button')).click();
  check('close removes the note', (await noteState()) === null);
  check('close removes highlight', (await page.evaluate(() => CSS.highlights.get('snapdragon')?.size)) === 0);

  // Closing mid-stream aborts the request
  await askVia('slow');
  await page.waitForFunction(() =>
    document.querySelector('snapdragon-host').shadowRoot.querySelector('.note .answer')?.textContent.includes('tick'));
  await (await shadow('.note-head button')).click();
  await new Promise(r => setTimeout(r, 500));
  check('closing mid-stream aborts the fetch', aborted);

  // Esc closes the popover even with focus in its textarea
  await select();
  await page.waitForFunction(() => document.querySelector('snapdragon-host').shadowRoot.querySelector('.bubble'));
  await (await shadow('.bubble')).click();
  await page.waitForFunction(() => document.querySelector('snapdragon-host').shadowRoot.querySelector('.popover'));
  await page.keyboard.press('Escape');
  check('Esc closes the popover', !(await page.evaluate(() =>
    document.querySelector('snapdragon-host').shadowRoot.querySelector('.popover'))));

  // Keyboard-command path: background -> content "open-popover"
  await select();
  await page.bringToFront();
  await sw.evaluate(async () => {
    const [t] = await chrome.tabs.query({ active: true, currentWindow: true });
    await chrome.tabs.sendMessage(t.id, { type: 'open-popover' });
  });
  const opened = await page.waitForFunction(() =>
    document.querySelector('snapdragon-host').shadowRoot.querySelector('.popover'), { timeout: 3000 })
    .then(() => true, () => false);
  check('open-popover message opens the popover', opened);
  await page.keyboard.press('Escape');

  // Refusal is reported
  await askVia('refuse');
  await page.waitForFunction(() =>
    document.querySelector('snapdragon-host').shadowRoot.querySelector('.note .status')?.textContent.includes('declined'))
    .then(() => check('refusal is reported', true), async () => check('refusal is reported', false, JSON.stringify(await noteState())));
  await (await shadow('.note-head button')).click();

  // Missing key -> settings prompt
  await setProvider('');
  await askVia('Explain');
  await page.waitForFunction(() =>
    document.querySelector('snapdragon-host').shadowRoot.querySelector('.note .status.error'));
  const err = await noteState();
  check('missing key shows settings prompt', err.status.includes('No API key') && err.status.includes('Open settings'), err.status);
} finally {
  await browser.close();
  server.close();
}

console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
process.exit(failures ? 1 : 0);
