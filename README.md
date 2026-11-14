# Tosafot

A browser extension for asking an LLM about text on any web page: select text, click **Ask** (or press Alt+Shift+A, or click the toolbar button), pick a preset or type a question, and the answer streams into a sidenote next to the passage. See `design/design.md`.

Named for the tosafot ("additions"), the medieval commentaries printed in the margins of the Talmud page.

Each note is a thread. Type in the box at its bottom: **Enter** asks a follow-up (the model sees the whole thread), **⌘/Ctrl+Enter** adds your own comment without calling the model. Comments are passed to the model as notes on later asks. A note can also start as a comment from the popover. **Stop** cancels a streaming answer; **Copy** puts the thread on the clipboard as Markdown.

Notes are ephemeral until you **Save** them (adding a comment saves a note automatically). Saved notes are stored in the extension and come back when you revisit the page: their text is highlighted with a dotted underline, and clicking it reopens the note. If the text a note was attached to is no longer on the page, a badge in the bottom-right corner lists it. **Delete** removes a saved note.

Status: milestone 3 (saved notes, re-anchoring, orphan list).

## Install from a package

Unzip `tosafot-<version>.zip` somewhere permanent, go to `chrome://extensions`, turn on Developer mode, click **Load unpacked**, and choose the unzipped folder. Chrome 105 or later. To upgrade, unzip the new version over the same folder and click reload on the extension card. Keep the folder where it is: an unpacked extension's ID, and so its saved notes, is tied to its path. Removing the extension deletes its notes.

The package is for a fresh install or another machine. If you build from source, keep loading `ext/` instead: a second copy loaded from another folder is a separate extension with its own (empty) notes, and running both doubles every bubble and sidenote.

## Build

Requires Node and a JDK (for shadow-cljs), and a checkout of [tunamelt](https://github.com/hyperphor/tunamelt) (Hyperphor's persistence library) next to this repo (`../tunamelt`), which provides storage. Babashka is optional.

```sh
npm install
bb build          # or: npm run build
```

Then go to `chrome://extensions`, turn on Developer mode, click **Load unpacked**, and choose `ext/`. The settings page opens on first install; add an Anthropic API key there.

Rebuild after changes and click reload on the extension card. Builds are release-only, since MV3's CSP blocks shadow-cljs's dev-mode loader.

```sh
bb package        # build, then write dist/tosafot-<version>.zip
bb icons          # re-render ext/icons/*.png from assets/*.svg (needs rsvg-convert)
```

The version lives in both `ext/manifest.json` and `package.json`; `bb package` refuses to run if they differ.

## Providers

Configured on the options page. Two API styles:

- **Anthropic**: Messages API, streamed. Defaults to `claude-opus-5-5` at `low` effort for quick answers. Server-side refusal fallback (`fallbacks: "default"`) is enabled for models that support it.
- **OpenAI-compatible**: any `/chat/completions` endpoint (OpenAI, OpenRouter, Ollama at `http://localhost:11434/v1`, …). The API key is optional.

Keys are stored in `chrome.storage.local`. Requests go from the extension's service worker directly to the provider; there is no backend.

## Tests

```sh
bb test           # unit tests (core, llm) under node
bb e2e            # build, then drive the extension in headless Chrome for Testing against a mock SSE server
EXT_DIR=path node dev/e2e.mjs   # same, against another unpacked copy (e.g. an unzipped package)
```

## Layout

| Path | What |
|---|---|
| `src/tosafot/core.cljc` | annotation/thread data model, prompt construction |
| `src/tosafot/llm.cljs` | provider adapters, fetch + SSE streaming |
| `src/tosafot/ui.cljs`, `dom.cljs` | popover and sidenote components (shadow DOM) |
| `src/tosafot/anchor.cljc` | text-quote/position selectors and re-anchoring |
| `src/tosafot/config.cljs` | settings storage |
| `src/tosafot/ext/` | extension entry points: content script, service worker, options page |
| `ext/` | unpacked extension (manifest, options.html, icons, built `js/`) |
| `assets/` | icon sources (`icon.svg`; `icon-small.svg` is a simplified 16 px version) |
| `dev/e2e.mjs` | end-to-end test |
