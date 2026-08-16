"""症状抽取模块契约测试。LLM 打桩，离线确定性。"""
import pytest

from app.agents.extract import extract


def _fake_chat(response: str):
    async def _chat(prompt, system=None):
        return response
    return _chat


@pytest.mark.unit
async def test_extract_parses_structured_fields():
    fake = _fake_chat(
        '好的，结果如下：{"symptoms":["咳嗽","发热"],"duration":"3天",'
        '"severity":"中","history":["高血压"]} 以上。'
    )
    result = await extract("我咳嗽发热三天了", chat_fn=fake)
    assert result.symptoms == ("咳嗽", "发热")
    assert result.duration == "3天"
    assert result.severity == "中"
    assert result.history == ("高血压",)
    assert result.raw_text == "我咳嗽发热三天了"


@pytest.mark.unit
async def test_extract_detects_red_flags_from_raw_text():
    fake = _fake_chat('{"symptoms":["胸痛"]}')
    result = await extract("我突然胸痛还呼吸困难", chat_fn=fake)
    assert "胸痛" in result.red_flags
    assert "呼吸困难" in result.red_flags


@pytest.mark.unit
async def test_extract_tolerates_non_json_llm_output():
    fake = _fake_chat("抱歉我无法结构化输出")
    result = await extract("头痛", chat_fn=fake)
    assert result.symptoms == ()
    assert result.duration is None
    assert result.raw_text == "头痛"
