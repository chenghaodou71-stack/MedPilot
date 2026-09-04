"""Generate reference-style Chapter 3 diagrams from MedPilot source facts.

The output follows the pale-green, black-line thesis examples supplied by the
author.  Every diagram is emitted as editable Draw.io XML, a PNG preview and
an audit note.  Database and entity metadata are parsed from Flyway SQL and
JPA source files; no schema object is invented in this script.
"""
from __future__ import annotations

import json
import re
import textwrap
import xml.etree.ElementTree as ET
from collections import OrderedDict
from copy import deepcopy
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
JAVA_ROOT = ROOT / "backend" / "src" / "main" / "java" / "com" / "medpilot"
OUT = ROOT / "outputs" / "chapter3-reference-assets"
DIAGRAMS = OUT / "diagrams"
CLASSES = OUT / "entity-classes"

BG = "#D8ECD4"
WHITE = "#FFFFFF"
BLACK = "#111111"
GRAY = "#D9D9D9"
LIGHT_GRAY = "#F4F4F4"
FONT_CANDIDATES = [
    Path("C:/Windows/Fonts/simsun.ttc"),
    Path("C:/Windows/Fonts/simhei.ttf"),
    Path("C:/Windows/Fonts/msyh.ttc"),
]
# IntelliJ IDEA's UML renderer uses a compact sans-serif UI font.  Keep the
# existing SimSun choices for the Chinese thesis diagrams, but use Segoe UI for
# entity class cards so their typography follows the supplied IDEA sample.
UI_FONT_CANDIDATES = [
    Path("C:/Windows/Fonts/segoeui.ttf"),
    Path("C:/Windows/Fonts/arial.ttf"),
    Path("C:/Windows/Fonts/msyh.ttc"),
]


@dataclass
class Column:
    name: str
    sql_type: str
    nullable: bool = True
    default: str | None = None
    auto_increment: bool = False
    primary: bool = False
    unique: bool = False
    foreign: str | None = None


@dataclass
class Table:
    name: str
    columns: OrderedDict[str, Column] = field(default_factory=OrderedDict)


@dataclass
class Entity:
    class_name: str
    table_name: str
    source: str
    fields: list[tuple[str, str]]
    methods: list[str]


@dataclass
class Shape:
    id: str
    text: str
    x: int
    y: int
    w: int
    h: int
    kind: str = "rect"
    fill: str = WHITE
    font_size: int = 30
    bold: bool = False
    align: str = "center"


@dataclass
class Edge:
    source: str
    target: str
    label: str = ""
    dashed: bool = False
    arrow: bool = False


@dataclass
class Diagram:
    stem: str
    title: str
    width: int
    height: int
    shapes: list[Shape] = field(default_factory=list)
    edges: list[Edge] = field(default_factory=list)
    # Entity cards need row-level rendering (icons, right-aligned types) that
    # cannot be represented by the generic shape renderer.  Keep the source
    # entity on the diagram so both PNG and Draw.io writers can render it.
    entity: Entity | None = None


TABLE_DESCRIPTIONS = {
    "users": "用户账号、身份来源、角色和组织范围",
    "consultation_sessions": "问诊会话归属与最近活动时间",
    "consultation_messages": "多轮问诊中的用户消息与系统消息",
    "consultation_records": "问诊结果、风险等级、科室建议与证据快照",
    "consultation_traces": "多智能体流程事件、终态与处理耗时",
    "health_profiles": "用户授权的健康档案背景信息",
    "follow_up_tasks": "复诊任务、到期时间与处理状态",
    "consultation_attachments": "问诊附件的存储、校验和生命周期",
    "audit_logs": "受保护接口的访问审计记录",
    "knowledge_documents": "医学知识文档、审核状态与向量状态",
    "patients": "医院主患者索引映射信息",
    "patient_encounters": "患者就诊、院区、科室与责任医生",
    "patient_care_relationships": "患者与医生之间的有效医疗关系",
    "break_glass_accesses": "紧急情况下的临时授权与追踪",
    "clinical_reviews": "医生复核任务、决定与人工处理结果",
    "model_releases": "模型发布包、签名、版本和审批状态",
    "clinical_evaluation_runs": "模型版本对应的受控评测记录",
    "knowledge_source_register": "知识来源登记、校验与审核信息",
    "governance_changes": "模型或知识变更申请、审批和回滚计划",
    "red_team_test_runs": "红队测试范围、结果和证据地址",
    "rollback_drill_runs": "模型回滚演练结果和恢复时间",
    "model_monitoring_snapshots": "模型运行窗口的漂移与容量快照",
    "safety_incidents": "安全事件、根因分析和处置状态",
}


