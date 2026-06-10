#!/usr/bin/env python3
"""dais ear worker: microphone capture + VAD + ASR behind an ndjson contract.

Resident process supervised by the dais daemon (the whisper model loads ONCE,
not per utterance — the lesson from lausu's per-call worker).

stdin (one JSON object per line):
  {"type":"latch_start"}              start recording (manual latch)
  {"type":"latch_stop"}               stop recording, transcribe, emit transcript
  {"type":"set_mode","mode":"vad"}    open the mic, Silero-endpoint utterances
  {"type":"set_mode","mode":"off"}    stop VAD / discard any recording, go idle
  {"type":"set_levels","on":true}     emit asr.level meter events (~8 Hz) while
                                      in VAD mode; off by default so the system
                                      carries zero overhead when no UI watches

stdout (one dais.event.v1 envelope per line):
  asr.ready | asr.listening | asr.speech_start | asr.speech_end
  voice.transcript | asr.dropped | asr.error

stderr: diagnostics only. Exits when stdin closes (daemon gone).

VAD mode runs three pieces: a capture thread (parec raw s16le 16k mono ->
512-sample chunks), a streaming wrapper around the Silero ONNX model bundled
with faster-whisper (persistent h/c state + 64-sample context), and an ASR
queue thread shared with the latch path. False-trigger gates (min voiced
duration, no_speech_prob, avg_logprob) keep room noise from becoming
keystrokes — whisper happily hallucinates "Hello." from silence.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import queue
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
import wave
from pathlib import Path

SAMPLE_RATE = 16000
CHUNK_SAMPLES = 512            # Silero's unit at 16 kHz = 32 ms
CHUNK_BYTES = CHUNK_SAMPLES * 2
CHUNK_MS = 1000 * CHUNK_SAMPLES // SAMPLE_RATE

DEFAULT_TUNING = {
    "threshold": 0.5,          # speech probability to open an utterance
    "release": 0.35,           # hysteresis: below this counts as silence
    "min_speech_ms": 300,      # voiced time required to keep an utterance
    "silence_ms": 700,         # trailing silence that ends an utterance
    "pre_roll_ms": 300,        # audio kept from before speech onset
    "max_utterance_s": 30,     # hard cap
    "no_speech_prob_max": 0.7, # VAD-mode transcript gates (mean over segments)
    "avg_logprob_min": -1.2,
    "startup_mute_ms": 700,    # ignore triggers right after VAD start (the
                               # daemon's own "VAD listening" notification
                               # chime is otherwise the first thing heard)
}


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
                        help="Latch recorder; must accept --rate/--channels and finalize on SIGINT.")
    parser.add_argument("--stream-cmd", default="parec",
                        help="Raw streaming capture for VAD mode (s16le on stdout).")
    parser.add_argument("--source", default="",
                        help="PipeWire source node name; empty = system default "
                             "(follows headset plug/unplug via WirePlumber).")
    parser.add_argument("--tuning", default="{}",
                        help="JSON overrides for VAD/gate tuning (see DEFAULT_TUNING).")
    parser.add_argument("--check-deps", action="store_true")
    return parser.parse_args(argv)


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


_emit_lock = threading.Lock()


def emit(event_type: str, payload: dict, model: str) -> None:
    event = {
        "schema": "dais.event.v1",
        "id": str(uuid.uuid4()),
        "time": utc_now(),
        "type": event_type,
        "source": {"module": "dais-ear", "provider": "faster-whisper", "model": model},
        "payload": payload,
    }
    with _emit_lock:
        print(json.dumps(event, separators=(",", ":")), flush=True)


def log(msg: str) -> None:
    print(f"dais-ear: {msg}", file=sys.stderr, flush=True)


def write_wav(path: Path, pcm: bytes) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm)


class StreamingVad:
    """Streaming wrapper over the Silero ONNX session bundled with
    faster-whisper. The bundled SileroVADModel.__call__ is batch-only (resets
    h/c per call); this carries the recurrent state and the 64-sample context
    across chunks, which is exactly what its batch path does internally."""

    CONTEXT = 64

    def __init__(self):
        import numpy as np
        from faster_whisper.vad import get_vad_model

        self._np = np
        self._session = get_vad_model().session
        self.reset()

    def reset(self) -> None:
        np = self._np
        self._h = np.zeros((1, 1, 128), dtype="float32")
        self._c = np.zeros((1, 1, 128), dtype="float32")
        self._context = np.zeros((1, self.CONTEXT), dtype="float32")

    def to_float(self, chunk_bytes: bytes):
        np = self._np
        return np.frombuffer(chunk_bytes, dtype="<i2").astype("float32") / 32768.0

    def prob_array(self, chunk) -> float:
        np = self._np
        x = np.concatenate([self._context, chunk.reshape(1, -1)], axis=1)
        out, self._h, self._c = self._session.run(
            None, {"input": x, "h": self._h, "c": self._c})
        self._context = chunk[-self.CONTEXT:].reshape(1, -1)
        return float(np.asarray(out).reshape(-1)[0])

    def prob(self, chunk_bytes: bytes) -> float:
        return self.prob_array(self.to_float(chunk_bytes))

    @staticmethod
    def rms(chunk) -> float:
        import numpy as np
        return float(np.sqrt(np.mean(chunk * chunk)))


class VadStream(threading.Thread):
    """Capture thread for VAD mode: parec -> chunks -> endpointed utterances
    onto the ASR queue. Emits asr.speech_start/speech_end as it goes."""

    def __init__(self, ear: "Ear"):
        super().__init__(daemon=True, name="vad-stream")
        self.ear = ear
        self.tuning = ear.tuning
        self.stopping = False
        self.proc: subprocess.Popen | None = None

    def stop(self) -> None:
        self.stopping = True
        if self.proc is not None:
            self.proc.terminate()

    def run(self) -> None:
        t = self.tuning
        pre_roll_chunks = max(1, t["pre_roll_ms"] // CHUNK_MS)
        silence_chunks = max(1, t["silence_ms"] // CHUNK_MS)
        max_chunks = t["max_utterance_s"] * 1000 // CHUNK_MS

        # Low latency keeps chunks near real-time; without it parec delivers
        # ~2s bursts and endpointing lags the speaker badly.
        argv = [self.ear.args.stream_cmd, f"--rate={SAMPLE_RATE}",
                "--channels=1", "--format=s16le", "--latency-msec=50"]
        if self.ear.args.source:
            argv.append(f"--device={self.ear.args.source}")
        try:
            self.proc = subprocess.Popen(argv, stdout=subprocess.PIPE,
                                         stderr=subprocess.DEVNULL)
        except OSError as exc:
            emit("asr.error", {"error": f"stream capture failed to start: {exc}"},
                 self.ear.args.model)
            return

        vad = self.ear.vad
        vad.reset()
        emit("asr.listening", {"mode": "vad"}, self.ear.args.model)

        pre_roll: list[bytes] = []
        utterance: list[bytes] = []
        in_speech = False
        voiced = 0
        silent_tail = 0
        mute_chunks = t["startup_mute_ms"] // CHUNK_MS

        def finish(reason: str) -> None:
            nonlocal utterance, in_speech, voiced, silent_tail
            voiced_ms = voiced * CHUNK_MS
            emit("asr.speech_end", {"voiced_ms" : voiced_ms, "reason": reason},
                 self.ear.args.model)
            if voiced_ms >= t["min_speech_ms"]:
                pcm = b"".join(utterance)
                path = self.ear.new_audio_path()
                write_wav(path, pcm)
                self.ear.asr_q.put({"path": path, "origin": "vad",
                                    "duration_ms": len(pcm) // 32})
            else:
                emit("asr.dropped", {"reason": f"too short ({voiced_ms}ms voiced)"},
                     self.ear.args.model)
            utterance, in_speech, voiced, silent_tail = [], False, 0, 0

        chunk_idx = 0
        while not self.stopping:
            data = self.proc.stdout.read(CHUNK_BYTES)
            if not data or len(data) < CHUNK_BYTES:
                break
            chunk = vad.to_float(data)
            p = vad.prob_array(chunk)
            chunk_idx += 1
            # Meter events only when a UI subscribed (daemon toggles the flag);
            # otherwise this path costs nothing beyond the VAD it always does.
            if self.ear.levels_on and chunk_idx % 4 == 0:
                emit("asr.level",
                     {"rms": round(vad.rms(chunk), 4), "prob": round(p, 3),
                      "speech": in_speech},
                     self.ear.args.model)
            if mute_chunks > 0:
                mute_chunks -= 1
                continue
            if not in_speech:
                pre_roll.append(data)
                if len(pre_roll) > pre_roll_chunks:
                    pre_roll.pop(0)
                if p >= t["threshold"]:
                    in_speech = True
                    utterance = list(pre_roll)
                    pre_roll = []
                    voiced, silent_tail = 1, 0
                    emit("asr.speech_start", {}, self.ear.args.model)
            else:
                utterance.append(data)
                if p >= t["release"]:
                    voiced += 1
                    silent_tail = 0
                else:
                    silent_tail += 1
                if silent_tail >= silence_chunks:
                    finish("endpoint")
                elif len(utterance) >= max_chunks:
                    finish("max-length")

        if in_speech:
            if self.stopping:
                # Off means OFF: a deliberate stop (F9/voice-off mid-speech)
                # DISCARDS the partial utterance — transcribing it could type
                # or press keys after the user said stop. Only an unexpected
                # capture death keeps what was heard.
                emit("asr.speech_end",
                     {"voiced_ms": voiced * CHUNK_MS, "reason": "stopped"},
                     self.ear.args.model)
                emit("asr.dropped", {"reason": "vad stopped mid-utterance"},
                     self.ear.args.model)
            else:
                finish("capture-stopped")
        if self.proc is not None:
            self.proc.terminate()
        log("vad stream stopped")


class Ear:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.tuning = {**DEFAULT_TUNING, **json.loads(args.tuning)}
        self.model = None
        self.vad: StreamingVad | None = None
        self.rec: subprocess.Popen | None = None
        self.rec_path: Path | None = None
        self.rec_started: float = 0.0
        self.vad_stream: VadStream | None = None
        self.levels_on = False
        self.asr_q: queue.Queue = queue.Queue()

    def load_models(self) -> None:
        from faster_whisper import WhisperModel

        t0 = time.monotonic()
        self.model = WhisperModel(self.args.model, device=self.args.device,
                                  compute_type=self.args.compute_type)
        self.vad = StreamingVad()
        log(f"models loaded in {time.monotonic() - t0:.1f}s")

    def new_audio_path(self) -> Path:
        audio_dir = Path(self.args.audio_dir)
        audio_dir.mkdir(parents=True, exist_ok=True)
        return audio_dir / f"utt-{dt.datetime.now():%Y%m%d-%H%M%S-%f}.wav"

    # --- latch recording (pw-record to a file) ---

    def latch_start(self) -> None:
        if self.rec is not None:
            emit("asr.error", {"error": "already recording"}, self.args.model)
            return
        self.rec_path = self.new_audio_path()
        argv = [self.args.record_cmd, "--rate", str(SAMPLE_RATE), "--channels", "1"]
        if self.args.source:
            argv += ["--target", self.args.source]
        argv.append(str(self.rec_path))
        self.rec = subprocess.Popen(argv, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL)
        self.rec_started = time.monotonic()
        emit("asr.listening", {"mode": "latch", "audio_path": str(self.rec_path)},
             self.args.model)

    def _stop_recorder(self) -> tuple[Path | None, int]:
        """SIGINT the recorder so it finalizes the WAV. Returns (path, ms).
        pw-record exits 1 on SIGINT while still finalizing — the file (a
        non-empty RIFF body past the 44-byte header) is the success signal."""
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
        if path is None or not path.exists() or path.stat().st_size <= 44:
            emit("asr.error",
                 {"error": f"recorder produced no audio (exit {rec.returncode})"},
                 self.args.model)
            return None, duration_ms
        return path, duration_ms

    def latch_stop(self) -> None:
        if self.rec is None:
            emit("asr.error", {"error": "not recording"}, self.args.model)
            return
        path, duration_ms = self._stop_recorder()
        if path is not None:
            self.asr_q.put({"path": path, "origin": "latch", "duration_ms": duration_ms})

    def discard(self) -> None:
        path, _ = self._stop_recorder()
        if path is not None:
            log(f"discarded recording {path}")

    # --- VAD mode ---

    def vad_start(self) -> None:
        if self.vad_stream is not None and self.vad_stream.is_alive():
            return
        self.discard()
        self.vad_stream = VadStream(self)
        self.vad_stream.start()

    def vad_stop(self) -> None:
        if self.vad_stream is not None:
            self.vad_stream.stop()
            self.vad_stream = None

    # --- ASR queue thread (shared by latch + VAD) ---

    def asr_loop(self) -> None:
        while True:
            item = self.asr_q.get()
            if item is None:
                return
            self.transcribe(**item)

    def transcribe(self, path: Path, origin: str, duration_ms: int) -> None:
        t0 = time.monotonic()
        t = self.tuning
        try:
            raw_segments, info = self.model.transcribe(
                str(path), beam_size=self.args.beam_size, language=self.args.language)
            segments, texts, no_speech, logprob = [], [], [], []
            for seg in raw_segments:
                text = str(seg.text).strip()
                if text:
                    texts.append(text)
                no_speech.append(float(seg.no_speech_prob))
                logprob.append(float(seg.avg_logprob))
                segments.append({"start_ms": int(seg.start * 1000),
                                 "end_ms": int(seg.end * 1000), "text": text})
            full_text = " ".join(texts).strip()
            asr_ms = int((time.monotonic() - t0) * 1000)

            def dropped(reason: str) -> None:
                emit("asr.dropped",
                     {"reason": reason, "text": full_text, "origin": origin,
                      "audio_path": str(path)}, self.args.model)

            mean = lambda xs: sum(xs) / len(xs) if xs else 0.0
            if not full_text:
                dropped("empty transcript")
            elif origin == "vad" and mean(no_speech) > t["no_speech_prob_max"]:
                dropped(f"no_speech_prob {mean(no_speech):.2f} > {t['no_speech_prob_max']}")
            elif origin == "vad" and mean(logprob) < t["avg_logprob_min"]:
                dropped(f"avg_logprob {mean(logprob):.2f} < {t['avg_logprob_min']}")
            else:
                emit("voice.transcript",
                     {"text": full_text,
                      "language": getattr(info, "language", None) or self.args.language,
                      "origin": origin,
                      "duration_ms": duration_ms,
                      "asr_ms": asr_ms,
                      "no_speech_prob": round(mean(no_speech), 3),
                      "avg_logprob": round(mean(logprob), 3),
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
                self.vad_stop()
                self.discard()
            elif mode == "vad":
                self.vad_start()
            else:
                emit("asr.error", {"error": f"unknown mode: {mode!r}"}, self.args.model)
        elif kind == "set_levels":
            self.levels_on = bool(msg.get("on"))
        else:
            emit("asr.error", {"error": f"unknown message type: {kind!r}"}, self.args.model)

    def shutdown(self) -> None:
        self.vad_stop()
        self.discard()
        self.asr_q.put(None)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    if args.check_deps:
        import numpy  # noqa: F401
        import faster_whisper  # noqa: F401
        from faster_whisper.vad import get_vad_model  # noqa: F401
        missing = [c for c in (args.record_cmd, args.stream_cmd) if shutil.which(c) is None]
        if missing:
            print(f"missing recorder(s): {', '.join(missing)}", file=sys.stderr)
            return 1
        print("ok")
        return 0

    ear = Ear(args)
    ear.load_models()
    threading.Thread(target=ear.asr_loop, daemon=True, name="asr").start()
    emit("asr.ready", {"model": args.model, "device": args.device,
                       "compute_type": args.compute_type,
                       "tuning": ear.tuning}, args.model)
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
        ear.shutdown()
        log("stdin closed; exiting")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
