# dais tools

Thin clients for the daemon's control socket
(`$XDG_RUNTIME_DIR/dais/control.sock`, override with `$DAIS_SOCKET`).
Start the daemon first: `clojure -M:daemon` (add `--dry-run` to plan without
executing, `--no-ear` to skip the audio worker).

| Tool | Purpose |
|------|---------|
| `dais-ctl` | Full verb set (node). Status, inject, targets, events, replay. |
| `dais-top` | Live TUI cockpit: mode, mic level meter, heard-list, counters, targets. |
| `dais-vad` | Toggle the VAD session. Bind to **F9**. |
| `dais-rec` | Toggle the manual record latch. Bind to **F10**. |
| `dais-esc` | Send Escape to the active target immediately. Bind to **F11**. |

The three `dais-*` toggles are plain `sh` + `socat` with no PATH/zsh
assumptions, so they work as KDE global shortcuts as-is. State lives in the
daemon — the scripts are stateless one-liners.

## Examples

Pipeline smoke test without a microphone (`inject` feeds a fake transcript
into the router; with `--dry-run` nothing reaches tmux/ydotool):

```sh
tools/dais-ctl status
tools/dais-ctl inject press enter            # whole-utterance command
tools/dais-ctl inject choose option two      # grammar: fillers + ordinals
tools/dais-ctl inject please review PR 123   # dictation -> type-text, no submit
tools/dais-ctl inject voice off              # voice control of the daemon itself
```

Brief a fresh agent session about voice input (delivers a pointer to
`BRIEF.md` into the target — the agent reads the file itself; `--full` pastes
the whole text for targets without file access). Then say "press enter":

```sh
tools/dais-ctl brief
```

Real dictation via the latch (speak between the two `rec` calls):

```sh
tools/dais-ctl rec      # or press F10
# ... speak ...
tools/dais-ctl rec      # stop -> transcribe -> route -> deliver
tools/dais-ctl events 3 # see what happened
```

Targets (slot 1/2/... are defined in config/dais.edn and adjustable live):

```sh
tools/dais-ctl target list
tools/dais-ctl target set 2 app:claude.0 # a tmux pane (window NAMES work too)
tools/dais-ctl target set 1 focus        # the focused app (ydotool)
tools/dais-ctl target set 3 current      # grab the active tmux pane
tools/dais-ctl target use 2              # switch; or say "target two"
tools/dais-ctl target next               # cycle to the next slot (= "next target")
tools/dais-ctl target prev               # cycle to the previous slot
tools/dais-ctl target next-live          # next deliverable slot (skips dead targets)
tools/dais-ctl target panes              # all panes, agents ★, slots annotated
tools/dais-ctl target pick [slot]        # fzf-pick a pane (default: active slot)
```

In dais-top, `t` opens the same picker as an overlay (↑/↓, Enter, then the
slot digit). Picked targets are **durable**: the daemon persists them to
`~/.local/state/dais/targets.edn` and they override the `config/dais.edn`
defaults on restart — delete that file to return to config defaults.

Debugging a miss:

```sh
tools/dais-ctl events 20                 # recent events across the log
tools/dais-ctl replay <trace_id>         # full chain for one utterance
jq . "$XDG_RUNTIME_DIR/dais/state.json"  # mode/target/last utterance
```

One-shot command arming (the next utterance must be a command; a miss does
nothing instead of being typed):

```sh
tools/dais-ctl arm
tools/dais-ctl off                       # force voice off
tools/dais-ctl shutdown                  # stop the daemon (cleans up socket/markers)
```

## dais-top

```sh
tools/dais-top
```

Full-screen status cockpit: mode badge (pulsing while recording), htop-style
mic level bar + sparkline with the Silero speech probability (VAD mode only —
the latch records to a file, so it shows a pulse animation instead), target
slots with the active one highlighted, the last utterances with their outcome
(`→ Enter`, `→ typed`, `✗ dropped: …`), and today's counters (listened time,
utterances, words, drops, mean ASR latency) seeded from the event log.

Control keys: `v` VAD toggle · `r` record latch · `Esc` send Escape ·
`1`–`5` switch target · `Tab` next live target (skips dead ones) ·
`t` pane picker · `a` arm · `e` cycle enter-mode ·
`s` cycle router strategy · `?` help · `q` quit. Press `?` for a full
key + voice-command reference — it also lists the unobtrusive cycle keys
(`>` next / `<` prev target) and your config commands, pulled live from the
daemon so the list never drifts from `config/dais.edn`. The `e`/`s` toggles (also
`dais-ctl set enter-mode ...` / `set strategy ...`) are **session-only** —
a daemon restart returns to the config defaults; calibration knobs (VAD
tuning, idle timeout) stay config-file only on purpose.

Entirely optional: when no dais-top is connected the daemon broadcasts
nothing and the ear skips level computation entirely (the daemon toggles the
meter via `set_levels` on subscriber transitions) — zero overhead.

## Voice commands

Daemon controls (work in every router strategy):

| Say | Effect |
|------|---------|
| "voice off" / "dais off" | stop listening (in-flight latch recording is discarded) |
| "next target" | cycle target slot |
| "target two" (one–five, ordinals, digits) | switch slot |

Built-in whole-utterance commands:

| Say | Effect |
|------|---------|
| "scratch that" | `C-u` — clear the input line |
| "cancel" / "stop" | `Escape` |
| "yes" / "no" | type `yes`/`no` + Enter |

Key presses: trigger verb (*press/hit/push/choose/select/pick/answer*) + keys;
fillers (*the/a/and/then/option/number/key/button*) ignored; max 5 keys.
Keys: *enter, return, escape, esc, up, down, left, right, tab, backspace*,
*one–nine* / *first–ninth* / digits, *control|ctrl + a c d k u w*.

```text
"press enter"                      -> Enter
"choose option two"                -> 2
"select the third option"          -> 3
"press down and enter"             -> Down Enter
"press control a then control k"   -> C-a C-k (clear the line)
```

Every non-filler word must map to a key, or the utterance is dictation —
"select the right abstraction for this" types as text. Everything that isn't
a command is dictation: typed as one line, never submitted (`:no-enter`; with
`:enter-auto` a trailing spoken "enter" submits). Add your own commands in
`config/dais.edn` under `:router :commands`.

## Statusline integration

`$XDG_RUNTIME_DIR/dais/` holds `state.json` plus marker files meant for
`[ -f ... ]` checks in a tmux powerline / Claude statusline segment:

```sh
[ -f "$XDG_RUNTIME_DIR/dais/mic-recording" ] && echo "🎤"
[ -f "$XDG_RUNTIME_DIR/dais/speech-detected" ] && echo "🗣"
```
