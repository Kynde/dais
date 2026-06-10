# Do As I Say v1 Plan

## Summary

Build a personal Linux voice-control system for driving the currently focused TUI coding agent on Fedora KDE Wayland.

The v1 architecture is:

- Clojure daemon owns state, config, command grammar, status files, and input actions.
- Python ASR worker is narrow: microphone capture, Silero VAD, faster-whisper transcription.
- Communication is newline-delimited JSON over a Unix socket, using an `op` request wrapper and event envelopes.
- Input goes to the currently focused app using `wtype`.
- No LLM intent layer in v1.
- CLI tools control start/stop/status; hardware keys can bind to those tools later.
- Every accepted event is appended to a JSONL audit log for debugging and replay.

## Lessons From `../lausu`

Reuse these ideas from the old implementation:

- The common event envelope: schema, id, parent_id, trace_id, time, sequence, type, mode, source, payload.
- Dot-named event and action types, with plain JSON as the wire format and EDN only for local config.
- A local Unix socket protocol where clients send one JSON request per line and receive one JSON response per line.
- A small dependency-free Node CLI style for developer/operator tools.
- Append-only JSONL event logs with `events` and `replay` queries.
- Pure Clojure functions for event creation, validation, command/action planning, and state transitions.
- Pixi-managed Python only for the faster-whisper/Silero boundary.

Do not carry forward these older v1 choices:

- tmux targeting as the primary execution path.
- LLM intent normalization, Bedrock integration, policy confirmation flow, or memory.
- Batch-only `voice.audio_recorded -> asr -> emit` producer flow as the primary UX.
- KDE latch/push-to-talk flow as the primary UX.

The new UX is always-listening while enabled, focused-app delivery, deterministic command grammar, and a `voice off` escape hatch.

## Key Changes

- Add an EDN config with defaults:
  - Whisper model: `small.en`
  - Model/device options configurable for experimentation.
  - Unix socket path under `$XDG_RUNTIME_DIR/dais/dais.sock`.
  - Indicator directory under `$XDG_RUNTIME_DIR/dais/`.
  - JSONL events directory, defaulting to `events/`.
  - Injection backend: `wtype`.
  - Free-form dictation behavior: type text, then press Enter.
- Add a Clojure daemon:
  - Listens on the Unix socket.
  - Accepts `publish` requests containing event envelopes from the Python ASR worker.
  - Accepts `control`, `query`, `events`, and `replay` requests from helper tools.
  - Maintains enabled/disabled state.
  - Assigns daemon-local monotonic `sequence` numbers to accepted events.
  - Appends accepted ASR/control/action events to JSONL logs.
  - Creates/removes indicator files:
    - `mic-recording` while mic capture/listening is active.
    - `speech-detected` while VAD reports active speech, if easy to expose.
  - Starts disabled by default unless config says otherwise.
- Add a Python ASR worker managed by the daemon or launched by a tool:
  - Uses Silero VAD for utterance boundaries.
  - Uses faster-whisper for transcription.
  - Emits structured events only; does not decide actions.
  - Connects to the daemon socket and publishes ASR lifecycle/transcript events.
  - Emits transcript events with raw text, timestamps, duration, language, segments, and confidence-like metadata if available.
- Add manual helper tools:
  - `tools/start`
  - `tools/stop`
  - `tools/status`
  - `tools/enable`
  - `tools/disable`
  - `tools/toggle`
  - `tools/send-test-event`
  - `tools/events`
  - `tools/replay`
- Document `ydotool` as a future fallback backend, but do not implement it in v1.

## Socket / JSON Interface

Use one JSON request per line over the Unix socket. The daemon replies with one JSON response per line.

Events use a common envelope:

```json
{
  "schema": "dais.event.v1",
  "id": "uuid-or-ulid",
  "parent_id": "optional-parent-id",
  "trace_id": "optional-trace-id",
  "time": "2026-06-10T10:00:00.000Z",
  "sequence": 1,
  "type": "voice.transcript",
  "mode": "agent",
  "source": {
    "module": "dais-asr",
    "provider": "faster-whisper",
    "model": "small.en"
  },
  "payload": {}
}
```

Required event fields are `schema`, `id`, `time`, `type`, `mode`, `source`, and `payload`. `parent_id`, `trace_id`, and `sequence` are optional; the daemon assigns `sequence`.

Request protocol:

- `{"op":"publish","event":{...}}`
- `{"op":"control","action":"enable"}`
- `{"op":"control","action":"disable"}`
- `{"op":"control","action":"toggle"}`
- `{"op":"control","action":"shutdown"}`
- `{"op":"query","query":"status"}`
- `{"op":"events","last":20}`
- `{"op":"replay","trace_id":"..."}`

The daemon replies with JSON status/ack objects. Error responses use `{"ok":false,"error":"..."}`.

ASR worker events:

- `asr.ready`
- `asr.listening`
- `asr.speech_start`
- `asr.speech_end`
- `voice.transcript`
- `asr.error`

Control/action events logged by the daemon:

- `control.state_changed`
- `action.executed`
- `action.error`

## Command Behavior

When enabled, each transcript is handled in this order:

1. Read text from `event.payload.text`.
2. Normalize transcript text for command matching only.
3. If it exactly matches a deterministic command, execute that command.
4. Otherwise type the transcript into the focused app with `wtype`, then press Enter.

V1 deterministic command grammar:

- `voice off` disables the system.
- `press enter`, `enter` sends Enter.
- `press escape`, `escape` sends Escape.
- `select one` through `select ten`, and `select 1` through `select 10`, type the number and press Enter.
- `yes` types `yes` and presses Enter.
- `no` types `no` and presses Enter.
- `cancel`, `stop` sends Escape.
- Exact key commands:
  - `tab`
  - `backspace`
  - `up`, `down`, `left`, `right`
  - `control c`, `ctrl c`
  - `control d`, `ctrl d`

Near misses are treated as dictation, not commands.

## Test Plan

- Unit test event envelope creation, parsing, and validation.
- Unit test daemon request handling for `publish`, `control`, `query`, `events`, and `replay`.
- Unit test transcript normalization and command matching.
- Unit test action generation without executing `wtype`.
- Unit test daemon state transitions for enable, disable, toggle, status, shutdown.
- Unit test indicator file lifecycle for disabled, listening, speech active, and error cases.
- Integration test the socket protocol with fake ASR events.
- Integration test JSONL audit logging and replay by trace id.
- Add a dry-run action backend for tests and development.
- Manual acceptance test:
  - Start daemon.
  - Enable voice control.
  - Confirm `mic-recording` appears.
  - Send fake transcript `press enter`; confirm Enter action is generated.
  - Send fake transcript `please review pr 123`; confirm text-plus-Enter action is generated.
  - Send fake transcript `voice off`; confirm disabled state and indicator cleanup.

## Assumptions

- v1 targets the currently focused Wayland application only.
- `wtype` is available and sufficient for focused TUI input.
- The user will bind F9-F12 externally later if desired.
- Python dependencies are managed through `pixi`.
- Clojure is preferred for daemon logic; Python is used only where ASR libraries require it.
- The codebase can borrow structure from `../lausu`, but should use `dais.*` names and `dais.event.v1` schemas.
