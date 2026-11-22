"""Original, deterministic percussion. Python stdlib only; no sampled recordings."""
import math
import random
import struct
import wave
from pathlib import Path

RATE = 44100
OUT = Path(__file__).resolve().parents[1] / "src/main/resources/sounds"

def render(kind, duration):
    rng = random.Random(42)
    previous = phase = 0.0
    samples = []
    for i in range(int(RATE * duration)):
        t = i / RATE
        noise = rng.uniform(-1, 1)
        high = (noise - previous) / 2
        previous = noise
        if kind == "kick":
            phase += 2 * math.pi * (48 + 145 * math.exp(-t * 34)) / RATE
            value = math.sin(phase) * math.exp(-t * 9) + noise * math.exp(-t * 220) * .17
        elif kind == "snare":
            value = .66 * high * math.exp(-t * 15) + .36 * math.sin(2 * math.pi * 185 * t) * math.exp(-t * 24)
        elif kind == "hat":
            value = high * math.exp(-t * 43)
        elif kind == "openhat":
            value = high * math.exp(-t * 10) * .8 + noise * math.sin(2 * math.pi * 7300 * t) * math.exp(-t * 8) * .2
        elif kind == "perc":
            value = .62 * high * math.exp(-t * 55) + .45 * math.sin(2 * math.pi * 1850 * t) * math.exp(-t * 38)
        elif kind == "clap":
            env = sum(math.exp(-(t - s) * 95) for s in (0, .012, .025) if t >= s)
            value = high * (env * .48 + .35 * math.exp(-t * 14))
        elif kind == "tom":
            phase += 2 * math.pi * (95 + 80 * math.exp(-t * 25)) / RATE
            value = math.sin(phase) * math.exp(-t * 8)
        else:
            value = high * math.exp(-t * 4) * .65 + noise * math.sin(2 * math.pi * 6070 * t) * math.exp(-t * 7) * .15
        value *= min(1, i / 18) * min(1, (duration - t) / .015)
        samples.append(value)
    peak = max(abs(v) for v in samples)
    return b"".join(struct.pack("<h", round(v / peak * 26000)) for v in samples)

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for kind, duration in (("kick", .65), ("snare", .45), ("hat", .18), ("openhat", .5), ("clap", .45), ("tom", .7), ("perc", .22)):
        pcm = render(kind, duration)
        assert len(pcm) > 1000 and any(pcm)
        with wave.open(str(OUT / f"{kind}.wav"), "wb") as f:
            f.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
            f.writeframes(pcm)
    print("Generated and checked 7 original mono 44.1 kHz percussion samples.")

if __name__ == "__main__":
    main()
