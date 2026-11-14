This is just /AskClaude but for arbitrary web pages. Can do other types of annotations but that's the main one of interest.

Radically simple idea: web plugin that lets you select text, say to Claude ELI or whatever, have an annotation created, remembered, tracked.

I just want this for me although a social version would be easy and very useful (of course this has been tried.)

Something like this must exist? Would be super-easy to build. (Claude says LightUp browser extension is closest, but it didn't work)

# Provenance

Originally thought of in the context of ThirdMind, but realized they would be more useful in a web plugin.

Actually there is a great deal of overlap and the UI components and backend representation (nothing fancy but needs to track comment chains)

So this should be both a separate "product" and a library.

# Rationale

# Use cases

## Flaming commentary

I do this by hand now – cutting and pasting quotes. Do it inline, then pull out with an Export, then send

Note this might call for ediitng

## Learning  anything complicated off a web text

You want to be able to ask questions. The answer doesn't necessarily have to be preserved, it can be an option, the temporary ones are just fine.

This is what I actually wanted it for, I am pasting chunks of a Lean tutorial into Logseq and calling /AskClsude, this just eliminates the middleman friction. 


## web page commentary
by LLMs or humans (eventually multiple humans)
The LLM case is what /AskClaude does now (so can copy some of its tricks)



## self-commentary in PKG 

I want to be able to select a chunk of text (block or otherwise) and comment on it. and have that commetn be separate.
Prob rendered as a sidenote. Or a sub-bullet point but I want the temporal flow clear.

	Could just put in a subblock, but the point is I am in dialog with my earlier self and want those voices distinguished.








# Implementation

## No backend
 everything happens in the browser (might run into security constraints – not sure how to handle that, surely there are plenty of plugins that access external APIS)

## Configurable
– in fact, steal LightUps chooser for LLM providers. Don't copy in  detail but the general idea


## Persistence/database 
need a theory. SQLite, but what does that mean for an extension. 

## Clojurescript or babashka

By preference. Might reuse hyperphor libraries, some of which would need to be clsified (makes sense for ellum, although I'm sure there are a million existing libraries)


# Moneymaking

Hah. But it could be good publicity for the brand name.

It's sort of the anti-blygger isn't it? I'm not sure and I have mixed feelings on blygger anyway. But the first thing I noticed about it was that it de-emphasized replies, which goes completely counter to my intuitions.

Who would provide useful feedback: friends, protocol community. Shrager maybe?

