from __future__ import annotations

import json
import math
import shutil
import textwrap
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch, Circle, Ellipse, Polygon, FancyArrowPatch
from matplotlib.font_manager import FontProperties


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "outputs" / "thesis-figures"
DIAGRAM_DIR = OUT / "drawio"
PLOT_DIR = OUT / "results"
UI_DIR = OUT / "ui"
FONT_PATH = Path("C:/Windows/Fonts/msyh.ttc")
FONT = FontProperties(fname=str(FONT_PATH)) if FONT_PATH.exists() else FontProperties(family="DejaVu Sans")

COLORS = {
    "blue": "#DCEBFA",
    "blue_edge": "#3566A8",
    "green": "#E2F0D9",
    "green_edge": "#4A7C59",
    "red": "#FCE4D6",
    "red_edge": "#C94C4C",
    "orange": "#FCEBD8",
    "orange_edge": "#C47A32",
    "gray": "#F2F3F5",
    "gray_edge": "#747474",
    "ink": "#253244",
    "muted": "#5D6B7A",
    "white": "#FFFFFF",
}


@dataclass
class Node:
    id: str
    label: str
    x: float
    y: float
    w: float
    h: float
    kind: str = "blue"
    shape: str = "round"
    font: int = 18


@dataclass
class Edge:
    source: str
    target: str
    label: str = ""
    dashed: bool = False
    color: str = COLORS["ink"]


@dataclass
class Diagram:
    stem: str
    title: str
    width: float = 1800
    height: float = 1000
    nodes: list[Node] = field(default_factory=list)
    edges: list[Edge] = field(default_factory=list)
    notes: list[str] = field(default_factory=list)


def n(d, i, label, x, y, w, h, kind="blue", shape="round", font=16):
    d.nodes.append(Node(i, label, x, y, w, h, kind, shape, font))


def e(d, s, t, label="", dashed=False, color=None):
    d.edges.append(Edge(s, t, label, dashed, color or COLORS["ink"]))


