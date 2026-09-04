"""Generate editable Visio diagrams for the MedPilot thesis.

The local environment does not contain Microsoft Visio, so this script uses
the open-source ``vsdx`` package to create genuine ``.vsdx`` files from a
minimal Visio template. The resulting files can be opened and edited in
Microsoft Visio. PNG/PDF previews are generated separately when LibreOffice
supports VSDX import.
"""
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Iterable
import xml.etree.ElementTree as ET

import vsdx
from vsdx import Connect, Media, VisioFile, namespace

ET.register_namespace("r", "http://schemas.openxmlformats.org/officeDocument/2006/relationships")
ET.register_namespace("", namespace[1:-1])
ET.register_namespace("ct", "http://schemas.openxmlformats.org/package/2006/content-types")


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "outputs" / "visio-diagrams"
MEDIA_PATH = Path(vsdx.__file__).resolve().parent / "media" / "media.vsdx"

PAGE_W = 11.6929133858
PAGE_H = 8.2677165354

COLORS = {
    # The thesis specification calls for restrained black-and-white technical
    # drawings.  Grayscale is used only for a small amount of hierarchy.
    "navy": "#000000",
    "blue": "#FFFFFF",
    "blue2": "#FFFFFF",
    "teal": "#FFFFFF",
    "green": "#FFFFFF",
    "yellow": "#F2F2F2",
    "orange": "#FFFFFF",
    "red": "#FFFFFF",
    "purple": "#FFFFFF",
    "gray": "#F2F2F2",
    "dark": "#000000",
    "white": "#FFFFFF",
}


def _clear_page(page, title: str) -> None:
    page.name = title
    page.width = PAGE_W
    page.height = PAGE_H
    root = page.xml.getroot()
    for child in list(root):
        if child.tag in {f"{namespace}Shapes", f"{namespace}Connects"}:
            root.remove(child)
    root.append(ET.Element(f"{namespace}Shapes"))
    # The bundled Visio template already declares the ``r`` relationship
    # namespace on PageContents.  Setting an xmlns attribute through
    # ElementTree would serialize it as ``ns1:r`` and produce invalid XML.
    # The bundled template is portrait. After switching to landscape, update
    # the page viewport as well; otherwise LibreOffice/Visio may open the
    # drawing focused on the old portrait coordinates and appear blank.
    page_record = page.vis.pages_xml.find(f"{namespace}Page[@ID='{page.page_id}']")
    if page_record is not None:
        page_record.attrib["ViewScale"] = "1"
        page_record.attrib["ViewCenterX"] = str(PAGE_W / 2)
        page_record.attrib["ViewCenterY"] = str(PAGE_H / 2)


def _new_document(filename: str, title: str):
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    target = OUTPUT_DIR / filename
    if target.exists():
        target.unlink()
    shutil.copy2(MEDIA_PATH, target)
    vis = VisioFile(str(target))
    page = vis.pages[0]
    _clear_page(page, title)
    return vis, page


def _set_character(shape, *, size: float = 0.13, color: str = "#1F1F1F", font: str = "Microsoft YaHei") -> None:
    section = shape.xml.find(f"{namespace}Section[@N='Character']")
    if section is None:
        section = ET.Element(f"{namespace}Section", {"N": "Character"})
        shape.xml.append(section)
    row = section.find(f"{namespace}Row")
    if row is None:
        row = ET.SubElement(section, f"{namespace}Row", {"IX": "0"})
    values = {
        "Font": font,
        "AsianFont": font,
        "Color": color,
        "Size": str(size),
        "Style": "0",
    }
    for name, value in values.items():
        cell = row.find(f"{namespace}Cell[@N='{name}']")
        if cell is None:
            cell = ET.SubElement(row, f"{namespace}Cell", {"N": name})
        cell.attrib["V"] = value


def _set_paragraph(shape, align: str = "1") -> None:
    section = shape.xml.find(f"{namespace}Section[@N='Paragraph']")
    if section is None:
        section = ET.Element(f"{namespace}Section", {"N": "Paragraph"})
        shape.xml.append(section)
    row = section.find(f"{namespace}Row")
    if row is None:
        row = ET.SubElement(section, f"{namespace}Row", {"IX": "0"})
    cell = row.find(f"{namespace}Cell[@N='HorzAlign']")
    if cell is None:
        cell = ET.SubElement(row, f"{namespace}Cell", {"N": "HorzAlign"})
    cell.attrib["V"] = align


