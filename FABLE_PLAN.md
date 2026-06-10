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
| Output channel | Two complementary delivery backends: `:tmux` (send-keys / paste-buffer to a targeted pane) and `:focus` (ydotool/uinput to the focused app) | Different use cases, not competitors: tmux = deterministic targeting regardless of focus; focus = clean simple UX when you're looking at the thing. wtype is impossible on KWin (verified 2026-06-10: no `zwp_virtual_keyboard_manager_v1`); ydotool is the focus backend, pending setup (see contract below). |
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

### Wire protocol (lausu's envelope + CODEX_PLAN's op wrapper)

Events use the lausu-style envelope — `schema: "dais.event.v1"`, `id`, `parent_id`,
`trace_id`, `time`, `sequence`, `type`, `source`, `payload` — with dot-named types.
The daemon assigns a monotonic `sequence` to every accepted event. No formal JSON
Schemas in v1 (one supervising process); shapes live here, move to a PROTOCOL.md if
they grow.

Socket requests are one JSON object per line, wrapped in an `op`; one JSON reply per
line, errors as `{"ok":false,"error":"..."}`:

```json
{"op":"publish","event":{...}}            // ear → daemon (also dais-ctl inject)
{"op":"control","action":"toggle-vad"}    // toggle-record | esc | enable | disable | shutdown
{"op":"query","query":"status"}
{"op":"target","action":"set","slot":1,"pane":"app:1.2"}
{"op":"events","last":20}                 // tail the JSONL log
{"op":"replay","trace_id":"..."}          // full chain for one utterance
```

Ear-worker event types: `asr.ready`, `asr.listening`, `asr.speech_start`,
`asr.speech_end`, `voice.transcript`, `asr.error`. Daemon-logged types:
`control.state_changed`, `action.executed`, `action.error`. Each utterance keeps its
WAV on disk and the transcript event references it, so misses are replayable.

### State machine (daemon-owned; ear is mode-dumb)

- `off` — daemon up, mic closed. **The daemon starts here** unless config says otherwise.
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
whitelist (`Enter`, `Escape`, `Up`, `Down`, `Left`, `Right`, `Tab`, `BSpace`, `C-c`,
`C-d`, `1`–`9`), never free-form `send-keys` input. Vocabulary entries map to key
*sequences* in config, so e.g. whether "select two" sends `2` or `2 Enter` is a config
choice per entry, tunable against how Claude Code's menus actually behave.

Special commands handled before the grammar: "voice off" (exit `vad-listening`),
"scratch that" (Ctrl-U the input line), "next target" / "target two".

### Executor (Clojure — adapted from lausu `src/lausu/executor/tmux.clj`)

The router plans abstract actions (`type-text`, `press-keys`); a **delivery backend**
renders them. Two backends, selected by the active target:

**`:tmux` backend**
- Dictation text: normalize to a single line, deliver via `load-buffer -` (stdin) +
  `paste-buffer -p`. Text never appears in argv or a shell string.
- Keys: `send-keys <KeyName>` with whitelisted names only.
- Target existence checked with `list-panes -t` (exits non-zero for unknown targets;
  `display-message` is unsuitable — it exits 0 and falls back to the current client).
  Stale target = hard error + notification, never a silent fallback.
- tmux invoked as argv vectors, never shell strings.
- The lausu agent-pane allowlist guard is deliberately **not** ported (see decisions).

**`:focus` backend (ydotool — gated on setup, see contract)**
- Dictation text: `ydotool type --file -` (stdin, same no-argv principle).
- Keys: `ydotool key` with numeric keycode press/release pairs (Enter `28:1 28:0`,
  Esc `1:1 1:0`, Tab `15`, BSpace `14`, arrows `103/108/105/106`,
  Ctrl-C `29:1 46:1 46:0 29:0`).
- `YDOTOOL_SOCKET` comes from `dais.edn`, set explicitly on the subprocess env —
  never inherited from an interactive shell.

Setup contract — **satisfied 2026-06-10**:
1. **Layout fidelity** — by policy: ydotool emits US-QWERTY keycodes, and the US
   layout is the norm here (fi only while typing Finnish). The `:focus` backend
   assumes US is active; fi support (clipboard-paste delivery via `wl-copy` +
   Ctrl-Shift-V would sidestep layout entirely) is deferred until actually wanted.
2. ✓ Clean-env invocation: `env -i YDOTOOL_SOCKET=… ydotool type` works.
3. ✓ Persistence: enabled user unit `~/.config/systemd/user/ydotoold.service`,
   socket at `%t/.ydotool_socket` (the client default). Caveat: the `/dev/uinput`
   ACL comes from Steam's packaged `60-steam-input.rules` (`uaccess` tag) —
   removing `steam-devices` would silently break this.
4. Throughput at low `--key-delay`: verify during milestone 4b (needs a focused
   window to type into; not testable non-interactively).

### Enter modes (config)

- `:no-enter` (default) — type text, never submit.
- `:enter-auto` — utterance *ending* with the word "enter" strips it and sends Enter;
  fuzzy on the last word ("Enter.", trailing pause).
