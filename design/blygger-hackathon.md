# Blygger hackathon: posting from Tosafot via ThirdMind

A demo, not a product. Three things to show:

1. **Annotations flow into Logseq.** With a "sync to Logseq" mode on, each saved Tosafot note appears on a Logseq page for the web page it annotates, with an anchor back to the passage. There's one Logseq page per annotated web page.
2. **Post to a blyg from the margin.** Share → **Post to blyg** turns the note into a blyg item, published by ThirdMind.
3. **Comment on a blyg item and it becomes a reply.** If the page you annotated is itself a blyg item, the post is a stub of that item (§10.6). It quotes your selection as a partial transclusion (§10.1), and a Webmention (§15) makes it show up under the original post's responses.

Section references (§) are to blygger-spec `docs/protocol-v0.4.md`. The earlier design, `design/blyg-share.md` on branch `blyg-share`, covers the general multi-destination version. This doc cuts it down to the ThirdMind path and adds the Logseq sync.

Note: For "logseq", read "any markdown based personal knowledge graph (tine, obsidian, etc)". The UI should probaly say PKG even though nobody knows what that is.

## The demo script

1. On someone's blyg post (a blygger-studio page, e.g. on blyg.blygger.org), select a sentence and **Comment** in Tosafot: "This is the same point Engelbart made about bootstrapping." Save.
2. Switch to Logseq. A page `Tosafot/<post title>` has appeared, holding the quote, the comment, and a link that jumps back to the exact passage.
3. Back in Tosafot, choose Share → **Post to blyg**. The note shows "Posted ↗" with a link to the new item on the demo blyg.
4. Open the original post. After a refresh, the reply is listed under its responses, quoting the selected sentence.
5. Optional: do the same on a plain web page (Wikipedia). The post is a `{url}` stub with `cited`, which shows that it works off-blyg too.

Steps 1–2 work with no network. Steps 3–4 need the demo blyg to be public (below).

## Architecture

```
Tosafot (browser)                ThirdMind (local, lein serve / watch)         Public
─────────────────                ────────────────────────────────────         ──────
save / edit / delete note ──POST /tosafot/sync──▶ write Logseq page file
                                                  (pages/Tosafot%2F….md)
Share → Post to blyg ──────POST /tosafot/post───▶ tag block, publish item ──▶ demo blyg origin
                                                  send Webmention ─────────▶ target's /webmention
```

- **ThirdMind is the only writer**, of the graph and of the blyg.
  - Tosafot talks to it over HTTP on `127.0.0.1`. That reuses the approach on Tosafot's `export-pkg-server` branch (`notebook.cljs`), which was built for a local server and then shelved.
  - It's simpler than the file-picker export for this. The service worker can't re-grant folder access after a browser restart, and the server path has no Brave problem.
- **Tosafot sends facts, not formats.** It sends the annotation (`{id url title target thread}`) and the page's blyg links (below), and nothing Logseq- or blyg-shaped. ThirdMind decides the page layout and the wire format. Either side can then change its format without changing Tosafot.
- **No new extension permissions.** `<all_urls>` already covers `http://127.0.0.1`.

## 1. Sync to Logseq: one page per annotated web page

**Turning it on.** In Tosafot settings, "Sync notes to ThirdMind" takes an endpoint, default `http://127.0.0.1:8080`, which is ThirdMind's `lein serve` port. A status line shows whether ThirdMind answers. When sync is on:
- Every persist of a saved note (save, new turn, comment edit, end of an answer) POSTs `/tosafot/sync` with the annotation.
- Delete POSTs `/tosafot/delete` with the annotation's id.
- Failures are queued in `chrome.storage.local` and retried on the next sync. That way, notes made while ThirdMind was down still arrive.

**The page.** It's keyed by the source URL (fragment stripped, as Tosafot's `normalize-url` already does). ThirdMind finds it by its `source::` property (`t/find "page" {:attrs {:prop/source url}}`). If there's no such page, it creates one:

```
title:: Tosafot/Sourdough
source:: https://en.wikipedia.org/wiki/Sourdough
tags:: tosafot

- > the fermentation process produces lactic acid
  id:: 6f1c…   (the annotation's uuid)
  tosafot-anchor:: [↗ passage](https://en.wikipedia.org/wiki/Sourdough#:~:text=process-,produces%20lactic%20acid,-and)
	- Why mine tastes more sour after a long proof in the fridge.
	- Does a longer proof make it more sour?
		- #AskClaude
			- Yes: …
```

- **File name:** `pages/Tosafot%2F<title>.md`, the legacy namespace spelling ammdi uses (ThirdMind's parser already decodes `%XX`). `title::` is set explicitly anyway.
- **Title collision:** a second URL with the same title gets ` (host)` appended.
- **Blocks:** one top-level block per note.
  - The block's first line is the quote. Its `id::` is the annotation uuid, the same identity trick `tosafot.pkg` uses for re-export. ThirdMind's identity work (`ids.clj`, `:tmid`) then needs no matching for these blocks.
  - Children follow `tosafot.pkg/thread-blocks`: questions with answers under `#AskClaude`, comments as plain blocks, hidden instructions never written. Port `pkg.cljc`'s `markdown->blocks` and `render` to ThirdMind (they're `.cljc`, so they can be copied as they are), or reuse ThirdMind's own writer if it already covers this.