def _style(shape, *, fill: str = COLORS["white"], line: str = COLORS["navy"], text: str = "#1F1F1F", size: float = 0.13, align: str = "1", no_fill: bool = False, no_line: bool = False) -> None:
    shape.set_cell_value("FillForegnd", fill)
    shape.set_cell_value("LineColor", line)
    shape.set_cell_value("LineWeight", "0.018")
    shape.set_cell_value("NoFill", "1" if no_fill else "0")
    shape.set_cell_value("NoLine", "1" if no_line else "0")
    shape.set_cell_value("FillPattern", "1")
    _set_character(shape, size=size, color=text)
    _set_paragraph(shape, align)


def _add_box(page, media: Media, text: str, x: float, y: float, w: float, h: float, *, fill: str = COLORS["white"], line: str = COLORS["navy"], text_color: str = "#1F1F1F", size: float = 0.13, align: str = "1"):
    shape = media.rectangle.copy(page)
    shape.text = text
    shape.x, shape.y, shape.width, shape.height = x, y, w, h
    _style(shape, fill=fill, line=line, text=text_color, size=size, align=align)
    return shape


def _add_circle(page, media: Media, text: str, x: float, y: float, d: float, *, fill: str = COLORS["white"], line: str = COLORS["navy"], size: float = 0.12):
    shape = media.circle.copy(page)
    shape.text = text
    shape.x, shape.y, shape.width, shape.height = x, y, d, d
    _style(shape, fill=fill, line=line, size=size)
    return shape


def _add_title(page, media: Media, title: str) -> None:
    shape = _add_box(page, media, title, PAGE_W / 2, PAGE_H - 0.35, PAGE_W - 0.6, 0.45, fill=COLORS["white"], line=COLORS["white"], text_color=COLORS["navy"], size=0.2)
    _style(shape, fill=COLORS["white"], line=COLORS["white"], text=COLORS["navy"], size=0.2, no_fill=True, no_line=True)


def _add_rule(page, media: Media, x: float, y: float, w: float, h: float = 0.022):
    """Draw a real editable Visio rule for strict orthogonal layouts."""
    shape = media.rectangle.copy(page)
    shape.text = ""
    shape.x, shape.y, shape.width, shape.height = x, y, w, h
    _style(shape, fill=COLORS["dark"], line=COLORS["dark"], size=0.01)
    return shape


def _connect(page, a, b, *, color: str = COLORS["dark"], arrow: bool = True):
    connector = Connect.create(page=page, from_shape=a, to_shape=b)
    if connector is not None:
        connector.set_cell_value("LineColor", color)
        connector.set_cell_value("LineWeight", "0.016")
        connector.end_arrow = arrow
    return connector


def _save(vis: VisioFile, filename: str) -> None:
    target = OUTPUT_DIR / filename
    vis.save_vsdx(str(target))
    vis.close_vsdx()


def function_structure() -> None:
    """Create the requested top-node/bus/aligned-column functional diagram."""
    vis, page = _new_document("00_系统功能结构图.vsdx", "MedPilot 系统功能结构图")
    media = Media()
    _add_title(page, media, "图 2-1  MedPilot 系统功能结构图")
    _add_box(page, media, "MedPilot 医疗多智能体辅助分诊平台", PAGE_W / 2, 6.55, 3.7, 0.72, fill=COLORS["white"], line=COLORS["dark"], size=0.16)
    _add_rule(page, media, PAGE_W / 2, 5.98, 0.022, 0.60)
    _add_rule(page, media, PAGE_W / 2, 5.70, 10.55, 0.028)
    labels = [
        "用户\n接入", "账户与\n权限", "智能\n问诊", "红旗\n筛查", "主动\n追问",
        "医学\n检索", "辅助\n分诊", "健康\n档案", "医生\n复核", "监控\n审计",
    ]
    first_x, step = 0.72, 1.14
    for index, label in enumerate(labels):
        x = first_x + index * step
        _add_rule(page, media, x, 4.47, 0.022, 1.25)
        _add_box(page, media, label, x, 2.75, 0.86, 2.12, fill=COLORS["white"], line=COLORS["dark"], size=0.125)
    _save(vis, "00_系统功能结构图.vsdx")


