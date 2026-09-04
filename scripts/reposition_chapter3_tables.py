from copy import deepcopy
from pathlib import Path

from docx import Document
from docx.oxml import OxmlElement
from docx.oxml.ns import qn


INPUT = Path(
    r"D:\毕设制作\outputs\7230264110_窦成皓_23软件1_基于多智能体协同与大语言模型的多专科医疗健康咨询及辅助分诊系统_千例评测增强版.docx"
)
OUTPUT = Path(
    r"D:\毕设制作\outputs\7230264110_窦成皓_23软件1_基于多智能体协同与大语言模型的多专科医疗健康咨询及辅助分诊系统_第三章图表重排版.docx"
)


def paragraph_text(element) -> str:
    return "".join(element.itertext())


def find_paragraph(body, prefix: str):
    for element in body:
        if element.tag == qn("w:p") and paragraph_text(element).strip().startswith(prefix):
            return element
    raise ValueError(f"paragraph not found: {prefix}")


def find_caption_and_table(body, caption_prefix: str):
    elements = list(body)
    for index, element in enumerate(elements):
        if element.tag != qn("w:p"):
            continue
        if not paragraph_text(element).strip().startswith(caption_prefix):
            continue
        for following in elements[index + 1 :]:
            if following.tag == qn("w:tbl"):
                return element, following
            if following.tag == qn("w:p") and paragraph_text(following).strip():
                break
    raise ValueError(f"table not found: {caption_prefix}")


def make_reference_paragraph(anchor, text: str):
    paragraph = OxmlElement("w:p")
    ppr = anchor.find(qn("w:pPr"))
    if ppr is not None:
        paragraph.append(deepcopy(ppr))

    run = OxmlElement("w:r")
    if anchor.find(qn("w:r")) is not None:
        anchor_run_pr = anchor.find(qn("w:r")).find(qn("w:rPr"))
        if anchor_run_pr is not None:
            run.append(deepcopy(anchor_run_pr))
    text_node = OxmlElement("w:t")
    text_node.text = text
    run.append(text_node)
    paragraph.append(run)
    return paragraph


def move_group(body, caption_prefix: str, target, reference_text: str):
    caption, table = find_caption_and_table(body, caption_prefix)
    body.remove(caption)
    body.remove(table)
    reference = make_reference_paragraph(target, reference_text)
    target.addprevious(reference)
    reference.addnext(caption)
    caption.addnext(table)


def main():
    doc = Document(str(INPUT))
    body = doc.element.body

    # Core interface summary belongs to the module-dependency discussion.
    interface_anchor = find_paragraph(body, "3.3 ")
    move_group(
        body,
        "表3-2",
        interface_anchor,
        "系统主要接口、调用方法、用途及权限约束汇总见表3-2。",
    )

    # Node responsibilities belong after the workflow overview and before node details.
    node_anchor = find_paragraph(body, "3.3.2")
    move_group(
        body,
        "表3-1",
        node_anchor,
        "各智能体节点的输入、输出及路由条件汇总见表3-1。",
    )

    doc.save(str(OUTPUT))
    print(OUTPUT)


if __name__ == "__main__":
    main()