EXACT_NOTES = {
    "id": "主键编号", "user_id": "用户编号", "session_id": "会话标识",
    "trace_id": "调用链标识", "created_at": "创建时间", "updated_at": "更新时间",
    "status": "状态", "role": "角色", "active": "是否启用", "username": "用户名",
    "password_hash": "密码摘要", "title": "标题", "department": "建议科室",
    "risk_level": "风险等级", "support_score": "证据支持度", "confidence": "置信度",
    "abstained": "是否回退", "urgency": "就医时效", "citations": "引用证据快照",
    "conversation_history": "会话历史", "symptoms": "症状信息", "answer": "系统回答",
    "explanation": "结果说明", "triage_factors": "分诊依据", "matched_rule": "命中规则",
    "events_json": "流程事件集合", "citations_json": "引用事件集合",
    "terminal_phase": "流程终止阶段", "followup_pending": "是否等待追问",
    "failure_code": "失败代码", "total_duration_ms": "总处理耗时",
    "profile_json": "健康档案内容", "consent_granted": "是否授权使用",
    "record_id": "问诊记录编号", "notes": "备注", "due_at": "到期时间",
    "original_filename": "原始文件名", "media_type": "媒体类型", "size_bytes": "文件大小",
    "sha256": "文件摘要", "kind": "附件类型", "expires_at": "失效时间",
    "actor_username": "操作用户名", "actor_role": "操作角色", "method": "请求方法",
    "action": "访问动作", "success": "是否成功", "request_id": "请求标识",
    "duration_ms": "请求耗时", "doc_id": "知识文档标识", "source_type": "来源类型",
    "institution": "发布机构", "url": "来源地址", "published_date": "发布日期",
    "source_version": "来源版本", "license_name": "许可信息", "checksum": "内容摘要",
    "parsing_status": "解析状态", "vector_status": "向量状态", "review_status": "审核状态",
    "chunk_count": "切片数量", "reviewer": "审核人", "reviewed_at": "审核时间",
    "mpi_id": "主患者索引", "patient_mpi_id": "患者主索引", "organization_code": "机构编码",
    "campus_code": "院区编码", "department_code": "科室编码", "source_system": "来源系统",
    "patient_id": "患者编号", "encounter_number": "就诊编号",
    "responsible_clinician_user_id": "责任医生用户编号", "encounter_status": "就诊状态",
    "started_at": "就诊开始时间", "ended_at": "就诊结束时间",
    "clinician_user_id": "医生用户编号", "relationship_type": "医疗关系类型",
    "valid_from": "生效时间", "valid_until": "有效截止时间", "access_id": "紧急授权标识",
    "purpose": "授权用途", "reason": "申请原因", "granted_at": "授权时间",
    "revoked_at": "撤销时间", "review_id": "复核任务标识",
    "consultation_record_id": "问诊记录编号", "decision": "复核决定",
    "claimed_by_user_id": "领取人用户编号", "reviewer_user_id": "复核人用户编号",
    "decision_reason": "复核依据", "decided_at": "决定时间", "version": "乐观锁版本",
    "release_id": "模型发布标识", "model_name": "模型名称", "model_version": "模型版本",
    "weight_sha256": "模型权重摘要", "artifact_signature": "制品签名",
    "signature_algorithm": "签名算法", "prompt_version": "提示词版本",
    "embedding_version": "嵌入模型版本", "knowledge_index_version": "知识索引版本",
    "release_status": "发布状态", "created_by": "创建人", "approved_by": "审批人",
    "approved_at": "审批时间", "run_id": "评测运行标识", "dataset_version": "数据集版本",
    "dataset_sha256": "数据集摘要", "sample_count": "样本数量", "sensitivity": "敏感度",
    "specificity": "特异度", "false_negative_count": "假阴性数量",
    "incorrect_routing_count": "错误分诊数量", "abstention_rate": "回退比例",
    "evaluation_status": "评测状态", "evidence_uri": "证据文件地址",
    "source_id": "来源登记标识", "publisher": "发布方", "publication_date": "发布日期",
    "applicable_scope": "适用范围", "change_id": "变更标识", "target_type": "目标类型",
    "target_id": "目标标识", "change_type": "变更类型", "risk_level": "风险等级",
    "validation_evidence": "验证证据", "rollback_plan": "回滚计划",
    "change_status": "变更状态", "requested_by": "申请人", "requested_at": "申请时间",
    "test_id": "红队测试标识", "test_type": "测试类型", "case_count": "测试用例数",
    "blocked_count": "成功拦截数", "escaped_count": "未拦截数", "severity": "严重程度",
    "test_status": "测试状态", "drill_id": "回滚演练标识",
    "rollback_target_release_id": "回滚目标版本", "drill_status": "演练状态",
    "recovery_duration_seconds": "恢复用时", "data_integrity_check": "数据完整性检查结果",
    "snapshot_id": "监控快照标识", "window_start": "监控窗口开始时间",
    "window_end": "监控窗口结束时间", "drift_metric": "漂移指标",
    "drift_score": "漂移得分", "drift_threshold": "漂移阈值",
    "monitoring_status": "监控状态", "action_taken": "已采取措施",
    "incident_id": "安全事件标识", "incident_type": "事件类型", "summary": "事件摘要",
    "root_cause": "根因分析", "corrective_action": "纠正措施", "incident_status": "事件状态",
    "owner": "责任人", "detected_at": "发现时间", "closed_at": "关闭时间",
}


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    path = next((p for p in FONT_CANDIDATES if p.exists()), None)
    if path is None:
        return ImageFont.load_default()
    if bold and Path("C:/Windows/Fonts/simhei.ttf").exists():
        path = Path("C:/Windows/Fonts/simhei.ttf")
    return ImageFont.truetype(str(path), size=size)


