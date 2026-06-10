#!/usr/bin/env python3
"""dais ear worker: microphone capture + ASR behind an ndjson contract.

Resident process supervised by the dais daemon (the whisper model loads ONCE,
not per utterance — the lesson from lausu's per-call worker).

stdin (one JSON object per line):
  {"type":"latch_start"}              start recording (manual latch)
  {"type":"latch_stop"}               stop recording, transcribe, emit transcript
  {"type":"set_mode","mode":"off"}    discard any active recording, go idle
  {"type":"set_mode","mode":"vad"}    milestone 3 — emits asr.error for now

stdout (one dais.event.v1 envelope per line):
  asr.ready | asr.listening | voice.transcript | asr.error

stderr: diagnostics only. Exits when stdin closes (daemon gone).
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import shutil
import signal
import subprocess
import sys
import time
import uuid
from pathlib import Path


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="dais ear worker")
    parser.add_argument("--model", default="small.en")
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--compute-type", default="int8")
    parser.add_argument("--language", default="en")
    parser.add_argument("--beam-size", type=int, default=5)
    parser.add_argument("--audio-dir", default="/tmp/dais/audio",
                        help="Where utterance WAVs are kept (referenced by transcript events).")
    parser.add_argument("--record-cmd", default="pw-record",
                        help="Recorder binary; must accept --rate/--channels and finalize on SIGINT.")
    parser.add_argument("--check-deps", action="store_true",
                        help="Verify faster_whisper imports and the recorder exists, then exit.")
    return parser.parse_args(argv)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def emit(event_type: str, payload: dict, model: str) -> None:
    event = {
        "schema": "dais.event.v1",
        "id": str(uuid.uuid4()),
        "time": utc_now(),
        "type": event_type,
        "source": {"module": "dais-ear", "provider": "faster-whisper", "model": model},
        "payload": payload,
    }
    print(json.dumps(event, separators=(",", ":")), flush=True)


def log(msg: str) -> None:
    print(f"dais-ear: {msg}", file=sys.stderr, flush=True)


class Ear:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.model = None
        self.rec: subprocess.Popen | None = None
        self.rec_path: Path | None = None
        self.rec_started: float = 0.0

    def load_model(self) -> None:
        from faster_whisper import WhisperModel  # deferred: --check-deps reports cleanly

        t0 = time.monotonic()
        self.model = WhisperModel(self.args.model, device=self.args.device,
                                  compute_type=self.args.compute_type)
        log(f"model {self.args.model} loaded in {time.monotonic() - t0:.1f}s")

    # --- recording ---

    def latch_start(self) -> None:
        if self.rec is not None:
            emit("asr.error", {"error": "already recording"}, self.args.model)
            return
        audio_dir = Path(self.args.audio_dir)
        audio_dir.mkdir(parents=True, exist_ok=True)
        self.rec_path = audio_dir / f"utt-{dt.datetime.now():%Y%m%d-%H%M%S}.wav"
        argv = [self.args.record_cmd, "--rate", "16000", "--channels", "1", str(self.rec_path)]
        self.rec = subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        self.rec_started = time.monotonic()
        emit("asr.listening", {"mode": "latch", "audio_path": str(self.rec_path)}, self.args.model)

    def _stop_recorder(self) -> tuple[Path | None, int]:
        """SIGINT the recorder so it finalizes the WAV. Returns (path, duration_ms)."""
        rec, path = self.rec, self.rec_path
        self.rec, self.rec_path = None, None
        if rec is None:
            return None, 0
        duration_ms = int((time.monotonic() - self.rec_started) * 1000)
        rec.send_signal(signal.SIGINT)
        try:
            rec.wait(timeout=3)
        except subprocess.TimeoutExpired:
            rec.terminate()
            rec.wait(timeout=2)
        # pw-record exits 1 on SIGINT while still finalizing the WAV — the
        # file (a non-empty RIFF body past the 44-byte header) is the real
        # success signal, not the exit code.
        if path is None or not path.exists() or path.stat().st_size <= 44:
            err = rec.stderr.read().decode(errors="replace").strip() if rec.stderr else ""
            emit("asr.error",
                 {"error": f"recorder produced no audio (exit {rec.returncode}): {err}"},
                 self.args.model)
            return None, duration_ms
        return path, duration_ms

    def latch_stop(self) -> None:
        if self.rec is None:
            emit("asr.error", {"error": "not recording"}, self.args.model)
            return
        path, duration_ms = self._stop_recorder()
        if path is None or not path.exists():
            return
        self.transcribe(path, duration_ms)

    def discard(self) -> None:
        path, _ = self._stop_recorder()
        if path is not None:
            log(f"discarded recording {path}")

    # --- ASR ---

    def transcribe(self, path: Path, duration_ms: int) -> None:
        t0 = time.monotonic()
        try:
            raw_segments, info = self.model.transcribe(
                str(path), beam_size=self.args.beam_size, language=self.args.language)
            segments, texts = [], []
            for seg in raw_segments:
                text = str(seg.text).strip()
                if text:
                    texts.append(text)
                segments.append({"start_ms": int(seg.start * 1000),
                                 "end_ms": int(seg.end * 1000),
                                 "text": text})
            emit("voice.transcript",
                 {"text": " ".join(texts).strip(),
                  "language": getattr(info, "language", None) or self.args.language,
                  "duration_ms": duration_ms,
                  "asr_ms": int((time.monotonic() - t0) * 1000),
                  "audio_path": str(path),
                  "segments": segments},
                 self.args.model)
        except Exception as exc:
            emit("asr.error", {"error": f"transcription failed: {exc}",
                               "audio_path": str(path)}, self.args.model)

    # --- control ---

    def handle(self, msg: dict) -> None:
        kind = msg.get("type")
        if kind == "latch_start":
            self.latch_start()
        elif kind == "latch_stop":
            self.latch_stop()
        elif kind == "set_mode":
            mode = msg.get("mode")
            if mode == "off":
                self.discard()
            elif mode == "vad":
                self.discard()
                emit("asr.error", {"error": "VAD mode not implemented yet (milestone 3)"},
                     self.args.model)
            else:
                emit("asr.error", {"error": f"unknown mode: {mode!r}"}, self.args.model)
        else:
            emit("asr.error", {"error": f"unknown message type: {kind!r}"}, self.args.model)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.check_deps:
        import faster_whisper  # noqa: F401
        if shutil.which(args.record_cmd) is None:
            print(f"missing recorder: {args.record_cmd}", file=sys.stderr)
            return 1
        print("ok")
        return 0

    ear = Ear(args)
    ear.load_model()
    emit("asr.ready", {"model": args.model, "device": args.device,
                       "compute_type": args.compute_type}, args.model)
    try:
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                ear.handle(json.loads(line))
            except json.JSONDecodeError as exc:
                emit("asr.error", {"error": f"bad control line: {exc}"}, args.model)
    finally:
        ear.discard()
        log("stdin closed; exiting")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
