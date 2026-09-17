# -*- coding: utf-8 -*-
"""多民族医学智能体 · RAG 评估运行器。

用途：每次改动检索 / 提示词 / 理解逻辑后跑一遍，量化「改好了还是改坏了」。

检查三层，逐层给出通过率：
  1. 问题理解 —— 民族 / 疾病 / 意图 / 是否泛化
  2. 知识检索 —— 是否找到证据、意图缺失判定、证据里该有的数值在不在
  3. 回答生成 —— 回答里该有的数值在不在、不该有的在不在、有没有编造文献外的数字

用法：
    python eval/run_eval.py                  # 全量（含大模型生成，较慢）
    python eval/run_eval.py --no-generate    # 只跑理解 + 检索（快，改检索时用）
    python eval/run_eval.py --only 泛化检索   # 只跑某个分类
    python eval/run_eval.py --compare eval/results/baseline.json   # 与基线对比

依赖：AI 服务已在 8000 端口运行（默认 http://127.0.0.1:8000）。
"""
import argparse
import io
import json
import os
import re
import sys
import time
from datetime import datetime

import httpx

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
CASES_PATH = os.path.join(HERE, "cases.json")
RESULTS_DIR = os.path.join(HERE, "results")

PCT_RE = re.compile(r"(\d+(?:\.\d+)?)\s*%")


def call(base, path, payload, timeout=180):
    r = httpx.post(f"{base}{path}", json=payload, timeout=timeout)
    r.raise_for_status()
    body = r.json()
    # FastAPI 直接返回业务数据；若被 Spring 信封包了一层则解开
    if isinstance(body, dict) and "code" in body and "data" in body:
        return body["data"]
    return body


def check(expect_val, actual_val, label, results):
    """记录一条断言结果。expect 为 None 表示该用例不约束这一项。"""
    if expect_val is None:
        return
    ok = actual_val == expect_val
    results.append({"check": label, "expected": expect_val, "actual": actual_val, "pass": ok})


def contains_all(haystack, needles):
    return all(n in (haystack or "") for n in needles)


def contains_none(haystack, needles):
    return all(n not in (haystack or "") for n in needles)


