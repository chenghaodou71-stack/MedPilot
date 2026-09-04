"""Create a clean, axis-symmetric MedPilot E-R diagram.

The diagram keeps the nine entities and relationships used by the thesis while
replacing the previous cross-heavy placement with a centered vertical spine and
three mirrored left/right branches.
"""
from __future__ import annotations

import html
from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "outputs" / "chapter3-reference-assets" / "diagrams"
STEM = "3-4_core_er_symmetric"
DRAWIO = OUT_DIR / f"{STEM}.drawio"
PNG = OUT_DIR / f"{STEM}.png"
AUDIT = OUT_DIR / f"{STEM}.audit.md"


PAGE_W = 2200
PAGE_H = 1100
FONT = "SimSun"
BLACK = "#111111"
WHITE = "#FFFFFF"
BG = "#D8ECD4"


def geometry(x: float, y: float, w: float, h: float) -> ET.Element:
    return ET.Element("mxGeometry", {"x": str(x), "y": str(y), "width": str(w), "height": str(h), "as": "geometry"})


def cell(root: ET.Element, ident: str, value: str, style: str, *, x: float, y: float, w: float, h: float) -> None:
    node = ET.SubElement(root, "mxCell", {"id": ident, "value": html.escape(value), "style": style, "parent": "1", "vertex": "1"})
    node.append(geometry(x, y, w, h))


def edge(root: ET.Element, ident: str, source: str, target: str, label: str = "") -> None:
    style = "edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;strokeColor=#111111;strokeWidth=2;endArrow=none;"
    node = ET.SubElement(root, "mxCell", {"id": ident, "value": html.escape(label), "style": style, "parent": "1", "source": source, "target": target, "edge": "1"})
    node.append(ET.Element("mxGeometry", {"relative": "1", "as": "geometry"}))


def _font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = [
        Path("C:/Windows/Fonts/simsun.ttc"),
        Path("C:/Windows/Fonts/simhei.ttf") if bold else Path("C:/Windows/Fonts/simsun.ttc"),
        Path("C:/Windows/Fonts/msyh.ttc"),
    ]
    path = next((p for p in candidates if p.exists()), None)
    return ImageFont.truetype(str(path), size=size) if path else ImageFont.load_default()


def _draw_text(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], text: str, size: int) -> None:
    fnt = _font(size)
    lines = []
    for source in text.split("\n"):
        current = ""
        for ch in source:
            trial = current + ch
            if current and draw.textbbox((0, 0), trial, font=fnt)[2] > box[2] - box[0] - 16:
                lines.append(current)
                current = ch
            else:
                current = trial
        lines.append(current)
    line_h = int(size * 1.35)
    top = box[1] + (box[3] - box[1] - line_h * len(lines)) / 2
    for line in lines:
        tb = draw.textbbox((0, 0), line, font=fnt)
        x = box[0] + (box[2] - box[0] - (tb[2] - tb[0])) / 2
        draw.text((x, top), line, font=fnt, fill=BLACK)
        top += line_h


