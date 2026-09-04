# 国内来源登记模板（不可直接激活）

这是登记模板，不是可进入 RAG 的数据文件。导入前由医院知识管理员替换所有占位内容，并在系统中提交真实原文、正式 HTTPS 地址、版本日期和 SHA-256。

```yaml
sourceId: "nhc-local-clinical-quality-<year>"
docId: "hospital-local-quality-policy"
publisher: "本院医务处/信息中心/质量管理部门"
title: "本院医疗质量安全核心制度与急诊分诊流程"
url: "https://<hospital-approved-domain>/<controlled-document>"
domesticOfficial: true
publicationDate: "YYYY-MM-DD"
sourceVersion: "<document-version>"
checksum: "<64-hex-sha256>"
applicableScope: "院区、科室、流程和生效条件"
reviewStatus: PENDING
```

推荐先登记医院本地制度，再登记国家卫生健康委员会、国家药品监督管理局和国家网信部门发布的适用文件。任何来源在临床审核前都必须保持 `PENDING`，不能因为域名看起来官方就自动批准。
