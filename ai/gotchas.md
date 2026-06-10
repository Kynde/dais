# Gotchas

`fact :: detail`. Field-trial knowledge this project paid for — verify against
code/reality before deleting anything here.

pw-record exit code lies :: it exits 1 on SIGINT while still finalizing a valid WAV; success = file exists with body past the 44-byte RIFF header, NEVER the exit code (ear/dais_ear.py _stop_recorder).
parec buffers ~2s without --latency-msec :: chunks then arrive in bursts, VAD processes them in catch-up and speech_start/speech_end timestamps become near-simultaneous lies; keep --latency-msec=50.
bundled Silero is batch-only :: faster_whisper.vad SileroVADModel.__call__ resets h/c per call; StreamingVad drives model.session directly carrying h/c + 64-sample context across chunks; output needs reshape(-1)[0] (shape is (1,), not (1,1)).
whisper hallucinates from near-silence :: "Hello." / "Thank you." with HIGH confidence (no_speech_prob ~0.03 observed) when the audio is a chime or noise burst — confidence gates alone are insufficient.
the daemon can hear itself :: the "Listening" notification chime triggered VAD and transcribed as "Hello!" in trials; startup_mute_ms in the ear AND the suppress-sound notify-send hint both exist for this — do not remove either.
wtype is dead on KWin :: no zwp_virtual_keyboard_manager_v1 (verified 2026-06-10); the focus backend is ydotool/uinput, no compositor protocol involved.
uinput access rides on Steam :: /dev/uinput ACL for the user comes from steam-devices' udev rule (uaccess tag); removing that package silently breaks ydotoold after reboot.
ydotool speaks US-QWERTY :: keycodes map to symbols via the active layout; fi layout garbles : ' " / @ — the user keeps US active for dais; fi support would need clipboard-paste delivery (wl-copy + Ctrl-Shift-V).
tmux display-message cannot validate targets :: it exits 0 and falls back to the current client for unknown targets — pane existence checks must use list-panes -t.
single-line dictation is load-bearing :: Claude Code collapses MULTI-line pastes into "[Pasted text +N lines]"; the router joins transcripts to one line so the text stays visible in the input box.
kglobalaccel has no key-release events :: push-to-talk is impossible via KDE shortcuts; every hotkey is a stateless toggle and the daemon owns the state.
"a" is a filler word :: in the keypress grammar, control/ctrl+letter pairing must run BEFORE filler removal or "press control a" loses its "a".
KDE/systemd exec env has no user PATH :: node comes from fnm (interactive zsh only) and pixi lives in ~/bin — KDE-bound tools are sh+socat with absolute paths; dais-ctl (node) is NOT safely KDE-bindable; config uses /home/k/bin/pixi absolutely.
testing against the live daemon types into the user's focused window :: default target slot is :focus and the systemd daemon runs LIVE; use a private dry-run daemon (see AGENTS.md) or point a slot at a scratch tmux pane first.
whisper model cache is shared :: ~/.cache/huggingface (shared with ../lausu); warm load ~1s, cold load downloads the model.