def _boundary(shape: tuple[int, int, int, int], other: tuple[int, int, int, int]) -> tuple[tuple[int, int], tuple[int, int]]:
    sx, sy, sw, sh = shape
    tx, ty, tw, th = other
    sc = (sx + sw // 2, sy + sh // 2)
    tc = (tx + tw // 2, ty + th // 2)
    if abs(tc[0] - sc[0]) > abs(tc[1] - sc[1]):
        return ((sx + sw if tc[0] > sc[0] else sx, sc[1]), (tx if tc[0] > sc[0] else tx + tw, tc[1]))
    return ((sc[0], sy + sh if tc[1] > sc[1] else sy), (tc[0], ty if tc[1] > sc[1] else ty + th))


def render_preview() -> None:
    """Render the same geometry as a PNG when Draw.io CLI is unavailable."""
    image = Image.new("RGB", (PAGE_W, PAGE_H), BG)
    draw = ImageDraw.Draw(image)
    rects = {
        "user": (980, 80, 240, 80), "session": (980, 380, 240, 80), "record": (980, 680, 240, 80),
        "profile": (170, 80, 240, 80), "audit": (1790, 80, 240, 80), "message": (170, 380, 240, 80),
        "trace": (1790, 380, 240, 80), "document": (170, 680, 240, 80), "review": (1790, 680, 240, 80),
        "r_create": (1040, 225, 120, 70), "r_form": (1040, 525, 120, 70), "r_maintain": (600, 85, 120, 70),
        "r_record": (1480, 85, 120, 70), "r_contains": (600, 385, 120, 70), "r_produce": (1480, 525, 120, 70),
        "r_cite": (600, 685, 120, 70), "r_enter": (1480, 685, 120, 70),
        "user_name": (900, 20, 170, 55), "user_role": (1130, 20, 170, 55), "profile_auth": (220, 180, 170, 55),
        "audit_result": (1810, 180, 170, 55), "session_id": (430, 500, 170, 55), "trace_phase": (1810, 500, 170, 55),
        "record_risk": (860, 850, 170, 55), "record_dept": (1100, 850, 170, 55), "message_content": (220, 500, 170, 55),
        "review_decision": (1810, 850, 170, 55), "document_status": (220, 850, 170, 55),
    }
    labels = {
        "user": "用户", "session": "问诊会话", "record": "问诊记录", "profile": "健康档案", "audit": "审计日志",
        "message": "问诊消息", "trace": "执行轨迹", "document": "知识文档", "review": "临床复核", "r_create": "创建",
        "r_form": "形成", "r_maintain": "维护", "r_record": "记录", "r_contains": "包含", "r_produce": "产生",
        "r_cite": "引用", "r_enter": "进入", "user_name": "用户名", "user_role": "角色", "profile_auth": "授权状态",
        "audit_result": "操作结果", "session_id": "会话标识", "trace_phase": "流程终态", "record_risk": "风险等级",
        "record_dept": "建议科室", "message_content": "消息内容", "review_decision": "复核决定", "document_status": "审核状态",
    }
    links = [
        ("user", "r_create", "1"), ("r_create", "session", "n"), ("session", "r_form", "1"), ("r_form", "record", "1"),
        ("user", "r_maintain", "1"), ("r_maintain", "profile", "1"), ("user", "r_record", "1"), ("r_record", "audit", "n"),
        ("session", "r_contains", "1"), ("r_contains", "message", "n"), ("record", "r_produce", "1"), ("r_produce", "trace", "n"),
        ("document", "r_cite", "n"), ("r_cite", "record", "n"), ("record", "r_enter", "1"), ("r_enter", "review", "1"),
    ]
    for source, target, label in links:
        a, b = _boundary(rects[source], rects[target])
        draw.line([a, b], fill=BLACK, width=3)
        mx, my = (a[0] + b[0]) // 2, (a[1] + b[1]) // 2
        fnt = _font(22, True)
        tb = draw.textbbox((0, 0), label, font=fnt)
        draw.rectangle((mx - (tb[2]-tb[0])//2 - 5, my - 15, mx + (tb[2]-tb[0])//2 + 5, my + 15), fill=BG)
        draw.text((mx - (tb[2]-tb[0])//2, my - 13), label, font=fnt, fill=BLACK)
    attr_links = [("user", "user_name"), ("user", "user_role"), ("profile", "profile_auth"), ("audit", "audit_result"),
                  ("session", "session_id"), ("trace", "trace_phase"), ("record", "record_risk"), ("record", "record_dept"),
                  ("message", "message_content"), ("review", "review_decision"), ("document", "document_status")]
    for source, target in attr_links:
        a, b = _boundary(rects[source], rects[target])
        draw.line([a, b], fill=BLACK, width=3)
    diamonds = {k for k in rects if k.startswith("r_")}
    ellipses = {k for k in rects if k in {"user_name", "user_role", "profile_auth", "audit_result", "session_id", "trace_phase", "record_risk", "record_dept", "message_content", "review_decision", "document_status"}}
    for ident, (x, y, w, h) in rects.items():
        box = (x, y, x + w, y + h)
        if ident in diamonds:
            pts = [(x + w//2, y), (x+w, y+h//2), (x+w//2, y+h), (x, y+h//2)]
            draw.polygon(pts, fill=WHITE, outline=BLACK)
            draw.line(pts + [pts[0]], fill=BLACK, width=3)
        elif ident in ellipses:
            draw.ellipse(box, fill=WHITE, outline=BLACK, width=3)
        else:
            draw.rectangle(box, fill=WHITE, outline=BLACK, width=3)
        _draw_text(draw, box, labels[ident], 28 if ident not in diamonds and ident not in ellipses else (23 if ident in diamonds else 21))
    image.save(PNG, dpi=(300, 300))


def build() -> ET.ElementTree:
    mxfile = ET.Element("mxfile", {"host": "app.diagrams.net", "version": "24.7.17"})
    diagram = ET.SubElement(mxfile, "diagram", {"id": STEM, "name": "MedPilot轴对称核心E-R图"})
    model = ET.SubElement(
        diagram,
        "mxGraphModel",
        {
            "dx": str(PAGE_W),
            "dy": str(PAGE_H),
            "grid": "1",
            "page": "1",
            "pageWidth": str(PAGE_W),
            "pageHeight": str(PAGE_H),
            "background": BG,
        },
    )
    root = ET.SubElement(model, "root")
    ET.SubElement(root, "mxCell", {"id": "0"})
    ET.SubElement(root, "mxCell", {"id": "1", "parent": "0"})

    rect = f"rounded=0;whiteSpace=wrap;html=1;fillColor={WHITE};strokeColor={BLACK};fontColor={BLACK};fontFamily={FONT};fontSize=28;fontStyle=0;"
    diamond = f"shape=rhombus;whiteSpace=wrap;html=1;fillColor={WHITE};strokeColor={BLACK};fontColor={BLACK};fontFamily={FONT};fontSize=23;fontStyle=0;"
    oval = f"shape=ellipse;whiteSpace=wrap;html=1;fillColor={WHITE};strokeColor={BLACK};fontColor={BLACK};fontFamily={FONT};fontSize=21;fontStyle=0;"

    # Central vertical spine.
    cell(root, "user", "用户", rect, x=980, y=80, w=240, h=80)
    cell(root, "r_create", "创建", diamond, x=1040, y=225, w=120, h=70)
    cell(root, "session", "问诊会话", rect, x=980, y=380, w=240, h=80)
    cell(root, "r_form", "形成", diamond, x=1040, y=525, w=120, h=70)
    cell(root, "record", "问诊记录", rect, x=980, y=680, w=240, h=80)

    # Mirrored entities and relationship diamonds.
    cell(root, "profile", "健康档案", rect, x=170, y=80, w=240, h=80)
    cell(root, "r_maintain", "维护", diamond, x=600, y=85, w=120, h=70)
    cell(root, "audit", "审计日志", rect, x=1790, y=80, w=240, h=80)
    cell(root, "r_record", "记录", diamond, x=1480, y=85, w=120, h=70)

    cell(root, "message", "问诊消息", rect, x=170, y=380, w=240, h=80)
    cell(root, "r_contains", "包含", diamond, x=600, y=385, w=120, h=70)
    cell(root, "trace", "执行轨迹", rect, x=1790, y=380, w=240, h=80)
    cell(root, "r_produce", "产生", diamond, x=1480, y=525, w=120, h=70)

    cell(root, "document", "知识文档", rect, x=170, y=680, w=240, h=80)
    cell(root, "r_cite", "引用", diamond, x=600, y=685, w=120, h=70)
    cell(root, "review", "临床复核", rect, x=1790, y=680, w=240, h=80)
    cell(root, "r_enter", "进入", diamond, x=1480, y=685, w=120, h=70)

    # Relationship connectors and cardinalities.
    edge(root, "e_create_1", "user", "r_create", "1")
    edge(root, "e_create_n", "r_create", "session", "n")
    edge(root, "e_form_1", "session", "r_form", "1")
    edge(root, "e_form_1b", "r_form", "record", "1")
    edge(root, "e_maintain_1", "user", "r_maintain", "1")
    edge(root, "e_maintain_1b", "r_maintain", "profile", "1")
    edge(root, "e_record_1", "user", "r_record", "1")
    edge(root, "e_record_n", "r_record", "audit", "n")
    edge(root, "e_contains_1", "session", "r_contains", "1")
    edge(root, "e_contains_n", "r_contains", "message", "n")
    edge(root, "e_produce_1", "record", "r_produce", "1")
    edge(root, "e_produce_n", "r_produce", "trace", "n")
    edge(root, "e_cite_n", "document", "r_cite", "n")
    edge(root, "e_cite_n2", "r_cite", "record", "n")
    edge(root, "e_enter_1", "record", "r_enter", "1")
    edge(root, "e_enter_1b", "r_enter", "review", "1")

    # Attributes are placed outside their owner, with left/right mirroring.
    attrs = [
        ("user_name", "用户名", "user", 900, 15),
        ("user_role", "角色", "user", 1130, 15),
        ("profile_auth", "授权状态", "profile", 220, 200),
        ("audit_result", "操作结果", "audit", 1810, 200),
        ("session_id", "会话标识", "session", 430, 500),
        ("trace_phase", "流程终态", "trace", 1810, 500),
        ("record_risk", "风险等级", "record", 860, 850),
        ("record_dept", "建议科室", "record", 1100, 850),
        ("message_content", "消息内容", "message", 220, 500),
        ("review_decision", "复核决定", "review", 1810, 850),
        ("document_status", "审核状态", "document", 220, 850),
    ]
    for ident, label, owner, x, y in attrs:
        cell(root, ident, label, oval, x=x, y=y, w=170, h=55)
        edge(root, f"a_{ident}", owner, ident)

    return ET.ElementTree(mxfile)


def write_audit() -> None:
    AUDIT.write_text(
        """# MedPilot 轴对称核心 E-R 图

- 输出：`3-4_core_er_symmetric.drawio` 与 `3-4_core_er_symmetric.png`。
- 画布：2200 × 1100 px，浅绿色背景，与论文示例保持一致。
- 布局：用户—问诊会话—问诊记录构成中心竖向主链；左、右各三组实体镜像分布。
- 媒介：实体矩形、联系菱形、属性椭圆和连接线均为 Draw.io 原生元素，可继续编辑。
- 关系：创建、形成、维护、记录、包含、产生、引用、进入，共 8 组；基数标注保留 1/n。

## 视觉检查

- 中心主链垂直对齐，左右实体列使用相同尺寸和间距。
- 关系线采用正交连接，避免穿过实体和属性；属性椭圆置于实体外侧。
- 未使用渐变、阴影或装饰性图标，导出后适合论文黑白打印。
""",
        encoding="utf-8",
    )


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tree = build()
    ET.indent(tree, space="  ")
    tree.write(DRAWIO, encoding="utf-8", xml_declaration=False)
    render_preview()
    write_audit()
    print(DRAWIO)
    print(PNG)
    print(AUDIT)


if __name__ == "__main__":
    main()
