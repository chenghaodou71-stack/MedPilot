# MedPilot 运维手册

## 备份

在后端和 AI 服务停止写入或处于低流量窗口时执行：

```powershell
$env:DB_URL = 'jdbc:mysql://db-host:3306/medpilot'
$env:DB_USER = 'medpilot'
$env:DB_PASSWORD = '<secret>'
.\scripts\backup.ps1 -IncludeAttachments
```

备份目录包含 `database.sql`、AI 索引版本和 `manifest.json`。医疗字段在数据库和附件存储中保持 AES-GCM 密文；清单使用 SHA-256，便于恢复前核验。默认保留 30 天，可用 `-RetentionDays` 调整。

## 恢复

恢复会覆盖数据库和本地索引，必须先确认停机窗口并显式传入开关：

```powershell
.\scripts\restore.ps1 -BackupPath .\backups\medpilot-20260810-120000Z -ConfirmRestore
```

脚本先验证清单中的每个文件，再执行恢复。恢复后重启服务，运行 `scripts\preflight.ps1 -Production`，确认健康检查通过后再开放流量。

## 生产预检与发布

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
.\scripts\preflight.ps1 -Production
```

生产环境必须显式提供数据库、服务令牌、JWT 密钥和 Base64 编码的 32 字节数据加密密钥。禁止使用 `dev` 配置中的示例值。建议发布顺序为：备份 -> 构建与测试 -> 预检 -> 健康检查 -> 小流量验证 -> 全量切换；失败时保留上一份镜像、数据库备份和索引版本，按同一脚本回滚。

## 密钥轮换与保留

当前密钥通过环境变量注入，禁止写入仓库。轮换时将旧主密钥保留在 `MEDPILOT_DATA_ENCRYPTION_KEY`，把新密钥加入 `MEDPILOT_DATA_ENCRYPTION_KEYS=v2=<base64-key>`，再设置 `MEDPILOT_DATA_ENCRYPTION_ACTIVE_KEY_ID=v2`；部署兼容版本后触发业务数据重保存，确认旧密文数量为零后撤下旧密钥。数据库备份、审计日志和附件遵循最小保留期限；到期备份由 `backup.ps1` 的保留策略清理，附件由应用的过期清理任务删除。任何导出都应使用脱敏接口，不得导出会话原文或模型提示词。

## 医疗安全边界

图片和音频只生成“待确认草稿”，不会直接进入分诊结论。文本附件仅提取有限 UTF-8/PDF 文本并标注来源；用户确认前不得作为症状事实写入问诊请求。任何高风险命中仍以安全快速通道和人工/急救建议为优先。

## 模型与知识变更

生产预检要求 `MEDPILOT_RUNTIME_MODE=clinical|production`，并校验模型权重 SHA-256、制品签名、提示词/嵌入/知识索引版本和 `FROZEN` 发布状态。模型发布还必须有通过的 `/api/governance/rollback-drills` 证据（恢复时长和数据完整性检查）。任何权重、提示词、嵌入模型或知识索引变化都应新建 `/api/governance/changes` 记录，完成独立审批、验证证据和回滚计划后再执行；禁止覆盖既有冻结版本。

医院本地制度和国内主管部门资料先登记到知识来源台账，默认 `PENDING`，临床复核通过且未过期后才允许进入索引。提示词注入、恶意附件和 PHI 外泄红队测试、漂移/GPU 容量快照、错误分诊事故及 CAPA 均保存在 MySQL 治理表中。完整闸门和证据包见 [模型、知识库与变更治理](model-knowledge-change-governance.md)。
