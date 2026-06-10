# Working on dais

Read `ai/` first (`ai/gotchas.md` especially) — it holds the non-obvious,
hard-won facts. Two kinds of truth with different rules:

- `ai/` is **descriptive** (facts about the system): when it disagrees with
  the code, the code wins and the `ai/` file gets fixed.
- `FABLE_PLAN.md` is **normative** (the agreed design): when the code drifts
  from it, that's a decision, not a typo — flag it to the user rather than
  silently "fixing" either side.

## Build & test

```sh
clojure -X:test                # MUST run from the repo root (deps.edn here)
cd ear && pixi run check       # ear deps + recorder presence
```

Clojure tests use dry-run executors and scratch tmux server names — never the
user's tmux sessions. Keep it that way.

## Safety: the user's daemon is usually LIVE

`systemctl --user status dais` — if active, it executes for real, and the
default target is the **focused app**: a careless `tools/dais-ctl inject ...`
types into whatever window the user has focused. For manual testing, start a
private dry-run instance instead:

```sh
clojure -M:daemon /tmp/dais-test.sock --dry-run --no-ear &
DAIS_SOCKET=/tmp/dais-test.sock tools/dais-ctl inject press enter
```

After changing daemon/ear code: run tests, then
`systemctl --user daemon-reload && systemctl --user restart dais`.

## Invariants (do not relax)

- Subprocesses are argv vectors, never shell strings; dictation text travels
  on **stdin** (`tmux load-buffer -`, `ydotool type --file -`), never argv.
- Key names are whitelisted in BOTH `dais.router` (what speech can produce)
  and `dais.executor` (what may be delivered). New keys update both + tests.
- A failed command match in a command-only context (armed, prefixed) executes
  nothing — it must never fall through to typing.
- The pure core (`router`, `state`, `event`, `ear/ear-message`) stays I/O-free;
  socket/file/process I/O lives in `daemon`, `executor`, `ear`, `log`, `notify`.
- Events are string-keyed maps that round-trip JSON unchanged.

## Conventions

- Config is EDN (`config/dais.edn`); the `:vad` map deliberately uses
  underscore string keys (it is JSON-serialized to the Python worker).
- Wire events follow `dais.event.v1` (see daemon docstring for the op set).
- Commit messages: plain, no attribution trailers.
