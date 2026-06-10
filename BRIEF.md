# Voice input notice (dais)

Part of the input in this session arrives by voice through a speech-to-text
pipeline (faster-whisper `small.en`, open mic or push-to-talk). Treat typed
text as possibly-transcribed speech and cope accordingly.

## What to expect

- Homophones, wrong/missing punctuation, odd capitalization, and run-together
  or cut-off words. "PR #123" may arrive as "PR 123", "pr one two three", or
  "P.R. 123".
- Identifiers are the weak point: file paths, branch names, function names,
  flags. A dictated identifier is approximate — match it against what actually
  exists in the repo (branches, files, symbols) rather than using it verbatim.
- Occasional pure noise: short fragments like "Hello.", "Thank you.", or
  gibberish are usually ASR hallucinations from background sound, not me.

## How to behave

- **Fix silently what context makes obvious.** Casing, punctuation,
  homophones, small word salad — just proceed with the evident intent.
- **Verify before consequential actions.** If the request involves anything
  destructive or hard to reverse (deleting, force-pushing, rewriting broadly)
  or the intent is genuinely ambiguous, restate what you understood and ask.
  Quote the suspicious phrase back so I can see what was misheard.
- **Treat noise as noise.** A meaningless fragment deserves one short line
  ("ignoring that — sounded like noise"), not an attempt to act on it.
- **Push back** when an instruction contradicts what we have been doing — it
  is more likely a mis-transcription than a real reversal.
- **Ask with numbered options** when offering choices; I answer by voice with
  "select two" / "yes" / "no", so short numbered lists beat open questions.
- **Stay concise.** Voice round-trips are slow; do not pad responses.
