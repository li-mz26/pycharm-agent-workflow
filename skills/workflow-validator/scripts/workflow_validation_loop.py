#!/usr/bin/env python3
"""Workflow validation loop runner.

Implements: modify -> workflow_valid -> workflow_run -> analyze logs -> iterate.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import shlex
import subprocess
import sys
from pathlib import Path


NODE_PATTERNS = [
    re.compile(r"node\s*[:=]\s*([a-zA-Z0-9_\-\.]+)", re.IGNORECASE),
    re.compile(r"节点\s*[:：]\s*([a-zA-Z0-9_\-\.]+)"),
    re.compile(r"at\s+([a-zA-Z0-9_\-\.]+)\s+node", re.IGNORECASE),
]


def run_cmd(cmd: str, workflow: str) -> tuple[int, str]:
    full_cmd = cmd.format(workflow=workflow)
    proc = subprocess.run(
        shlex.split(full_cmd),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    return proc.returncode, proc.stdout


def extract_failed_node(log_text: str) -> str | None:
    for pat in NODE_PATTERNS:
        m = pat.search(log_text)
        if m:
            return m.group(1)
    return None


def save_log(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="执行 workflow_valid/workflow_run 闭环验证脚本")
    parser.add_argument("--workflow", required=True, help="工作流文件路径")
    parser.add_argument("--max-iterations", type=int, default=5, help="最大迭代轮次（默认 5）")
    parser.add_argument("--stable-runs", type=int, default=2, help="连续成功轮次阈值（默认 2）")
    parser.add_argument("--valid-cmd", default="workflow_valid {workflow}", help="校验命令模板")
    parser.add_argument("--run-cmd", default="workflow_run {workflow}", help="运行命令模板")
    parser.add_argument("--expect", default=None, help="可选：期望输出关键字，未命中视为失败")
    args = parser.parse_args()

    workflow = args.workflow
    if not Path(workflow).exists():
        print(f"[错误] 工作流文件不存在: {workflow}")
        return 2

    ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    log_dir = Path(".workflow-validator-logs") / ts
    log_dir.mkdir(parents=True, exist_ok=True)

    print(f"[信息] 日志目录: {log_dir}")
    print(f"[信息] 开始闭环验证: workflow={workflow}, max_iterations={args.max_iterations}, stable_runs={args.stable_runs}")

    stable = 0
    for i in range(1, args.max_iterations + 1):
        print(f"\n===== 迭代 {i} =====")

        valid_code, valid_out = run_cmd(args.valid_cmd, workflow)
        save_log(log_dir / f"iter_{i:02d}_valid.log", valid_out)
        if valid_code != 0:
            print("[失败] workflow_valid 未通过。")
            print("[建议] 根据日志定位字段/节点/边后修复，再重跑。")
            print(f"[日志] {log_dir / f'iter_{i:02d}_valid.log'}")
            return 1
        print("[通过] workflow_valid")

        run_code, run_out = run_cmd(args.run_cmd, workflow)
        save_log(log_dir / f"iter_{i:02d}_run.log", run_out)

        if run_code != 0:
            failed_node = extract_failed_node(run_out)
            print("[失败] workflow_run 未通过。")
            if failed_node:
                print(f"[定位] 疑似首个失败节点: {failed_node}")
            else:
                print("[定位] 未提取到失败节点，请人工检查日志堆栈。")
            print("[建议] 先做单节点调试：核对输入映射、文件路径、节点配置。")
            print(f"[日志] {log_dir / f'iter_{i:02d}_run.log'}")
            return 1

        if args.expect and args.expect not in run_out:
            print("[失败] workflow_run 执行成功但未命中期望输出关键字。")
            print(f"[期望] {args.expect}")
            print(f"[日志] {log_dir / f'iter_{i:02d}_run.log'}")
            return 1

        stable += 1
        print(f"[通过] workflow_run（连续稳定轮次: {stable}/{args.stable_runs}）")

        if stable >= args.stable_runs:
            print("\n[完成] 达到稳定标准：连续运行成功。")
            print(f"[日志目录] {log_dir}")
            return 0

    print("\n[未完成] 达到最大迭代次数，尚未达到稳定阈值。")
    print(f"[日志目录] {log_dir}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
