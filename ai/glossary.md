# Glossary

One line per term: `TERM :: definition`.

ear :: the resident Python worker (mic capture, Silero VAD, faster-whisper) supervised by the daemon over stdin/stdout ndjson; deliberately mode-DUMB — the daemon owns all mode state.
latch :: manual recording mode (F10/`dais-rec`): start/stop toggle, no VAD endpointing; stopping via toggle TRANSCRIBES, stopping via "voice off" DISCARDS.
target slot :: numbered entry in daemon state mapping to a delivery target — a tmux pane (`{:type :tmux :pane "s:w.p"}`) or the focused app (`{:type :focus}`); switched by voice ("target two") or `dais-ctl target use`.
focus backend :: delivery via ydotool/uinput to whatever window is focused; assumes US keyboard layout (uinput emits US keycodes).
whole-match :: default router strategy — an utterance is a command iff the ENTIRE utterance matches (vocabulary or keypress grammar); near-misses become dictation, never partial commands.
armed :: single-shot daemon flag (`dais-ctl arm`) forcing the next utterance to be command-only; a miss executes nothing and the flag clears either way.
enter-mode :: dictation submit policy — :no-enter (default, type only), :enter-auto (trailing spoken "enter" submits), :enter-always.
via :: origin string carried through a state transition (e.g. "toggle-record" vs "voice:voice-off"); dais.ear/ear-message uses it to pick latch_stop (transcribe) vs set_mode off (discard).
asr.dropped :: event for an utterance rejected by the false-trigger gates (min voiced duration, no_speech_prob, avg_logprob); the WAV is kept and referenced for replay.
marker files :: `mic-recording` / `speech-detected` in $XDG_RUNTIME_DIR/dais/ — existence-only flags for statusline `[ -f ]` checks; maintained by the same code as state.json.
brief :: BRIEF.md + `dais-ctl brief` — the voice-input notice a driven agent reads at session start (pointer by default, `--full` pastes the text).
keypress grammar :: trigger verb + every-non-filler-token-must-map-to-a-key rule, ported from lausu intent.clj; the every-token rule is the safety property.
