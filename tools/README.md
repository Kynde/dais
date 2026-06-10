# dais tools

Thin clients for the daemon's control socket
(`$XDG_RUNTIME_DIR/dais/control.sock`, override with `$DAIS_SOCKET`).
Start the daemon first: `clojure -M:daemon` (add `--dry-run` to plan without
executing, `--no-ear` to skip the audio worker).

| Tool | Purpose |
|------|---------|
| `dais-ctl` | Full verb set (node). Status, inject, targets, events, replay. |
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
tools/dais-ctl target set 2 work:1.0     # a tmux pane
tools/dais-ctl target set 1 focus        # the focused app (ydotool)
tools/dais-ctl target set 3 current      # grab the active tmux pane
tools/dais-ctl target use 2              # switch; or say "target two"
```

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

## Statusline integration

`$XDG_RUNTIME_DIR/dais/` holds `state.json` plus marker files meant for
`[ -f ... ]` checks in a tmux powerline / Claude statusline segment:

```sh
[ -f "$XDG_RUNTIME_DIR/dais/mic-recording" ] && echo "🎤"
[ -f "$XDG_RUNTIME_DIR/dais/speech-detected" ] && echo "🗣"
```
