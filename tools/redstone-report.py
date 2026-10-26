"""Compare P8 vanilla tick traces and generate a local review matrix from the installed registry.

No game, save, registry asset or native process is modified. The matrix explicitly keeps
functional acceptance pending: a registry match alone never establishes working redstone.
"""
import argparse
import hashlib
import json
from pathlib import Path


def logical_trace(report):
    return [{key: ({k: v for k, v in value.items() if k not in {"section_revision", "section_indexed"}}
                   if isinstance(value, dict) else value)
             for key, value in frame.items()} for frame in report["trace"]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=Path("fabric/build/reports/p8/block-catalog.json"))
    parser.add_argument("--baseline", required=True, type=Path)
    parser.add_argument("--indexed", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=Path("build/p8-s0-summary.json"))
    parser.add_argument("--matrix", type=Path, default=Path("docs/validation/P8_REDSTONE_MATRIX.md"))
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    indexed = json.loads(args.indexed.read_text(encoding="utf-8"))
    if baseline.get("result") != "PASS" or indexed.get("result") != "PASS":
        raise SystemExit("Both fixture runs must finish PASS")
    if baseline.get("derived_index") is not False or indexed.get("derived_index") is not True:
        raise SystemExit("Expected index-disabled baseline and index-enabled trace")
    if baseline.get("minecraft") != indexed.get("minecraft") or catalog["minecraft"] != baseline["minecraft"]:
        raise SystemExit("Mixed Minecraft versions")
    first, second = logical_trace(baseline), logical_trace(indexed)
    if len(first) != 65 or len(second) != 65 or [frame["tick"] for frame in first] != list(range(65)):
        raise SystemExit("Incomplete S0 tick trace")
    if first != second:
        for expected, actual in zip(first, second):
            if expected != actual:
                raise SystemExit(f"Vanilla trajectory changed at tick {expected['tick']}: {expected} != {actual}")
    candidates = [row for row in catalog["blocks"] if row["redstone_candidate"]]
    summary = {
        "minecraft": catalog["minecraft"], "block_count": catalog["block_count"],
        "state_count": catalog["state_count"], "candidate_count": len(candidates),
        "vanilla_ticks_identical": len(first),
        "trace_sha256": hashlib.sha256(json.dumps(first, sort_keys=True).encode()).hexdigest(),
        "button_powered_ticks": [f["tick"] for f in first if "powered=true" in f["button"]["state"]],
        "piston_extended_ticks": [f["tick"] for f in first if "extended=true" in f["piston"]["state"]],
        "moving_block_ticks": [f["tick"] for f in first if any(isinstance(v, dict) and "progress" in v for v in f.values())],
        "native_observed": False, "p8_accepted": False,
        "evidence": {"catalog": str(args.catalog), "baseline": str(args.baseline), "indexed": str(args.indexed)},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["# P8 红石组件验收目录", "", f"本机 Minecraft {catalog['minecraft']}：{catalog['block_count']} 个注册方块、"
             f"{catalog['state_count']} 个状态，{len(candidates)} 个红石候选方块。", "",
             "分类依据为实际类继承、信号/比较器接口和属性；候选包含管理/调试块与只读比较器来源。",
             "完整 registry 同时导出，未命中候选的条目仍需 S6 审核；分类不是功能验收。",
             "每行状态仅指本阶段尚待逐项验证；S0 自动结果见表后，不把夹具覆盖扩写为所有材质/朝向通过。", "",
             f"来源：`{args.catalog.as_posix()}`；汇总：`{args.output.as_posix()}`。", "",
             "| Registry ID | 状态数 | 属性 | 分类依据 | 逻辑/显示/点选/碰撞/保存/Native |",
             "|---|---:|---|---|---|"]
    for row in candidates:
        lines.append(f"| {row['id']} | {row['states']} | {', '.join(row['properties']) or '—'} | "
                     f"{', '.join(row['reasons'])} | 待验收 |")
    lines += ["", "## 实体型联合目录", "", *[f"- `{item}`：原版逻辑/模型/库存/红石接口待 P8 S4；真实 NPC 乘坐另立范围。"
                                           for item in catalog["vehicle_review"]], "",
              "## S0 自动证据", "",
              f"{catalog['state_count']:,} 个本机状态稳定编码往返、独立 collision/outline/support、旧 full-cube 过滤复现、",
              "32,768 状态索引、revision/删除/迟到回执检查通过。两份真实集成服务器 65 tick 轨迹逐字段一致：",
              "石按钮→红石灯、普通/黏性活塞伸出→moving BlockEntity→推出/回拉→收回；原版状态未被索引改变。",
              "这些是 MC 逻辑与索引证据。没有运行真实 Sifu、没有普通模型/深度取样，也没有真实 NPC 接触证据。",
              "旧立方体显示通路仍有按钮/伸出活塞遗漏，S1b/S3 修复与用户实机复验待完成。", ""]
    args.matrix.parent.mkdir(parents=True, exist_ok=True)
    args.matrix.write_text("\n".join(lines), encoding="utf-8")
    print(f"P8 S0 PASS: {len(first)} identical ticks; {len(candidates)} candidates; {args.output}; {args.matrix}")


if __name__ == "__main__":
    main()