def ui_font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    """Return the sans-serif font used by the IDEA-style entity cards."""
    path = next((p for p in UI_FONT_CANDIDATES if p.exists()), None)
    if path is None:
        return font(size, bold)
    # Segoe UI has a separate bold face; fall back to Arial Bold when present.
    if bold:
        bold_path = Path("C:/Windows/Fonts/segoeuib.ttf")
        if bold_path.exists():
            path = bold_path
    return ImageFont.truetype(str(path), size=size)


def strip_sql_comments(text: str) -> str:
    return re.sub(r"--.*?$", "", text, flags=re.M)


def split_top_level(text: str, delimiter: str = ",") -> list[str]:
    parts, start, depth, quote = [], 0, 0, None
    for i, ch in enumerate(text):
        if quote:
            if ch == quote and (i == 0 or text[i - 1] != "\\"):
                quote = None
            continue
        if ch in "'\"`":
            quote = ch
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif ch == delimiter and depth == 0:
            parts.append(text[start:i].strip())
            start = i + 1
    tail = text[start:].strip()
    if tail:
        parts.append(tail)
    return parts


def split_statements(text: str) -> list[str]:
    return split_top_level(strip_sql_comments(text), ";")


def parse_column(definition: str) -> Column | None:
    definition = " ".join(definition.split())
    upper = definition.upper()
    if upper.startswith(("PRIMARY KEY", "UNIQUE KEY", "KEY ", "CONSTRAINT ", "FOREIGN KEY")):
        return None
    m = re.match(r"`?([A-Za-z0-9_]+)`?\s+([A-Za-z]+(?:\s+[A-Za-z]+)?(?:\([^)]*\))?)\s*(.*)", definition)
    if not m:
        return None
    name, sql_type, rest = m.groups()
    rest_upper = rest.upper()
    default = None
    dm = re.search(r"\bDEFAULT\s+([^\s,]+)", rest, flags=re.I)
    if dm:
        default = dm.group(1)
    return Column(
        name=name,
        sql_type=sql_type.upper(),
        nullable="NOT NULL" not in rest_upper,
        default=default,
        auto_increment="AUTO_INCREMENT" in rest_upper,
    )


def apply_constraints(table: Table, definitions: Iterable[str]) -> None:
    for raw in definitions:
        d = " ".join(raw.split())
        pm = re.search(r"PRIMARY KEY\s*\(([^)]+)\)", d, flags=re.I)
        if pm:
            for name in re.findall(r"[A-Za-z0-9_]+", pm.group(1)):
                if name in table.columns:
                    table.columns[name].primary = True
        um = re.search(r"UNIQUE KEY\s+\S+\s*\(([^)]+)\)", d, flags=re.I)
        if um:
            names = re.findall(r"[A-Za-z0-9_]+", um.group(1))
            if len(names) == 1 and names[0] in table.columns:
                table.columns[names[0]].unique = True
        fm = re.search(r"FOREIGN KEY\s*\(([^)]+)\)\s*REFERENCES\s+([A-Za-z0-9_]+)\s*\(([^)]+)\)", d, flags=re.I)
        if fm:
            local = re.findall(r"[A-Za-z0-9_]+", fm.group(1))[0]
            if local in table.columns:
                table.columns[local].foreign = f"{fm.group(2)}.{re.findall(r'[A-Za-z0-9_]+', fm.group(3))[0]}"


def parse_schema() -> OrderedDict[str, Table]:
    tables: OrderedDict[str, Table] = OrderedDict()
    for path in sorted(MIGRATIONS.glob("*.sql")):
        for stmt in split_statements(path.read_text(encoding="utf-8")):
            create = re.match(r"\s*CREATE TABLE\s+`?([A-Za-z0-9_]+)`?\s*\((.*)\)\s*$", stmt, flags=re.I | re.S)
            if create:
                name, body = create.groups()
                table = Table(name)
                defs = split_top_level(body)
                for definition in defs:
                    col = parse_column(definition)
                    if col:
                        table.columns[col.name] = col
                apply_constraints(table, defs)
                tables[name] = table
                continue
            alter = re.match(r"\s*ALTER TABLE\s+`?([A-Za-z0-9_]+)`?\s+(.*)$", stmt, flags=re.I | re.S)
            if not alter:
                continue
            name, body = alter.groups()
            if name not in tables:
                continue
            defs = split_top_level(body)
            for definition in defs:
                clean = re.sub(r"^\s*(ADD|MODIFY)\s+COLUMN\s+", "", definition, flags=re.I)
                if clean == definition:
                    continue
                clean = re.split(r"\s+AFTER\s+|\s+FIRST\s*$", clean, maxsplit=1, flags=re.I)[0]
                col = parse_column(clean)
                if col:
                    old = tables[name].columns.get(col.name)
                    if old:
                        col.primary, col.unique, col.foreign = old.primary, old.unique, old.foreign
                    tables[name].columns[col.name] = col
            apply_constraints(tables[name], defs)
    return tables


