// End-to-end check: load the built extension in Chrome for Testing, point it at a mock
// Anthropic SSE server, and drive select -> Ask -> streamed sidenote. Run via `bb e2e`.
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';

const extPath = path.resolve(process.env.EXT_DIR ?? path.join(path.dirname(fileURLToPath(import.meta.url)), '../ext'));
const requests = [];
let aborted = false;

// Page variants for re-anchoring tests: the target paragraph shifted, its text changed,
// or rendered late by script.
let variant = 'normal';
const TARGET = '<p id="target">The Curry-Howard correspondence says that implication corresponds to function types.</p>';
const pageHtml = () => PAGE
  .replace(TARGET, {
    normal: TARGET,
    shifted: '<p>An inserted paragraph that moves everything down.</p>' + TARGET,
    changed: '<p id="target">The correspondence says implication is a function type.</p>',
    late: '<div id="slot"></div><script>setTimeout(() => { document.getElementById("slot").outerHTML = '
          + JSON.stringify(TARGET) + '; }, 800);</script>',
  }[variant]);

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
    return res.end(pageHtml());
  }
  let body = '';
  req.on('data', d => (body += d));
  req.on('end', async () => {
    const parsed = JSON.parse(body);
    requests.push({ headers: req.headers, body: parsed });
    const q = parsed.messages.at(-1).content;
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

  // Helpers: everything in the extension UI lives in an open shadow root.
  const $ = sel => page.evaluateHandle(
    s => document.querySelector('tosafot-host')?.shadowRoot?.querySelector(s), sel);
  const has = sel => page.evaluate(
    s => !!document.querySelector('tosafot-host')?.shadowRoot?.querySelector(s), sel);
  const waitFor = (sel, text, timeout = 10000) => page.waitForFunction((s, t) => {
    const el = document.querySelector('tosafot-host')?.shadowRoot?.querySelector(s);
    return el && (!t || el.textContent.includes(t));
  }, { timeout }, sel, text);
  const select = () => page.evaluate(() => {
    const t = document.querySelector('#target').firstChild;
    const r = document.createRange();
    r.setStart(t, 4);                       // "Curry-Howard correspondence"
    r.setEnd(t, 31);
    getSelection().removeAllRanges();
    getSelection().addRange(r);
    document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  });
  const openPopover = async () => {
    await select();
    await waitFor('.bubble');
    await (await $('.bubble')).click();
    await waitFor('.popover textarea');
  };
  // Submit text from whichever textarea has focus. mod=true sends Cmd+Enter (comment).
  const submit = async (text, mod = false) => {
    await page.keyboard.type(text);
    if (mod) await page.keyboard.down('Meta');
    await page.keyboard.press('Enter');
    if (mod) await page.keyboard.up('Meta');
  };
  const follow = async (text, mod = false) => {
    await (await $('.note .composer textarea')).focus();
    await submit(text, mod);
  };
  const turns = () => page.evaluate(() =>
    [...document.querySelector('tosafot-host').shadowRoot.querySelectorAll('.note .turn')].map(t => ({
      cls: t.className, meta: t.querySelector('.meta').textContent,
      html: t.querySelector('.text').innerHTML, status: t.querySelector('.status').textContent,
    })));
  const lastLlmDone = () => page.waitForFunction(() => {
    const n = document.querySelector('tosafot-host').shadowRoot.querySelector('.note');
    const ts = n?.querySelectorAll('.turn.llm');
    return ts?.length && n.querySelector('button.ask') && !n.querySelector('button.ask').disabled;
  }, { timeout: 10000 });
  const closeNote = async () => (await $('.note-head button.close')).click();

  // First ask
  await openPopover();
  await submit('What is this?');
  await waitFor('.turn.llm .text', 'proofs to programs');
  await lastLlmDone();
  let ts = await turns();
  check('question and answer appear as turns',
        ts.length === 2 && ts[0].cls.includes('human') && ts[1].cls.includes('llm'), JSON.stringify(ts));
  check('answer streamed and rendered as markdown',
        ts[1].html.includes('<strong>Curry-Howard</strong>') && ts[1].html.includes('<li>'), ts[1].html);
  check('answer labelled with model', ts[1].meta.startsWith('claude-opus-5-5 · '), ts[1].meta);
  check('status cleared after completion', ts[1].status === '', ts[1].status);
  const placed = await page.evaluate(() => {
    const n = document.querySelector('tosafot-host').shadowRoot.querySelector('.note');
    return n.getBoundingClientRect().left > document.querySelector('#target').getBoundingClientRect().right;
  });
  check('note placed in the right margin', placed);
  check('selection highlighted', (await page.evaluate(() => CSS.highlights.get('tosafot')?.size)) === 1);

  let req = requests.at(-1);
  check('sends API key and browser-access header',
        req.headers['x-api-key'] === 'test-key' && req.headers['anthropic-dangerous-direct-browser-access'] === 'true');
  check('requests streaming with effort and fallbacks',
        req.body.stream === true && req.body.output_config?.effort === 'low' && req.body.fallbacks === 'default');
  const content = req.body.messages[0].content;
  check('prompt has selection in context and question',
        content.includes('The <selection>Curry-Howard correspondence</selection> says') &&
        content.includes('proof of a proposition') && content.endsWith('What is this?'), content);
  check('extra system prompt appended', req.body.system.endsWith('Be brief.'));

  // Comment, then follow-up: comment adds no request, and both reach the next prompt
  const n = requests.length;
  await follow('I think this is about Lean specifically', true);
  await waitFor('.turn.comment .text', 'Lean specifically');
  check('comment adds a turn without calling the LLM', requests.length === n);
  check('comment is labelled as a note', (await turns()).at(-1).meta.startsWith('Note · '));
  await follow('Who named it?');
  await page.waitForFunction(n => {
    const ts = document.querySelector('tosafot-host').shadowRoot.querySelectorAll('.turn.llm');
    return ts.length === 2 && ts[1].textContent.includes('proofs to programs');
  }, { timeout: 10000 }, n);
  await lastLlmDone();
  req = requests.at(-1);
  const msgs = req.body.messages;
  check('follow-up sends the thread as alternating turns',
        msgs.map(m => m.role).join() === 'user,assistant,user', msgs.map(m => m.role).join());
  check('first turn keeps the page context', msgs[0].content.includes('<selection>'));
  check('prior answer is sent back', msgs[1].content.includes('proofs to programs'));
  check('comment is merged into the follow-up as a note',
        msgs[2].content === '[note] I think this is about Lean specifically\n\nWho named it?', msgs[2].content);
  check('composer is cleared after submit',
        (await page.evaluate(() => document.querySelector('tosafot-host').shadowRoot
          .querySelector('.note .composer textarea').value)) === '');

  // Copy puts the thread on the clipboard as Markdown
  const ctx = browser.defaultBrowserContext();
  await ctx.overridePermissions(base, ['clipboard-read', 'clipboard-write', 'clipboard-sanitized-write']);
  await (await $('.composer button[title^="Copy"]')).click();
  const clip = await page.evaluate(() => navigator.clipboard.readText());
  check('copy exports the thread as Markdown',
        clip.startsWith('> Curry-Howard correspondence') && clip.includes('**Q:** What is this?') &&
        clip.includes('**Note:** I think') && clip.includes('**claude-opus-5-5:** The **Curry-Howard**'), clip);

  if (process.env.SHOT) await page.screenshot({ path: process.env.SHOT });

  // Stop mid-stream: aborts the request, keeps the partial, re-enables Ask
  await follow('slow');
  await waitFor('.turn.llm:last-child .text', 'tick');
  check('Ask is disabled while streaming', await page.evaluate(() =>
    document.querySelector('tosafot-host').shadowRoot.querySelector('.note button.ask').disabled));
  const nTurns = (await turns()).length;
  await follow('typed while busy');
  const kept = await page.evaluate(() => document.querySelector('tosafot-host').shadowRoot
    .querySelector('.note .composer textarea').value);
  check('Enter while streaming keeps the typed text', kept === 'typed while busy' && (await turns()).length === nTurns, kept);
  await page.evaluate(() => { document.querySelector('tosafot-host').shadowRoot
    .querySelector('.note .composer textarea').value = ''; });
  await (await $('.note button.stop')).click();
  await new Promise(r => setTimeout(r, 500));
  check('Stop aborts the fetch', aborted);
  ts = await turns();
  check('stopped answer is kept and marked', ts.at(-1).status === 'Stopped.' && ts.at(-1).html.includes('tick'));
  check('Ask is re-enabled after stopping', await page.evaluate(() =>
    !document.querySelector('tosafot-host').shadowRoot.querySelector('.note button.ask').disabled));

  // Close removes note and highlight
  await closeNote();
  check('close removes the note', !(await has('.note')));
  check('close removes highlight', (await page.evaluate(() => CSS.highlights.get('tosafot')?.size)) === 0);

  // Closing mid-stream aborts the request
  aborted = false;
  await openPopover();
  await submit('slow');
  await waitFor('.turn.llm .text', 'tick');
  await closeNote();
  await new Promise(r => setTimeout(r, 500));
  check('closing mid-stream aborts the fetch', aborted);

  // A note can start as a comment, with no LLM call
  const before = requests.length;
  await openPopover();
  await submit('Remember to check the HoTT book', true);
  await waitFor('.turn.comment .text', 'HoTT book');
  ts = await turns();
  check('popover comment starts a note without calling the LLM',
        ts.length === 1 && requests.length === before, JSON.stringify(ts));
  await closeNote();

  // Esc closes the popover even with focus in its textarea
  await openPopover();
  await page.keyboard.press('Escape');
  check('Esc closes the popover', !(await has('.popover')));

  // Keyboard-command path: background -> content "open-popover"
  await select();
  await page.bringToFront();
  const sendOpen = () => sw.evaluate(async () => {
    const [t] = await chrome.tabs.query({ active: true, currentWindow: true });
    return chrome.tabs.sendMessage(t.id, { type: 'open-popover' });
  });
  const opened = await sendOpen();
  check('open-popover message opens the popover',
        await waitFor('.popover', null, 3000).then(() => true, () => false));
  check('open-popover replies true with a selection', opened === true);
  await page.keyboard.press('Escape');
  await page.evaluate(() => getSelection().removeAllRanges());
  check('open-popover replies false without a selection',
        (await sendOpen()) === false && !(await has('.popover')));

  // Refusal is reported and excluded from later history
  await openPopover();
  await submit('refuse');
  check('refusal is reported',
        await waitFor('.turn.llm .status', 'declined').then(() => true, () => false));
  await follow('Try again');
  await waitFor('.turn.llm:last-child .text', 'proofs to programs');
  const roles = requests.at(-1).body.messages.map(m => m.role).join();
  check('refused answer is left out of the history', roles === 'user', roles);
  await lastLlmDone();
  await closeNote();

  // ---- Saved notes (milestone 3); a separate URL keeps them apart from the notes above
  const savedSize = () => page.evaluate(() => CSS.highlights.get('tosafot-saved')?.size ?? 0);
  const savedText = () => page.evaluate(() =>
    [...(CSS.highlights.get('tosafot-saved') || [])].map(r => r.toString()));
  const reload = async () => {
    await page.reload();
    await page.waitForFunction(() => document.readyState === 'complete');
  };
  const clickTarget = async () => {
    const pt = await page.evaluate(() => {
      const t = [...document.querySelectorAll('p')].find(p => p.textContent.includes('Curry-Howard')).firstChild;
      const i = t.data.indexOf('Curry-Howard');
      const r = document.createRange(); r.setStart(t, i); r.setEnd(t, i + 27);
      const b = r.getBoundingClientRect();
      return { x: b.left + b.width / 2, y: b.top + b.height / 2 };
    });
    await page.mouse.click(pt.x, pt.y);
  };
  const persistUrl = `${base}/page?persist`;
  await page.goto(persistUrl);
  await openPopover();
  await submit('What is this?');
  await waitFor('.turn.llm .text', 'proofs to programs');
  await lastLlmDone();
  check('new notes start unsaved', (await (await $('.note-head button.save')).evaluate(b => b.textContent)) === 'Save');
  await (await $('.note-head button.save')).click();
  check('Save marks the note saved',
        await (await $('.note-head button.save')).evaluate(b => b.textContent === 'Saved ✓' && b.disabled));
  await closeNote();
  check('closing a saved note leaves a saved highlight', (await savedSize()) === 1);

  await reload();
  await page.waitForFunction(() => (CSS.highlights.get('tosafot-saved')?.size ?? 0) === 1, { timeout: 5000 })
    .catch(() => {});
  check('saved note is highlighted after reload', (await savedSize()) === 1);
  check('saved highlight covers the original text', (await savedText())[0] === 'Curry-Howard correspondence',
        JSON.stringify(await savedText()));
  check('saved notes start closed', !(await has('.note')));
  await clickTarget();
  check('clicking the highlight opens the note', await waitFor('.note', null, 3000).then(() => true, () => false));
  ts = await turns();
  check('reopened note has its thread', ts.length === 2 && ts[1].html.includes('proofs to programs'), JSON.stringify(ts));
  check('reopened note shows as saved',
        await (await $('.note-head button.save')).evaluate(b => b.textContent === 'Saved ✓'));
  await follow('Who named it?');
  await page.waitForFunction(() =>
    document.querySelector('tosafot-host').shadowRoot.querySelectorAll('.turn.llm').length === 2);
  await lastLlmDone();
  await closeNote();
  await reload();
  await page.waitForFunction(() => (CSS.highlights.get('tosafot-saved')?.size ?? 0) === 1, { timeout: 5000 });
  await clickTarget();
  await waitFor('.note', null, 3000);
  check('follow-up on a saved note persists', (await turns()).length === 4);
  const followReq = requests.at(-1).body.messages[0].content;
  check('follow-up on a reopened note still sends page context',
        followReq.includes('<selection>Curry-Howard correspondence</selection>') && followReq.includes('proof of a proposition'),
        followReq);
  await closeNote();

  variant = 'shifted';
  await reload();
  await page.waitForFunction(() => (CSS.highlights.get('tosafot-saved')?.size ?? 0) === 1, { timeout: 5000 })
    .catch(() => {});
  check('note re-anchors after the text moves', JSON.stringify(await savedText()) === '["Curry-Howard correspondence"]',
        JSON.stringify(await savedText()));

  variant = 'changed';
  await reload();
  check('note whose text is gone is listed as an orphan',
        await waitFor('.orphans .badge', "1 Tosafot note couldn't be placed", 5000).then(() => true, () => false));
  check('orphan is not highlighted', (await savedSize()) === 0);
  await (await $('.orphans .badge')).click();
  if (process.env.SHOT_ORPHANS) await page.screenshot({ path: process.env.SHOT_ORPHANS });
  await (await $('.orphans li')).click();
  await waitFor('.note', null, 3000);
  check('orphan opens with its thread', (await turns()).length === 4);
  await (await $('.note-head button.delete')).click();
  check('delete removes the note and the orphan badge', !(await has('.note')) && !(await has('.orphans')));
  variant = 'normal';
  await reload();
  await new Promise(r => setTimeout(r, 800));
  check('deleted note stays gone after reload', (await savedSize()) === 0 && !(await has('.orphans')));

  // Comments save automatically; notes still anchor when the page renders their text late
  await page.goto(`${base}/page?late`);
  await openPopover();
  await submit('Check against the HoTT book', true);
  await waitFor('.turn.comment .text', 'HoTT book');
  check('commenting saves the note',
        await (await $('.note-head button.save')).evaluate(b => b.textContent === 'Saved ✓'));
  await closeNote();
  variant = 'late';
  await reload();
  check('late-rendered text: note placed once it appears',
        await page.waitForFunction(() => (CSS.highlights.get('tosafot-saved')?.size ?? 0) === 1, { timeout: 5000 })
          .then(() => true, () => false));
  check('late-rendered text: no orphan badge left', !(await has('.orphans')));
  variant = 'normal';

  // Missing key -> settings prompt
  await setProvider('');
  await openPopover();
  await submit('Explain');
  check('missing key shows settings prompt',
        await waitFor('.turn.llm .status.error', 'Open settings').then(() => true, () => false));
} finally {
  await browser.close();
  server.close();
}

console.log(failures ? `\n${failures} check(s) failed` : '\nAll checks passed');
process.exit(failures ? 1 : 0);