- **The anchor:** `tosafot-anchor::` is a text-fragment link built from the stored `{prefix exact suffix}` selector. Clicking it in Logseq opens the page scrolled to the passage, with it highlighted.
  - Text fragments were taken out of the social-media share as noise. Here they're the point.
  - Clicking the highlight in the browser still opens the Tosafot note, as now.
- **Upsert:** on each sync the block (and its subtree) is replaced in place by `id::`, as `tosafot.pkg/upsert` does. ThirdMind's watcher then re-ingests the file like any other change.

**Ownership (demo rule):** Tosafot owns the note block and its subtree. Anything you add under it in Logseq is overwritten at the next sync, so add your notes as sibling blocks. Two-way sync (Logseq edits flowing back into Tosafot) is out of scope.

**Logseq picking it up:** Logseq reloads a page when its file changes on disk. Check before the demo whether it prompts when the page is open. If it does, keep the page closed until after the sync in step 2.

ADDENDUM: has to work under page renames; that is, the source field shoud be indexed and looked up to find the Logseq page, not the name. 

## 2. Post to blyg

**Menu item.** Share → **Post to blyg** appears when sync is on and ThirdMind answers. Tosafot POSTs `/tosafot/post` with:
- the annotation;
- the page's blyg links, read by the content script: `<link rel="alternate" type="application/json">`, `<link rel="webmention">`, and `<link rel="canonical">`. Studio pages emit all three (`src/pages.ts`).

ThirdMind replies `{item_url, id, version}`. The note shows "Posted ↗" in its message line and remembers `:annotation/blyg {:id :url}`, so a second post updates the same item instead of creating a new one.

**A comment is required.** A stub with nothing to say is a misuse under §10.6, so the item is disabled until the note has a comment ("Add a comment to post").

**What ThirdMind does:**
1. Sync the block (as in section 1), then mark it for publishing:
   - add `#blyg-reply` to the block;
   - add a `blyg-reply-to:: <url>` property. That's the target's item URL from the JSON alternate, if there is one, or else the page URL.
   - Today `publish/reply-target` takes "the one URL in the block's own text", which the anchor link would make ambiguous. So `reply-target` should prefer the explicit property and fall back to the one-URL rule.
2. Build the item. A Tosafot block is a single **thread** (stubs are threads, §10.6), not a thread whose children are promoted to fragments. So `content.clj` needs a Tosafot rule for blocks with an `id::` and `tosafot-anchor::`:
   - **body:** the quote, then a blank line, then the comment children joined. `#AskClaude` subtrees are left out.
   - **`stub_of`:** as in section 3.
   - **LLM answers:** out of the demo. They'd need `generated[]` (§5.7) and an opt-in.
3. Publish that one item: state entry, version, item JSON, index, feed, manifest. That's phase 2's "re-derive just the affected items" path, called directly instead of from the watcher.
4. Push the demo origin (below), then send the Webmention if it's a reply (section 3).

## 3. Commenting on a blyg item is a reply

When the annotated page is a blyg item (it has the JSON alternate), the post is a **stub of that item with a partial transclusion of the selection**:

```
![[4s3dq678jkt319vd8jfr8hesnq]]
> the selected sentence, exactly as on the page

This is the same point Engelbart made about bootstrapping.
```
```json
"stub_of": { "origin": "https://blyg.blygger.org/", "id": "4s3dq678jkt319vd8jfr8hesnq", "version": 3,
             "cited": { "source": "…", "excerpt": "…", "url": "…", "retrieved": "2026-…" } }
```

