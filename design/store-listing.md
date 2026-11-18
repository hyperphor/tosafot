# Chrome Web Store listing

Text to paste into the developer dashboard. Images are in `assets/store/` (regenerate with `node dev/store-shots.mjs`).

## Package

Upload `dist/tosafot-<version>.zip` from `bb package`.

## Store listing tab

**Name:** Tosafot (from the manifest)

**Summary** (manifest `description`, 132 chars max): Select text on any page and ask an LLM about it.

**Description:**

> Tosafot lets you select text on any web page and ask an LLM about it, then keep the conversation as a note in the margin.
>
> • Select text and a small toolbar appears: Comment, Ask…, or a one-click action like Explain, Define, or ELI5.
> • Answers stream into a sidenote next to the passage. Ask follow-ups; the model sees the whole thread and the surrounding page text.
> • Add your own comments to the thread, with or without asking anything.
> • Save a note and the passage stays highlighted. Come back to the page later and your notes are still there.
> • Customize the actions: rename them, change their questions, add instructions for the model, and choose which ones show.
> • Share a note: copy it as Markdown, or open a post to X, Bluesky, or Mastodon with the quote and the page link.
> • Export a note to Obsidian, Logseq, or another Markdown-based notebook. Pick the notebook's folder once, and each export adds the note to the latest journal page. Exporting it again updates it in place.
>
> Tosafot uses your own API key. Anthropic is the default provider; any OpenAI-compatible API works too, including local models. Notes and settings stay on your computer, and the developer receives nothing. Page text goes to your provider only when you ask a question.
>
> Named after the Tosafot, the medieval commentaries printed in the margins of the Talmud.
>
> Source code: https://github.com/hyperphor/tosafot

**Category:** Productivity › Tools (alternative: Education)

**Language:** English

**Store icon:** `ext/icons/icon128.png`

**Screenshots** (1280×800, in this order):
1. `assets/store/1-toolbar.png`
2. `assets/store/2-thread.png`
3. `assets/store/3-saved.png`
4. `assets/store/4-share.png`
5. `assets/store/5-pubmed.jpg`

**Small promo tile:** `assets/store/promo-440x280.png`

**Homepage URL:** https://github.com/hyperphor/tosafot

**Support URL:** https://github.com/hyperphor/tosafot/issues

## Privacy practices tab

**Single purpose:**

> Lets the user ask an LLM about text they select on a web page and keep the resulting conversation as a note anchored to that passage.

**Permission justifications:**

- **storage:** Stores the user's settings (LLM provider, API key, model, custom actions) in chrome.storage.local, on the user's computer. The extension also lets users save notes: the selected passage, its position on the page, the page URL and title, and the thread of questions, answers, and the user's own comments. These are kept locally in the extension's IndexedDB database so they reappear when the user revisits the page. If the user turns on notebook export, the extension also keeps a handle to the folder they picked, plus their export settings (tag and menu name), so later exports can write there. None of this is uploaded or synced, and the developer receives none of it.
- **Host permission `<all_urls>`:** The content script runs on every page because the user may select text to ask about on any page they read, and because saved notes are re-highlighted when the user returns to a page. Host access is also needed for the background worker to call the LLM API the user configures. That is Anthropic by default, but it can be any OpenAI-compatible endpoint the user enters, including a local server. Page content is sent only to that endpoint, and only when the user asks a question.

**Remote code:** No, I am not using remote code. (All JavaScript is in the package. The only network calls are to the user's LLM API, which returns text that is rendered as sanitized Markdown (DOMPurify), never executed.)

**Data usage.** Check:

- **Authentication information:** the user's own API key for their LLM provider, sent only to that provider.
- **Website content:** the selected passage, nearby page text, and the page's title and URL, sent to the user's LLM provider when they ask a question.

Leave everything else unchecked. Settings and saved notes stay on the device, and nothing is sent to the developer. Notebook export writes files to a folder on the user's own computer; it sends nothing over the network.

**Certify all three:**

- I do not sell or transfer user data to third parties, outside of the approved use cases.
- I do not use or transfer user data for purposes that are unrelated to my item's single purpose.
- I do not use or transfer user data to determine creditworthiness or for lending purposes.

**Privacy policy URL:** https://github.com/hyperphor/tosafot/blob/main/PRIVACY.md

## Distribution tab

- **Visibility:** Unlisted, meaning only people with the link can install it. Switch to Public later if wanted.
- **Regions:** all.
- **Payment:** free.

## Account

- **Developer account:** register at https://chrome.google.com/webstore/devconsole (one-time fee). The account email can't be changed later.
- **Trader status:** non-trader (personal, non-commercial).

## Notes for review

- **Minified code:** the JavaScript is minified by the ClojureScript compiler (Closure advanced mode). It isn't obfuscated; the source is linked in the description.
- **Notebook export:** uses the File System Access API (`showDirectoryPicker`). The user picks the folder and grants access in Chrome's own prompt. This needs no manifest permission, and the extension writes only inside that folder.
- **Extension ID:** the store install has a different extension ID from an unpacked copy, so notes saved in one don't appear in the other.
