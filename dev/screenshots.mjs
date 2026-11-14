// Render README screenshots into assets/screenshots/ (light mode, 2x).
// Drives the built extension against a demo page and a mock Anthropic stream.
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = name => path.join(root, 'assets/screenshots', name);

const PAGE = `<!DOCTYPE html><html><head><meta charset="utf-8"><title>Propositions as Types</title>
<style>
  body { margin: 0; background: #fff; color: #222; font: 17px/1.6 Georgia, serif; }
  main { max-width: 600px; margin: 0 0 0 48px; padding: 36px 0; }
  h1 { font-size: 30px; margin: 0 0 .4em; } h2 { font-size: 20px; margin: 1.4em 0 .4em; }
  code { font: 14px Menlo, monospace; background: #f3f3f5; padding: 1px 4px; border-radius: 3px; }
</style></head><body><main>
<h1>Propositions as Types</h1>
<p>In Lean, every proposition is a type, and a proof of a proposition is a term of that type.
Checking a proof is the same as type-checking a program.</p>
<h2>Implication</h2>
<p id="target">Under the Curry–Howard correspondence, an implication <code>p → q</code> is just the type of
functions from proofs of <code>p</code> to proofs of <code>q</code>. To prove it, you write such a function.</p>
<p>Conjunction <code>p ∧ q</code> corresponds to a pair of proofs, and disjunction <code>p ∨ q</code> to a tagged
choice of one. Falsity is the empty type: there is nothing to construct, so a proof of <code>False</code>
can prove anything.</p>
<p>This is why tactics like <code>intro</code> feel like writing a lambda: they are.</p>
</main></body></html>`;

const ANSWERS = [
  `It's the observation that **logic and programming are the same thing, viewed twice**:

- a *proposition* is a *type*
- a *proof* is a *program* (a term of that type)
- checking a proof is *type-checking*

So \`p → q\` is the type of functions turning evidence for \`p\` into evidence for \`q\`, and proving it means writing one, e.g. \`fun hp => …\`.`,
  `Haskell Curry noticed the pattern for combinatory logic in the 1930s–50s, and William Howard
extended it to natural deduction and the typed lambda calculus in 1969. The name joins the two.`,
];
let n = 0;

const server = http.createServer((req, res) => {
  if (req.method === 'GET') { res.writeHead(200, { 'content-type': 'text/html' }); return res.end(PAGE); }
  let body = '';
  req.on('data', d => (body += d));
  req.on('end', async () => {
    const { model } = JSON.parse(body);
    const ev = (type, data) => res.write(`event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`);
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    ev('message_start', { message: { model } });
    ev('content_block_start', { index: 0, content_block: { type: 'text', text: '' } });
    for (const text of ANSWERS[n++ % ANSWERS.length].match(/[\s\S]{1,40}/g)) {
      ev('content_block_delta', { index: 0, delta: { type: 'text_delta', text } });
      await new Promise(r => setTimeout(r, 5));
    }
    ev('content_block_stop', { index: 0 });
    ev('message_delta', { delta: { stop_reason: 'end_turn' } });
    ev('message_stop', {});
    res.end();
  });
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await puppeteer.launch({ headless: true, enableExtensions: [path.join(root, 'ext')] });
try {
  const swt = await browser.waitForTarget(t => t.type() === 'service_worker');
  const sw = await swt.worker();
  await sw.evaluate(b => chrome.storage.local.set({ 'snapdragon/settings': {
    providers: [{ id: 'p1', name: 'Anthropic', api: 'anthropic', 'base-url': b, 'api-key': 'demo',
                  model: 'claude-opus-5-5', effort: 'low' }],
    active: 'p1', 'system-prompt': '', 'show-bubble?': true } }), base);

  const page = await browser.newPage();
  await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }]);
  await page.setViewport({ width: 1100, height: 1100, deviceScaleFactor: 2 });
  await page.goto(`${base}/`);
  await new Promise(r => setTimeout(r, 500)); // content script loads at document_idle
  const shadow = s => page.evaluateHandle(s => document.querySelector('tosafot-host').shadowRoot.querySelector(s), s);
  const waitShadow = (s, text) => page.waitForFunction((s, t) => {
    const el = document.querySelector('tosafot-host')?.shadowRoot?.querySelector(s);
    return el && (!t || el.textContent.includes(t));
  }, { timeout: 10000 }, s, text);
  const idle = () => page.waitForFunction(() => {
    const b = document.querySelector('tosafot-host').shadowRoot.querySelector('.note button.ask');
    return b && !b.disabled;
  });
  const typeIn = async (text, mod) => {
    await (await shadow('.note .composer textarea')).focus();
    await page.keyboard.type(text);
    if (mod) await page.keyboard.down('Meta');
    await page.keyboard.press('Enter');
    if (mod) await page.keyboard.up('Meta');
  };

  // Select "Curry–Howard correspondence" and show the toolbar with ⋯ open.
  await page.evaluate(() => {
    const t = document.querySelector('#target').firstChild;
    const i = t.data.indexOf('Curry');
    const r = document.createRange();
    r.setStart(t, i); r.setEnd(t, i + 'Curry–Howard correspondence'.length);
    getSelection().removeAllRanges(); getSelection().addRange(r);
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });
  await waitShadow('.toolbar');
  await (await shadow('.toolbar .more')).click();
  const h2 = await page.evaluate(() => document.querySelector('h2').getBoundingClientRect().top);
  await page.screenshot({ path: out('toolbar.png'), clip: { x: 20, y: h2 - 16, width: 640, height: 300 } });

  // A thread: Explain, a comment, a follow-up.
  await (await shadow('.toolbar > .item.action')).click();
  await waitShadow('.turn.llm .text', 'fun hp');
  await idle();
  await typeIn('So intro is literally lambda abstraction.', true);
  await typeIn('Who were Curry and Howard?');
  await waitShadow('.turn.llm:last-child .text', '1969');
  await idle();
  await (await shadow('.note-head button.save')).click();
  await page.mouse.move(1090, 1090);
  const bottom = await page.evaluate(() => {
    const n = document.querySelector('tosafot-host').shadowRoot.querySelector('.note');
    n.querySelector('.note-body').scrollTop = 0;
    return n.getBoundingClientRect().bottom;
  });
  await page.screenshot({ path: out('thread.png'), clip: { x: 20, y: 0, width: 1080, height: bottom + 24 } });
  console.log('Wrote assets/screenshots/toolbar.png, thread.png');
} finally {
  await browser.close();
  server.close();
}
