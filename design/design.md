# Snapdragon — Design

## Summary

A browser extension that does what `/AskClaude` does in Logseq, but on any web page. You select text, ask an LLM something about it ("ELI5", "what does this term mean", a free-form question), and the answer is attached to the selection as an annotation. Annotations can be thrown away or kept; kept ones stay anchored to the page and show up again on later visits.

LLM Q&A is the main use. The same mechanism also covers plain human comments on a selection.

It's for my own use first. A social or shared version would be easy to add and genuinely useful, but people have tried that before and it's out of scope for now.

## Background

- **Origin.** The idea came up while working on ThirdMind. It turned out to fit a browser plugin better, but the two share a lot: the UI pieces (selection → comment → thread) and the backend representation (nothing fancy, but it has to track comment chains).
- **Prior art.** LightUp is the closest existing extension, but it didn't work when I tried it. Hypothesis covers human web annotation and is the reference for anchoring and data format.
- **Contrast with blygger.** Blygger downplays replies. This project puts replies and dialog at the center.

## Goals

1. Select text on any page → ask an LLM → see the answer inline, without copying and pasting.
2. Optionally keep the exchange as an annotation that re-anchors when I come back to the page.
3. Support threads: follow-up questions, plus my own comments mixed in with LLM replies.
4. Let me choose the LLM provider and model.
5. Ship as a standalone extension and as a library that ThirdMind and other hyperphor projects can reuse.

### Non-goals (for now)

- Multi-user or social annotation.
- A hosted backend.
- Monetization.

## Use cases

### Learning from a technical web text

The motivating case. I'm working through a Lean tutorial and currently paste chunks into Logseq so I can run `/AskClaude` on them. Snapdragon removes that step. Most answers are throwaway, so ephemeral should be the default and saving should be one click.

### Commenting on web pages

Commentary by an LLM or by me (eventually by several people). The LLM case is what `/AskClaude` does today, so its prompt tricks can carry over.

### Self-commentary in a PKG

Select a chunk of my own notes (a block or part of one) and comment on it, keeping the comment separate from the original text. It would probably render as a sidenote, or as a sub-bullet that makes the order in time clear. A plain sub-block won't do: I'm in dialog with my earlier self, and the two voices need to stay distinct. This is mostly the library/ThirdMind side of the project.

## Architecture

Everything runs in the browser. There is no backend.

```
┌──────────────── page ────────────────┐
│ content script                       │
│  - selection capture + "Ask" popover │
│  - anchoring / highlight rendering   │
│  - sidenote / thread UI (shadow DOM) │
└──────────────┬───────────────────────┘
               │ runtime messages
┌──────────────▼───────────────────────┐
│ service worker (background)          │
│  - LLM client (provider-agnostic)    │
│  - storage access                    │
└──────────────┬───────────────────────┘
               │
   LLM provider APIs     local store
```

- **Manifest V3** extension. Chrome first, Firefox if it's cheap.
- **Content script** captures the selection, shows a small popover (preset prompts plus a free-text box), and renders highlights and threads. The UI lives in a shadow root so page CSS doesn't interfere.
- **Service worker** makes the LLM calls. Declaring the provider hosts in `host_permissions` avoids the CORS problems a page script would hit. Anthropic's direct-browser-access header may still be needed. UUNAPI keys are stored in extension storage and never exposed to the page.
- **Options page** holds provider and model configuration and any global settings.

### LLM provider configuration

Copy the general idea of LightUp's provider chooser, not its details. Each provider entry has a name, an API style (Anthropic, OpenAI-compatible, …), a base URL, a key, and a model. A small adapter per API style is enough. Stream responses into the thread UI.

The prompt sent to the model includes the selection, some surrounding context (the enclosing paragraph or section plus the page title and URL), any earlier turns in the thread, and the question.

### Anchoring

Use the W3C Web Annotation selector model, as Hypothesis does:

- `TextQuoteSelector`: the exact text plus a short prefix and suffix. This is the main way to find the anchor again.
- `TextPositionSelector`: character offsets, used as a fast path and a tiebreaker.

When an annotation can't be re-anchored because the page changed, it is kept and listed as an orphan instead of being dropped.

## Data model

```clojure
{:annotation/id       uuid
 :annotation/url      "https://…"          ; normalized
 :annotation/title    "page title"
 :annotation/target   {:quote  {:exact "…" :prefix "…" :suffix "…"}
                       :pos    {:start 1234 :end 1301}}
 :annotation/kind     :ask | :comment
 :annotation/saved?   boolean              ; false = ephemeral
 :annotation/created  inst
 :annotation/thread   [{:msg/id      uuid
                        :msg/author  {:type :human} | {:type :llm :provider "…" :model "…"}
                        :msg/parent  uuid?  ; for branching follow-ups
                        :msg/kind    :ask | :comment                          ; human messages
                        :msg/status  :streaming | :done | :stopped | :refused | :error  ; LLM messages
                        :msg/text    "…"
                        :msg/created inst}]}
```

Threads are a flat list of messages with parent links. That supports linear chains now and branching later. Ephemeral annotations live only in memory and disappear on navigation unless saved.

## Persistence

I need to settle what "SQLite in an extension" means in practice. Options:

1. **IndexedDB** (directly or through a thin wrapper). Built in and works in both the service worker and extension pages. **Start here.**
2. **SQLite via WASM** (official sqlite-wasm with OPFS). This is the real SQLite I'd prefer, but OPFS sync access needs a worker or offscreen document, not the service worker itself. More moving parts.
3. `chrome.storage.local`. Fine for settings, not for annotation data.

The plan is to keep storage behind a small protocol so the backend can move from IndexedDB to SQLite later. JSON export/import from the start also gives backup and a path to sync or sharing.

### Good, see it makes sense to port tunamelt to cljs

## Implementation language

ClojureScript, built with shadow-cljs, with separate builds/modules for the content script, service worker, and options page. Babashka can handle scripting and tooling.

Reuse hyperphor libraries where it makes sense. Some will need porting to cljc, and ellum is the obvious candidate. A ready-made JS library is fine where one already does the job well, e.g. text-quote anchoring. Tunamelt also. 

### Library split

- `snapdragon.core` (cljc): data model, thread operations, prompt construction, storage protocol.
- `snapdragon.llm`: provider adapters and streaming.
- `snapdragon.anchor`: selector creation and re-anchoring (browser only).
- `snapdragon.ui`: popover, highlight, and sidenote/thread components.
- The extension shell wires these together. ThirdMind and Logseq integrations reuse core, llm, and ui.

## Milestones

1. **MVP:** select text → popover → ask a configured LLM → streamed answer in an ephemeral sidenote.
2. Follow-up questions in the thread, plus human comments.
3. Save annotations, then re-anchor and render them on revisit, with an orphan list.
4. A sidebar listing all annotations for the current page and across pages, plus export/import.
5. Extract the library and use it from ThirdMind.

## Open questions

- Is SQLite worth the complexity over IndexedDB, and when?
- Should ephemeral answers be kept anywhere, for example as recent history?
- How should a sidenote render on pages with hostile layouts? (Possible fallback: a sidebar.)
- How much page context should go to the LLM, and should it be configurable per prompt?
- Is there a Logseq plugin path, or is the PKG use case ThirdMind-only?

## Feedback

People to show it to: friends, the protocol community, maybe Shrager. A side benefit is publicity for the hyperphor name.
