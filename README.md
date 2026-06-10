# dais — Do As I Say

Personal Linux voice control for driving TUI coding agents (Claude Code,
Codex) on Fedora/KDE Wayland. Speak; dais transcribes locally
(faster-whisper + Silero VAD), routes each utterance as either a **command**
("press enter", "select two", "voice off") or **dictation**, and delivers it
to a tmux pane or the focused app. No cloud, no intent LLM — the agent being
driven does its own transcript cleanup (see `BRIEF.md`).

```text
F9/F10 toggles ──► Clojure daemon (router · targets · executor · audit log)
                        │ ndjson                 │ tmux paste / ydotool
                        ▼                        ▼
              Python "ear" (pixi): mic · Silero VAD · whisper, resident
```

## Run

```sh
systemctl --user start dais      # unit: tools/dais.service (enabled here)
tools/dais-ctl status
tools/dais-vad                   # open mic; say "voice off" to stop
```

See `tools/README.md` for the tools and the voice-command reference.

## Reading order

- `FABLE_PLAN.md` — design decisions with rationale, milestone status.
- `tools/README.md` — usage, examples, voice commands.
- `BRIEF.md` — the notice a driven agent should read (`dais-ctl brief`).
- `AGENTS.md` + `ai/` — for coding agents working on this repo.
- `GOAL.md`, `CODEX_PLAN.md` — original goal notes and an alternative plan.
