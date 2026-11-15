// Chrome Web Store screenshots (1280x800, no alpha) into assets/store/.
// Drives the built extension on real pages with real answers: needs ANTHROPIC_API_KEY and network.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const outDir = path.join(root, 'assets/store');
fs.mkdirSync(outDir, { recursive: true });
const out = name => path.join(outDir, name);
const only = process.argv.slice(2);           // optional scene names to run
const want = name => !only.length || only.includes(name);

const key = process.env.ANTHROPIC_API_KEY;
if (!key) throw new Error('ANTHROPIC_API_KEY not set');

const settings = apiKey => ({ 'snapdragon/settings': {
  providers: [{ id: 'p1', name: 'Anthropic', api: 'anthropic', 'base-url': 'https://api.anthropic.com',
                'api-key': apiKey, model: 'claude-opus-5-5', effort: 'low' }],
  active: 'p1', 'system-prompt': 'Keep answers short: a few sentences or bullets.', 'show-bubble?': true } });

const browser = await puppeteer.launch({ headless: true, enableExtensions: [path.join(root, 'ext')] });
try {
  const sw = await (await browser.waitForTarget(t => t.type() === 'service_worker')).worker();
  const extId = new URL(sw.url()).host;
  const setSettings = s => sw.evaluate(s => chrome.storage.local.set(s), s);

  const newPage = async () => {
    const page = await browser.newPage();
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }]);
    await page.setViewport({ width: 1280, height: 800 });
    return page;
  };
  const shot = (page, name) => page.screenshot({ path: out(name), type: 'png', omitBackground: false });

  // Shadow-root helpers for a page.
  const ui = page => {
    const $ = s => page.evaluateHandle(s => document.querySelector('tosafot-host').shadowRoot.querySelector(s), s);
    const waitFor = (s, text, timeout = 90000) => page.waitForFunction((s, t) => {
      const el = document.querySelector('tosafot-host')?.shadowRoot?.querySelector(s);
      return el && (!t || el.textContent.includes(t));
    }, { timeout }, s, text);
    const idle = () => page.waitForFunction(() => {
      const ns = document.querySelector('tosafot-host').shadowRoot.querySelectorAll('.note');
      const b = ns[ns.length - 1]?.querySelector('button.ask');
      const llm = ns[ns.length - 1]?.querySelector('.turn.llm:last-child .text');
      return b && !b.disabled && llm && llm.textContent.trim();
    }, { timeout: 120000 });
    const click = async s => (await $(s)).click();
    // Type into the last note's composer; mod=true comments.
    const typeIn = async (text, mod) => {
      await page.evaluate(() => {
        const ns = document.querySelector('tosafot-host').shadowRoot.querySelectorAll('.note');
        ns[ns.length - 1].querySelector('.composer textarea').focus();
      });
      await page.keyboard.type(text);
      if (mod) await page.keyboard.down('Meta');
      await page.keyboard.press('Enter');
      if (mod) await page.keyboard.up('Meta');
    };
    // Select the first occurrence of `text` (whitespace-insensitive, may span elements) under
    // `scope`, scroll it to `y`, and fire mouseup so the toolbar appears.
    const select = (text, scope = 'body', y = 260) => page.evaluate((text, scope, y) => {
      const map = []; let s = '';
      const w = document.createTreeWalker(document.querySelector(scope), NodeFilter.SHOW_TEXT);
      for (let n; (n = w.nextNode());) {
        for (let i = 0; i < n.data.length; i++) {
          const ws = /\s/.test(n.data[i]);
          if (ws && s.endsWith(' ')) continue;
          s += ws ? ' ' : n.data[i]; map.push([n, i]);
        }
      }
      const at = s.indexOf(text);
      if (at < 0) throw new Error('not found: ' + text);
      const [sn, so] = map[at], [en, eo] = map[at + text.length - 1];
      const r = document.createRange();
      r.setStart(sn, so); r.setEnd(en, eo + 1);
      window.scrollBy(0, r.getBoundingClientRect().top - y);
      getSelection().removeAllRanges(); getSelection().addRange(r);
      document.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
    }, text, scope, y);
    return { $, waitFor, idle, click, typeIn, select };
  };
  const visit = async (page, url) => {
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
    await new Promise(r => setTimeout(r, 800)); // content script loads at document_idle
    // Wikipedia: unpin the Appearance panel, freeing the right margin for notes.
    await page.evaluate(() => document.querySelector('.vector-column-end .vector-pinnable-header-unpin-button')?.click());
  };
  const park = page => page.mouse.move(1279, 799);

  // Small promo tile, 440x280.
  if (want('tile')) {
    const page = await browser.newPage();
    await page.setViewport({ width: 440, height: 280 });
    const icon = fs.readFileSync(path.join(root, 'assets/icon.svg'), 'utf8');
    await page.setContent(`<!DOCTYPE html><html><body style="margin:0;width:440px;height:280px;display:flex;
      align-items:center;gap:26px;padding:0 34px;box-sizing:border-box;font-family:system-ui,-apple-system,sans-serif;
      background:linear-gradient(135deg,#f6f3ff,#e9e3ff)">
      <div style="width:120px;height:120px;flex:none">${icon.replace('<svg', '<svg width="120" height="120"')}</div>
      <div><div style="font-size:40px;font-weight:700;color:#3d2f8f">Tosafot</div>
      <div style="font-size:17px;line-height:1.35;color:#4b4470;margin-top:6px">Ask an LLM about what you're reading. Keep notes in the margin.</div></div>
      </body></html>`);
    await shot(page, 'promo-440x280.png');
    await page.close();
  }

  // Settings: the actions editor (spare; the store takes 5) (dummy key, so no real key appears).
  if (want('settings')) {
    await setSettings(settings('sk-ant-••••••••••••••••'));
    const page = await newPage();
    await page.goto(`chrome-extension://${extId}/options.html`);
    await page.waitForSelector('.actions-editor .action-row');
    await page.evaluate(() => window.scrollTo(0, document.querySelector('.actions-editor').offsetTop - 330));
    await shot(page, 'settings.png');
    await page.close();
  }
  await setSettings(settings(key));

  // 1. The selection toolbar with ⋯ open.
  if (want('toolbar')) {
    const page = await newPage(); const u = ui(page);
    await visit(page, 'https://en.wikipedia.org/wiki/Offside_(association_football)');
    await u.select("a player is in an offside position if they are in the opponents' half of the pitch", '#mw-content-text', 300);
    await u.waitFor('.toolbar');
    await u.click('.toolbar .more');
    await shot(page, '1-toolbar.png');
    await page.close();
  }

  // 2. A thread: Explain, a comment, a follow-up.
  if (want('thread')) {
    const page = await newPage(); const u = ui(page);
    await visit(page, 'https://en.wikipedia.org/wiki/Maillard_reaction');
    await u.select('The reaction is a form of non-enzymatic browning which typically proceeds rapidly', '#mw-content-text', 90);
    await u.waitFor('.toolbar');
    await u.click('.toolbar > .item.action');
    await u.idle();
    await u.typeIn("So that's why you pat the steak dry first.", true);
    await u.typeIn('Does salting it the night before help or hurt the crust?');
    await u.idle();
    await u.click('.note-head button.save');
    await page.evaluate(() => {   // scroll the thread to the comment
      const n = document.querySelector('tosafot-host').shadowRoot.querySelector('.note');
      const c = n.querySelector('.turn.comment');
      n.querySelector('.note-body').scrollTop = c.offsetTop - n.querySelector('.note-body').offsetTop - 60;
    });
    await park(page);
    await shot(page, '2-thread.png');
    await page.close();
  }

  // 3. Saved notes highlighted, one open.
  if (want('saved')) {
    const page = await newPage(); const u = ui(page);
    await visit(page, 'https://en.wikipedia.org/wiki/Sourdough');
    await u.select('the fermentation process produces lactic acid', '#mw-content-text', 160);
    await u.waitFor('.toolbar');
    await u.click('.toolbar .item.comment');
    await u.typeIn('Why mine tastes more sour after a long proof in the fridge.');
    await u.click('.note-head button.close');
    await u.select('referred to as a starter', '#mw-content-text', 120);
    await u.waitFor('.toolbar');
    await u.click('.toolbar .item.ask');
    await u.typeIn('How often do I need to feed a starter that lives in the fridge?');
    await u.idle();
    await u.click('.note-head button.save');
    await page.evaluate(() => getSelection().removeAllRanges());
    await park(page);
    await shot(page, '3-saved.png');
    await page.close();
  }

  // 4. Sharing a note.
  if (want('share')) {
    const page = await newPage(); const u = ui(page);
    await visit(page, 'https://en.wikipedia.org/wiki/Daylight_saving_time');
    await u.select('set clocks back by one hour in the autumn', '#mw-content-text', 110);
    await u.waitFor('.toolbar');
    await u.click('.toolbar .item.ask');
    await u.typeIn('So do I gain or lose an hour of sleep this weekend?');
    await u.idle();
    await u.typeIn('Spring forward, fall back. Every year I have to look it up.', true);
    await u.click('.note-head button.save');
    await u.click('.note-head .share-btn');
    await park(page);
    await shot(page, '4-share.png');
    await page.close();
  }
  console.log('Wrote', fs.readdirSync(outDir).join(', '));
} finally {
  await browser.close();
}
