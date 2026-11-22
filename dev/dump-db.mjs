// Snapshot the extension's IndexedDB (tunamelt store "snapdragon") from a browser profile
// into a SQLite file. Usage: node dev/dump-db.mjs [out.db]   (default: out/tosafot.db)
// Env: PROFILE_DIR (default: Brave's Default profile).
// Chromium keeps IndexedDB as LevelDB, so we copy it into a scratch profile, open it with the
// unpacked extension, read the stores from its service worker, and write them out with sqlite3.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';

const here = path.dirname(fileURLToPath(import.meta.url));
const extPath = path.resolve(here, '../ext');
const outDb = path.resolve(process.argv[2] ?? path.join(here, '../out/tosafot.db'));
const profile = process.env.PROFILE_DIR
  ?? path.join(os.homedir(), 'Library/Application Support/BraveSoftware/Brave-Browser/Default');
const DB_NAME = 'snapdragon';

// The profile's extension ID for ext/, from its Secure Preferences / Preferences.
function profileExtId() {
  for (const f of ['Secure Preferences', 'Preferences']) {
    const p = path.join(profile, f);
    if (!fs.existsSync(p)) continue;
    const settings = JSON.parse(fs.readFileSync(p, 'utf8')).extensions?.settings ?? {};
    for (const [id, v] of Object.entries(settings)) if (v.path === extPath) return id;
  }
  throw new Error(`ext/ is not loaded in ${profile}`);
}

const idbDir = id => `chrome-extension_${id}_0.indexeddb.leveldb`;
const src = path.join(profile, 'IndexedDB', idbDir(profileExtId()));
if (!fs.existsSync(src)) throw new Error(`No IndexedDB at ${src}`);

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tosafot-dump-'));
const launch = () => puppeteer.launch({ headless: true, userDataDir, enableExtensions: [extPath], args: ['--no-first-run'] });
const worker = async b => (await b.waitForTarget(t => t.type() === 'service_worker')).worker();

try {
  // First launch only learns the ID this browser gives ext/; then copy the data in under it.
  let browser = await launch();
  const id = new URL((await browser.waitForTarget(t => t.type() === 'service_worker')).url()).host;
  await browser.close();
  const dest = path.join(userDataDir, 'Default', 'IndexedDB', idbDir(id));
  fs.rmSync(dest, { recursive: true, force: true });
  fs.cpSync(src, dest, { recursive: true, filter: f => path.basename(f) !== 'LOCK' });

  browser = await launch();
  const rows = await (await worker(browser)).evaluate(name => new Promise((ok, fail) => {
    const req = indexedDB.open(name);
    req.onerror = () => fail(req.error);
    req.onsuccess = () => {
      const db = req.result;
      const stores = ['objects', 'revisions'].filter(s => db.objectStoreNames.contains(s));
      const tx = db.transaction(stores, 'readonly');
      const out = {};
      for (const s of stores) tx.objectStore(s).getAll().onsuccess = e => (out[s] = e.target.result);
      tx.oncomplete = () => ok(out);
      tx.onerror = () => fail(tx.error);
    };
  }), DB_NAME);
  await browser.close();

  // Build the SQLite file from one SQL script piped to sqlite3.
  const q = v => (v == null ? 'NULL' : typeof v === 'number' ? String(v) : `'${String(v).replaceAll("'", "''")}'`);
  const ms = v => (v == null ? 'NULL' : `datetime(${Number(v)}/1000.0,'unixepoch')`);
  const sql = [
    'BEGIN;',
    'CREATE TABLE objects (realm TEXT, type TEXT, id TEXT, created TEXT, updated TEXT, obj JSON, PRIMARY KEY (realm, type, id));',
    'CREATE TABLE revisions (realm TEXT, type TEXT, id TEXT, rev INTEGER, mode TEXT, ts TEXT, obj JSON);',
    ...(rows.objects ?? []).map(r =>
      `INSERT INTO objects VALUES (${q(r.realm)},${q(r.type)},${q(r.id)},${ms(r.created)},${ms(r.updated)},${q(r.obj)});`),
    ...(rows.revisions ?? []).map(r =>
      `INSERT INTO revisions VALUES (${q(r.realm)},${q(r.type)},${q(r.id)},${q(r.rev)},${q(r.mode)},${ms(r.ts)},${q(r.obj)});`),
    'COMMIT;',
  ].join('\n');
  fs.mkdirSync(path.dirname(outDb), { recursive: true });
  fs.rmSync(outDb, { force: true });
  execFileSync('sqlite3', [outDb], { input: sql });
  console.log(`Wrote ${outDb}: ${rows.objects?.length ?? 0} objects, ${rows.revisions?.length ?? 0} revisions`);
} finally {
  fs.rmSync(userDataDir, { recursive: true, force: true });
}