def architecture() -> None:
    vis, page = _new_document("01_系统总体架构图.vsdx", "MedPilot 系统总体架构图")
    media = Media()
    _add_title(page, media, "图 3-1  MedPilot 系统总体架构图")
    ui = _add_box(page, media, "用户端 / 管理端\nVue 3 + Element Plus", 1.3, 4.3, 2.1, 1.05, fill=COLORS["blue"])
    api = _add_box(page, media, "业务后端\nSpring Boot\nREST API / JWT / 审计", 4.1, 4.3, 2.2, 1.05, fill=COLORS["teal"])
    ai = _add_box(page, media, "AI 服务\nFastAPI\nNDJSON 流式事件", 7.0, 4.3, 2.1, 1.05, fill=COLORS["purple"])
    db = _add_box(page, media, "MySQL\n问诊记录 / 健康档案\n复核 / 权限 / 审计", 4.1, 1.9, 2.2, 1.05, fill=COLORS["yellow"])
    llm = _add_box(page, media, "Ollama\nqwen2.5:7b\n大语言模型", 8.9, 6.0, 1.8, 0.9, fill=COLORS["orange"])
    rag = _add_box(page, media, "医学知识库\nbge-m3 + FAISS\n四专科审核语料", 8.9, 2.3, 1.8, 0.9, fill=COLORS["green"])
    graph = _add_box(page, media, "LangGraph 分诊工作流\n安全筛查 → 抽取 → 追问\n→ 检索 → 分诊 → 编排", 7.0, 4.0, 2.1, 1.65, fill=COLORS["blue2"])
    _connect(page, ui, api)
    _connect(page, api, ai)
    _connect(page, api, db)
    _connect(page, ai, graph)
    _connect(page, graph, llm)
    _connect(page, graph, rag)
    _connect(page, ai, api, color=COLORS["navy"])
    _save(vis, "01_系统总体架构图.vsdx")


def workflow() -> None:
    vis, page = _new_document("02_辅助分诊流程图.vsdx", "MedPilot 辅助分诊流程图")
    media = Media()
    _add_title(page, media, "图 4-1  安全优先的辅助分诊流程")
    start = _add_circle(page, media, "开始", 0.65, 3.85, 0.8, fill=COLORS["green"])
    screen = _add_box(page, media, "安全筛查\n危险信号 + 否定表达", 1.9, 3.65, 1.65, 1.15, fill=COLORS["red"])
    danger = _add_box(page, media, "高风险快速通道\n急诊建议 / 危险提示", 4.5, 5.7, 1.8, 1.0, fill=COLORS["orange"])
    extract = _add_box(page, media, "症状抽取\nStructuredSymptoms", 4.5, 3.65, 1.8, 1.15, fill=COLORS["blue"])
    enough = _add_box(page, media, "信息是否充分？", 7.0, 3.8, 1.5, 0.85, fill=COLORS["yellow"])
    follow = _add_box(page, media, "主动追问\n持续时间 / 严重程度\n其他症状", 9.25, 5.7, 1.8, 1.0, fill=COLORS["purple"])
    retrieve = _add_box(page, media, "医学知识检索\nEmbedding + FAISS", 9.25, 3.65, 1.8, 1.15, fill=COLORS["green"])
    triage = _add_box(page, media, "证据融合分诊\n科室 / 风险 / 时效", 9.25, 1.75, 1.8, 1.0, fill=COLORS["teal"])
    answer = _add_box(page, media, "回答编排\n引用 + 安全边界", 6.55, 1.75, 1.8, 1.0, fill=COLORS["blue2"])
    end = _add_circle(page, media, "输出", 3.1, 1.95, 0.8, fill=COLORS["green"])
    _connect(page, start, screen)
    _connect(page, screen, danger, color=COLORS["red"])
    _connect(page, screen, extract)
    _connect(page, extract, enough)
    _connect(page, enough, follow, color=COLORS["purple"])
    _connect(page, enough, retrieve, color=COLORS["green"])
    _connect(page, retrieve, triage)
    _connect(page, triage, answer)
    _connect(page, answer, end)
    _connect(page, danger, end, color=COLORS["red"])
    _save(vis, "02_辅助分诊流程图.vsdx")


