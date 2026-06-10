# FABLE_PLAN — Do As I Say (dais)

A personal voice-control system for driving a TUI coding agent (Claude Code) in tmux,
on Fedora/KDE Wayland, Lenovo X1 Carbon Gen 13.

Predecessor: `../lausu` (Clojure daemon + JSON event bus + Bedrock intent normalizer).
dais keeps lausu's proven mechanics and drops the intent-LLM / policy / confirmation
architecture — the controlled agent does its own transcript cleanup (told via initial
prompt that it is being voice-driven).

## Decisions made (and why)

| Decision | Choice | Rationale |
|---|---|---|
| Output channel | `tmux send-keys` / `paste-buffer` only | Deterministic targeting, testable without audio, no uinput/Wayland focus fragility. evdev out of scope. |
| Intent LLM layer | None | The old Opus/Bedrock-intent layer was an unnecessary hop; the agent consuming the text has the best context to fix it. |
| Daemon | **Clojure** (JVM, resident) | Router, vocabulary, executor, config are the parts the user will keep touching — Clojure preferred there. JVM startup cost irrelevant for a resident daemon. Large reuse from lausu. |
| Audio side | **Resident Python "ear" worker** (pixi) | Python only where the ecosystem demands it: PipeWire capture, Silero VAD, faster-whisper. Resident process so the whisper model loads once (lausu's per-call worker paid ~1s model load per utterance). |
| ASR | faster-whisper, English-only, int8 CPU | `small.en` baseline, try `distil-small.en`. ~1–2 s per short utterance expected. NPU/OpenVINO deferred. |
| VAD | Silero, streaming, endpointing only in VAD mode | Manual latch bypasses VAD entirely. |
| Hotkeys | Standalone toggle scripts in `tools/`, bound via KDE later | kglobalaccel fires on key-press only (no release — confirmed in lausu phase 5), so push-to-talk is impossible; scripts are stateful toggles. Scripts must run under KDE's environment, not an interactive zsh (see Tools). |
| Config | `dais.edn` | Native to the Clojure daemon now. Ear worker receives its settings from the daemon (args/ndjson), never reads edn itself. |
| Dictation submit | Type only by default; enter-mode configurable | `:no-enter` / `:enter-auto` / `:enter-always`. |
| Dictation delivery | Single-line normalize, then `load-buffer -` + `paste-buffer -p` (default); `:send-keys` as config alternative | Paste is atomic and bracketed (what Claude Code expects). Transcripts are joined to ONE line (whisper segments joined with spaces), so the text stays visible in the input box — the "[Pasted text +N lines]" collapse only triggers on multi-line/huge pastes. If collapse still annoys in practice, flip `:delivery :send-keys`. |
| Command vs dictation routing | Whole-utterance match first; strategy configurable | Fallback ladder `:whole-match` → `:prefix` → `:key-armed`, switchable in config. |
| Agent-pane send guard | **Not in v1** | Tried in lausu, hurt UX more than it protected. Revisit only with a precisely identified real problem and a clear allow/prevent picture. |

## Architecture

```
tools/  (KDE-env-safe shell/node scripts; thin ndjson socket clients)
  dais-vad     toggle VAD session          (→ F9 later)
  dais-rec     toggle manual record latch  (→ F10 later)
  dais-esc     send Escape to target       (→ F11 later)
  dais-ctl     status / target / inject / config verbs
        │ ndjson over unix socket  $XDG_RUNTIME_DIR/dais/control.sock
        ▼
Clojure daemon (resident JVM)
  control server · session state machine · router (keypress grammar,
  vocabulary) · enter-modes · target slots · tmux executor ·
  state.json · JSONL event log · notify
        │ supervises subprocess; ndjson over stdin/stdout
        ▼
Python "ear" worker (resident, pixi)
  PipeWire capture (16 kHz mono) · Silero VAD endpointing ·
  faster-whisper small.en int8 (model loaded once)
  in:  {"type":"set_mode","mode":"vad"|"off"} · {"type":"latch_start"} · {"type":"latch_stop"}
  out: voice.transcript events · status events (listening / speech_start / transcribing) · errors
```

Event/message fields follow lausu naming (`type`, `trace_id`, `payload`, `time`) so logs
stay jq-friendly. No formal JSON Schemas in v1 — one supervising process, the contract
is documented here (move shapes to a PROTOCOL.md if they grow). Each utterance keeps its
WAV on disk and the events reference it, so misses are replayable.

### State machine (daemon-owned; ear is mode-dumb)

- `off` — daemon up, mic closed.
- `vad-listening` — entered/left via `dais-vad` or spoken "voice off". Ear segments
  utterances with Silero, emits transcripts as they come.
- `manual-recording` — `dais-rec` starts, `dais-rec` stops; the whole recording is one
  utterance, no VAD endpointing. Good for long dictation with thinking pauses; also
  skips the VAD silence wait.
- **v1 rule:** `dais-rec` while `vad-listening` is refused with a notification
  ("stop VAD first"). The nicer behavior — pause VAD, take the latch utterance, resume —
  is an explicit later enhancement, not v1.
- Transcription/dispatch is async; capture keeps running.

### Router (Clojure — port of lausu `src/lausu/intent.clj` keypress grammar)

Strategy in `dais.edn`:

1. `:whole-match` (v1 default) — utterance is a command iff it starts with a trigger
   verb (press/hit/push/choose/select/pick/answer) **and** every non-filler token maps
   to a whitelisted key. Filler words (the/a/and/then/option/number/key/button…) are
   ignored; ordinals map to digits ("choose the second option" → `2`); compound
   sequences work ("press down and enter" → `Down Enter`, capped at 5 keys).
   "Select the right abstraction for this" fails the every-token rule → dictation.
2. `:prefix` (fallback) — commands need a spoken prefix; everything else is dictation.
3. `:key-armed` (last resort) — utterances are always dictation; a key arms
   next-utterance-is-command.

Safety property in all strategies: a failed command match executes nothing and is
logged — it never falls through to typing in command context, and key names are a
whitelist (`Enter`, `Escape`, `Up`, `Down`, `Left`, `Right`, `Tab`, `1`–`9`), never
free-form `send-keys` input.

Special commands handled before the grammar: "voice off" (exit `vad-listening`),
"scratch that" (Ctrl-U the input line), "next target" / "target two".

### Executor (Clojure — adapted from lausu `src/lausu/executor/tmux.clj`)

- Dictation text: normalize to a single line, deliver via `load-buffer -` (stdin) +
  `paste-buffer -p`. Text never appears in argv or a shell string.
- Keys: `send-keys <KeyName>` with whitelisted names only.
- Target existence checked with `list-panes -t` (exits non-zero for unknown targets;
  `display-message` is unsuitable — it exits 0 and falls back to the current client).
  Stale target = hard error + notification, never a silent fallback.
- tmux invoked as argv vectors, never shell strings.
- The lausu agent-pane allowlist guard is deliberately **not** ported (see decisions).

### Enter modes (config)

- `:no-enter` (default) — type text, never submit.
- `:enter-auto` — utterance *ending* with the word "enter" strips it and sends Enter;
  fuzzy on the last word ("Enter.", trailing pause).
- `:enter-always` — every dictation ends with Enter.

### Targets

- Named slots (2–3 panes) in daemon state: `dais-ctl target set 1 app:1.2`,
  `dais-ctl target set 1 current` (grab active pane). Active slot sticky, in
  `state.json`, switchable by voice or CLI. Key binding for cycling can come later.
- The LLM target resolver ("the claude under app") stays a separate future tool that
  just calls `dais-ctl target set`.

### Feedback

- `state.json` in `$XDG_RUNTIME_DIR/dais/`: `{mode, enabled, target, last_utterance}` —
  for tmux powerline / Claude statusline.
- `notify-send` (lausu `tools/notify` pattern): state transitions and errors only, not
  every utterance; config-toggleable. **All notifications carry a timeout**
  (`notify-send -t`) so they fold away on their own — no action buttons, nothing that
  waits for a mouse click.
- JSONL event log `events/<date>.jsonl` with `trace_id` linking
  audio → transcript → route → action, for the tuning era and regression fixtures.

### Open-mic false-trigger mitigations (VAD mode)

- Minimum voiced duration (~300 ms); reject high `no_speech_prob` / low `avg_logprob`;
  drop empty transcripts. Every drop logged with its reason.

## Repo layout

```
dais/
  deps.edn
  src/dais/          # daemon, router, executor, config, state, log, notify
  test/dais/
  ear/               # pixi project: capture, vad, asr (resident worker)
  tools/             # dais-ctl, dais-vad, dais-rec, dais-esc (+ kde/*.desktop later)
  config/dais.edn
  events/            # JSONL logs + kept WAVs (gitignored)
  FABLE_PLAN.md
  GOAL.md
```

## Tools (`tools/`)

Thin clients that write one ndjson line to the control socket and print/notify the
result. Constraints, learned from lausu's KDE phase:

- **Must run under KDE's execution environment, not an interactive zsh**: `#!/bin/sh`
  or node with absolute interpreter paths, no reliance on user PATH/aliases/env;
  resolve the repo root from the script's own location.
- Stateful toggling lives in the **daemon**, not the scripts: `dais-vad` just sends
  `{"type":"toggle-vad"}` and the daemon decides start vs stop. No PID files in
  scripts (lausu needed them because its capture was a transient process; dais's ear
  is resident).
- Testable from a shell first; KDE shortcut binding (`.desktop` files, System
  Settings) is a later step once behavior is right.
- `dais-ctl inject "<text>"` feeds a fake transcript into the router — the whole
  routing/execution pipeline is testable with no microphone (lausu's `--mock-text`,
  proven useful).

## What we reuse from lausu (and what we don't)

**Port/adapt:** tmux executor mechanics (paste vs keys, `list-panes -t` check, argv
vectors), keypress grammar (`intent.clj:48-83`), ndjson socket + JSONL event-log
plumbing from the daemon, `tools/notify` shape (with timeouts), `.desktop` binding
notes, faster-whisper invocation parameters from `workers/asr-faster-whisper` (the
code shape, not the per-call process model).

**Drop:** Bedrock/intent providers, policy & risk classification, confirmation
registry/approve flow, context collector, memory, JSON Schema validation, the
agent-pane send guard, per-utterance worker processes.

## Milestones

1. **Clojure skeleton, no audio.** Daemon + socket + `state.json` + tools + tmux
   executor + router + `inject`. `dais-ctl inject "press enter"` presses Enter in the
   target pane; `inject "hello world"` pastes it. Vocabulary, enter-modes, targets all
   exercisable from a shell.
2. **Ear worker, latch path.** Resident pixi process under daemon supervision; `dais-rec`
   toggles recording; speak → text lands in pane. Model stays loaded between utterances.
3. **VAD mode.** Silero streaming endpointing, `dais-vad` toggle, "voice off",
   false-trigger mitigations, status events → `state.json` + notifications.
4. **Polish.** Target slots + voice switching, `dais-esc`, `:enter-auto`, notification
   tuning, KDE `.desktop` files + shortcut binding notes.
5. **Tuning era.** Live with it; field-trial discipline from lausu (`docs/field-trials.md`
   style: terse miss log, JSONL has the detail; saved WAVs become regression fixtures).
   Adjust vocabulary, VAD thresholds, model, delivery mode, router strategy as observed.

## Deferred / out of scope

- evdev / uinput, hold-to-talk.
- F10-during-F9 pause/resume interplay (v1 refuses; design later).
- Agent-pane send guard — only with a precisely identified real problem.
- NPU/OpenVINO inference.
- LLM tmux-target resolver (separate tool).
- Plasma widget indicator (anything can read `state.json`).
- Auto-punctuation/normalization maps ("hash" → "#") — only if agent-side cleanup
  proves insufficient.