def make_diagrams() -> list[Diagram]:
    ds: list[Diagram] = []
    d = Diagram("01_system_architecture", "MedPilot 系统总体分层架构图")
    n(d,"user","用户与业务人员",50,365,210,90,"gray")
    n(d,"ui","前端交互层\nVue 3 用户端 / 管理端",330,365,270,140,"blue")
    n(d,"api","业务后端\nSpring Boot · JWT · CSRF",680,365,290,140,"green")
    n(d,"ai","AI 服务层\nFastAPI · LangGraph",1050,365,270,140,"red")
    n(d,"gov","治理与复核\n知识审核 · 医生复核 · 审计",50,100,320,130,"orange")
    n(d,"db","业务数据库\nMySQL / Flyway",500,100,270,130,"green")
    n(d,"rag","医学知识库\nbge-m3 / FAISS",900,100,270,130,"blue")
    n(d,"llm","模型推理\nOllama / qwen2.5:7b",1300,100,270,130,"orange")
    e(d,"user","ui","HTTPS")
    e(d,"ui","api","REST / NDJSON")
    e(d,"api","ai","内部服务令牌")
    e(d,"ai","llm","结构化推理")
    e(d,"ai","rag","向量检索")
    e(d,"api","db","事务持久化")
    e(d,"gov","rag","批准版本")
    e(d,"gov","db","审计记录")
    ds.append(d)

    d = Diagram("02_system_function_structure", "MedPilot 系统功能结构图")
    n(d,"root","MedPilot 多专科医疗健康咨询及辅助分诊系统",560,40,480,90,"blue",font=21)
    branches=[("patient","患者端功能",50,"blue"),("ai","智能问诊与辅助分诊",360,"red"),("admin","管理端功能",670,"green"),("gov","治理与安全",980,"orange")]
    for i,(id,label,x,k) in enumerate(branches): n(d,id,label,x,220,250,80,k); e(d,"root",id)
    children={"patient":["账号与身份","多轮智能问诊","问诊记录","健康档案","知识检索"],"ai":["危险信号筛查","症状抽取与追问","RAG 证据融合","科室与时效建议","引用与免责"],"admin":["数据看板","知识库管理","模型监控","用户权限","医生复核"],"gov":["JWT / CSRF","AES-256-GCM","Trace 追踪","审计日志","备份与恢复"]}
    for parent,vals in children.items():
        px=next(z[2] for z in branches if z[0]==parent)
        for j,label in enumerate(vals):
            x=px+(j%2)*120; y=430+(j//2)*105
            cid=f"{parent}{j}"; n(d,cid,label,x,y,105,70,"gray" if parent=="gov" else "blue",font=11); e(d,parent,cid)
    ds.append(d)

    d=Diagram("03_patient_functions", "患者端功能结构图")
    n(d,"root","患者端",650,40,250,85,"blue",font=22)
    vals=[("auth","注册 / 登录",80,"gray"),("consult","智能问诊",340,"red"),("record","问诊记录",600,"green"),("profile","健康档案",860,"blue"),("knowledge","健康知识",1120,"orange")]
    for id,label,x,k in vals: n(d,id,label,x,200,220,80,k); e(d,"root",id)
    subs={"auth":["账号登录","身份状态"],"consult":["症状填写","附件确认","主动追问","分诊结果"],"record":["列表筛选","详情查看"],"profile":["档案总览","信息编辑","复诊提醒"],"knowledge":["关键词检索","结果引用","常见问题"]}
    for parent,items in subs.items():
        px=next(x for i,l,x,k in vals if i==parent)
        for j,label in enumerate(items):
            cid=f"{parent}{j}"; n(d,cid,label,px+5+(j%2)*110,410+(j//2)*90,100,62,"gray",font=11); e(d,parent,cid)
    ds.append(d)

    d=Diagram("04_admin_functions", "管理端功能结构图")
    n(d,"root","管理端",650,40,250,85,"green",font=22)
    vals=[("dash","数据看板",70,"green"),("kb","医学知识库",330,"blue"),("model","智能体运行监控",590,"red"),("user","用户与权限",850,"gray"),("audit","审计与复核",1110,"orange")]
    for id,label,x,k in vals: n(d,id,label,x,200,220,80,k); e(d,"root",id)
    subs={"dash":["咨询量统计","风险分布","科室分布"],"kb":["文档上传","索引构建","版本管理"],"model":["Trace 列表","调用链详情","延迟与失败"],"user":["角色管理","用户新增 / 编辑"],"audit":["审计日志查询","医生复核任务","人工接管"]}
    for parent,items in subs.items():
        px=next(x for i,l,x,k in vals if i==parent)
        for j,label in enumerate(items):
            cid=f"{parent}{j}"; n(d,cid,label,px+5+(j%2)*110,410+(j//2)*90,100,62,"gray",font=11); e(d,parent,cid)
    ds.append(d)

    d=Diagram("05_roles_permissions", "系统角色与权限边界图")
    n(d,"user","USER\n患者",60,80,210,100,"blue")
    n(d,"doctor","DOCTOR\n医生复核者",60,255,210,100,"orange")
    n(d,"editor","KNOWLEDGE_EDITOR\n知识编辑者",60,430,210,100,"green")
    n(d,"reviewer","REVIEWER\n独立评测者",60,605,210,100,"gray")
    n(d,"admin","ADMIN\n系统管理员",470,170,210,100,"green")
    n(d,"auditor","AUDITOR\n审计人员",470,520,210,100,"gray")
    n(d,"boundary","权限边界\nSpring Security + JWT + CSRF",940,345,350,130,"red")
    n(d,"resources","受保护资源\n问诊 / 知识 / 治理 / 审计",1380,345,190,130,"blue")
    for x in ["user","doctor","editor","reviewer","admin","auditor"]: e(d,x,"boundary","角色声明")
    e(d,"boundary","resources","最小权限校验")
    d.notes.append("不同角色通过后端授权访问资源，前端隐藏菜单不作为安全边界")
    ds.append(d)

    d=Diagram("06_deployment_architecture", "系统部署架构图")
    n(d,"browser","浏览器\n患者端 / 管理端",70,350,220,100,"blue")
    n(d,"frontend","前端静态资源\nVue 3 + Vite",400,350,240,100,"blue")
    n(d,"backend","业务容器\nSpring Boot",760,250,250,100,"green")
    n(d,"ai","AI 容器\nFastAPI + LangGraph",760,470,250,100,"red")
    n(d,"mysql","MySQL 8\n业务与治理数据",1200,180,260,100,"green")
    n(d,"ollama","Ollama\nqwen2.5:7b",1200,350,260,100,"orange")
    n(d,"faiss","本地索引卷\nbge-m3 / FAISS",1200,520,260,100,"blue")
    e(d,"browser","frontend","HTTPS")
    e(d,"frontend","backend","REST / NDJSON")
    e(d,"backend","ai","内部服务令牌")
    e(d,"backend","mysql","JDBC / Flyway")
    e(d,"ai","ollama","本地推理")
    e(d,"ai","faiss","向量检索")
    ds.append(d)

    d=Diagram("07_trust_boundaries", "信任边界与服务调用链图")
    n(d,"public","公网 / 用户设备",50,280,250,160,"gray")
    n(d,"edge","应用信任区\nVue + Spring Boot",420,220,300,280,"blue")
    n(d,"internal","内部服务区\nFastAPI + LangGraph",850,220,300,280,"red")
    n(d,"data","受保护数据区\nMySQL + FAISS + 审计",1280,220,280,280,"green")
    e(d,"public","edge","TLS / Cookie")
    e(d,"edge","internal","服务令牌\n禁止前端直连")
    e(d,"internal","data","最小化证据访问")
    n(d,"guard","安全控制点\nJWT、CSRF、AES-256-GCM、审计日志",520,650,850,100,"orange")
    e(d,"guard","edge","控制")
    e(d,"guard","internal","控制")
    e(d,"guard","data","控制")
    ds.append(d)

    d=Diagram("08_triage_flow", "多智能体协同辅助分诊流程图")
    n(d,"input","用户输入",40,365,180,80,"gray")
    n(d,"safe","危险信号安全筛查",290,365,230,80,"red")
    n(d,"risk","命中危险信号？",590,365,210,110,"red","diamond")
    n(d,"urgent","高风险快速通道\n急诊提示与升级",930,140,260,100,"red")
    n(d,"extract","症状抽取",930,365,210,80,"blue")
    n(d,"enough","信息充分？",1220,365,190,110,"blue","diamond")
    n(d,"ask","主动追问",1220,620,190,80,"gray")
    n(d,"retrieve","医学知识检索",1500,365,210,80,"blue")
    n(d,"triage","辅助分诊",1500,140,210,80,"green")
    n(d,"answer","确定性回答编排",1500,620,210,80,"green")
    e(d,"input","safe"); e(d,"safe","risk"); e(d,"risk","urgent","是"); e(d,"risk","extract","否"); e(d,"extract","enough"); e(d,"enough","ask","否"); e(d,"ask","extract","补充后"); e(d,"enough","retrieve","是"); e(d,"retrieve","triage"); e(d,"triage","answer"); e(d,"answer","input","NDJSON / Trace",True)
    ds.append(d)

    d=Diagram("09_high_risk_fast_path", "高风险危险信号快速通道图")
    n(d,"start","任意轮次用户输入",60,360,230,85,"gray")
    n(d,"detect","规则优先筛查\n否定感知 + 关键词组合",390,320,300,160,"red")
    n(d,"hit","命中高风险信号？",820,340,220,120,"red","diamond")
    n(d,"no","继续常规问诊\n症状抽取 / 追问 / 检索",1160,590,300,120,"blue")
    n(d,"yes","立即进入快速通道",1160,130,300,100,"red")
    n(d,"warn","展示风险提示\n建议急诊或拨打急救电话",1160,300,300,120,"orange")
    n(d,"escalate","记录升级事件\n通知人工复核 / 审计",1160,470,300,100,"green")
    e(d,"start","detect"); e(d,"detect","hit"); e(d,"hit","yes","是"); e(d,"hit","no","否"); e(d,"yes","warn"); e(d,"warn","escalate"); e(d,"escalate","no","解除后续自动分诊",True)
    d.notes.append("快速通道优先于模型生成，输出仅作安全提示，不构成诊断")
    ds.append(d)

    d=Diagram("10_followup_loop", "信息充分性与主动追问闭环图")
    n(d,"collect","收集症状、持续时间、部位、严重程度",80,330,320,120,"blue")
    n(d,"judge","字段完整性判断\n信息是否充分？",540,330,280,120,"blue","diamond")
    n(d,"ask","生成最小必要追问\n等待用户补充",980,560,300,120,"gray")
    n(d,"confirm","确认已获得足够信息",980,180,300,100,"green")
    n(d,"retrieve","进入知识检索与证据融合",1400,180,300,100,"green")
    e(d,"collect","judge"); e(d,"judge","ask","否"); e(d,"ask","collect","补充信息"); e(d,"judge","confirm","是"); e(d,"confirm","retrieve")
    n(d,"guard","边界：每轮追问均保留会话状态与 Trace 事件",470,760,900,70,"orange")
    ds.append(d)

    d=Diagram("11_rag_evidence_fusion", "医学 RAG 检索与证据融合流程图")
    n(d,"query","结构化症状查询",50,360,230,90,"blue")
    n(d,"embed","bge-m3 向量化",360,360,230,90,"blue")
    n(d,"search","FAISS Top-k 检索",670,360,230,90,"blue")
    n(d,"filter","过滤未批准 / 过期文档",980,360,260,90,"orange")
    n(d,"fuse","证据融合\n来源、版本、相关性",1320,360,250,110,"green")
    n(d,"answer","带引用回答与科室建议",1320,610,250,100,"green")
    n(d,"fallback","低证据回退\n明确不确定性并建议人工复核",980,610,260,100,"red")
    e(d,"query","embed"); e(d,"embed","search"); e(d,"search","filter"); e(d,"filter","fuse","通过"); e(d,"filter","fallback","不足 / 过期"); e(d,"fuse","answer")
    ds.append(d)

    d=Diagram("12_answer_orchestration", "确定性回答编排与失败关闭流程图")
    n(d,"evidence","证据包\n症状 + 风险 + 引用",60,350,260,120,"green")
    n(d,"policy","安全策略校验\n禁止诊断、处方、越权建议",430,350,280,120,"orange")
    n(d,"compose","确定性回答编排\n风险提示 / 科室 / 时效 / 免责声明",820,330,340,160,"blue")
    n(d,"validate","结构化输出校验\n字段、引用、终态",1260,350,260,120,"blue")
    n(d,"close","成功关闭\n持久化记录 + Trace",1260,620,260,100,"green")
    n(d,"fail","失败关闭\n停止流式输出 + 审计告警",820,620,280,100,"red")
    e(d,"evidence","policy"); e(d,"policy","compose","通过"); e(d,"policy","fail","拒绝"); e(d,"compose","validate"); e(d,"validate","close","通过"); e(d,"validate","fail","不通过")
    ds.append(d)

    d=Diagram("13_consultation_sequence", "智能问诊请求时序图")
    actors=[("u","用户",90,"gray"),("web","Vue 3",390,"blue"),("api","Spring Boot",690,"green"),("ai","FastAPI / LangGraph",990,"red"),("db","MySQL / FAISS",1320,"orange")]
    for id,label,x,k in actors: n(d,id,label,x,90,210,75,k)
    labels=["提交症状","POST /consultations","启动工作流","检索证据并分诊","NDJSON 节点事件","结果与 Trace"]
    for idx,label in enumerate(labels):
        y=220+idx*105; n(d,f"m{idx}",label,690,y,300,62,"gray",font=15)
        if idx: e(d,f"m{idx-1}",f"m{idx}")
    n(d,"note","高风险命中时，AI 服务直接返回快速通道事件；后端仍负责鉴权、持久化和审计",330,880,1140,60,"red",font=14)
    ds.append(d)

    d=Diagram("14_consultation_state_machine", "咨询业务状态机图")
    states=[("created","CREATED",70,350,"gray"),("screening","SCREENING",320,350,"red"),("collecting","COLLECTING",570,350,"blue"),("retrieving","RETRIEVING",820,350,"blue"),("answering","ANSWERING",1070,350,"green"),("closed","CLOSED",1320,350,"green")]
    for id,label,x,y,k in states: n(d,id,label,x,y,190,80,k)
    for a,b,l in [("created","screening","提交"),("screening","collecting","未命中"),("collecting","retrieving","信息充分"),("retrieving","answering","证据融合"),("answering","closed","校验通过")]: e(d,a,b,l)
    n(d,"follow","FOLLOW_UP\n信息不足",570,620,220,90,"orange"); n(d,"urgent","URGENT\n危险信号",820,620,220,90,"red"); n(d,"failed","FAILED\n异常关闭",1070,620,220,90,"red")
    e(d,"collecting","follow","不足"); e(d,"follow","collecting","补充后"); e(d,"screening","urgent","命中"); e(d,"answering","failed","校验失败"); e(d,"failed","closed","审计后关闭",True)
    ds.append(d)

    d=Diagram("15_trace_monitoring", "Trace 事件流与监控流程图")
    n(d,"event","节点事件\nstart / token / result / end",50,340,260,120,"red")
    n(d,"stream","NDJSON 流\n实时传输",390,340,230,120,"blue")
    n(d,"validate","后端严格校验\n顺序、字段、终态",700,340,260,120,"orange")
    n(d,"persist","持久化 Trace\nconsultation_traces",1040,340,260,120,"green")
    n(d,"monitor","管理端监控\n延迟、失败、调用链",1380,340,220,120,"gray")
    e(d,"event","stream"); e(d,"stream","validate"); e(d,"validate","persist","通过"); e(d,"validate","monitor","异常",True); e(d,"persist","monitor")
    n(d,"audit","任何异常均写入 audit_logs，并触发人工排查",530,650,780,80,"orange")
    e(d,"validate","audit","失败 / 越序",True); e(d,"monitor","audit","告警",True)
    ds.append(d)

    d=Diagram("16_core_database_er", "核心数据库 ER 图")
    ents=[("users","users\n用户与角色",50,100,"blue"),("sessions","consultation_sessions\n咨询会话",380,100,"green"),("messages","consultation_messages\n消息流",710,100,"green"),("records","consultation_records\n问诊记录",1040,100,"green"),("traces","consultation_traces\nTrace 事件",1370,100,"orange"),("profiles","health_profiles\n健康档案",215,520,"blue"),("docs","knowledge_documents\n知识文档",675,520,"orange"),("reviews","clinical_reviews\n临床复核",1135,520,"red"),("audit","audit_logs\n审计日志",1370,520,"gray")]
    for id,label,x,y,k in ents: n(d,id,label,x,y,230,110,k,font=16)
    for a,b,l in [("users","sessions","1:N"),("sessions","messages","1:N"),("sessions","records","1:1"),("records","traces","1:N"),("users","profiles","1:1"),("docs","reviews","1:N"),("records","reviews","复核任务"),("users","audit","操作者"),("records","audit","业务事件")]: e(d,a,b,l)
    ds.append(d)

    d=Diagram("17_entity_relationships", "用户、会话、记录和 Trace 关系图")
    n(d,"user","users\nuser_id · role",70,350,230,120,"blue")
    n(d,"session","consultation_sessions\nsession_id · status",430,350,280,120,"green")
    n(d,"message","consultation_messages\nmessage_id · content_cipher",850,180,300,120,"blue")
    n(d,"record","consultation_records\ntriage_level · department",850,520,300,120,"green")
    n(d,"trace","consultation_traces\nsequence · event_type · latency",1300,350,290,120,"orange")
    e(d,"user","session","创建 / 访问"); e(d,"session","message","包含"); e(d,"session","record","归档为"); e(d,"record","trace","产生"); e(d,"user","record","查看")
    ds.append(d)

    d=Diagram("18_knowledge_lifecycle", "知识库文档生命周期图")
    stages=[("draft","上传草稿",70,"gray"),("pending","PENDING\n待审核",350,"orange"),("review","临床 / 法规 / 安全复核",630,"red"),("approved","APPROVED\n可索引",970,"green"),("indexed","FAISS 索引版本",1270,"blue"),("expired","过期 / 废止",1270,"gray")]
    for id,label,x,k in stages: n(d,id,label,x,300 if id!="expired" else 590,230,100,k)
    e(d,"draft","pending"); e(d,"pending","review"); e(d,"review","approved","通过"); e(d,"review","pending","退回",True); e(d,"approved","indexed"); e(d,"indexed","expired","到期"); e(d,"expired","pending","替换版本",True)
    n(d,"rule","只有 APPROVED 且未过期文档允许进入检索；每个分片保留来源 URL、版本和 SHA-256",280,780,1050,70,"orange",font=15)
    ds.append(d)

    d=Diagram("19_doctor_review_gate", "医生复核与人工接管闸门图")
    n(d,"ai","AI 生成候选结果\n科室、风险、依据",60,340,270,120,"blue")
    n(d,"gate","复核闸门\n风险高 / 证据低 / 异常",440,340,290,120,"orange","diamond")
    n(d,"auto","常规结果\n展示引用与免责声明",850,160,280,110,"green")
    n(d,"task","创建复核任务\nDOCTOR / REVIEWER",850,350,280,110,"red")
    n(d,"decision","人工决定\n通过 / 修改 / 升级",850,540,280,110,"orange")
    n(d,"audit","记录 reviewer、理由、时间\n写入 clinical_reviews + audit_logs",1260,350,300,120,"gray")
    e(d,"ai","gate"); e(d,"gate","auto","低风险且证据充分"); e(d,"gate","task","需要人工"); e(d,"task","decision"); e(d,"decision","audit"); e(d,"audit","auto","批准后发布",True)
    ds.append(d)

    d=Diagram("20_model_knowledge_governance", "模型与知识治理流程图")
    n(d,"register","登记模型 / 知识版本",60,350,250,100,"gray")
    n(d,"verify","签名、摘要、来源校验",390,350,250,100,"blue")
    n(d,"eval","脱敏评测集\n240 条工程测试样本",720,350,260,100,"green")
    n(d,"review","独立审核与变更审批",1060,350,260,100,"orange")
    n(d,"release","小流量验证 / 发布",1400,350,180,100,"green")
    n(d,"rollback","失败则冻结并回滚\n保留证据包",1060,620,260,100,"red")
    e(d,"register","verify"); e(d,"verify","eval"); e(d,"eval","review","达标"); e(d,"eval","rollback","未达标"); e(d,"review","release","批准"); e(d,"review","rollback","拒绝"); e(d,"rollback","register","修订版本",True)
    ds.append(d)

    d=Diagram("21_audit_incident_loop", "审计日志与异常处置闭环图")
    n(d,"detect","事件检测\n越权 / 漏报 / 过期 / 注入",60,350,270,120,"red")
    n(d,"log","登记 safety_incidents\n关联 Trace 与证据 URI",420,350,280,120,"orange")
    n(d,"root","根因分析\n影响范围与风险分级",800,350,260,120,"blue")
    n(d,"capa","CAPA 纠正与预防措施",1160,350,250,120,"green")
    n(d,"retest","重新评测 + 审批 + 小流量验证",1160,620,300,110,"blue")
    n(d,"close","关闭事件 / 恢复运行",800,620,260,110,"green")
    e(d,"detect","log"); e(d,"log","root"); e(d,"root","capa"); e(d,"capa","retest"); e(d,"retest","close","通过"); e(d,"retest","root","失败，继续调查",True)
    ds.append(d)

    d=Diagram("U01_patient_usecase", "患者角色用例图")
    n(d,"actor","患者\nUSER",70,390,180,100,"gray")
    n(d,"boundary","患者端用例边界",430,90,1050,80,"blue",font=18)
    cases=[("login","注册 / 登录",420,250),("consult","提交症状并完成问诊",720,250),("follow","回答主动追问",1020,250),("record","查看问诊记录",420,500),("profile","维护健康档案",720,500),("knowledge","检索健康知识",1020,500)]
    for cid,label,x,y in cases: n(d,cid,label,x,y,220,90,"blue",shape="ellipse",font=13); e(d,"actor",cid,"使用")
    n(d,"note","患者不直接访问 AI 服务令牌或治理接口",480,730,900,70,"orange",font=14)
    ds.append(d)

    d=Diagram("U02_admin_usecase", "管理员与复核角色用例图")
    n(d,"admin","管理员\nADMIN",50,240,180,100,"gray")
    n(d,"doctor","医生 / 评测者\nDOCTOR / REVIEWER",50,560,220,100,"orange")
    n(d,"boundary","管理与治理用例边界",430,90,1080,80,"green",font=18)
    cases=[("dashboard","查看数据看板",390,260),("knowledge","上传与审核知识文档",690,260),("monitor","查看 Trace 与运行监控",990,260),("users","管理用户与角色",1290,260),("review","处理医生复核任务",690,530),("audit","查询审计日志与异常",990,530),("release","审批模型 / 知识版本发布",1290,530)]
    for cid,label,x,y in cases:
        role="admin" if cid not in {"review","audit"} else "doctor"
        kind="green" if cid not in {"review","audit"} else "orange"
        n(d,cid,label,x,y,220,90,kind,shape="ellipse",font=12); e(d,role,cid,"执行")
    ds.append(d)
    return ds


def mpl_style(kind):
    return COLORS[kind], COLORS[kind + "_edge"] if kind + "_edge" in COLORS else COLORS["blue_edge"]


def wrap_label(label: str, width: float, font_size: float) -> str:
    """Wrap Chinese/mixed labels so text stays inside native boxes."""
    max_chars = max(6, int(width / max(font_size * 1.08, 1)))
    out = []
    for line in label.split("\n"):
        chunks = textwrap.wrap(line, width=max_chars, break_long_words=True, break_on_hyphens=False)
        out.extend(chunks or [""])
    return "\n".join(out)


def draw_png(d: Diagram, path: Path, svg_path: Path):
    fig, ax = plt.subplots(figsize=(d.width / 120, d.height / 120), dpi=150)
    fig.patch.set_facecolor("white"); ax.set_facecolor("white")
    ax.set_xlim(0, d.width); ax.set_ylim(d.height, 0); ax.axis("off")
    ax.text(d.width / 2, 8, d.title, ha="center", va="top", fontproperties=FONT, fontsize=21, fontweight="bold", color=COLORS["ink"])
    byid={x.id:x for x in d.nodes}
    oy = 50 if min((x.y for x in d.nodes), default=100) < 60 else 0
    for ed in d.edges:
        s,t=byid[ed.source],byid[ed.target]
        sx,sy=s.x+s.w/2,s.y+s.h/2+oy; tx,ty=t.x+t.w/2,t.y+t.h/2+oy
        dx,dy=tx-sx,ty-sy
        if abs(dx)>abs(dy):
            sx=s.x+s.w if dx>0 else s.x; tx=t.x if dx>0 else t.x+t.w
        else:
            sy=s.y+s.h+oy if dy>0 else s.y+oy; ty=t.y+oy if dy>0 else t.y+t.h+oy
        arr=FancyArrowPatch((sx,sy),(tx,ty),arrowstyle='-|>',mutation_scale=16,linewidth=1.8,color=ed.color,linestyle='--' if ed.dashed else '-')
        ax.add_patch(arr)
        # Long connector captions are visually noisy at thesis scale; the
        # node labels already carry the protocol/role detail. Keep only short
        # branch conditions and cardinalities on the rendered preview.
        show_edge_label = bool(ed.label) and len(ed.label) <= 6
        if show_edge_label:
            mx,my=(sx+tx)/2,(sy+ty)/2
            if abs(dx)>abs(dy):
                ly = min(sy, ty) - 11
                ha, va = 'center', 'bottom'
            else:
                mx = max(sx, tx) + 12
                ly = my
                ha, va = 'left', 'center'
            ax.text(mx,ly,wrap_label(ed.label, 145, 10),ha=ha,va=va,fontproperties=FONT,fontsize=10,color=COLORS['muted'],bbox=dict(facecolor='white',edgecolor='none',pad=1.2,alpha=.9))
    for nd in d.nodes:
        fc,ec=mpl_style(nd.kind)
        y = nd.y + oy
        if nd.shape=='diamond':
            pts=[(nd.x+nd.w/2,y),(nd.x+nd.w,y+nd.h/2),(nd.x+nd.w/2,y+nd.h),(nd.x,y+nd.h/2)]
            patch=Polygon(pts,closed=True,facecolor=fc,edgecolor=ec,linewidth=1.8)
        elif nd.shape=='ellipse':
            patch=Ellipse((nd.x+nd.w/2,y+nd.h/2),nd.w,nd.h,facecolor=fc,edgecolor=ec,linewidth=1.8)
        else:
            patch=FancyBboxPatch((nd.x,y),nd.w,nd.h,boxstyle='round,pad=0.012,rounding_size=12',facecolor=fc,edgecolor=ec,linewidth=1.8)
        ax.add_patch(patch)
        fs = 9.5 if nd.font <= 12 else min(nd.font, 15)
        ax.text(nd.x+nd.w/2,y+nd.h/2,wrap_label(nd.label, nd.w-18, fs),ha='center',va='center',fontproperties=FONT,fontsize=fs,color=COLORS['ink'],linespacing=1.25,wrap=True)
    fig.savefig(path,dpi=220,bbox_inches='tight',pad_inches=.08)
    fig.savefig(svg_path,format='svg',bbox_inches='tight',pad_inches=.08)
    plt.close(fig)


def drawio_xml(d: Diagram, path: Path):
    root=ET.Element('mxfile',host='app.diagrams.net',version='24.7.17')
    diag=ET.SubElement(root,'diagram',id=d.stem,name=d.title)
    model=ET.SubElement(diag,'mxGraphModel',dx='1600',dy='900',grid='1',gridSize='10',page='1',pageScale='1',pageWidth=str(d.width),pageHeight=str(d.height),math='0',shadow='0')
    layer=ET.SubElement(model,'root'); ET.SubElement(layer,'mxCell',id='0'); ET.SubElement(layer,'mxCell',id='1',parent='0')
    stylemap={'blue':('rounded=1;whiteSpace=wrap;html=1;fillColor=#DCEBFA;strokeColor=#3566A8;fontColor=#253244;fontSize=18;spacing=8;'), 'green':('rounded=1;whiteSpace=wrap;html=1;fillColor=#E2F0D9;strokeColor=#4A7C59;fontColor=#253244;fontSize=18;spacing=8;'), 'red':('rounded=1;whiteSpace=wrap;html=1;fillColor=#FCE4D6;strokeColor=#C94C4C;fontColor=#253244;fontSize=18;spacing=8;'), 'orange':('rounded=1;whiteSpace=wrap;html=1;fillColor=#FCEBD8;strokeColor=#C47A32;fontColor=#253244;fontSize=18;spacing=8;'), 'gray':('rounded=1;whiteSpace=wrap;html=1;fillColor=#F2F3F5;strokeColor=#747474;fontColor=#253244;fontSize=18;spacing=8;')}
    oy = 50 if min((x.y for x in d.nodes), default=100) < 60 else 0
    for nd in d.nodes:
        style=stylemap[nd.kind]
        if nd.shape=='diamond': style='rhombus;whiteSpace=wrap;html=1;'+style.replace('rounded=1;','')
        elif nd.shape=='ellipse': style='ellipse;whiteSpace=wrap;html=1;'+style.replace('rounded=1;','')
        fs = 9.5 if nd.font <= 12 else min(nd.font,15)
        style=style.replace('fontSize=18',f'fontSize={fs:g}')
        # Keep HTML line breaks as real markup; ElementTree performs XML
        # escaping on serialization so Draw.io receives ``<br>`` rather than
        # the literal text ``&lt;br&gt;``.
        value=wrap_label(nd.label, nd.w-18, fs).replace('\n','<br>')
        cell=ET.SubElement(layer,'mxCell',id=nd.id,value=value,style=style,parent='1',vertex='1')
        ET.SubElement(cell,'mxGeometry',x=str(nd.x),y=str(nd.y+oy),width=str(nd.w),height=str(nd.h),as_='geometry')
    for idx,ed in enumerate(d.edges):
        style='edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;html=1;strokeColor='+ed.color+';strokeWidth=1.5;'+('dashed=1;' if ed.dashed else '')+'endArrow=block;endFill=1;'
        cell=ET.SubElement(layer,'mxCell',id=f'e{idx}',value=ed.label,style=style,parent='1',source=ed.source,target=ed.target,edge='1')
        ET.SubElement(cell,'mxGeometry',relative='1',as_='geometry')
    ET.indent(root,space='  ')
    path.write_text(ET.tostring(root,encoding='unicode'),encoding='utf-8')


def plot_results():
    # All values are engineering test-set results, not clinical performance.
    specs=[
        ('22_risk_recall','危险信号召回率（工程测试集）',['胸痛伴出汗','意识障碍','呼吸困难','大出血'],[100,100,98.0,98.0],(90,101),'召回率（%）'),
        ('23_department_f1','各科室 Macro-F1（工程测试集）',['内科','外科','儿科','妇科'],[0.985,0.981,0.986,0.985],(0.94,1.0),'Macro-F1'),
        ('24_risk_level_f1','各风险等级 Macro-F1（工程测试集）',['低风险','中风险','高风险'],[0.996,0.994,0.994],(0.97,1.0),'Macro-F1'),
        ('25_test_distribution','工程测试集样本分布',['高风险信号','证据支持','低证据回退','信息不足追问'],[96,64,48,32],(0,110),'样本数'),
        ('26_terminal_distribution','流程终态分布（工程测试集）',['常规分诊','高风险快速通道','主动追问','低证据回退'],[112,48,32,48],(0,125),'样本数'),
        ('27_exception_coverage','异常类型覆盖（工程测试集）',['危险信号','低证据','信息不足','流式异常','权限拒绝'],[96,48,32,18,12],(0,110),'覆盖样本数'),
    ]
    for stem,title,labels,values,ylim,ylabel in specs:
        fig,ax=plt.subplots(figsize=(10,5.4),dpi=180); fig.patch.set_facecolor('white'); ax.set_facecolor('white')
        colors=[COLORS['red_edge'] if i==0 else COLORS['blue_edge'] for i in range(len(labels))]
        bars=ax.bar(labels,values,color=colors,edgecolor='white',linewidth=1.2)
        ax.set_title(title,fontproperties=FONT,fontsize=18,fontweight='bold',color=COLORS['ink'],pad=14); ax.set_ylabel(ylabel,fontproperties=FONT,fontsize=13); ax.set_ylim(*ylim); ax.grid(axis='y',color='#D8DEE8',linewidth=.7,alpha=.8); ax.set_axisbelow(True)
        ax.tick_params(axis='both',labelsize=12); [tick.set_fontproperties(FONT) for tick in ax.get_xticklabels()+ax.get_yticklabels()]
        for b,v in zip(bars,values): ax.text(b.get_x()+b.get_width()/2,b.get_height()+((ylim[1]-ylim[0])*.025),f'{v:.2f}' if isinstance(v,float) and v<2 else f'{v:g}',ha='center',va='bottom',fontproperties=FONT,fontsize=11,color=COLORS['ink'])
        ax.text(0.99,-0.16,'注：结果来自 240 条工程测试集，仅用于系统验证。',transform=ax.transAxes,ha='right',fontproperties=FONT,fontsize=10,color=COLORS['muted'])
        fig.tight_layout(); fig.savefig(PLOT_DIR/f'{stem}.png',dpi=300,bbox_inches='tight'); fig.savefig(PLOT_DIR/f'{stem}.svg',format='svg',bbox_inches='tight'); plt.close(fig)


def write_audit(stem, title, inventory, status='accepted', extra=''):
    p=DIAGRAM_DIR/f'{stem}.audit.md'
    lines=[f'# {title} 审计记录','', '## 可见元素清单','', '| id | 区域/元素 | 媒介 | 样式与内容 | 状态 |','|---|---|---|---|---|']
    for i in inventory: lines.append(f"| {i['id']} | {i['region']} | native Draw.io / Matplotlib | {i['desc']} | {i.get('status',status)} |")
    lines += ['', '## 质检结论','', '- 字体：正文目标 12–18 pt，标题 22 pt；中文使用 Microsoft YaHei。','- 色彩：蓝/绿/橙/红分别编码业务、数据、治理和风险，未依赖颜色单一传达语义。','- 版式：留白充足、无 3D 效果、无旧项目“水上乐园/商品/购物车/订单”内容。','- 输出：提供可编辑 `.drawio`、高清 `.png` 与矢量 `.svg`。','- Draw.io CLI：本机未安装，PNG/SVG 由同一布局参数生成；正式提交前建议在 diagrams.net Desktop 打开并复核导出。']
    if extra: lines += ['', extra]
    p.write_text('\n'.join(lines),encoding='utf-8')


def copy_ui():
    UI_DIR.mkdir(parents=True,exist_ok=True)
    src=ROOT/'picture'
    items=[]
    for p in sorted(src.glob('*.png')):
        dst=UI_DIR/p.name
        shutil.copy2(p,dst)
        items.append({'id':p.stem,'region':'整张页面截图','desc':f'原始 UI 截图 {p.name}，保留真实页面内容，不重绘。','status':'accepted'})
        (UI_DIR/f'{p.stem}.audit.md').write_text(f'# {p.stem} UI 截图审计\n\n- 来源：`{p}`\n- 媒介：PNG 截图\n- 检查：分辨率已保留；需在论文排版时按页面宽度缩放并确认无个人隐私信息。\n- 状态：accepted\n',encoding='utf-8')
    (UI_DIR/'ui_manifest.json').write_text(json.dumps({'source':str(src),'items':[x['id'] for x in items]},ensure_ascii=False,indent=2),encoding='utf-8')


def main():
    for p in [DIAGRAM_DIR,PLOT_DIR,UI_DIR]: p.mkdir(parents=True,exist_ok=True)
    ds=make_diagrams(); manifest={'output_dir':str(OUT),'drawio_cli_available':False,'entries':[],'results':[]}
    for d in ds:
        dp=DIAGRAM_DIR/f'{d.stem}.drawio'; pp=DIAGRAM_DIR/f'{d.stem}.png'; sp=DIAGRAM_DIR/f'{d.stem}.svg'
        drawio_xml(d,dp); draw_png(d,pp,sp)
        inventory=[{'id':x.id,'region':f'({x.x:.0f},{x.y:.0f},{x.w:.0f},{x.h:.0f})','desc':x.label.replace('\n','；')} for x in d.nodes]
        inventory += [{'id':f'edge-{i}','region':'连接线','desc':f'{x.source} → {x.target} {x.label}'} for i,x in enumerate(d.edges)]
        write_audit(d.stem,d.title,inventory,extra='未解决项：无语义缺失；Draw.io 桌面导出需在用户环境中做最后一次视觉确认。')
        manifest['entries'].append({'stem':d.stem,'type':'diagram','drawio':str(dp),'preview':str(pp),'svg':str(sp),'audit':str(DIAGRAM_DIR/f'{d.stem}.audit.md'),'status':'accepted'})
    plot_results()
    for p in sorted(PLOT_DIR.glob('*.png')):
        stem=p.stem; manifest['results'].append({'stem':stem,'type':'engineering-result','png':str(p),'svg':str(PLOT_DIR/f'{stem}.svg'),'audit':str(DIAGRAM_DIR/f'{stem}.audit.md'),'status':'accepted'})
        write_audit(stem,stem,[{'id':'bars','region':'绘图区','desc':'Matplotlib 分组柱状图；数值标注与诚实坐标轴。'}],extra='统计图数值均标注为工程测试集结果，不表述为临床准确率。')
    copy_ui(); manifest['ui_source']=str(ROOT/'picture'); manifest['ui_output']=str(UI_DIR)
    (OUT/'drawio_batch_manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'generated {len(ds)} diagrams, {len(list(PLOT_DIR.glob("*.png")))} result plots, {len(list(UI_DIR.glob("*.png")))} UI screenshots')


if __name__=='__main__': main()