def database() -> None:
    vis, page = _new_document("03_数据库关系示意图.vsdx", "MedPilot 数据库关系示意图")
    media = Media()
    _add_title(page, media, "图 3-5  MedPilot 核心数据库关系示意图")
    users = _add_box(page, media, "users\nPK id\nusername / role\nemployee_number / MFA\norganization_code", 1.2, 5.8, 2.0, 1.35, fill=COLORS["blue"] , align="0", size=0.11)
    records = _add_box(page, media, "consultation_records\nPK id\nuser_id / session_id\npatient_mpi_id\ntriage / trace", 3.35, 5.8, 2.1, 1.35, fill=COLORS["teal"], align="0", size=0.11)
    sessions = _add_box(page, media, "consultation_sessions\nPK id\nuser_id\nturn_count / status", 3.35, 3.75, 2.1, 1.05, fill=COLORS["gray"], align="0", size=0.11)
    messages = _add_box(page, media, "consultation_messages\nPK id\nsession_id\nrole / content", 3.35, 1.75, 2.1, 1.05, fill=COLORS["gray"], align="0", size=0.11)
    reviews = _add_box(page, media, "clinical_reviews\nPK id / review_id\nconsultation_record_id\nstatus / decision\nreviewer_user_id", 6.15, 5.8, 2.2, 1.35, fill=COLORS["orange"], align="0", size=0.11)
    care = _add_box(page, media, "patient_care_relationships\nPK id\npatient_mpi_id\nclinician_user_id\norganization / campus", 9.0, 5.8, 2.1, 1.35, fill=COLORS["purple"], align="0", size=0.105)
    traces = _add_box(page, media, "consultation_traces\nPK id / trace_id\nrecord_id\nevent_sequence / payload", 6.15, 3.55, 2.2, 1.1, fill=COLORS["green"], align="0", size=0.11)
    audit = _add_box(page, media, "audit_logs\nPK id\nactor / action\nresource / timestamp", 9.0, 3.55, 2.1, 1.1, fill=COLORS["yellow"], align="0", size=0.11)
    health = _add_box(page, media, "health_profiles\nPK id\nuser_id\nallergies / conditions\nmedications / notes", 1.2, 1.75, 2.0, 1.05, fill=COLORS["green"], align="0", size=0.105)
    _connect(page, users, records)
    _connect(page, users, sessions)
    _connect(page, users, health)
    _connect(page, sessions, messages)
    _connect(page, records, reviews)
    _connect(page, records, traces)
    _connect(page, users, care)
    _connect(page, reviews, audit)
    _connect(page, traces, audit)
    _save(vis, "03_数据库关系示意图.vsdx")


