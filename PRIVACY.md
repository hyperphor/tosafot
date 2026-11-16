# Tosafot privacy policy

*Effective November 14, 2026*

Tosafot is a browser extension for asking an LLM about text you select on a web page and keeping notes on it. It has no server of its own. The developer receives none of your data.

## What stays on your computer

- **Settings**, including the API key you enter, are kept in Chrome's extension storage on your computer.
- **Notes you save** (the passage, its position on the page, the page's URL and title, and the thread of questions, answers, and comments) are kept in the extension's own database on your computer.

Removing the extension deletes both.

## What is sent, and to whom

When you ask a question, Tosafot sends a request to the LLM provider you set up in its settings. By default that's Anthropic. The request contains:

- the passage you selected, and up to about 2,000 characters of the page text on each side of it;
- the page's title and URL;
- the note's thread so far, meaning your earlier questions, comments, and the earlier answers;
- your API key, which authenticates you to that provider.

Nothing is sent until you ask. Comments you write without asking are not sent at that point; they go along with later questions in the same note. The provider handles the request under its own terms and privacy policy (for Anthropic, see [anthropic.com/legal/privacy](https://www.anthropic.com/legal/privacy)).

## Sharing

If you choose **Share → Post to X, Bluesky, or Mastodon**, Tosafot opens that service's compose page in a new tab with the passage, your comments (or the first answer), and the page's title and URL filled in. Nothing is posted unless you post it there. **Copy Markdown** puts the thread on your clipboard. **Export to Logseq** writes the note into the Logseq graph folder you chose, on your computer; it sends nothing anywhere.

## What Tosafot doesn't do

- It doesn't send page content anywhere unless you ask a question. The extension loads on every page so it can show the selection toolbar and put your saved highlights back, but that happens entirely on your computer.
- It doesn't use analytics or tracking, and doesn't show ads.
- It doesn't sell or transfer your data, or use it for anything except the features above.

## Contact

Questions or problems: [github.com/hyperphor/tosafot/issues](https://github.com/hyperphor/tosafot/issues).
