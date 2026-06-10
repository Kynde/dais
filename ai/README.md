# ai/

Curated, AI-friendly context for this repository. Treat it as ground truth on
the same footing as code — when a fact here disagrees with the code, the code
wins and the file gets fixed.

## Purpose

Reduce the cost of reconstructing dais-specific context every agent session.
Only facts that are **hard to infer from the source or the other .md files**
belong here: platform quirks discovered in field trials, invariants whose
violation fails silently, distinctions that are easy to confuse. Nothing that
a quick read of the code already states.

## How content gets added

- Small, human-verified chunks — checked against reality before landing.
- Opportunistic: when a discovery during normal work would have saved an hour
  had it been written down, it goes here.
- Code wins on conflict; stale entries get fixed or deleted.

## Layout

- `glossary.md` — one line per term, `TERM :: definition`. Grep-friendly.
- `where-to-look.md` — `task :: path # note` pointers. Grep-friendly.
- `gotchas.md` — `fact :: detail` lines; the platform/field-trial knowledge
  this project paid for. Read before touching audio, tmux, or delivery code.