def use_case() -> None:
    vis, page = _new_document("04_系统功能用例图.vsdx", "MedPilot 系统功能用例图")
    media = Media()
    _add_title(page, media, "图 2-1  MedPilot 系统角色与功能用例图")
    patient = _add_box(page, media, "患者", 0.8, 4.9, 1.25, 0.75, fill=COLORS["blue"])
    doctor = _add_box(page, media, "医生 / 复核员", 0.8, 2.8, 1.25, 0.75, fill=COLORS["orange"])
    admin = _add_box(page, media, "管理员", 0.8, 0.75, 1.25, 0.75, fill=COLORS["purple"])
    consult = _add_box(page, media, "智能问诊\n症状采集 / 主动追问", 3.0, 5.0, 2.0, 0.9, fill=COLORS["blue2"])
    record = _add_box(page, media, "问诊记录与\n健康档案", 5.9, 5.0, 1.8, 0.9, fill=COLORS["green"])
    review = _add_box(page, media, "医生复核\n领取 / 决策 / 升级", 3.0, 2.9, 2.0, 0.9, fill=COLORS["orange"])
    knowledge = _add_box(page, media, "医学知识库\n入库 / 审核 / 版本", 5.9, 2.9, 1.8, 0.9, fill=COLORS["yellow"])
    monitor = _add_box(page, media, "智能体监控\nTrace / 延迟 / 错误", 8.55, 5.0, 1.8, 0.9, fill=COLORS["teal"])
    governance = _add_box(page, media, "模型与知识治理\n评测 / 红队 / 回滚", 8.55, 2.9, 1.8, 0.9, fill=COLORS["purple"])
    audit = _add_box(page, media, "权限与审计\nMFA / 医疗关系 / 日志", 5.9, 0.85, 1.8, 0.9, fill=COLORS["gray"])
    _connect(page, patient, consult)
    _connect(page, patient, record)
    _connect(page, doctor, review)
    _connect(page, doctor, knowledge)
    _connect(page, admin, audit)
    _connect(page, admin, governance)
    _connect(page, review, audit)
    _connect(page, knowledge, governance)
    _connect(page, consult, monitor)
    _save(vis, "04_系统功能用例图.vsdx")


def sequence() -> None:
    vis, page = _new_document("05_智能问诊时序图.vsdx", "MedPilot 智能问诊时序图")
    media = Media()
    _add_title(page, media, "图 5-1  智能问诊请求处理时序图")
    actors = [
        ("患者端", 1.0, COLORS["blue"]),
        ("Spring Boot", 3.15, COLORS["teal"]),
        ("FastAPI", 5.3, COLORS["purple"]),
        ("LangGraph", 7.45, COLORS["green"]),
        ("MySQL / Trace", 9.6, COLORS["yellow"]),
    ]
    heads = []
    for label, x, fill in actors:
        heads.append(_add_box(page, media, label, x, 6.75, 1.35, 0.65, fill=fill, size=0.11))
    steps = [
        ("提交症状描述", 5.8, 0),
        ("POST /consult", 5.2, 1),
        ("安全筛查", 4.6, 2),
        ("症状抽取 / 追问", 4.0, 3),
        ("RAG 检索与证据分诊", 3.4, 4),
        ("NDJSON 节点事件", 2.8, 3),
        ("保存记录与 Trace", 2.2, 4),
        ("返回答案 / 复核状态", 1.6, 1),
    ]
    for text, y, target in steps:
        box = _add_box(page, media, text, 4.85, y, 2.0, 0.42, fill=COLORS["gray"], size=0.095)
        if target == 0:
            _connect(page, heads[0], box)
        elif target == 1:
            _connect(page, heads[0], box)
            _connect(page, box, heads[1])
        elif target == 2:
            _connect(page, heads[1], box)
            _connect(page, box, heads[2])
        elif target == 3:
            _connect(page, heads[2], box)
            _connect(page, box, heads[3])
        else:
            _connect(page, heads[3], box)
            _connect(page, box, heads[4])
    _save(vis, "05_智能问诊时序图.vsdx")


def rag() -> None:
    vis, page = _new_document("06_医学RAG流程图.vsdx", "医学 RAG 流程图")
    media = Media()
    _add_title(page, media, "图 4-2  面向多专科咨询的医学 RAG 流程")
    docs = _add_box(page, media, "审核通过的医学文档\n官方来源 / 本院制度", 1.2, 4.35, 1.9, 1.0, fill=COLORS["green"])
    chunk = _add_box(page, media, "文本切分\n句子级 chunk", 3.15, 4.35, 1.65, 1.0, fill=COLORS["blue"])
    embed = _add_box(page, media, "Embedding\nbge-m3", 5.35, 4.35, 1.5, 1.0, fill=COLORS["purple"])
    index = _add_box(page, media, "FAISS 索引\nIndexFlatIP\n版本化保存", 7.45, 4.35, 1.7, 1.0, fill=COLORS["yellow"])
    query = _add_box(page, media, "用户症状\n+ 同义词扩展", 1.55, 1.65, 1.65, 1.0, fill=COLORS["orange"])
    retrieve = _add_box(page, media, "向量检索 +\n字符 n-gram 词法融合", 4.0, 1.65, 1.9, 1.0, fill=COLORS["teal"])
    evidence = _add_box(page, media, "RankedEvidence\n科室 / 分数 / 来源 / URL", 6.75, 1.65, 2.0, 1.0, fill=COLORS["blue2"])
    answer = _add_box(page, media, "证据约束回答\n引用可追溯\n拒绝诊断与处方", 9.55, 1.65, 1.65, 1.0, fill=COLORS["red"])
    _connect(page, docs, chunk)
    _connect(page, chunk, embed)
    _connect(page, embed, index)
    _connect(page, query, retrieve)
    _connect(page, index, retrieve)
    _connect(page, retrieve, evidence)
    _connect(page, evidence, answer)
    _save(vis, "06_医学RAG流程图.vsdx")