def run_case(base, case, do_generate=True):
    q = case["question"]
    exp = case.get("expect", {})
    row = {"id": case["id"], "category": case["category"], "question": q, "checks": []}
    checks = row["checks"]

    t0 = time.time()
    try:
        u = call(base, "/understand", {"question": q}, timeout=90)
    except Exception as e:
        row["error"] = f"understand 调用失败：{e}"
        return row

    check(exp.get("ethnicity"), u.get("ethnicity"), "理解·民族", checks)
    if "disease" in exp:
        check(exp["disease"], u.get("disease"), "理解·疾病", checks)
    if "intent" in exp:
        check(exp["intent"], u.get("intent"), "理解·意图", checks)
    if "generalized" in exp:
        check(exp["generalized"], bool(u.get("generalized")), "理解·泛化", checks)
    if "medical_advice" in exp:
        check(exp["medical_advice"], bool(u.get("medical_advice")), "理解·用药咨询", checks)

    understand = {
        "ethnicity": u.get("ethnicity") or "",
        "disease": u.get("disease") or "",
        "intent": u.get("intent") or "",
        "metric": u.get("metric"),
        "generalized": bool(u.get("generalized")),
        # 必须回传：后端据此跳过检索、直接返回医疗安全话术
        "medical_advice": bool(u.get("medical_advice")),
    }

    try:
        pool = call(base, "/evidence-pool",
                    {"question": q, "understand": understand, "knowledge": []}, timeout=120)
    except Exception as e:
        row["error"] = f"evidence-pool 调用失败：{e}"
        return row

    evidence_text = " ".join((e.get("fragment") or "") for e in (pool.get("evidence") or []))
    row["evidence_count"] = len(pool.get("evidence") or [])
    row["status"] = pool.get("status")

    if "evidence_found" in exp:
        check(exp["evidence_found"], bool(pool.get("evidence_found")), "检索·找到证据", checks)
    if "intent_missing" in exp:
        check(exp["intent_missing"], bool(pool.get("intent_missing")), "检索·意图缺失标记", checks)
    if "blocked" in exp:
        check(exp["blocked"], bool(pool.get("blocked")), "检索·安全拦截", checks)
    if exp.get("evidence_contains"):
        checks.append({
            "check": "检索·证据含关键值",
            "expected": exp["evidence_contains"],
            "actual": [n for n in exp["evidence_contains"] if n in evidence_text],
            "pass": contains_all(evidence_text, exp["evidence_contains"]),
        })
    if exp.get("evidence_not_contains"):
        leaked = [n for n in exp["evidence_not_contains"] if n in evidence_text]
        checks.append({
            "check": "检索·证据不含无关内容",
            "expected": f"不含 {exp['evidence_not_contains']}",
            "actual": f"泄漏 {leaked}" if leaked else "无泄漏",
            "pass": not leaked,
        })

    if do_generate:
        try:
            gen = call(base, "/generate",
                       {"question": q, "understand": understand, "retrieve_result": pool},
                       timeout=240)
        except Exception as e:
            row["error"] = f"generate 调用失败：{e}"
            return row
        ans = gen.get("answer") or {}
        answer_text = " ".join(str(ans.get(k) or "") for k in
                               ("conclusion", "detailed", "actions", "cautions"))
        row["answer_head"] = (ans.get("conclusion") or "")[:120]

        if exp.get("answer_contains"):
            checks.append({
                "check": "回答·含关键值",
                "expected": exp["answer_contains"],
                "actual": [n for n in exp["answer_contains"] if n in answer_text],
                "pass": contains_all(answer_text, exp["answer_contains"]),
            })
        if exp.get("answer_not_contains"):
            leaked = [n for n in exp["answer_not_contains"] if n in answer_text]
            checks.append({
                "check": "回答·不含无关数值",
                "expected": f"不含 {exp['answer_not_contains']}",
                "actual": f"泄漏 {leaked}" if leaked else "无泄漏",
                "pass": not leaked,
            })

        # 反幻觉：回答里的百分比必须能在「大模型实际看到的原文」里找到出处。
        # 基准必须是候选资料的 content（原始切片），不能只用展示用的聚焦片段——
        # 聚焦片段是有损截取，拿它当基准会把本来有出处的数字误判成编造
        # （实测：纳西族肥胖 15.4、哈尼族心脏瓣膜病 9.5 就被这样误报过）。
        raw_corpus = " ".join((c.get("content") or "") for c in (pool.get("candidates") or []))
        grounded = evidence_text + " " + raw_corpus
        halluc = sorted({m.group(1) for m in PCT_RE.finditer(answer_text)
                         if m.group(1) not in grounded})
        row["hallucinated_percents"] = halluc
        row["answer_text"] = answer_text[:2000]
        checks.append({
            "check": "回答·无编造数字",
            "expected": "回答里的百分比都能在证据中找到",
            "actual": f"无出处 {halluc}" if halluc else "全部有出处",
            "pass": not halluc,
        })

    row["elapsed"] = round(time.time() - t0, 1)
    return row


def summarize(rows):
    def rate(prefix):
        total = passed = 0
        for r in rows:
            for c in r.get("checks", []):
                if c["check"].startswith(prefix):
                    total += 1
                    passed += 1 if c["pass"] else 0
        return passed, total

    layers = {
        "理解": rate("理解·"),
        "检索": rate("检索·"),
        "回答": rate("回答·"),
    }
    return layers


def print_report(rows, do_generate):
    print("=" * 78)
    print("多民族医学智能体 · RAG 评估报告")
    print("=" * 78)

    failed_cases = []
    for r in rows:
        bad = [c for c in r.get("checks", []) if not c["pass"]]
        mark = "✅" if not bad and "error" not in r else "❌"
        print(f"\n{mark} [{r['category']}] {r['question']}")
        if r.get("error"):
            print(f"    错误：{r['error']}")
            failed_cases.append(r)
            continue
        print(f"    证据 {r.get('evidence_count', 0)} 条 · 充分度 {r.get('status')} · 耗时 {r.get('elapsed')}s")
        if r.get("answer_head"):
            print(f"    回答摘录：{r['answer_head']}")
        for c in bad:
            print(f"    ✗ {c['check']}：期望 {c['expected']}，实际 {c['actual']}")
        if bad:
            failed_cases.append(r)

    print()
    print("=" * 78)
    layers = summarize(rows)
    for name, (p, t) in layers.items():
        pct = f"{p / t * 100:.1f}%" if t else "—"
        print(f"  {name}层：{p}/{t} 通过 ({pct})")
    print(f"  用例：{len(rows) - len(failed_cases)}/{len(rows)} 全项通过")
    if not do_generate:
        print("  （--no-generate：未跑回答生成层）")
    print("=" * 78)
    return failed_cases


