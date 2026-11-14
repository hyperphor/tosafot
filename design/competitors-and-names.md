# My notes

. Curiosity looks pretty good and close.
https://margin.at/ is an atprotocol based annotation plugin, might look into doing the protocol

# From Claude 

**(b) Prior art.** Each half of the idea is crowded, but I found only a few tiny projects doing both, and none is dominant.

| Project | What it does | What it lacks next to yours |
|---|---|---|
| [AskBubble](https://imayday2329-glitch.github.io/AskBubble/) | Right-click a selection and a chat bubble opens beside the text, with follow-ups in the same bubble | No mention of notes surviving a revisit |
| [Curiosity](https://github.com/eethersway/Curiosity) | Ask your own LLM about a selection; a saved exchange turns into a linked markdown note in Obsidian, and a revisit badge scrolls you back to each passage | Notes live in Obsidian, not in the margin |
| [GPT Threads](https://chromewebstore.google.com/detail/gpt-threads/bgdglegbaeioofgjohpggkpijgcobofm) | Highlight text, ask a question, and a GPT chat starts inside the page | OpenAI only, as far as the listing shows |
| [Explain AI](https://chromewebstore.google.com/detail/hgdhahipoomjkhbadikhoopfdkllpbgf) | A soft underline on the highlight opens a short, page-aware explanation, with follow-up questions under any answer | Explain-focused; the listing doesn't mention your own comments or saved notes |
| [Notate](https://chromewebstore.google.com/detail/notate-%E2%80%94-research-highlig/jkhlljnilbhpeoaijknfjbcanafcohbd) | Highlighter and clipper where you can add a note, ask AI a question, or file it in a collection | AI is a side feature of a clipper |
| [Offthread](https://chromewebstore.google.com/detail/ieekphhmaohoodalgaboiolcmmpoagmi) | Margin notes for Claude, ChatGPT, and Gemini | Only works on those chat sites |
| [Hypothesisor](https://fxp.github.io/Hypothesisor/) | An LLM reads the page and generates 5-40 candidate annotations to publish to Hypothesis | Batch generation, not a conversation |

Beyond these there are dozens of select-and-explain popups with your own API key (rabbitholes, Gnosis, Plainly, LightUp, Context Explain), all ephemeral. Hypothesis has the anchoring and threads, but a June 2026 comparison lists it as having no AI.

What I didn't find elsewhere is your combination: a thread where your own comments and the model's answers interleave, anchored with re-anchoring and an orphan list, with configurable actions and no backend. I only read listings and READMEs, so some of these may do more than they advertise.

**(a) Name.** My pick is **Gloss**: it is the English word for exactly this (a marginal note explaining a passage), it keeps the Tosafot lineage (the medieval glossators), and it works as a verb ("gloss this").

Alternatives:
- **Glossator**: more distinctive and easier to search for, same meaning.
- **Scholia**: the Greek equivalent, but Wikidata's Scholia tool already uses it.
- **Apostil**: an obscure word for a marginal note, so probably as forgettable as Tosafot.
- **Margin**, **Sidenote**, **Marginalia**: all taken by existing extensions or products.

I haven't checked Gloss or Glossator against the Chrome Web Store.