def safety_gate() -> None:
    vis, page = _new_document("07_医生复核安全闸门图.vsdx", "医生复核安全闸门图")
    media = Media()
    _add_title(page, media, "图 4-3  医生复核与人工接管安全闸门")
    ai = _add_box(page, media, "AI 辅助分诊结果\n科室 / 风险 / 时效\n原始结果不可变", 1.25, 3.75, 2.0, 1.2, fill=COLORS["blue"])
    gate = _add_box(page, media, "复核队列\n医疗关系 + 院区匹配\nMFA ≥ 2", 3.65, 3.75, 2.1, 1.2, fill=COLORS["orange"])
    claim = _add_box(page, media, "医生领取\n禁止自复核\n记录 reviewer", 6.55, 5.35, 1.8, 1.0, fill=COLORS["yellow"])
    decide = _add_box(page, media, "复核决定\n确认 / 修改 / 退回\n急诊升级", 6.55, 3.55, 1.8, 1.0, fill=COLORS["green"])
    audit = _add_box(page, media, "审计留痕\n依据 / 时间 / 操作者\n原始 AI 结果保留", 9.25, 3.55, 1.8, 1.0, fill=COLORS["purple"])
    fallback = _add_box(page, media, "系统兜底\nabstain / 线下分诊\n不能安全判断时停止自动推荐", 3.65, 1.45, 2.1, 1.15, fill=COLORS["red"])
    _connect(page, ai, gate)
    _connect(page, gate, claim)
    _connect(page, claim, decide)
    _connect(page, decide, audit)
    _connect(page, gate, fallback, color=COLORS["red"])
    _connect(page, fallback, audit, color=COLORS["red"])
    _save(vis, "07_医生复核安全闸门图.vsdx")


def permission() -> None:
    vis, page = _new_document("08_角色权限模型图.vsdx", "角色权限模型图")
    media = Media()
    _add_title(page, media, "图 3-4  系统角色与权限边界模型")
    user = _add_box(page, media, "患者 USER\n问诊 / 记录 / 健康档案", 1.3, 5.55, 2.1, 1.0, fill=COLORS["blue"])
    doctor = _add_box(page, media, "医生 DOCTOR\n知识审核 / 医生复核\n需医院员工档案 + MFA", 3.4, 5.55, 2.25, 1.0, fill=COLORS["orange"])
    reviewer = _add_box(page, media, "复核员 REVIEWER\n临床评测 / 复核决定", 6.25, 5.55, 2.0, 1.0, fill=COLORS["yellow"])
    editor = _add_box(page, media, "知识编辑器\nKNOWLEDGE_EDITOR\n知识入库与版本构建", 8.9, 5.55, 2.0, 1.0, fill=COLORS["green"])
    admin = _add_box(page, media, "管理员 ADMIN\n用户 / 模型治理 / 系统运维", 3.4, 2.6, 2.25, 1.0, fill=COLORS["purple"])
    auditor = _add_box(page, media, "审计员 AUDITOR\n监控 / 审计读取\n不参与临床决策", 6.25, 2.6, 2.0, 1.0, fill=COLORS["teal"])
    boundary = _add_box(page, media, "统一安全边界\nHttpOnly JWT Cookie · CSRF\n医疗关系 · 院区 · MFA · AES-256-GCM", 3.7, 0.8, 4.5, 0.85, fill=COLORS["gray"])
    for actor in [user, doctor, reviewer, editor, admin, auditor]:
        _connect(page, actor, boundary, color=COLORS["dark"], arrow=False)
    _save(vis, "08_角色权限模型图.vsdx")


