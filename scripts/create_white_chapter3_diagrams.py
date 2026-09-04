"""Create standalone white-background versions of the six Chapter 3 diagrams."""
from __future__ import annotations

import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
GEN_PATH = ROOT / "scripts" / "generate_chapter3_reference_assets.py"
OUT_DIR = ROOT / "outputs" / "chapter3-reference-assets" / "diagrams-white"
ROOT_OUT = ROOT / "outputs"


def load_generator():
    spec = importlib.util.spec_from_file_location("chapter3_generator", GEN_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {GEN_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def main() -> None:
    gen = load_generator()
    # The generator uses one background token for both Draw.io XML and PNG.
    # Changing it before building keeps both outputs pixel-consistent.
    gen.BG = "#FFFFFF"
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    diagrams = [
        (gen.function_structure(), "3-1_overall_function_white", "用户目录要求与 MedPilot 前后端功能"),
        (
            gen.tree_diagram(
                "3-2_admin_functions_white",
                "管理员端设计图",
                "管理员端",
                [
                    ("数据看板", ["咨询统计", "风险分布", "科室分布"]),
                    ("知识库管理", ["文档上传", "审核管理", "索引版本"]),
                    ("复核治理", ["复核队列", "复核决定", "模型治理"]),
                    ("运行监控", ["Trace列表", "调用详情", "异常状态"]),
                    ("权限审计", ["用户管理", "角色授权", "审计日志"]),
                ],
            ),
            "3-2_admin_functions_white",
            "frontend/src/views 管理端页面",
        ),
        (
            gen.tree_diagram(
                "3-3_user_functions_white",
                "用户端设计图",
                "用户端",
                [
                    ("账号访问", ["登录", "身份校验"]),
                    ("智能问诊", ["症状填写", "附件上传", "主动追问"]),
                    ("分诊结果", ["风险提示", "科室建议", "证据引用"]),
                    ("问诊记录", ["记录查询", "详情查看", "执行轨迹"]),
                    ("健康管理", ["健康档案", "复诊计划", "到期提醒"]),
                    ("知识服务", ["知识检索", "常见问题", "系统设置"]),
                ],
                width=2150,
                height=1280,
            ),
            "3-3_user_functions_white",
            "frontend/src/views 用户端页面",
        ),
        (build_symmetric_er(gen), "3-4_core_er_symmetric_white", "Flyway 外键与业务归属关系"),
        (
            gen.use_case(
                "3-5_user_usecase_white",
                "用户用例图",
                "用户",
                [
                    ("智能问诊", ["填写症状", "回答追问", "查看分诊结果"]),
                    ("问诊记录", ["筛选记录", "查看详情", "查看证据链"]),
                    ("健康档案", ["维护档案", "管理复诊任务"]),
                    ("知识服务", ["检索健康知识", "查看常见问题"]),
                ],
            ),
            "3-5_user_usecase_white",
            "用户端业务用例",
        ),
        (
            gen.use_case(
                "3-6_admin_usecase_white",
                "管理员用例图",
                "管理员",
                [
                    ("用户与权限", ["新增用户", "编辑角色", "启停账号"]),
                    ("知识库管理", ["上传文档", "审核文档", "切换索引版本"]),
                    ("复核治理", ["查看复核队列", "登记模型版本", "处理治理变更"]),
                    ("监控审计", ["查看Trace", "查看调用链", "查询审计日志"]),
                ],
            ),
            "3-6_admin_usecase_white",
            "管理端业务用例",
        ),
    ]

    # The symmetric E-R diagram is maintained separately from the older
    # generator and is imported from the local creator to preserve its layout.
    for diagram, stem, source in diagrams:
        diagram.stem = stem
        result = gen.emit(diagram, OUT_DIR, source)
        audit_path = OUT_DIR / f"{stem}.audit.md"
        audit_text = audit_path.read_text(encoding="utf-8").replace("浅绿色背景", "白色背景")
        audit_path.write_text(audit_text, encoding="utf-8")
        print(result["png"])
        print(result["drawio"])

    names = {
        "3-1_overall_function_white": "图3-1_总体功能结构图_白底",
        "3-2_admin_functions_white": "图3-2_管理员端设计图_白底",
        "3-3_user_functions_white": "图3-3_用户端设计图_白底",
        "3-4_core_er_symmetric_white": "图3-4_核心ER图_轴对称_白底",
        "3-5_user_usecase_white": "图3-5_用户用例图_白底",
        "3-6_admin_usecase_white": "图3-6_管理员用例图_白底",
    }
    for stem, label in names.items():
        for suffix in ("png", "drawio", "audit.md"):
            source = OUT_DIR / f"{stem}.{suffix}"
            target = ROOT_OUT / f"{label}.{suffix}"
            target.write_bytes(source.read_bytes())
            print(target)


def build_symmetric_er(gen):
    # Load the already reviewed axis-symmetric builder without copying its
    # implementation, then apply the same white background token.
    path = ROOT / "scripts" / "create_symmetric_er.py"
    spec = importlib.util.spec_from_file_location("symmetric_er", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    module.BG = "#FFFFFF"
    # Convert the XML builder's element tree to the generator's Diagram model
    # by parsing its native objects directly.  This keeps the PNG renderer and
    # Draw.io exporter on one coordinate system.
    import xml.etree.ElementTree as ET

    tree = module.build()
    page = tree.find(".//diagram/mxGraphModel")
    shapes = []
    edges = []
    for node in page.findall("./root/mxCell"):
        if node.attrib.get("vertex") == "1":
            geo = node.find("mxGeometry")
            if geo is None:
                continue
            style = node.attrib.get("style", "")
            kind = "rect"
            if "rhombus" in style:
                kind = "diamond"
            elif "ellipse" in style:
                kind = "ellipse"
            shapes.append(gen.Shape(node.attrib["id"], node.attrib.get("value", ""), int(float(geo.attrib.get("x", 0))), int(float(geo.attrib.get("y", 0))), int(float(geo.attrib.get("width", 0))), int(float(geo.attrib.get("height", 0))), kind=kind, font_size=28 if kind == "rect" else (23 if kind == "diamond" else 21)))
        elif node.attrib.get("edge") == "1":
            edges.append(gen.Edge(node.attrib.get("source", ""), node.attrib.get("target", ""), node.attrib.get("value", "")))
    return gen.Diagram("3-4_core_er_symmetric_white", "MedPilot轴对称核心E-R图", 2200, 1100, shapes=shapes, edges=edges)


if __name__ == "__main__":
    main()