- **Resolving the target.** ThirdMind (not Tosafot, so there's no CORS issue) fetches the item JSON from the alternate link. That gives `id` and `version`. It then fetches the manifest for `origin`, `title` and `author`, for `cited` (§5.9).
  - This is the "reply target not resolved (new replies need a fetch, not yet supported)" gap in `publish/candidates`. It's also the one-off snapshot §16.11 explicitly allows: "a browser extension … resolve it … hold the item as a one-off snapshot with no subscription, and open a stub with the quote".
- **The selection must match.** §10.2 checks a partial transclusion's text against the pinned version's text. Tosafot's `exact` comes from the rendered page and is whitespace-collapsed.
  - Normalize on ThirdMind's side the way §10.2 says the check normalizes.
  - If it still doesn't match (e.g. the selection crossed formatting), fall back to a whole transclusion followed by a plain blockquote. §10.1 says a blank line detaches the blockquote, and the result is still a valid stub.
- **A plain web page** becomes a `{url}` stub with `cited`, the quote as an ordinary blockquote, and no `selector` (§10.6). ThirdMind's current publisher can't make these: goddinpotty skipped every non-blyg target, and this is the first time it's possible.
- **Notifying the target.** After the item is live, ThirdMind POSTs a Webmention: `source` = the new item's page, `target` = the target's page, sent to the `rel="webmention"` endpoint read from the page.
  - The studio verifies structurally (§15: it fetches the source's item document and looks for `stub_of`), then lists the stub under the post's responses (`listPublicResponses`, `itemShowsResponses` in `src/pages.ts`).
  - **Check before the demo:** whether the target studio shows responses at once or holds them for moderation. Use a target post on a studio you own, so you can approve it on stage if needed.
  - This is `#blyg-reply` webmentions from phase 4 of ThirdMind's `design/blygger.md` (porting `blyg_remote`), pulled forward.

## The demo blyg origin (don't touch ammdi's live blyg)

ThirdMind's publisher hasn't cut over from goddinpotty (phase 3 of `thirdmind/design/blygger.md`). ammdi's live `/blyg/` items have block↔id mappings that exist only in `.blyg-state.edn`. A hackathon is the wrong time to migrate that.

**Recommendation:** a separate demo graph and a separate origin.
- **Graph:** a small Logseq graph (e.g. `~/blyg-demo`). ThirdMind watches it with its own db (`lein watch ~/blyg-demo blyg-demo.db`), and Logseq has it open during the demo. Sync mode works the same against ammdi later; only the endpoint's graph changes.
- **Origin:** a GitHub Pages repo (e.g. `hyperphor/blyg-demo`). ThirdMind writes the surface into its checkout and commits and pushes only that directory, the same rule as phase 3's `lein blyg push`. Pages deploys in under a minute, which is talk-over time in step 3.
  - Webmention verification needs a public source, so localhost won't do for step 4.
- **The surface needs item pages, not only JSON.** The Webmention `source` is the item's page (§15), and studio pages are what readers click. ThirdMind's `wire.clj` ported the JSON surface and feed but not "the HTML archive/permalink pages". A minimal permalink page per item is enough: the rendered `content_html`, and `<link rel="alternate" type="application/json">` pointing at the item document.
  - Also a manifest with no `webmention` key, since the demo blyg doesn't receive mentions.
- **Run the spec's conformance checker** against the demo origin once before the demo.

## Work list

Sized for a hackathon. **Bold items are on the demo path**; the rest can be faked or cut.

**Tosafot** (branch off main):
- **Settings: "Sync notes to ThirdMind" (on/off, endpoint, status ping). Persist hook: POST `/tosafot/sync` and `/tosafot/delete`, with a retry queue.** Bring `notebook.cljs` back from `export-pkg-server` as the client.
- **Content script: read the alternate, webmention and canonical links into the annotation's page info.**
- **Share → Post to blyg: POST `/tosafot/post`; "Posted ↗" message; remember `:annotation/blyg`; disabled without a comment.**
- Leave the file-picker "Export to notebook" in place for non-ThirdMind users.

**ThirdMind** (branch off `blygger`; this needs `graph_write`, so check how far `edit` has merged):
- **`/tosafot/sync`, `/tosafot/delete`, `/tosafot/post` routes in `ui/server.clj`.** POST only, JSON body, and the request's `Origin` must be a `chrome-extension://` origin. These are the same guards as `export-pkg-server`'s `notebook_server.clj`.
- **Page writer:** find-or-create the page by `source::`, upsert the block by `id::` (port `tosafot.pkg`), then let the watcher ingest.
- **`reply-target` prefers `blyg-reply-to::`. A Tosafot-thread rule in `content.clj`.**
- **Target resolution:** fetch the item JSON and manifest, build `stub_of` with `cited`, check the selection with a fallback. `{url}` stubs for plain pages.
- **Publish one item into the demo origin: minimal permalink page, push.**
- **Webmention send.**

**Fake or cut if time runs out, in this order:**
1. The push and Webmention: show the item at a local `/blyg/` served by ThirdMind instead, and show the reply's item JSON with its `stub_of`.
2. Partial transclusion: use a whole transclusion followed by a quote.
3. The retry queue.

## Open questions

- **Which ThirdMind process serves the routes?** `lein serve` (the UI server) is read-only today, and `lein watch` doesn't serve HTTP. Writing from the server while the watcher runs needs one process, or a file lock. For the demo, run the routes inside `watch` (one process, already has `:write?` actions).

Let's make watch and serve part of the same server (could be different processes with a file lock). I always want them both rinning.

- **Per-note items.** One item per note matches "a stub responds to exactly one thing". Several notes on one page make several stubs, which is fine for the demo.
- **Editing after posting.** Re-posting bumps the version. Should sync of a posted note republish automatically? Not for the demo: only an explicit Post publishes.
- **Withdrawal.** Deleting a posted note in Tosafot should not silently withdraw the item. It stays until withdrawn in ThirdMind.
- **After the hackathon:** pointing sync at ammdi needs the phase 3 cut-over first. Tosafot items would then be ordinary `#blyg-reply` blocks in the main graph.