- `:enter-always` — every dictation ends with Enter.

### Targets

- Named slots (2–3) in daemon state: `dais-ctl target set 1 app:1.2`,
  `dais-ctl target set 1 current` (grab active pane), `dais-ctl target set 2 focus`
  (the focused-app backend is just another slot value). Active slot sticky, in
  `state.json`, switchable by voice or CLI — "next target" can cycle from a tmux pane
  to the focused app and back. Key binding for cycling can come later.
- The LLM target resolver ("the claude under app") stays a separate future tool that
  just calls `dais-ctl target set`.

### Feedback

- `state.json` in `$XDG_RUNTIME_DIR/dais/`: `{mode, enabled, target, last_utterance}` —
  for tmux powerline / Claude statusline.
- Marker files next to it (CODEX_PLAN idea): `mic-recording` while capture is open,
  `speech-detected` while VAD reports active speech. Redundant with `state.json`, but
  `[ -f … ]` in a powerline segment beats parsing JSON; both are maintained by the same
  state-transition code.
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

## Testing (largely CODEX_PLAN's list — it was good)

- Unit: event envelope create/parse/validate; daemon request handling per `op`;
  transcript normalization + command matching (the router is pure functions — feed it
  transcripts, assert planned actions); state transitions (toggle-vad, toggle-record,
  refuse-latch-during-vad, voice off); enter-mode behavior; indicator/marker lifecycle.
- A **dry-run executor backend**: action planning returns the full tmux argv plan
  without invoking tmux (lausu's `--dry-run` / `would_run` pattern). All router and
  daemon tests run dry.
- Integration: socket protocol with fake `voice.transcript` events (no mic, no model);
  JSONL logging + `replay` by trace id; a live tmux test against an isolated
  `tmux -L dais-test-<uuid>` server (lausu's pattern — never the real sessions),
  skipped when tmux is absent.
- Manual acceptance: start daemon → enable → `mic-recording` appears →
  `inject "press enter"` plans Enter → `inject "please review PR 123"` plans paste →
  `inject "voice off"` disables and cleans indicators.

## Milestones

1. **Clojure skeleton, no audio.** ✓ done 2026-06-10. Daemon + socket + `state.json` +
   tools + tmux/focus executors + router + `inject`. 35 tests / 127 assertions green;
   manual acceptance passed against a dry-run daemon (inject command/dictation, VAD
   toggle markers, voice off, target ops, replay, shutdown cleanup).
2. **Ear worker, latch path.** ✓ done 2026-06-10. Resident pixi process under daemon
   supervision; `dais-rec` toggles recording (pw-record → faster-whisper, model loads
   once at startup, 1.4 s). Live acceptance: 3 s latch recording transcribed in ~1 s and
   routed to a type-text plan. Note for milestone 3: whisper hallucinated "Hello." from
   room noise — the no_speech_prob/min-duration mitigations are not optional.
3. **VAD mode.** ✓ done 2026-06-10. Streaming wrapper over the Silero v6 ONNX model
   bundled with faster-whisper (no new deps), parec capture at 50 ms latency,
   endpointing with pre-roll/hysteresis/max-length, ASR-queue thread shared with the
   latch path. Gates: min voiced duration, no_speech_prob, avg_logprob — drops logged
   as `asr.dropped` with the WAV kept. Live acceptance via espeak through speakers:
   silence → nothing, "press enter" → Enter plan, "voice off" → mode off by voice.
   Field finding: the daemon's own VAD-start notification chime transcribed as
   "Hello!" — fixed with a startup-mute window + a suppress-sound notification hint.
   Mic = system default source (internal Lunar Lake DMIC here); pin via `:asr :source`.
4. **Polish.** Target slots + voice switching, `dais-esc`, `:enter-auto`, notification
   tuning, KDE `.desktop` files + shortcut binding notes.
4b. **`:focus` backend.** ydotool setup contract is satisfied (see Executor section) —
   ungated. Backend implementation + `focus` as a target-slot value + interactive
   throughput check. Independent of milestones 2–4 (executor-side only).
5. **Tuning era.** Live with it; field-trial discipline from lausu (`docs/field-trials.md`
   style: terse miss log, JSONL has the detail; saved WAVs become regression fixtures).
   Adjust vocabulary, VAD thresholds, model, delivery mode, router strategy as observed.

## Deferred / out of scope

- evdev key *reading*, hold-to-talk.
- wtype: permanently out — KWin has no `zwp_virtual_keyboard_manager_v1` (verified
  2026-06-10). The focus backend is ydotool/uinput, in scope, gated on the setup
  contract in the Executor section.
- F10-during-F9 pause/resume interplay (v1 refuses; design later).
- Agent-pane send guard — only with a precisely identified real problem.
- NPU/OpenVINO inference.
- LLM tmux-target resolver (separate tool).
- Plasma widget indicator (anything can read `state.json`).
- Auto-punctuation/normalization maps ("hash" → "#") — only if agent-side cleanup
  proves insufficient.