def compare(baseline_path, rows):
    try:
        with open(baseline_path, encoding="utf-8") as f:
            base = json.load(f)
    except Exception as e:
        print(f"\n无法读取基线 {baseline_path}：{e}")
        return
    old = {r["id"]: r for r in base.get("cases", [])}
    print("\n" + "=" * 78)
    print(f"与基线对比：{os.path.basename(baseline_path)}")
    print("=" * 78)
    regress = improve = 0
    for r in rows:
        o = old.get(r["id"])
        if not o:
            print(f"  [新增] {r['id']}")
            continue
        # 只比较基线里也有的检查项：基线若没跑回答层，不该把回答层算成「修复」
        old_names = {c["check"] for c in o.get("checks", [])}
        of = {c["check"] for c in o.get("checks", []) if not c["pass"]}   # 以前失败的
        nf = {c["check"] for c in r.get("checks", [])
              if not c["pass"] and c["check"] in old_names}               # 现在失败的
        # 以前失败、现在通过 → 修复；以前通过、现在失败 → 退化
        for c in sorted(of - nf):
            print(f"  [修复] {r['id']} · {c}")
            improve += 1
        for c in sorted(nf - of):
            print(f"  [退化] {r['id']} · {c}")
            regress += 1
    skipped = sum(1 for r in rows
                  for c in r.get("checks", [])
                  if c["check"] not in {x["check"] for x in old.get(r["id"], {}).get("checks", [])})
    if skipped:
        print(f"  （{skipped} 项检查在基线里不存在，未参与对比）")
    if not regress and not improve:
        print("  无变化")
    print(f"\n  退化 {regress} 项，改善 {improve} 项")
    print("=" * 78)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:8000/api",
                    help="AI 服务地址（默认 http://127.0.0.1:8000/api）")
    ap.add_argument("--only", default="", help="只跑名称包含该字符串的分类")
    ap.add_argument("--no-generate", action="store_true", help="跳过回答生成（快）")
    ap.add_argument("--out", default="", help="结果输出路径（默认 eval/results/<时间戳>.json）")
    ap.add_argument("--compare", default="", help="与指定基线结果文件对比")
    args = ap.parse_args()

    with open(CASES_PATH, encoding="utf-8") as f:
        spec = json.load(f)
    cases = spec["cases"]
    if args.only:
        cases = [c for c in cases if args.only in c["category"]]

    # 先确认服务在跑，避免一长串网络错误
    try:
        httpx.get(f"{args.base}/ai/health", timeout=10).raise_for_status()
    except Exception as e:
        print(f"AI 服务不可用（{args.base}/ai/health）：{e}")
        print("请先启动：ai-service/restart-ai.bat")
        return 2

    do_generate = not args.no_generate
    print(f"开始评估：{len(cases)} 个用例"
          f"{'（含回答生成）' if do_generate else '（仅理解 + 检索）'}\n")

    rows = []
    for i, c in enumerate(cases, 1):
        print(f"  [{i}/{len(cases)}] {c['question']}", flush=True)
        rows.append(run_case(args.base, c, do_generate))

    failed = print_report(rows, do_generate)

    os.makedirs(RESULTS_DIR, exist_ok=True)
    out = args.out or os.path.join(
        RESULTS_DIR, f"eval-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json")
    payload = {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "base": args.base,
        "with_generate": do_generate,
        "layers": {k: {"passed": v[0], "total": v[1]} for k, v in summarize(rows).items()},
        "cases": rows,
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(payload, f, ensure_ascii=False, indent=2)
    print(f"\n结果已写入：{out}")

    if args.compare:
        compare(args.compare, rows)

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