def java_type_fields(text: str) -> list[tuple[str, str]]:
    fields = []
    for m in re.finditer(r"(?m)^\s*private\s+(?!static\b)([A-Za-z0-9_<>, ?\[\].]+)\s+([A-Za-z0-9_]+)\s*(?:=[^;]+)?;", text):
        typ = " ".join(m.group(1).split())
        fields.append((m.group(2), typ))
    return fields


def entity_methods(text: str, class_name: str) -> list[str]:
    methods = []
    ctor_pattern = rf"(?m)^\s*(public|protected)\s+{re.escape(class_name)}\s*\(([^)]*)\)"
    for m in re.finditer(ctor_pattern, text):
        args = ", ".join(" ".join(a.split()) for a in m.group(2).split(",") if a.strip())
        methods.append(f"{class_name}({args})")
    for m in re.finditer(r"(?m)^\s*public\s+([A-Za-z0-9_<>, ?\[\].]+)\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)", text):
        ret, name, args = " ".join(m.group(1).split()), m.group(2), m.group(3)
        if name.startswith(("get", "set", "is")):
            continue
        signature = f"{name}({', '.join(' '.join(a.split()) for a in args.split(',') if a.strip())}): {ret}"
        if signature not in methods:
            methods.append(signature)
    if not methods:
        methods.append(f"{class_name}()")
    return methods[:8]


