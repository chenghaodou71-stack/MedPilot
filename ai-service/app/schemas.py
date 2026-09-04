"""四深模块的输入/输出契约。全部 frozen，保证不可变。"""
from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field


class StructuredSymptoms(BaseModel):
    """症状抽取模块输出。"""
    model_config = {"frozen": True}

    symptoms: tuple[str, ...] = Field(default_factory=tuple, description="症状名列表")
    duration: str | None = Field(default=None, description="持续时间")
    severity: str | None = Field(default=None, description="严重程度")
    history: tuple[str, ...] = Field(default_factory=tuple, description="既往史")
    red_flags: tuple[str, ...] = Field(default_factory=tuple, description="命中的危险信号")
    raw_text: str = Field(default="", description="原始输入")


class RankedEvidence(BaseModel):
    """RAG 检索模块输出的单条证据。"""
    model_config = {"frozen": True}

    citation_id: str
    doc_id: str
    chunk_id: str
    department: str
    source: str
    source_type: str = ""
    quote: str
    score: float
    index_version: str
    institution: str = ""
    title: str = ""
    url: str = ""
    published_date: str = ""
    version: str = ""
    license: str = ""
    review_status: str = ""


class TriageFactor(BaseModel):
    """结构化分诊依据，供解释展示；不是疾病诊断结论。"""
    model_config = {"frozen": True}

    kind: Literal["rule", "evidence"]
    label: str = Field(min_length=1, max_length=128)
    reference: str = Field(default="", max_length=256)
    support: float = Field(ge=0.0, le=1.0)
    detail: str = Field(default="", max_length=512)


class TriageResult(BaseModel):
    """辅助分诊模块输出。"""
    model_config = {"frozen": True}

    department: Literal[
        "呼吸内科",
        "消化内科",
        "心血管内科",
        "皮肤科",
        "神经内科",
        "急诊科",
        "全科/建议线下分诊台",
    ] = Field(description="推荐科室")
    risk_level: Literal["低", "中", "高"] = Field(description="风险等级：低/中/高")
    confidence: float = Field(ge=0.0, le=1.0, description="置信度 0~1")
    urgency: Literal[
        "建议立即就医或呼叫急救",
        "建议立即就医",
        "建议尽快就医",
        "建议立即呼叫急救",
        "建议尽早于门诊就诊",
    ] = Field(description="建议就医时效")
    matched_rule: str | None = Field(default=None, description="命中的分诊规则名")
    support_score: float = Field(
        default=0.0,
        ge=0.0,
        le=1.0,
        description="规则或检索证据支持分，不代表临床准确率",
    )
    factors: tuple[TriageFactor, ...] = Field(
        default_factory=tuple,
        description="可解释分诊依据列表",
    )
    abstained: bool = Field(
        default=False,
        description="是否因证据不足暂缓科室判断",
    )
    explanation: str = Field(
        default="",
        max_length=512,
        description="面向用户的安全解释，不得替代诊断",
    )


class ComposedAnswer(BaseModel):
    """回答编排模块输出。"""
    model_config = {"frozen": True}

    text: str = Field(description="自然语言回答")
    citations: tuple[RankedEvidence, ...] = Field(
        default_factory=tuple,
        description="与检索分片逐项对应的不可变引用快照",
    )
    safety_boundary: str = Field(description="安全边界声明")


class FollowUpQuestion(BaseModel):
    """症状不足时的追问输出。"""
    model_config = {"frozen": True}

    question: str = Field(description="向用户追问的具体问题")
    missing: tuple[str, ...] = Field(
        default_factory=tuple,
        description="缺失信息类别：symptoms / duration / severity / more_symptoms",
    )
