# DONE copy button
Preferably producing markdown, at least for LLM output

# DONE Redo prompt option machinery

Confusing options
A prompt can appear in 3 different places:
- toolbar
- toolbar menu
- button on main panel

Change this:
- no buttons on main pane at all, instead just the sane menu as on toolbar
    and maybe the other options aside from ask...
- the non-obvious thing: tthey generate a prompt, put it in the input box, but doesn't trigger the query. (could be an option I suppose – but I'd always want a chance to edit I think)
- ALSO the menu in the main pain should have a "save prompt" option (at the top I guess)

Configuration:
Only one option (checkbox): is prompt buttonized or is it just on the menu
Could also have a diabled option ... why not.
Not sure what the "reset" button does, maybe it can be removed.



# Button order
[Comment] [Ask...]  |  [Explain] etc
Uh that is how it is, but Ask... looks weird there for some reason
Comment and Ask ... are fixed (I guess they could be disabled?)
the rest are from options

# DONE IDEA extensible prompt library
It already is, but you have to find the config which is a pain
Rather, there should be a button "Save prompt..." and you can give it a label and it goes in your menus/buttons
FOLDED into Redo Prompt Option Machinery

Use case: annoying quiz that didn't have answers, I wanted to get them (for eductational purposes). So want to save a prompt rather than typing it over and over


# DONE All the options are showing up as buttons
Seems wrong, maybe there needs to be another level of control in config?

# DONE Background system prompt
Easy and valuable, explain to the LLM who you are and what you want.

This is already there, maybe give it a good default

# recursion!
Need to be able to select text in a comment and operate on it.
Probably the result should just append to current sidebar box, need to experiment.


# configurator has some weirdnesses

# DONE Allow editing of old comments

# old comments are not quite visible enough
They are highlighted but that's all
Should have a Tosafot icon by them
This icon should have an affordance that says "expose all annotations on this page"
