# Where to look

`task :: path # note`

add a voice command :: config/dais.edn :router :commands # or built-ins in src/dais/router.clj default-commands
add a key or chord :: src/dais/router.clj (key-words / ctrl-letters) + src/dais/executor.clj (allowed-key-names / focus-keycodes) # BOTH whitelists + tests, or it routes but refuses to deliver
tune VAD / drop gates :: config/dais.edn :vad # underscore keys on purpose (JSON to the ear); defaults in ear/dais_ear.py DEFAULT_TUNING
confidence gate ("uncertain — say again?") :: config/dais.edn :confidence-min-logprob + src/dais/router.clj confidence-gate # refuses delivery below the floor; :control plans always pass
switch transcription language :: dais-ctl set language <code|auto> / dais-top l # session-only; needs a multilingual :asr :model (not *.en); commands tagged :lang match only their language
endpointing behavior :: ear/dais_ear.py VadStream.run # pre-roll, hysteresis, startup mute, max length
mode transitions / refusals :: src/dais/state.clj # pure; ear control-message mapping in src/dais/ear.clj ear-message
mic across suspend/lid-close :: src/dais/daemon.clj start-sleep-watch!/suspend-edge! # dbus-monitor on logind PrepareForSleep; voice-off on BOTH edges (pre-sleep write races the freeze), notifies only if the mic was open; not configurable on purpose
socket protocol / new op :: src/dais/daemon.clj handle-request # one lock serializes all requests
delivery mechanics :: src/dais/executor.clj # plans as data; dry-run returns would_run
notification look :: src/dais/daemon.clj mode-notification + src/dais/notify.clj
debug a misheard utterance :: tools/dais-ctl events / replay <trace_id> # WAV path in the transcript event payload
hotkey scripts / KDE binding :: tools/dais-{vad,rec,esc} + tools/kde/*.desktop # sh+socat on purpose, see gotchas
daemon service :: tools/dais.service # symlinked into ~/.config/systemd/user/; journalctl --user -u dais
live event stream / TUI :: tools/dais-top + subscribe op in src/dais/daemon.clj serve-connection # asr.level meter events are broadcast-only, never logged; ear emits them only while someone is subscribed (set_levels)
target picking / persistence :: dais-ctl target pick (fzf) + t key in dais-top; panes op in daemon handle-target; durable overlay in dais.state save-targets!/load-targets # see gotchas: overlay shadows config wholesale
