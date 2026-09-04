"""Reproducible 1,000-case engineering benchmark for MedPilot.

The generated records are synthetic engineering inputs, not clinical cases.
Primary evaluation calls the production safety, retrieval, classification,
and follow-up functions without changing their thresholds or rule tables.
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import json
import math
import platform
import random
import statistics
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from time import perf_counter
from typing import Iterable, Sequence

from app.agents.classify import classify
from app.agents.followup import build_followup, is_sufficient
from app.agents.safety import screen_for_emergency
from app.ollama_client import EMBED_MODEL, embed
from app.rag.retriever import retrieve
from app.schemas import StructuredSymptoms


SEED = 20260831
GENERATOR_VERSION = "medpilot-benchmark-1000-v1"
ABSTAIN_DEPARTMENT = "全科/建议线下分诊台"
PERTURBATION_NAMES = (
    "P0 规范表达",
    "P1 口语与同义表达",
    "P2 组合与背景干扰",
    "P3 否定与跨症状干扰",
    "P4 错别字与未登录改写",
)


@dataclass(frozen=True)
class BenchmarkCase:
    case_id: str
    task: str
    perturbation: int
    text: str
    gold_route: str
    gold_department: str | None = None
    gold_risk: str | None = None
    gold_red_flags: tuple[str, ...] = ()
    acceptable_doc_ids: tuple[str, ...] = ()
    structured_symptoms: tuple[str, ...] = ()
    duration: str | None = None
    severity: str | None = None


@dataclass
class BenchmarkOutcome:
    case_id: str
    task: str
    perturbation: int
    text: str
    gold_route: str
    predicted_route: str
    gold_department: str | None
    predicted_department: str | None
    gold_risk: str | None
    predicted_risk: str | None
    gold_red_flags: tuple[str, ...]
    predicted_red_flags: tuple[str, ...]
    acceptable_doc_ids: tuple[str, ...]
    evidence_doc_ids: tuple[str, ...]
    evidence_citation_ids: tuple[str, ...]
    evidence_departments: tuple[str, ...]
    evidence_scores: tuple[float, ...]
    abstained: bool
    followup_missing: tuple[str, ...]
    latency_ms: float
    error: str | None = None


FLAG_DEPARTMENTS = {
    "胸痛": "心血管内科",
    "呼吸困难": "呼吸内科",
    "气促": "呼吸内科",
    "咯血": "呼吸内科",
    "便血": "消化内科",
    "晕厥": "心血管内科",
    "意识不清": "神经内科",
    "大出血": "急诊科",
}

FLAG_EXPRESSIONS = {
    "胸痛": {
        "alias": ("胸痛", "胸痛", "胸痛", "胸痛"),
        "unseen": ("胸口像被压住一样疼", "心口突然疼得厉害", "胸前区压榨样疼", "胸口发紧并疼痛"),
    },
    "呼吸困难": {
        "alias": ("气短", "呼吸困难", "气短", "呼吸困难"),
        "unseen": ("突然喘不上来气", "感觉吸不进空气", "喘得说不出整句", "呼吸很费劲"),
    },
    "气促": {
        "alias": ("气促", "气促", "气促", "气促"),
        "unseen": ("呼吸特别急", "喘得越来越快", "上气不接下气", "喘得停不下来"),
    },
    "咯血": {
        "alias": ("咳血", "咳出血", "咳出了血", "咯血"),
        "unseen": ("痰里出现红色血丝", "咳出的痰是红色的", "咳嗽时带出鲜红液体", "痰液带明显血色"),
    },
    "便血": {
        "alias": ("黑便", "便血", "黑便", "便血"),
        "unseen": ("排便后看到鲜红色血液", "大便表面带红色血迹", "厕所里出现红色血水", "排便纸上沾了很多血"),
    },
    "晕厥": {
        "alias": ("昏倒", "晕厥", "昏倒", "晕厥"),
        "unseen": ("眼前一黑倒在地上", "突然失去知觉后又醒来", "人一下子倒下没有反应", "短暂断片并跌倒"),
    },
    "意识不清": {
        "alias": ("意识不清", "意识不清", "意识不清", "意识不清"),
        "unseen": ("神志模糊叫不醒", "答非所问并反应迟钝", "整个人迷迷糊糊无法交流", "叫他名字也没有正常反应"),
    },
    "大出血": {
        "alias": ("大出血", "大出血", "大出血", "大出血"),
        "unseen": ("伤口一直血流不止", "外伤后流了非常多的血", "纱布很快被血浸透", "伤口喷出大量鲜血"),
    },
}


EVIDENCE_PROFILES = {
    "心血管内科": (
        ("反复心慌，能感觉到心跳忽快忽慢", ("card-palpitations-nhs", "card-arrhythmias-nhlbi")),
        ("多次测量血压偏高，还会头胀", ("card-hypertension-overview", "card-high-blood-pressure-medlineplus", "card-high-blood-pressure-nhlbi")),
        ("双脚踝容易肿，活动耐力也明显下降", ("card-heart-failure-medlineplus", "card-heart-failure-nhlbi")),
        ("走一段路小腿就酸疼，休息后会缓解", ("card-peripheral-artery-disease", "card-peripheral-artery-medlineplus")),
        ("活动时胸口发紧，停下来休息会减轻", ("card-angina-medlineplus", "card-coronary-heart-disease")),
        ("体检提示胆固醇持续偏高", ("card-cholesterol-medlineplus",)),
        ("检查提到心脏杂音，最近容易疲劳", ("card-heart-valve-diseases-medlineplus",)),
        ("最近经常心跳漏一拍并伴轻微头晕", ("card-palpitations-nhs", "card-arrhythmias-nhlbi")),
    ),
    "呼吸内科": (
        ("夜间反复喘息和咳嗽，运动后更明显", ("resp-asthma-overview", "resp-asthma-nhs", "resp-asthma-nhlbi", "resp-asthma-medlineplus")),
        ("长期咳嗽咳痰，爬楼后容易喘", ("resp-copd-overview", "resp-copd-nhs", "resp-copd-nhlbi", "resp-copd-medlineplus")),
        ("发热后咳嗽加重，痰变得黏稠发黄", ("resp-pneumonia-medlineplus", "resp-pneumonia-nhlbi", "resp-pneumonia-nhs")),
        ("咳嗽伴喉咙痛、乏力和全身酸痛", ("resp-flu-medlineplus", "resp-influenza-seasonal-who")),
        ("睡觉打鼾很重，白天总是困倦", ("resp-sleep-apnea-medlineplus", "resp-sleep-apnea-nhlbi")),
        ("咳嗽很久并有夜间出汗和食欲下降", ("resp-tuberculosis-medlineplus", "resp-tuberculosis-who")),
        ("感冒后一直干咳，讲话多时更明显", ("resp-cough-nhs", "resp-bronchitis-medlineplus", "resp-bronchitis-nhs")),
        ("接触烟雾后咳嗽和喘息明显加重", ("resp-household-air-pollution", "resp-ambient-air-pollution")),
    ),
    "消化内科": (
        ("餐后反酸烧心，平躺时更明显", ("gast-reflux-symptoms",)),
        ("突然腹泻和呕吐，肚子一阵阵绞痛", ("gast-viral-gastroenteritis-niddk", "gast-diarrhea-warning-signs")),
        ("排便次数减少，大便干硬且费力", ("gast-constipation-medlineplus", "gast-constipation-niddk")),
        ("喝牛奶后腹胀、排气多并容易腹泻", ("gast-lactose-intolerance-niddk",)),
        ("腹部反复不适，排便后会有所缓解", ("gast-ibs-medlineplus", "gast-ibs-niddk")),
        ("吃油腻食物后右上腹疼并伴恶心", ("gast-gallstones-medlineplus", "gast-gallstones-niddk")),
        ("吃含面食的食物后腹胀腹泻，体重下降", ("gast-celiac-medlineplus", "gast-celiac-niddk")),
        ("上腹部有灼烧感，空腹时更明显", ("gast-peptic-ulcer-medlineplus",)),
    ),
    "皮肤科": (
        ("皮肤反复瘙痒干燥，并有红斑脱屑", ("derm-atopic-eczema", "derm-eczema-medlineplus")),
        ("身上突然出现一片片发痒的隆起风团", ("derm-hives", "derm-hives-medlineplus")),
        ("脸上反复长粉刺和红色丘疹", ("derm-acne-medlineplus", "derm-acne-nhs")),
        ("肘部出现边界清楚的红斑和银白色皮屑", ("derm-psoriasis-medlineplus", "derm-psoriasis-nhs", "derm-psoriasis-niams")),
        ("皮肤出现环形红疹，边缘更痒", ("derm-ringworm-nhs", "derm-fungal-infections-medlineplus")),
        ("接触清洁剂后手背发红、发痒并起小疹子", ("derm-contact-dermatitis-nhs",)),
        ("面颊长期发红发热，还能看到细小血丝", ("derm-rosacea-medlineplus", "derm-rosacea-nhs")),
        ("夜里皮肤特别痒，指缝也出现小疹子", ("derm-scabies-medlineplus", "derm-scabies-nhs", "derm-scabies-who")),
    ),
}

UNSUPPORTED_PROFILES = (
    "右膝关节活动时疼，上下楼更明显",
    "耳朵疼并伴有耳鸣和听力下降",
    "看东西有些模糊，眼睛还很干涩",
    "牙龈肿痛，咬东西时更疼",
    "尿频尿急，但没有发热",
    "月经周期紊乱并有下腹坠胀",
    "颈肩酸痛，手指偶尔发麻",
    "情绪持续低落，晚上也睡不好",
    "孩子晚上经常磨牙，白天精神一般",
    "脚踝扭伤后肿胀，走路时疼",
)

INSUFFICIENT_PROFILES = (
    ("最近不太舒服", ()),
    ("感觉状态不太对", ()),
    ("身体有点难受", ()),
    ("想咨询一下健康问题", ()),
    ("今天精神不太好", ()),
    ("总觉得哪里不舒服", ()),
    ("身体有些异常", ()),
    ("想问问这是不是有问题", ()),
    ("有点头痛", ("头痛",)),
    ("最近咳嗽", ("咳嗽",)),
    ("皮肤有点痒", ("皮肤瘙痒",)),
    ("肚子不舒服", ("腹部不适",)),
    ("偶尔心慌", ("心悸",)),
    ("没有胃口", ("食欲下降",)),
    ("腰有点酸", ("腰酸",)),
    ("鼻子堵", ("鼻塞",)),
)


def _noisy_text(value: str) -> str:
    replacements = {
        "反复": "反腹",
        "瘙痒": "骚痒",
        "心慌": "心荒",
        "心跳": "心眺",
        "咳嗽": "咳塑",
        "腹泻": "腹泄",
        "血压": "血鸭",
        "干燥": "干躁",
        "肿胀": "肿涨",
        "头晕": "头昏",
    }
    for source, target in replacements.items():
        if source in value:
            return value.replace(source, target, 1)
    midpoint = max(1, len(value) // 2)
    return value[:midpoint] + "、" + value[midpoint:]


def _evidence_variant(phrase: str, level: int, variant: int) -> str:
    if level == 0:
        return (f"{phrase}，已经持续三天。" if variant == 0 else f"主要情况是{phrase}，程度中等。")
    if level == 1:
        return (f"这阵子老是{phrase}，想知道该挂哪个科。" if variant == 0 else f"我说得口语一点：{phrase}，最近挺困扰。")
    if level == 2:
        background = ("最近工作忙、睡眠少，" if variant == 0 else "这段时间饮食和作息不规律，")
        return background + "但主要不适是" + phrase + "。"
    if level == 3:
        distractor = ("另外偶尔胃口不好" if variant == 0 else "同时有一点鼻塞")
        return f"主要是{phrase}；{distractor}，但前面的表现更明显。"
    return f"描述里可能有错别字：{_noisy_text(phrase)}，想做分诊。"


def _low_evidence_variant(phrase: str, level: int, variant: int) -> str:
    if level == 0:
        return f"{phrase}，已经两天。"
    if level == 1:
        return f"说得简单点就是{phrase}，该看什么科？"
    if level == 2:
        prefix = ("最近加班比较多，" if variant % 2 == 0 else "最近饮食作息一般，")
        return prefix + phrase + "。"
    if level == 3:
        return f"没有胸痛，也没有呼吸困难，主要是{phrase}。"
    return f"输入可能有错字：{_noisy_text(phrase)}。"


def generate_cases(seed: int = SEED) -> list[BenchmarkCase]:
    rng = random.Random(seed)
    cases: list[BenchmarkCase] = []

    wrappers = (
        "患者描述：{}。",
        "家属说刚才出现{}。",
        "我现在主要是{}。",
        "症状记录为{}。",
        "今天突然有{}。",
        "目前最明显的是{}。",
        "刚刚发现{}。",
        "需要分诊，表现为{}。",
    )
    other_flags = tuple(FLAG_DEPARTMENTS)
    high_index = 1
    for flag, department in FLAG_DEPARTMENTS.items():
        profile = FLAG_EXPRESSIONS[flag]
        for level in range(5):
            for variant in range(8):
                if level == 0:
                    expression = flag
                elif level == 1:
                    expression = profile["alias"][variant % len(profile["alias"])]
                elif level == 2:
                    expression = f"{flag}，同时伴有出汗和乏力"
                elif level == 3:
                    other = other_flags[(other_flags.index(flag) + variant + 1) % len(other_flags)]
                    expression = f"开始没有{other}，不过后来明确出现{flag}"
                else:
                    expression = profile["unseen"][variant % len(profile["unseen"])]
                text = wrappers[variant].format(expression)
                cases.append(BenchmarkCase(
                    case_id=f"HR{high_index:04d}",
                    task="high_risk",
                    perturbation=level,
                    text=text,
                    gold_route="high_risk_fast_path",
                    gold_department=department,
                    gold_risk="高",
                    gold_red_flags=(flag,),
                ))
                high_index += 1

    evidence_index = 1
    for department, profiles in EVIDENCE_PROFILES.items():
        for level in range(5):
            order = list(range(len(profiles)))
            rng.shuffle(order)
            for position, profile_index in enumerate(order):
                phrase, acceptable = profiles[profile_index]
                for variant in range(2):
                    cases.append(BenchmarkCase(
                        case_id=f"EV{evidence_index:04d}",
                        task="evidence_triage",
                        perturbation=level,
                        text=_evidence_variant(phrase, level, variant),
                        gold_route="evidence_triage",
                        gold_department=department,
                        gold_risk="中",
                        acceptable_doc_ids=acceptable,
                        structured_symptoms=(phrase,),
                        duration="三天",
                    ))
                    evidence_index += 1

    low_index = 1
    for level in range(5):
        for profile_index, phrase in enumerate(UNSUPPORTED_PROFILES):
            for variant in range(4):
                cases.append(BenchmarkCase(
                    case_id=f"LE{low_index:04d}",
                    task="low_evidence",
                    perturbation=level,
                    text=_low_evidence_variant(phrase, level, variant),
                    gold_route="low_evidence_abstain",
                    gold_department=ABSTAIN_DEPARTMENT,
                    gold_risk="低",
                    structured_symptoms=(phrase,),
                    duration="两天",
                ))
                low_index += 1

    followup_index = 1
    for level in range(5):
        order = list(range(len(INSUFFICIENT_PROFILES)))
        rng.shuffle(order)
        for profile_index in order:
            phrase, symptoms = INSUFFICIENT_PROFILES[profile_index]
            for variant in range(2):
                if level == 0:
                    text = phrase + "。"
                elif level == 1:
                    text = ("想简单问一下，" if variant == 0 else "我不太会描述，") + phrase + "。"
                elif level == 2:
                    text = "最近工作和作息有变化，但现在只知道" + phrase + "。"
                elif level == 3:
                    text = "没有胸痛，也没有呼吸困难，只是" + phrase + "。"
                else:
                    text = _noisy_text(phrase) + "。"
                cases.append(BenchmarkCase(
                    case_id=f"FU{followup_index:04d}",
                    task="insufficient_followup",
                    perturbation=level,
                    text=text,
                    gold_route="ask_followup",
                    structured_symptoms=symptoms,
                ))
                followup_index += 1

    if len(cases) != 1000:
        raise AssertionError(f"expected 1000 cases, generated {len(cases)}")
    if len({case.case_id for case in cases}) != len(cases):
        raise AssertionError("case IDs must be unique")
    return cases


def _base_outcome(
    case: BenchmarkCase,
    *,
    predicted_route: str,
    predicted_department: str | None,
    predicted_risk: str | None,
    predicted_red_flags: tuple[str, ...],
    latency_ms: float,
    abstained: bool = False,
    followup_missing: tuple[str, ...] = (),
    evidence_doc_ids: tuple[str, ...] = (),
    evidence_citation_ids: tuple[str, ...] = (),
    evidence_departments: tuple[str, ...] = (),
    evidence_scores: tuple[float, ...] = (),
    error: str | None = None,
) -> BenchmarkOutcome:
    return BenchmarkOutcome(
        case_id=case.case_id,
        task=case.task,
        perturbation=case.perturbation,
        text=case.text,
        gold_route=case.gold_route,
        predicted_route=predicted_route,
        gold_department=case.gold_department,
        predicted_department=predicted_department,
        gold_risk=case.gold_risk,
        predicted_risk=predicted_risk,
        gold_red_flags=case.gold_red_flags,
        predicted_red_flags=predicted_red_flags,
        acceptable_doc_ids=case.acceptable_doc_ids,
        evidence_doc_ids=evidence_doc_ids,
        evidence_citation_ids=evidence_citation_ids,
        evidence_departments=evidence_departments,
        evidence_scores=evidence_scores,
        abstained=abstained,
        followup_missing=followup_missing,
        latency_ms=round(latency_ms, 3),
        error=error,
    )


def _screen_and_followup(cases: Iterable[BenchmarkCase]) -> tuple[list[BenchmarkOutcome], list[BenchmarkCase]]:
    outcomes: list[BenchmarkOutcome] = []
    retrieval_cases: list[BenchmarkCase] = []
    for case in cases:
        started = perf_counter()
        screening = screen_for_emergency(case.text)
        if screening.matched:
            triage = screening.triage
            assert triage is not None
            outcomes.append(_base_outcome(
                case,
                predicted_route="high_risk_fast_path",
                predicted_department=triage.department,
                predicted_risk=triage.risk_level,
                predicted_red_flags=tuple(screening.matched_terms),
                latency_ms=(perf_counter() - started) * 1000,
                abstained=triage.abstained,
            ))
            continue

        if case.task == "insufficient_followup":
            symptoms = StructuredSymptoms(
                symptoms=case.structured_symptoms,
                duration=case.duration,
                severity=case.severity,
                red_flags=(),
                raw_text=case.text,
            )
            sufficient = is_sufficient(symptoms)
            followup = None if sufficient else build_followup(symptoms)
            outcomes.append(_base_outcome(
                case,
                predicted_route="unexpected_sufficient" if sufficient else "ask_followup",
                predicted_department=None,
                predicted_risk=None,
                predicted_red_flags=(),
                followup_missing=tuple(followup.missing) if followup else (),
                latency_ms=(perf_counter() - started) * 1000,
            ))
            continue

        retrieval_cases.append(case)
    return outcomes, retrieval_cases


async def _run_retrieval_case(case: BenchmarkCase, semaphore: asyncio.Semaphore) -> BenchmarkOutcome:
    try:
        async with semaphore:
            started = perf_counter()
            evidence = await retrieve(case.text, top_k=3, embed_fn=embed)
            symptoms = StructuredSymptoms(
                symptoms=case.structured_symptoms,
                duration=case.duration,
                severity=case.severity,
                red_flags=(),
                raw_text=case.text,
            )
            triage = classify(symptoms, evidence)
            route = "low_evidence_abstain" if triage.abstained else "evidence_triage"
            return _base_outcome(
                case,
                predicted_route=route,
                predicted_department=triage.department,
                predicted_risk=triage.risk_level,
                predicted_red_flags=(),
                evidence_doc_ids=tuple(item.doc_id for item in evidence),
                evidence_citation_ids=tuple(item.citation_id for item in evidence),
                evidence_departments=tuple(item.department for item in evidence),
                evidence_scores=tuple(item.score for item in evidence),
                abstained=triage.abstained,
                latency_ms=(perf_counter() - started) * 1000,
            )
    except Exception as exc:
        return _base_outcome(
            case,
            predicted_route="error",
            predicted_department=None,
            predicted_risk=None,
            predicted_red_flags=(),
            latency_ms=0.0,
            error=f"{type(exc).__name__}: {exc}",
        )


async def run_benchmark(cases: list[BenchmarkCase], concurrency: int) -> list[BenchmarkOutcome]:
    outcomes, retrieval_cases = _screen_and_followup(cases)
    semaphore = asyncio.Semaphore(concurrency)
    completed: list[BenchmarkOutcome] = []
    batch_size = max(10, concurrency * 4)
    for offset in range(0, len(retrieval_cases), batch_size):
        batch = retrieval_cases[offset:offset + batch_size]
        completed.extend(await asyncio.gather(*(
            _run_retrieval_case(case, semaphore) for case in batch
        )))
        done = min(offset + len(batch), len(retrieval_cases))
        print(f"retrieval progress: {done}/{len(retrieval_cases)}", flush=True)
    outcomes.extend(completed)
    by_id = {outcome.case_id: outcome for outcome in outcomes}
    return [by_id[case.case_id] for case in cases]


def _safe_ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _percentile(values: Sequence[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = (len(ordered) - 1) * percentile
    lower = int(math.floor(position))
    upper = int(math.ceil(position))
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] * (1 - weight) + ordered[upper] * weight


def _classification_report(
    gold: Sequence[str], predicted: Sequence[str | None], labels: Sequence[str]
) -> tuple[dict[str, dict[str, float | int]], float, float]:
    report: dict[str, dict[str, float | int]] = {}
    f1_values: list[float] = []
    correct = 0
    for label in labels:
        tp = sum(g == label and p == label for g, p in zip(gold, predicted))
        fp = sum(g != label and p == label for g, p in zip(gold, predicted))
        fn = sum(g == label and p != label for g, p in zip(gold, predicted))
        support = sum(g == label for g in gold)
        precision = _safe_ratio(tp, tp + fp)
        recall = _safe_ratio(tp, tp + fn)
        f1 = _safe_ratio(2 * precision * recall, precision + recall)
        report[label] = {
            "precision": round(precision, 4),
            "recall": round(recall, 4),
            "f1": round(f1, 4),
            "support": support,
        }
        f1_values.append(f1)
    correct = sum(g == p for g, p in zip(gold, predicted))
    return report, round(statistics.fmean(f1_values), 4), round(_safe_ratio(correct, len(gold)), 4)


def compute_metrics(cases: list[BenchmarkCase], outcomes: list[BenchmarkOutcome]) -> dict:
    case_by_id = {case.case_id: case for case in cases}
    high = [outcome for outcome in outcomes if outcome.task == "high_risk"]
    non_high = [outcome for outcome in outcomes if outcome.task != "high_risk"]
    evidence = [outcome for outcome in outcomes if outcome.task == "evidence_triage"]
    low = [outcome for outcome in outcomes if outcome.task == "low_evidence"]
    followup = [outcome for outcome in outcomes if outcome.task == "insufficient_followup"]
    classification = evidence + low
    risk_cases = high + evidence + low

    safety_hits = sum(
        set(outcome.gold_red_flags).issubset(set(outcome.predicted_red_flags))
        for outcome in high
    )
    safety_false_positives = sum(bool(outcome.predicted_red_flags) for outcome in non_high)

    department_labels = tuple(EVIDENCE_PROFILES) + (ABSTAIN_DEPARTMENT,)
    department_report, department_macro_f1, department_accuracy = _classification_report(
        [outcome.gold_department or "" for outcome in classification],
        [outcome.predicted_department for outcome in classification],
        department_labels,
    )
    supported_department_labels = tuple(EVIDENCE_PROFILES)
    supported_report, supported_macro_f1, supported_accuracy = _classification_report(
        [outcome.gold_department or "" for outcome in evidence],
        [outcome.predicted_department for outcome in evidence],
        supported_department_labels,
    )
    risk_labels = ("低", "中", "高")
    risk_report, risk_macro_f1, risk_accuracy = _classification_report(
        [outcome.gold_risk or "" for outcome in risk_cases],
        [outcome.predicted_risk for outcome in risk_cases],
        risk_labels,
    )

    evidence_hits = 0
    reciprocal_ranks: list[float] = []
    for outcome in evidence:
        acceptable = set(outcome.acceptable_doc_ids)
        rank = next(
            (index + 1 for index, doc_id in enumerate(outcome.evidence_doc_ids) if doc_id in acceptable),
            None,
        )
        if rank is not None:
            evidence_hits += 1
            reciprocal_ranks.append(1 / rank)
        else:
            reciprocal_ranks.append(0.0)

    flag_metrics: dict[str, dict[str, float | int]] = {}
    for flag in FLAG_DEPARTMENTS:
        members = [outcome for outcome in high if flag in outcome.gold_red_flags]
        hits = sum(flag in outcome.predicted_red_flags for outcome in members)
        flag_metrics[flag] = {
            "support": len(members),
            "recall": round(_safe_ratio(hits, len(members)), 4),
            "misses": len(members) - hits,
        }

    perturbation_metrics: list[dict] = []
    for level, name in enumerate(PERTURBATION_NAMES):
        high_level = [outcome for outcome in high if outcome.perturbation == level]
        evidence_level = [outcome for outcome in evidence if outcome.perturbation == level]
        low_level = [outcome for outcome in low if outcome.perturbation == level]
        followup_level = [outcome for outcome in followup if outcome.perturbation == level]
        perturbation_metrics.append({
            "level": level,
            "name": name,
            "safety_recall": round(_safe_ratio(
                sum(set(o.gold_red_flags).issubset(set(o.predicted_red_flags)) for o in high_level),
                len(high_level),
            ), 4),
            "department_accuracy": round(_safe_ratio(
                sum(o.gold_department == o.predicted_department for o in evidence_level),
                len(evidence_level),
            ), 4),
            "abstain_rate": round(_safe_ratio(
                sum(o.predicted_route == "low_evidence_abstain" for o in low_level),
                len(low_level),
            ), 4),
            "followup_trigger_rate": round(_safe_ratio(
                sum(o.predicted_route == "ask_followup" for o in followup_level),
                len(followup_level),
            ), 4),
            "sample_sizes": {
                "high_risk": len(high_level),
                "evidence_triage": len(evidence_level),
                "low_evidence": len(low_level),
                "insufficient_followup": len(followup_level),
            },
        })

    confusion = []
    for gold_label in department_labels:
        row = []
        for predicted_label in department_labels:
            row.append(sum(
                outcome.gold_department == gold_label
                and outcome.predicted_department == predicted_label
                for outcome in classification
            ))
        confusion.append(row)

    latency_by_task = {}
    for task in ("high_risk", "evidence_triage", "low_evidence", "insufficient_followup"):
        values = [outcome.latency_ms for outcome in outcomes if outcome.task == task and not outcome.error]
        latency_by_task[task] = {
            "count": len(values),
            "p50_ms": round(statistics.median(values), 3) if values else 0.0,
            "p95_ms": round(_percentile(values, 0.95), 3),
            "mean_ms": round(statistics.fmean(values), 3) if values else 0.0,
        }

    return {
        "benchmark": {
            "name": "MedPilot 1000-case synthetic engineering benchmark",
            "generator_version": GENERATOR_VERSION,
            "seed": SEED,
            "case_count": len(cases),
            "data_boundary": "固定种子生成的人工构造工程测试集，不是临床病例或临床验证数据",
        },
        "case_distribution": {
            "high_risk": len(high),
            "evidence_triage": len(evidence),
            "low_evidence": len(low),
            "insufficient_followup": len(followup),
        },
        "safety": {
            "support": len(high),
            "recall": round(_safe_ratio(safety_hits, len(high)), 4),
            "false_negatives": len(high) - safety_hits,
            "non_high_support": len(non_high),
            "false_positives": safety_false_positives,
            "specificity": round(1 - _safe_ratio(safety_false_positives, len(non_high)), 4),
            "by_flag": flag_metrics,
        },
        "department": {
            "support": len(classification),
            "labels": department_labels,
            "macro_f1": department_macro_f1,
            "accuracy": department_accuracy,
            "per_label": department_report,
            "confusion_matrix": confusion,
            "supported_only": {
                "support": len(evidence),
                "labels": supported_department_labels,
                "macro_f1": supported_macro_f1,
                "accuracy": supported_accuracy,
                "per_label": supported_report,
            },
        },
        "risk": {
            "support": len(risk_cases),
            "labels": risk_labels,
            "macro_f1": risk_macro_f1,
            "accuracy": risk_accuracy,
            "per_label": risk_report,
        },
        "retrieval": {
            "support": len(evidence),
            "evidence_return_rate": round(_safe_ratio(
                sum(bool(outcome.evidence_doc_ids) for outcome in evidence), len(evidence)
            ), 4),
            "recall_at_3": round(_safe_ratio(evidence_hits, len(evidence)), 4),
            "mrr_at_3": round(statistics.fmean(reciprocal_ranks), 4) if reciprocal_ranks else 0.0,
        },
        "fallback_and_followup": {
            "low_evidence_support": len(low),
            "low_evidence_abstain_rate": round(_safe_ratio(
                sum(outcome.predicted_route == "low_evidence_abstain" for outcome in low), len(low)
            ), 4),
            "followup_support": len(followup),
            "followup_trigger_rate": round(_safe_ratio(
                sum(outcome.predicted_route == "ask_followup" for outcome in followup), len(followup)
            ), 4),
        },
        "perturbation_metrics": perturbation_metrics,
        "latency_by_task": latency_by_task,
        "errors": {
            "count": sum(bool(outcome.error) for outcome in outcomes),
            "case_ids": [outcome.case_id for outcome in outcomes if outcome.error],
        },
    }


def _json_ready_case(case: BenchmarkCase) -> dict:
    payload = asdict(case)
    payload["perturbation_name"] = PERTURBATION_NAMES[case.perturbation]
    return payload


def _json_ready_outcome(outcome: BenchmarkOutcome) -> dict:
    payload = asdict(outcome)
    payload["perturbation_name"] = PERTURBATION_NAMES[outcome.perturbation]
    return payload


def write_outputs(
    cases: list[BenchmarkCase], outcomes: list[BenchmarkOutcome], output_dir: Path
) -> dict:
    output_dir.mkdir(parents=True, exist_ok=True)
    metrics = compute_metrics(cases, outcomes)
    metrics["execution"] = {
        "completed_at_utc": datetime.now(timezone.utc).isoformat(),
        "python": platform.python_version(),
        "platform": platform.platform(),
        "embedding_model": EMBED_MODEL,
        "retrieval_top_k": 3,
    }
    (output_dir / "benchmark_cases.json").write_text(
        json.dumps([_json_ready_case(case) for case in cases], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "case_outcomes.json").write_text(
        json.dumps([_json_ready_outcome(outcome) for outcome in outcomes], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (output_dir / "metrics.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    with (output_dir / "case_outcomes.csv").open("w", encoding="utf-8-sig", newline="") as handle:
        fieldnames = (
            "case_id", "task", "perturbation", "perturbation_name", "text",
            "gold_route", "predicted_route", "gold_department", "predicted_department",
            "gold_risk", "predicted_risk", "gold_red_flags", "predicted_red_flags",
            "acceptable_doc_ids", "evidence_doc_ids", "evidence_citation_ids",
            "evidence_departments", "evidence_scores", "abstained", "followup_missing",
            "latency_ms", "error",
        )
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        for outcome in outcomes:
            payload = _json_ready_outcome(outcome)
            for key in (
                "gold_red_flags", "predicted_red_flags", "acceptable_doc_ids",
                "evidence_doc_ids", "evidence_citation_ids", "evidence_departments",
                "evidence_scores", "followup_missing",
            ):
                payload[key] = " | ".join(str(value) for value in payload[key])
            writer.writerow({key: payload.get(key, "") for key in fieldnames})
    return metrics


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "evaluation-results-1000",
    )
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--generate-only", action="store_true")
    args = parser.parse_args(argv)
    if args.seed != SEED:
        parser.error(f"this benchmark version is frozen to seed {SEED}")
    if args.concurrency < 1 or args.concurrency > 16:
        parser.error("--concurrency must be between 1 and 16")

    cases = generate_cases(args.seed)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "benchmark_cases.json").write_text(
        json.dumps([_json_ready_case(case) for case in cases], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    if args.generate_only:
        print(f"generated {len(cases)} cases at {args.output_dir}")
        return

    outcomes = asyncio.run(run_benchmark(cases, args.concurrency))
    metrics = write_outputs(cases, outcomes, args.output_dir)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
