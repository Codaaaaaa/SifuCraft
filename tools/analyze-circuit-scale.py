"""Summarize the isolated clock/piston fixture and bounded Native GPU intervals.

Usage: python tools/analyze-circuit-scale.py live01 live02
Native rows use differences of cumulative counters, with both endpoints inside
one warmed phase. CPU submission time and GPU timestamp time stay separate.
"""
import datetime as dt
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent


def read(path):
    data = path.read_bytes()
    return data.decode("utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig")


def analyze(run):
    if not re.fullmatch(r"[a-zA-Z0-9_-]+", run):
        raise ValueError("run must be a local fixture name")
    result = json.loads(read(ROOT / f"fabric/run/saves/sifucraft-circuit-scale-check-{run}/p8-p9-circuit-scale.json"))
    if result["result"] != "PASS" or len(result["rows"]) != 7:
        raise ValueError(f"{run}: incomplete fixture")
    if not result["actual_ue"]:
        return result
    client = read(ROOT / f"build/p8-scale-{run}-client.log")
    if not re.search(r"\[circuit-scale-test\] PASS.*live=true", client) or "BUILD SUCCESSFUL" not in client:
        raise ValueError(f"{run}: fixture/client did not finish normally")
    native = read(ROOT / f"build/p8-scale-{run}-game.log")
    date = dt.date.fromisoformat(native[:10])
    starts, ends = {}, {}
    previous = None
    for line in client.splitlines():
        match = re.match(r"\[(\d\d:\d\d:\d\d)\].*\[circuit-scale-test\] (.*)", line)
        if not match:
            continue
        clock = dt.time.fromisoformat(match[1])
        if previous is not None and clock < previous:
            date += dt.timedelta(days=1)
        previous = clock
        at = dt.datetime.combine(date, clock)
        phase = re.search(r"phase[= ](\w+)", match[2])
        if not phase:
            continue
        (ends if match[2].startswith("measured") else starts)[phase[1]] = at
    samples = []
    for line in native.splitlines():
        if "[world-gpu]" in line:
            at = dt.datetime.strptime(line[:23], "%Y-%m-%d %H:%M:%S.%f")
            samples.append((at, {k: int(v) for k, v in re.findall(r"(\w+)=(\d+)", line)}))
    views = []
    for line in client.splitlines():
        match = re.match(r"\[(\d\d:\d\d:\d\d)\].*\[status\].*owner=(\w+).*yaw=([-\d.]+) pitch=([-\d.]+)", line)
        if match:
            at = dt.datetime.combine(date, dt.time.fromisoformat(match[1]))
            views.append((at, match[2], float(match[3]), float(match[4])))
    for row in result["rows"]:
        name = row["phase"]
        # Whole-second client logs: leave an extra second at either boundary.
        chosen = [(t, v) for t, v in samples
                  if starts[name] + dt.timedelta(seconds=5) <= t < ends[name] - dt.timedelta(seconds=1)]
        changed_view = any(owner != "MINECRAFT" or abs(yaw) > .01 or abs(pitch-30) > .01
                           for t, owner, yaw, pitch in views
                           if starts[name] + dt.timedelta(seconds=5) <= t < ends[name] - dt.timedelta(seconds=1))
        if changed_view or row.get("maximum_camera_deviation_degrees", 0) > .01 or row.get("maximum_position_deviation_m", 0) > .01:
            row["native_interval"] = {"unavailable": "observation pose or input ownership changed; not a fixed-view interval"}
            continue
        if len(chosen) < 2:
            raise ValueError(f"{run}/{name}: insufficient warmed Native samples")
        (t0, a), (t1, b) = chosen[0], chosen[-1]

        def delta(key):
            value = b[key] - a[key]
            if value < 0:
                raise ValueError(f"{run}/{name}: Native counters restarted")
            return value

        seconds = (t1 - t0).total_seconds()
        counters = ("n", "total_ns", "prepare_n", "prepare_total_us", "submit_n", "submit_total_us")
        if any(after[k] < before[k] for (_, before), (_, after) in zip(chosen, chosen[1:]) for k in counters):
            row["native_interval"] = {"unavailable": "renderer counters restarted within warmed phase"}
            continue
        if any(delta(k) for k in ("disjoint", "errors")):
            raise ValueError(f"{run}/{name}: invalid GPU measurements")
        row["native_interval"] = {
            "seconds": seconds,
            "gpu_samples": delta("n"),
            "gpu_draw_ms": delta("total_ns") / delta("n") / 1e6 if delta("n") else None,
            "cpu_prepare_ms": delta("prepare_total_us") / delta("prepare_n") / 1000 if delta("prepare_n") else None,
            "cpu_submit_ms": delta("submit_total_us") / delta("submit_n") / 1000 if delta("submit_n") else None,
            "source_prepares_per_second": delta("prepare_n") / seconds,
            "world_draw_submissions_per_second": delta("submit_n") / seconds,
            "gpu_samples_skipped": delta("skipped"),
            "sampled_source_pixels": [min(v["pixels"] for _, v in chosen), max(v["pixels"] for _, v in chosen)],
            "sampled_patches": [min(v["patches"] for _, v in chosen), max(v["patches"] for _, v in chosen)],
        }
    return result


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    report = {run: analyze(run) for run in sys.argv[1:]}
    destination = ROOT / "build/p8-p9-scale-analysis.json"
    destination.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for run, result in report.items():
        print(run, "actual_ue=", result["actual_ue"])
        for row in result["rows"]:
            native = row.get("native_interval", {})
            print(f"  {row['phase']:9} TPS={row['estimated_tps']:.2f} "
                  f"MC p95={row['mc_render_intervals']['p95_ms']:.2f}ms "
                  f"ACK max={row['shape_pending_spans']['max_ms']:.2f}ms "
                  f"Native={native}")
    print(destination)