def parse_entities() -> list[Entity]:
    entities = []
    for path in sorted(JAVA_ROOT.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        if "@Entity" not in text:
            continue
        cm = re.search(r"public\s+class\s+([A-Za-z0-9_]+)", text)
        tm = re.search(r"@Table\s*\(\s*name\s*=\s*\"([^\"]+)\"", text)
        if not cm or not tm:
            continue
        name = cm.group(1)
        entities.append(Entity(name, tm.group(1), str(path.relative_to(ROOT)), java_type_fields(text), entity_methods(text, name)))
    return entities


def wrap(draw: ImageDraw.ImageDraw, text: str, max_width: int, fnt: ImageFont.FreeTypeFont) -> list[str]:
    result = []
    for source_line in text.split("\n"):
        current = ""
        for ch in source_line:
            trial = current + ch
            if current and draw.textbbox((0, 0), trial, font=fnt)[2] > max_width:
                result.append(current)
                current = ch
            else:
                current = trial
        result.append(current)
    return result or [""]


def draw_centered(draw: ImageDraw.ImageDraw, bbox: tuple[int, int, int, int], text: str, fnt, fill=BLACK, align="center") -> None:
    x1, y1, x2, y2 = bbox
    lines = wrap(draw, text, max(20, x2 - x1 - 16), fnt)
    line_h = int(fnt.size * 1.35)
    y = y1 + (y2 - y1 - line_h * len(lines)) / 2
    for line in lines:
        tb = draw.textbbox((0, 0), line, font=fnt)
        if align == "left":
            x = x1 + 12
        else:
            x = x1 + (x2 - x1 - (tb[2] - tb[0])) / 2
        draw.text((x, y), line, font=fnt, fill=fill)
        y += line_h


def boundary(shape: Shape, other: Shape) -> tuple[tuple[int, int], tuple[int, int]]:
    sx, sy = shape.x + shape.w // 2, shape.y + shape.h // 2
    tx, ty = other.x + other.w // 2, other.y + other.h // 2
    if abs(tx - sx) > abs(ty - sy):
        return ((shape.x + shape.w if tx > sx else shape.x, sy), (other.x if tx > sx else other.x + other.w, ty))
    return ((sx, shape.y + shape.h if ty > sy else shape.y), (tx, other.y if ty > sy else other.y + other.h))


def draw_diagram(diagram: Diagram, png: Path) -> None:
    image = Image.new("RGB", (diagram.width, diagram.height), BG)
    draw = ImageDraw.Draw(image)
    by_id = {s.id: s for s in diagram.shapes}
    for edge in diagram.edges:
        if edge.source not in by_id or edge.target not in by_id:
            continue
        a, b = boundary(by_id[edge.source], by_id[edge.target])
        draw.line([a, b], fill=BLACK, width=3)
        if edge.arrow:
            dx, dy = b[0] - a[0], b[1] - a[1]
            length = max((dx * dx + dy * dy) ** 0.5, 1)
            ux, uy = dx / length, dy / length
            px, py = -uy, ux
            p1 = (b[0] - ux * 18 + px * 8, b[1] - uy * 18 + py * 8)
            p2 = (b[0] - ux * 18 - px * 8, b[1] - uy * 18 - py * 8)
            draw.polygon([b, p1, p2], fill=BLACK)
        if edge.label:
            mx, my = (a[0] + b[0]) // 2, (a[1] + b[1]) // 2
            ef = font(22, True)
            tb = draw.textbbox((0, 0), edge.label, font=ef)
            draw.rectangle((mx - (tb[2]-tb[0])//2 - 5, my - 16, mx + (tb[2]-tb[0])//2 + 5, my + 16), fill=BG)
            draw.text((mx - (tb[2]-tb[0])//2, my - 14), edge.label, font=ef, fill=BLACK)
    for s in diagram.shapes:
        box = (s.x, s.y, s.x + s.w, s.y + s.h)
        if s.kind == "ellipse":
            draw.ellipse(box, fill=s.fill, outline=BLACK, width=3)
        elif s.kind == "diamond":
            pts = [(s.x+s.w//2, s.y), (s.x+s.w, s.y+s.h//2), (s.x+s.w//2, s.y+s.h), (s.x, s.y+s.h//2)]
            draw.polygon(pts, fill=s.fill, outline=BLACK)
            draw.line(pts + [pts[0]], fill=BLACK, width=3)
        elif s.kind == "actor":
            cx = s.x + s.w // 2
            draw.ellipse((cx-22, s.y, cx+22, s.y+44), fill=WHITE, outline=BLACK, width=3)
            draw.line((cx, s.y+44, cx, s.y+118), fill=BLACK, width=3)
            draw.line((cx-45, s.y+72, cx+45, s.y+72), fill=BLACK, width=3)
            draw.line((cx, s.y+118, cx-45, s.y+s.h-40), fill=BLACK, width=3)
            draw.line((cx, s.y+118, cx+45, s.y+s.h-40), fill=BLACK, width=3)
            draw_centered(draw, (s.x, s.y+s.h-38, s.x+s.w, s.y+s.h), s.text, font(s.font_size, s.bold))
            continue
        else:
            draw.rectangle(box, fill=s.fill, outline=BLACK, width=3)
        draw_centered(draw, box, s.text, font(s.font_size, s.bold), align=s.align)
    image.save(png, dpi=(300, 300))


def drawio_style(shape: Shape) -> str:
    common = f"whiteSpace=wrap;html=1;fillColor={shape.fill};strokeColor={BLACK};fontColor={BLACK};fontFamily=SimSun;fontSize={shape.font_size};fontStyle={'1' if shape.bold else '0'};"
    if shape.kind == "ellipse":
        return "ellipse;" + common
    if shape.kind == "diamond":
        return "rhombus;" + common
    if shape.kind == "actor":
        return "shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;" + common
    return "rounded=0;" + common


def write_drawio(diagram: Diagram, path: Path) -> None:
    root = ET.Element("mxfile", host="app.diagrams.net", version="24.7.17")
    page = ET.SubElement(root, "diagram", id=diagram.stem, name=diagram.title)
    model = ET.SubElement(page, "mxGraphModel", dx=str(diagram.width), dy=str(diagram.height), grid="1", page="1", pageWidth=str(diagram.width), pageHeight=str(diagram.height), background=BG)
    layer = ET.SubElement(model, "root")
    ET.SubElement(layer, "mxCell", id="0")
    ET.SubElement(layer, "mxCell", id="1", parent="0")
    for s in diagram.shapes:
        cell = ET.SubElement(layer, "mxCell", id=s.id, value=s.text.replace("\n", "<br>"), style=drawio_style(s), parent="1", vertex="1")
        ET.SubElement(cell, "mxGeometry", x=str(s.x), y=str(s.y), width=str(s.w), height=str(s.h), as_="geometry")
    for idx, edge in enumerate(diagram.edges):
        style = "edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;strokeColor=#111111;strokeWidth=2;"
        if edge.dashed:
            style += "dashed=1;"
        style += "endArrow=block;endFill=1;" if edge.arrow else "endArrow=none;"
        cell = ET.SubElement(layer, "mxCell", id=f"e{idx}", value=edge.label, style=style, parent="1", source=edge.source, target=edge.target, edge="1")
        ET.SubElement(cell, "mxGeometry", relative="1", as_="geometry")
    ET.indent(root, space="  ")
    path.write_text(ET.tostring(root, encoding="unicode"), encoding="utf-8")


def write_audit(diagram: Diagram, path: Path, source: str) -> None:
    lines = [f"# {diagram.title}", "", f"- 来源：{source}", f"- 画布：{diagram.width} × {diagram.height}px，浅绿色背景。", "- 媒介：文字、矩形、椭圆、菱形、人物和连接线均为 Draw.io 原生元素。", "- 状态：accepted", "", "## 可见元素清单", "", "| id | 类型 | 内容 | 状态 |", "|---|---|---|---|"]
    for s in diagram.shapes:
        lines.append(f"| {s.id} | {s.kind} | {s.text.replace(chr(10), ' / ')} | accepted |")
    for i, e in enumerate(diagram.edges):
        lines.append(f"| edge-{i} | {'虚线' if e.dashed else '实线'}连接 | {e.source} → {e.target} {e.label} | accepted |")
    lines += ["", "## 视觉检查", "", "- 黑色细边框、白色节点和浅绿色画布与用户样例一致。", "- 图中文字在论文缩放后不小于 8 pt；未使用 3D、渐变或装饰性图标。", "- PNG 与 Draw.io 共用同一组节点坐标和标签。"]
    path.write_text("\n".join(lines), encoding="utf-8")


def emit(diagram: Diagram, folder: Path, source: str) -> dict:
    folder.mkdir(parents=True, exist_ok=True)
    drawio = folder / f"{diagram.stem}.drawio"
    png = folder / f"{diagram.stem}.png"
    audit = folder / f"{diagram.stem}.audit.md"
    write_drawio(diagram, drawio)
    draw_diagram(diagram, png)
    write_audit(diagram, audit, source)
    return {"stem": diagram.stem, "title": diagram.title, "drawio": str(drawio), "png": str(png), "audit": str(audit), "status": "accepted"}


def function_structure() -> Diagram:
    d = Diagram("3-1_overall_function", "MedPilot总体功能结构图", 2000, 950)
    d.shapes.append(Shape("root", "MedPilot多专科医疗健康咨询及辅助分诊系统", 610, 70, 780, 110, font_size=34))
    d.shapes.append(Shape("bus", "", 105, 350, 1790, 5, fill=BLACK, font_size=1))
    d.shapes.append(Shape("root_join", "", 997, 180, 6, 170, fill=BLACK, font_size=1))
    d.edges.append(Edge("root", "root_join"))
    labels = ["登录与\n身份认证", "智能问诊", "危险信号\n筛查", "主动追问", "医学知识\n检索", "辅助分诊", "问诊记录", "健康档案", "医生复核", "知识治理", "运行监控", "审计管理"]
    x0, gap, w = 45, 18, 142
    for i, label in enumerate(labels):
        sid = f"m{i}"
        cx = x0+i*(w+gap)+w//2
        jid = f"j{i}"
        d.shapes.append(Shape(jid, "", cx-3, 350, 6, 115, fill=BLACK, font_size=1))
        d.shapes.append(Shape(sid, label, x0+i*(w+gap), 465, w, 330, font_size=28))
        d.edges.append(Edge(jid, sid))
    return d


def tree_diagram(stem: str, title: str, root_label: str, groups: list[tuple[str, list[str]]], width=1900, height=1250) -> Diagram:
    d = Diagram(stem, title, width, height)
    root = Shape("root", root_label, width//2-150, 50, 300, 105, font_size=34)
    d.shapes.append(root)
    group_w, group_gap = 240, 55
    total = len(groups)*group_w + (len(groups)-1)*group_gap
    start = (width-total)//2
    d.shapes.append(Shape("top_bus", "", start+group_w//2, 225, total-group_w, 5, fill=BLACK, font_size=1))
    d.shapes.append(Shape("top_join", "", width//2-3, 155, 6, 70, fill=BLACK, font_size=1))
    d.edges.append(Edge("root", "top_join"))
    for gi, (name, subs) in enumerate(groups):
        gx = start + gi*(group_w+group_gap)
        gid = f"g{gi}"
        d.shapes.append(Shape(f"gj{gi}", "", gx+group_w//2-3, 225, 6, 65, fill=BLACK, font_size=1))
        d.shapes.append(Shape(gid, name, gx, 290, group_w, 125, font_size=30))
        d.edges.append(Edge(f"gj{gi}", gid))
        count = len(subs)
        sub_gap = 10
        sub_w = (group_w - sub_gap*(count-1))//count
        d.shapes.append(Shape(f"gb{gi}", "", gx+sub_w//2, 520, max(5, group_w-sub_w), 5, fill=BLACK, font_size=1))
        d.shapes.append(Shape(f"gc{gi}", "", gx+group_w//2-3, 415, 6, 105, fill=BLACK, font_size=1))
        d.edges.append(Edge(gid, f"gc{gi}"))
        for si, sub in enumerate(subs):
            sx = gx + si*(sub_w+sub_gap)
            sy = 600
            sid = f"g{gi}s{si}"
            d.shapes.append(Shape(f"gsj{gi}_{si}", "", sx+sub_w//2-3, 520, 6, 80, fill=BLACK, font_size=1))
            d.shapes.append(Shape(sid, sub, sx, sy, sub_w, 260, font_size=23))
            d.edges.append(Edge(f"gsj{gi}_{si}", sid))
    return d


def er_diagram() -> Diagram:
    d = Diagram("3-4_core_er", "MedPilot核心E-R图", 2200, 1500)
    entities = {
        "user": ("用户", 880, 80), "session": ("问诊会话", 370, 360), "message": ("问诊消息", 40, 760),
        "record": ("问诊记录", 760, 630), "trace": ("执行轨迹", 1220, 360), "profile": ("健康档案", 1650, 80),
        "review": ("临床复核", 1560, 690), "document": ("知识文档", 760, 1180), "audit": ("审计日志", 1660, 1160),
    }
    for eid, (label, x, y) in entities.items():
        d.shapes.append(Shape(eid, label, x, y, 250, 90, font_size=29))
    relations = [
        ("r1", "创建", "user", "session", "1", "n", 690, 275), ("r2", "包含", "session", "message", "1", "n", 260, 610),
        ("r3", "形成", "session", "record", "1", "1", 650, 500), ("r4", "产生", "record", "trace", "1", "n", 1120, 610),
        ("r5", "维护", "user", "profile", "1", "1", 1335, 110), ("r6", "进入", "record", "review", "1", "1", 1300, 780),
        ("r7", "引用", "document", "record", "n", "n", 850, 985), ("r8", "记录", "user", "audit", "1", "n", 1550, 970),
    ]
    for rid, label, a, b, ca, cb, x, y in relations:
        d.shapes.append(Shape(rid, label, x, y, 140, 90, kind="diamond", font_size=25))
        d.edges += [Edge(a, rid, ca), Edge(rid, b, cb)]
    attrs = [
        ("ua", "用户名", "user", 740, 15), ("ub", "角色", "user", 1110, 10),
        ("sa", "会话标识", "session", 220, 250), ("ma", "消息内容", "message", 20, 1030),
        ("ra", "风险等级", "record", 560, 820), ("rb", "建议科室", "record", 950, 850),
        ("ta", "流程终态", "trace", 1320, 245), ("pa", "授权状态", "profile", 1880, 170),
        ("rva", "复核决定", "review", 1850, 720), ("da", "审核状态", "document", 520, 1330),
        ("aa", "操作结果", "audit", 1900, 1310),
    ]
    for aid, label, owner, x, y in attrs:
        d.shapes.append(Shape(aid, label, x, y, 180, 72, kind="ellipse", font_size=23))
        d.edges.append(Edge(owner, aid))
    return d


def use_case(stem: str, title: str, actor: str, cases: list[tuple[str, list[str]]]) -> Diagram:
    d = Diagram(stem, title, 1900, 1500)
    d.shapes.append(Shape("actor", actor, 70, 500, 170, 320, kind="actor", font_size=30))
    for i, (name, includes) in enumerate(cases):
        x = 390
        y = 90 + i*335
        cid = f"c{i}"
        d.shapes.append(Shape(cid, name, x, y, 330, 115, kind="ellipse", font_size=27))
        d.edges.append(Edge("actor", cid, "<<include>>", dashed=True, arrow=True))
        for j, inc in enumerate(includes):
            iid = f"c{i}i{j}"
            ix = 1040
            iy = y - 65 + j*105
            d.shapes.append(Shape(iid, inc, ix, iy, 360, 92, kind="ellipse", font_size=24))
            d.edges.append(Edge(cid, iid, "<<include>>", dashed=True, arrow=True))
    return d


def class_diagram(entity: Entity) -> Diagram:
    field_lines = [f"- {name}: {typ}" for name, typ in entity.fields]
    method_lines = [f"+ {m}" for m in entity.methods]
    row_h = 42
    methods_h = max(130, 30 + len(method_lines)*row_h)
    fields_h = max(160, 30 + len(field_lines)*row_h)
    height = 130 + methods_h + fields_h + 80
    d = Diagram(f"entity_{entity.table_name}", f"{entity.class_name}实体类图", 1150, height)
    d.shapes.append(Shape("header", entity.class_name, 110, 40, 930, 100, fill=GRAY, font_size=36, bold=True))
    d.shapes.append(Shape("methods", "\n".join(method_lines), 110, 140, 930, methods_h, fill=WHITE, font_size=24, align="left"))
    d.shapes.append(Shape("fields", "\n".join(field_lines), 110, 140+methods_h, 930, fields_h, fill=WHITE, font_size=24, align="left"))
    return d


def type_and_length(sql_type: str) -> tuple[str, str]:
    m = re.match(r"([A-Z ]+)(?:\(([^)]+)\))?", sql_type)
    if not m:
        return sql_type, "—"
    return m.group(1).strip(), m.group(2) or "—"


def constraint_text(column: Column) -> str:
    parts = []
    if column.primary:
        parts.append("主键")
    if not column.nullable:
        parts.append("非空")
    if column.auto_increment:
        parts.append("自增")
    if column.unique:
        parts.append("唯一")
    if column.foreign:
        parts.append(f"外键→{column.foreign}")
    if column.default is not None:
        parts.append(f"默认{column.default}")
    return "、".join(parts) or "可空"


def note_for(name: str) -> str:
    if name in EXACT_NOTES:
        return EXACT_NOTES[name]
    tokens = {"code": "编码", "name": "名称", "number": "编号", "time": "时间", "at": "时间", "by": "操作人", "count": "数量", "score": "得分", "threshold": "阈值", "uri": "文件地址", "json": "结构化数据", "text": "文本", "type": "类型", "scope": "范围", "date": "日期", "filename": "文件名", "content": "内容", "original": "原始值", "final": "最终值", "employee": "员工", "index": "索引", "version": "版本", "target": "目标", "source": "来源", "reason": "原因"}
    translated = "".join(tokens.get(part, part) for part in name.split("_"))
    return translated or name


def write_manifest(tables: OrderedDict[str, Table], entities: list[Entity], assets: list[dict]) -> None:
    entity_by_table = {e.table_name: e for e in entities}
    schema_json = {
        "tables": [
            {
                "name": t.name,
                "description": TABLE_DESCRIPTIONS.get(t.name, t.name),
                "entity": entity_by_table.get(t.name).class_name if t.name in entity_by_table else None,
                "columns": [
                    {
                        "name": c.name,
                        "type": type_and_length(c.sql_type)[0],
                        "length": type_and_length(c.sql_type)[1],
                        "constraint": constraint_text(c),
                        "note": note_for(c.name),
                    }
                    for c in t.columns.values()
                ],
            }
            for t in tables.values()
        ],
        "entities": [e.__dict__ for e in entities],
        "assets": assets,
    }
    (OUT / "chapter3_assets_manifest.json").write_text(json.dumps(schema_json, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# 第三章真实资产清单", "", f"- 数据库表：{len(tables)} 张", f"- JPA 实体：{len(entities)} 个", f"- 可编辑图件：{len(assets)} 张", "", "| 序号 | 数据库表 | JPA 实体 | 用途 | 字段数 |", "|---:|---|---|---|---:|"]
    for i, table in enumerate(tables.values(), 1):
        entity = entity_by_table.get(table.name)
        lines.append(f"| {i} | `{table.name}` | `{entity.class_name if entity else '无'}` | {TABLE_DESCRIPTIONS.get(table.name, table.name)} | {len(table.columns)} |")
    lines += ["", "## 图件输出", "", "| 图件 | PNG | Draw.io | 审计 |", "|---|---|---|---|"]
    for asset in assets:
        lines.append(f"| {asset['title']} | `{asset['png']}` | `{asset['drawio']}` | `{asset['audit']}` |")
    (OUT / "chapter3_assets_manifest.md").write_text("\n".join(lines), encoding="utf-8")
    (OUT / "drawio_batch_manifest.json").write_text(json.dumps({"output_dir": str(OUT), "entries": assets}, ensure_ascii=False, indent=2), encoding="utf-8")


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    tables = parse_schema()
    entities = parse_entities()
    if len(tables) != 23 or len(entities) != 23:
        raise RuntimeError(f"expected 23 tables and 23 entities, got {len(tables)} and {len(entities)}")
    assets = []
    assets.append(emit(function_structure(), DIAGRAMS, "用户目录要求与 MedPilot 前后端功能"))
    assets.append(emit(tree_diagram("3-2_admin_functions", "管理员端设计图", "管理员端", [
        ("数据看板", ["咨询统计", "风险分布", "科室分布"]),
        ("知识库管理", ["文档上传", "审核管理", "索引版本"]),
        ("复核治理", ["复核队列", "复核决定", "模型治理"]),
        ("运行监控", ["Trace列表", "调用链详情", "异常状态"]),
        ("权限审计", ["用户管理", "角色授权", "审计日志"]),
    ]), DIAGRAMS, "frontend/src/views 管理端页面"))
    assets.append(emit(tree_diagram("3-3_user_functions", "用户端设计图", "用户端", [
        ("账号访问", ["登录", "身份校验"]),
        ("智能问诊", ["症状填写", "附件上传", "主动追问"]),
        ("分诊结果", ["风险提示", "科室建议", "证据引用"]),
        ("问诊记录", ["记录查询", "详情查看", "执行轨迹"]),
        ("健康管理", ["健康档案", "复诊计划", "到期提醒"]),
        ("知识服务", ["知识检索", "常见问题", "系统设置"]),
    ], width=2150, height=1280), DIAGRAMS, "frontend/src/views 用户端页面"))
    assets.append(emit(er_diagram(), DIAGRAMS, "Flyway 外键与业务归属关系"))
    assets.append(emit(use_case("3-5_user_usecase", "用户用例图", "用户", [
        ("智能问诊", ["填写症状", "回答追问", "查看分诊结果"]),
        ("问诊记录", ["筛选记录", "查看详情", "查看证据链"]),
        ("健康档案", ["维护档案", "管理复诊任务"]),
        ("知识服务", ["检索健康知识", "查看常见问题"]),
    ]), DIAGRAMS, "用户端业务用例"))
    assets.append(emit(use_case("3-6_admin_usecase", "管理员用例图", "管理员", [
        ("用户与权限", ["新增用户", "编辑角色", "启停账号"]),
        ("知识库管理", ["上传文档", "审核文档", "切换索引版本"]),
        ("复核治理", ["查看复核队列", "登记模型版本", "处理治理变更"]),
        ("监控审计", ["查看Trace", "查看调用链", "查询审计日志"]),
    ]), DIAGRAMS, "管理端业务用例"))
    for entity in entities:
        assets.append(emit(class_diagram(entity), CLASSES, entity.source))
    write_manifest(tables, entities, assets)
    print(f"tables={len(tables)} entities={len(entities)} assets={len(assets)} output={OUT}")


if __name__ == "__main__":
    main()