def deployment() -> None:
    vis, page = _new_document("09_系统部署架构图.vsdx", "系统部署架构图")
    media = Media()
    _add_title(page, media, "图 3-2  MedPilot 系统部署架构图")
    browser = _add_box(page, media, "浏览器\n用户 / 管理端", 0.85, 4.25, 1.6, 0.95, fill=COLORS["blue"])
    front = _add_box(page, media, "Frontend Node\nVite Dev Server\n127.0.0.1:5173", 3.05, 4.25, 1.8, 0.95, fill=COLORS["teal"])
    backend = _add_box(page, media, "Backend Node\nSpring Boot JAR\n127.0.0.1:8080", 5.65, 4.25, 1.9, 0.95, fill=COLORS["purple"])
    ai = _add_box(page, media, "AI Node\nFastAPI + Uvicorn\n127.0.0.1:8000", 8.3, 4.25, 1.9, 0.95, fill=COLORS["green"])
    mysql = _add_box(page, media, "MySQL 8\n业务数据库", 5.65, 1.9, 1.9, 0.95, fill=COLORS["yellow"])
    ollama = _add_box(page, media, "Ollama\nqwen2.5:7b + bge-m3", 8.3, 1.9, 1.9, 0.95, fill=COLORS["orange"])
    index = _add_box(page, media, "FAISS Index Store\n版本化医学索引", 10.0, 4.25, 1.2, 0.95, fill=COLORS["blue2"], size=0.1)
    _connect(page, browser, front)
    _connect(page, front, backend)
    _connect(page, backend, ai)
    _connect(page, backend, mysql)
    _connect(page, ai, ollama)
    _connect(page, ai, index)
    _save(vis, "09_系统部署架构图.vsdx")


def monitoring() -> None:
    vis, page = _new_document("10_Trace监控与审计流程图.vsdx", "Trace 监控与审计流程图")
    media = Media()
    _add_title(page, media, "图 5-2  智能体 Trace 监控与审计流程")
    node = _add_box(page, media, "LangGraph 节点\nstarted / completed / error", 1.4, 4.4, 2.1, 1.0, fill=COLORS["blue"])
    event = _add_box(page, media, "EventEmitter\nsequence 严格递增\nprotocol_version=1.0", 3.7, 4.4, 2.1, 1.0, fill=COLORS["teal"])
    stream = _add_box(page, media, "NDJSON 流式传输\nanswer_delta / done", 6.6, 4.4, 2.1, 1.0, fill=COLORS["purple"])
    ui = _add_box(page, media, "前端监控界面\n节点状态 / 耗时 / 引用", 9.45, 4.4, 1.7, 1.0, fill=COLORS["green"], size=0.1)
    persist = _add_box(page, media, "后端持久化\nconsultation_traces\n成功 / 失败链路", 3.7, 1.75, 2.1, 1.0, fill=COLORS["yellow"])
    audit = _add_box(page, media, "审计日志\n操作者 / 资源 / 动作\n可追溯复盘", 6.6, 1.75, 2.1, 1.0, fill=COLORS["orange"])
    alert = _add_box(page, media, "运行治理\n错误率 / 延迟 / 安全事件\n模型与知识变更", 9.45, 1.75, 1.7, 1.0, fill=COLORS["red"], size=0.1)
    _connect(page, node, event)
    _connect(page, event, stream)
    _connect(page, stream, ui)
    _connect(page, event, persist)
    _connect(page, persist, audit)
    _connect(page, audit, alert)
    _save(vis, "10_Trace监控与审计流程图.vsdx")


def main() -> None:
    # Media() resolves its bundled template relative to the current drive.
    # Keep execution on C: so it works when the project is on D:.
    os.chdir("C:\\")
    for builder in (function_structure, architecture, workflow, database, use_case, sequence, rag, safety_gate, permission, deployment, monitoring):
        builder()
    print(f"Generated {len(list(OUTPUT_DIR.glob('*.vsdx')))} Visio files in {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
