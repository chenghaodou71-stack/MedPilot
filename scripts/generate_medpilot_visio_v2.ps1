param([string]$Only = '')

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'visio_helpers_utf8.ps1')

$root = Split-Path -Parent $PSScriptRoot
$vsdxDir = Join-Path $root 'outputs\visio-diagrams-v2'
$pngDir = Join-Path $root 'outputs\thesis-visio-figures-v2'
$modelDir = Join-Path $root 'outputs\visio-graph-models-v2'
New-Item -ItemType Directory -Path $vsdxDir -Force | Out-Null
New-Item -ItemType Directory -Path $pngDir -Force | Out-Null
New-Item -ItemType Directory -Path $modelDir -Force | Out-Null

$flow = 'BASFLO_M.VSSX'
$basic = 'BASIC_M.VSSX'
$connector = 'Dynamic connector'
$black = 'RGB(0,0,0)'
$white = 'RGB(255,255,255)'
$gray = 'RGB(242,242,242)'
$visioContent2052 = Join-Path ${env:ProgramFiles} 'Microsoft Office\root\Office16\Visio Content\2052'
$umlUsecase = Join-Path $visioContent2052 'UML_USECASE_M.VSTX'
$dbuml = Join-Path $visioContent2052 'DBUML_M.VSSX'

function Node([string]$id,[string]$text,[double]$x,[double]$y,[double]$w=1.45,[double]$h=0.62,[string]$semantic='process',[string]$stencil=$flow,[string]$master='',[string]$fill=$white,[int]$bold=0,[double]$size=10.5) {
  $m = if ($master) { $master } else { Get-VisioGraphModelSemanticMaster -SemanticType $semantic }
  $renderText = $text -replace '\\n', "`n"
  [pscustomobject]@{ id=$id; text=$renderText; semanticType=$semantic; stencil=$stencil; preferredMaster=$m; x=$x; y=$y; width=$w; height=$h; style=[pscustomobject]@{fill=$fill;line=$black;lineWeight='1 pt';textColor=$black;textSize=$size;bold=$bold} }
}
function Edge([string]$from,[string]$to,[string]$text='',[string]$route='orthogonal',[Nullable[double]]$fromX=$null,[Nullable[double]]$fromY=$null,[Nullable[double]]$toX=$null,[Nullable[double]]$toY=$null,[int]$arrow=4) {
  $e = [ordered]@{from=$from;to=$to;connector=$connector;stencil=$flow;routeType=$route;style=[pscustomobject]@{lineColor=$black;lineWeight='1 pt';endArrow=$arrow}}
  if ($text) { $e.text=$text }
  if ($null -ne $fromX) { $e.fromX=$fromX }
  if ($null -ne $fromY) { $e.fromY=$fromY }
  if ($null -ne $toX) { $e.toX=$toX }
  if ($null -ne $toY) { $e.toY=$toY }
  [pscustomobject]$e
}
function IncludeEdge([string]$from,[string]$to) {
  $e = Edge $from $to '<<include>>' 'straight'
  $e.stencil = $flow
  $e.style | Add-Member -NotePropertyName linePattern -NotePropertyValue 2 -Force
  return $e
}
function AssociationEdge([string]$from,[string]$to) {
  $e = Edge $from $to '' 'straight' $null $null $null $null 0
  $e.stencil = $flow
  $e.style | Add-Member -NotePropertyName linePattern -NotePropertyValue 1 -Force
  return $e
}
function RelationEdge([string]$from,[string]$to,[string]$label) {
  $e = Edge $from $to $label 'straight' $null $null $null $null 0
  $e.stencil = $flow
  $e.style | Add-Member -NotePropertyName linePattern -NotePropertyValue 1 -Force
  return $e
}
function Model([string]$name,[string]$caption,[double]$w,[double]$h,$nodes,$edges) {
  [pscustomobject]@{name=$name;caption=$caption;diagramType='flowchart';page=[pscustomobject]@{width=$w;height=$h};nodes=@($nodes);edges=@($edges);layout=[pscustomobject]@{strategy='Flowchart';direction='LR'}}
}
function Render-VisioGraphModelFast {
  param(
    [Parameter(Mandatory = $true)] $GraphModel,
    [Parameter(Mandatory = $true)] [string] $OutputPath,
    [Parameter(Mandatory = $true)] [string] $PreviewPath
  )

  $OutputPath = [System.IO.Path]::GetFullPath($OutputPath)
  $PreviewPath = [System.IO.Path]::GetFullPath($PreviewPath)
  foreach ($path in @($OutputPath, $PreviewPath)) {
    $parent = Split-Path -Path $path -Parent
    if ($parent) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
  }

  # Fixed coordinates and explicit glue points avoid the expensive global route
  # optimizer while preserving native Visio masters and editable connectors.
  $visio = New-VisibleVisioApplication
  $openedStencils = @{}
  $shapeById = @{}
  $doc = $null
  try {
    $doc = $visio.Documents.Add('')
    $page = $visio.ActivePage
    if ($GraphModel.page.width) { $null = $page.PageSheet.CellsU('PageWidth').FormulaU = "$($GraphModel.page.width) in" }
    if ($GraphModel.page.height) { $null = $page.PageSheet.CellsU('PageHeight').FormulaU = "$($GraphModel.page.height) in" }

    foreach ($node in @($GraphModel.nodes)) {
      $stencilName = if ($node.stencil) { [string]$node.stencil } else { 'BASFLO_M.VSSX' }
      if (-not $openedStencils.ContainsKey($stencilName)) {
        $openedStencils[$stencilName] = Open-VisioStencilReadOnly -Visio $visio -StencilNameOrPath $stencilName
      }
      $masterName = if ($node.preferredMaster) { [string]$node.preferredMaster } elseif ($node.master) { [string]$node.master } elseif ($node.semanticType) { Get-VisioGraphModelSemanticMaster -SemanticType ([string]$node.semanticType) } else { 'Process' }
      $master = $openedStencils[$stencilName].Masters.ItemU($masterName)
      $x = if ($null -ne $node.x) { [double]$node.x } else { 1.0 }
      $y = if ($null -ne $node.y) { [double]$node.y } else { 1.0 }
      $width = if ($null -ne $node.width) { [double]$node.width } else { 1.4 }
      $height = if ($null -ne $node.height) { [double]$node.height } else { 0.6 }
      $shape = $page.Drop($master, $x, $y)
      try {
        Set-VisioShapeBounds -Shape $shape -X $x -Y $y -Width $width -Height $height
      } catch {
        # UML/DBUML masters may protect their native dimensions; preserve the
        # semantic master and at least place it by its pin coordinates.
        try { $null = $shape.CellsU('PinX').FormulaU = "$x in" } catch {}
        try { $null = $shape.CellsU('PinY').FormulaU = "$y in" } catch {}
      }
      if ($null -ne $node.text) { $shape.Text = [string]$node.text }
      if ($node.style) {
        $fill = if ($node.style.fill) { [string]$node.style.fill } else { $white }
        $line = if ($node.style.line) { [string]$node.style.line } else { $black }
        $lineWeight = if ($node.style.lineWeight) { [string]$node.style.lineWeight } else { '1 pt' }
        try { Set-VisioShapeFill -Shape $shape -Fill $fill -Line $line -LineWeight $lineWeight } catch {}
        $textColor = if ($node.style.textColor) { [string]$node.style.textColor } else { $black }
        $textSize = if ($node.style.textSize) { [double]$node.style.textSize } else { 10 }
        $bold = if ($null -ne $node.style.bold) { [int]$node.style.bold } else { 0 }
        try { Set-VisioTextStyle -Shape $shape -Color $textColor -Size $textSize -Bold $bold } catch {}
      }
      $shapeById[[string]$node.id] = $shape
    }

    foreach ($edge in @($GraphModel.edges)) {
      $fromShape = $shapeById[[string]$edge.from]
      $toShape = $shapeById[[string]$edge.to]
      if ($null -eq $fromShape -or $null -eq $toShape) { throw "Graph edge references missing node: $($edge.from) -> $($edge.to)" }
      $stencilName = if ($edge.stencil) { [string]$edge.stencil } else { 'BASFLO_M.VSSX' }
      if (-not $openedStencils.ContainsKey($stencilName)) {
        $openedStencils[$stencilName] = Open-VisioStencilReadOnly -Visio $visio -StencilNameOrPath $stencilName
      }
      $connectorName = if ($edge.connector) { [string]$edge.connector } else { 'Dynamic connector' }
      $connectorMaster = $openedStencils[$stencilName].Masters.ItemU($connectorName)
      $glue = Resolve-VisioConnectorGluePoints -From $fromShape -To $toShape -FromX $edge.fromX -FromY $edge.fromY -ToX $edge.toX -ToY $edge.toY
      if ([string]$edge.route -eq 'straight') {
        $connector = Connect-VisioShapesStraight -Page $page -ConnectorMaster $connectorMaster -From $fromShape -To $toShape -FromX $glue.FromX -FromY $glue.FromY -ToX $glue.ToX -ToY $glue.ToY
      } elseif ([string]$edge.route -eq 'curved') {
        $connector = Connect-VisioShapesCurved -Page $page -ConnectorMaster $connectorMaster -From $fromShape -To $toShape -FromX $glue.FromX -FromY $glue.FromY -ToX $glue.ToX -ToY $glue.ToY
      } else {
        $connector = Connect-VisioShapesOrthogonal -Page $page -ConnectorMaster $connectorMaster -From $fromShape -To $toShape -FromX $glue.FromX -FromY $glue.FromY -ToX $glue.ToX -ToY $glue.ToY
      }
      if ($edge.text) { $connector.Text = [string]$edge.text }
      if ($edge.style) {
        if ($edge.style.lineColor) { $null = $connector.CellsU('LineColor').FormulaU = [string]$edge.style.lineColor }
        if ($edge.style.lineWeight) { $null = $connector.CellsU('LineWeight').FormulaU = [string]$edge.style.lineWeight }
        if ($null -ne $edge.style.linePattern) { $null = $connector.CellsU('LinePattern').FormulaU = [string]$edge.style.linePattern }
        if ($null -ne $edge.style.endArrow) { $null = $connector.CellsU('EndArrow').FormulaU = "$([int]$edge.style.endArrow)" }
      }
    }
    $null = $doc.SaveAs($OutputPath)
    $null = $page.Export($PreviewPath)
  } finally {
    foreach ($stencil in $openedStencils.Values) { try { $null = $stencil.Close() } catch {} }
    if ($null -ne $doc) { try { $null = $doc.Close() } catch {} }
    try { $null = $visio.Quit() } catch {}
  }
}

function Save-Model($model) {
  $jsonPath = Join-Path $modelDir ($model.name + '.json')
  $outPath = Join-Path $vsdxDir ($model.name + '.vsdx')
  $pngPath = Join-Path $pngDir ($model.name + '.png')
  $model | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
  Render-VisioGraphModelFast -GraphModel $model -OutputPath $outPath -PreviewPath $pngPath
  [pscustomobject]@{name=$model.name;caption=$model.caption;vsdx=$outPath;png=$pngPath;model=$jsonPath}
}

$models = New-Object System.Collections.ArrayList

# 1. Strict top-platform / horizontal-bus / aligned-column structure requested by the user.
$ns = @((Node 'platform' 'MedPilot 医疗多智能体辅助分诊平台' 5.85 7.05 3.7 0.7 'process' $basic 'Rectangle' $white 1 12))
$es = @()
$labels = @('用户接入','账户与权限','智能问诊','红旗筛查','主动追问','医学检索','辅助分诊','健康档案','医生复核','监控审计')
for ($i=0; $i -lt $labels.Count; $i++) {
  $x = 0.85 + ($i * 1.12)
  $id = 'f' + $i
  $ns += Node $id $labels[$i] $x 3.05 0.86 2.25 'process' $basic 'Rectangle' $white 1 9.5
}
$ns += Node 'bus' '' 5.85 5.75 10.6 0.03 'process' $basic 'Rectangle' $black 0 1
$es += Edge 'platform' 'bus' '' 'orthogonal' 0.5 0 0.5 1 0
for ($i=0; $i -lt $labels.Count; $i++) { $es += Edge 'bus' ('f'+$i) '' 'orthogonal' 0.5 0 0.5 1 0 }
$models.Add((Model '图2-1_系统功能结构图' '图2-1 MedPilot 系统功能结构图' 11.7 8.3 $ns $es)) | Out-Null

# 2. Roles and permission boundary.
$ns=@((Node 'user' 'USER 患者' 1.2 6.1 1.45 0.68 'process' $basic 'Rectangle' $white 1 10), (Node 'doctor' 'DOCTOR 医生' 3.1 6.1 1.45 0.68 'process' $basic 'Rectangle' $white 1 10), (Node 'reviewer' 'REVIEWER 复核员' 5.0 6.1 1.65 0.68 'process' $basic 'Rectangle' $white 1 10), (Node 'editor' 'KNOWLEDGE_EDITOR' 7.1 6.1 1.85 0.68 'process' $basic 'Rectangle' $white 1 9), (Node 'admin' 'ADMIN 管理员' 9.25 6.1 1.55 0.68 'process' $basic 'Rectangle' $white 1 10), (Node 'audit' 'AUDITOR 审计员' 10.55 3.75 1.45 0.68 'process' $basic 'Rectangle' $white 1 9), (Node 'boundary' '统一安全边界\nHttpOnly Cookie · CSRF · 医疗关系 · 院区 · MFA' 5.85 1.25 6.9 0.8 'process' $basic 'Rectangle' $gray 1 10))
$es=@(); foreach($id in @('user','doctor','reviewer','editor','admin','audit')){$es += Edge $id 'boundary' '' 'orthogonal' 0.5 0 0.5 1 0}; $models.Add((Model '图2-2_用户角色与权限边界图' '图2-2 用户角色与权限边界图' 11.7 8.3 $ns $es)) | Out-Null

# 3. Consultation state machine.
$ns=@((Node 'draft' 'DRAFT 草稿' 1.1 4.25 1.45 0.65 'process' $basic 'Rounded Rectangle' $white 1 10), (Node 'screen' 'SAFETY_SCREEN' 3.15 4.25 1.65 0.65 'process' $basic 'Rectangle' $white 1 9.5), (Node 'follow' 'AWAITING_FOLLOWUP' 5.35 6.0 1.9 0.65 'process' $basic 'Rectangle' $white 1 9), (Node 'ready' 'READY_FOR_RETRIEVAL' 5.35 4.25 1.9 0.65 'process' $basic 'Rectangle' $white 1 9), (Node 'running' 'RUNNING' 7.7 4.25 1.4 0.65 'process' $basic 'Rectangle' $white 1 10), (Node 'review' 'REVIEW_REQUIRED' 9.75 6.0 1.85 0.65 'process' $basic 'Rectangle' $white 1 9), (Node 'done' 'COMPLETED / ABSTAINED' 9.75 2.45 2.0 0.65 'end' $basic 'Rounded Rectangle' $white 1 9))
$es=@((Edge 'draft' 'screen'),(Edge 'screen' 'follow' '信息不足'),(Edge 'screen' 'ready' '信息充分'),(Edge 'ready' 'running'),(Edge 'running' 'review' '需人工复核'),(Edge 'running' 'done' '安全输出'),(Edge 'review' 'done' '人工决定'),(Edge 'follow' 'screen' '补充后重新筛查')); $models.Add((Model '图2-3_咨询业务状态机图' '图2-3 咨询业务状态机图' 11.7 8.3 $ns $es)) | Out-Null

# 4. Overall layered architecture.
$ns=@((Node 'ui' '交互层\nVue 3 患者端 / 医生端 / 管理端' 1.7 6.75 2.45 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'api' '业务层\nSpring Boot REST / JWT / 审计' 4.55 6.75 2.35 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'ai' '智能层\nFastAPI / LangGraph / NDJSON' 7.35 6.75 2.35 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'data' '数据层\nMySQL / FAISS / Trace' 4.55 4.45 2.35 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'infra' '基础设施层\nOllama / 对象存储 / 备份' 7.35 2.15 2.35 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'boundary' '内部服务边界：浏览器不直连 AI；服务间使用受控令牌' 5.85 0.7 6.5 0.65 'process' $basic 'Rectangle' $gray 1 9.5))
$es=@((Edge 'ui' 'api'),(Edge 'api' 'ai'),(Edge 'api' 'data'),(Edge 'ai' 'data'),(Edge 'ai' 'infra'),(Edge 'api' 'boundary' '' 'orthogonal' 0.5 0 0.5 1 0)); $models.Add((Model '图3-1_系统总体分层架构图' '图3-1 MedPilot 系统总体分层架构图' 11.7 8.3 $ns $es)) | Out-Null

# 5. Trust boundaries and interface chain.
$ns=@((Node 'browser' '浏览器\n用户 / 管理端' 1.0 4.3 1.55 0.8 'process' $basic 'Rectangle' $white 1 10),(Node 'front' '前端代理\nJSON / NDJSON' 3.0 4.3 1.6 0.8 'process' $basic 'Rectangle' $white 1 10),(Node 'backend' 'Spring Boot\n认证 / 业务 / 事务' 5.15 4.3 1.85 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'ai' 'FastAPI\n工作流 / 模型令牌' 7.55 4.3 1.75 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'model' 'Ollama / FAISS\n推理与检索资源' 10.05 4.3 1.8 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'db' 'MySQL / 审计\n持久化边界' 5.15 1.9 1.85 0.8 'database' $basic 'Can' $white 1 9.5),(Node 'token' 'X-MedPilot-Service-Token\n仅服务端可见' 7.55 1.9 2.0 0.8 'document' $basic 'Rectangle' $gray 1 9))
$es=@((Edge 'browser' 'front' 'HTTPS'),(Edge 'front' 'backend' 'JSON / Cookie'),(Edge 'backend' 'ai' '内部令牌'),(Edge 'ai' 'model' '本地 HTTP'),(Edge 'backend' 'db' 'JPA 事务'),(Edge 'backend' 'token' '校验')); $models.Add((Model '图3-2_信任边界与接口调用链图' '图3-2 信任边界与接口调用链图' 11.7 8.3 $ns $es)) | Out-Null

# 6. Core database ER view.
$ns=@((Node 'users' 'users\nPK id · role · MFA' 1.0 6.1 1.75 0.85 'process' $basic 'Rectangle' $white 1 9),(Node 'profiles' 'health_profiles\nuser_id · consent' 1.0 3.8 1.75 0.85 'process' $basic 'Rectangle' $white 1 9),(Node 'sessions' 'consultation_sessions\nuser_id · status' 3.55 6.1 2.05 0.85 'process' $basic 'Rectangle' $white 1 9),(Node 'messages' 'consultation_messages\nsession_id · role' 3.55 3.8 2.05 0.85 'process' $basic 'Rectangle' $white 1 9),(Node 'records' 'consultation_records\ntriage · trace_id' 6.35 6.1 2.05 0.85 'process' $basic 'Rectangle' $white 1 9),(Node 'reviews' 'clinical_reviews\ndecision · reviewer' 6.35 3.8 2.05 0.85 'process' $basic 'Rectangle' $white 1 9),(Node 'traces' 'consultation_traces\nsequence · payload' 9.15 6.1 2.05 0.85 'process' $basic 'Rectangle' $white 1 9),(Node 'knowledge' 'knowledge_documents\nstatus · index_version' 9.15 3.8 2.05 0.85 'process' $basic 'Rectangle' $white 1 9))
$es=@((Edge 'users' 'profiles'),(Edge 'users' 'sessions'),(Edge 'sessions' 'messages'),(Edge 'sessions' 'records'),(Edge 'records' 'reviews'),(Edge 'records' 'traces'),(Edge 'knowledge' 'traces')); $models.Add((Model '图3-3_核心数据库ER图' '图3-3 MedPilot 核心数据库 ER 图' 11.7 8.3 $ns $es)) | Out-Null

# 7. Knowledge governance lifecycle.
$ns=@((Node 'submit' '文档提交' 1.0 4.3 1.35 0.7 'start' $flow 'Start/End' $white 1 10),(Node 'parse' '解析与脱敏' 2.95 4.3 1.45 0.7 'process' $flow 'Process' $white 1 10),(Node 'review' '人工审核' 4.95 4.3 1.45 0.7 'process' $flow 'Process' $white 1 10),(Node 'approved' 'APPROVED' 6.95 5.85 1.45 0.7 'process' $basic 'Rectangle' $white 1 10),(Node 'rejected' 'REJECTED' 6.95 2.75 1.45 0.7 'process' $basic 'Rectangle' $white 1 10),(Node 'build' '索引构建\nmanifest + hash' 8.95 5.85 1.85 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'active' 'ACTIVE_INDEX' 10.65 4.3 1.65 0.7 'end' $flow 'Start/End' $white 1 9.5),(Node 'rollback' '失败回滚\n保持旧 active' 8.95 2.75 1.85 0.7 'process' $basic 'Rectangle' $gray 1 9.5))
$es=@((Edge 'submit' 'parse'),(Edge 'parse' 'review'),(Edge 'review' 'approved' '通过'),(Edge 'review' 'rejected' '不通过'),(Edge 'approved' 'build'),(Edge 'build' 'active' '成功'),(Edge 'build' 'rollback' '失败'),(Edge 'rollback' 'active' '保留旧版')); $models.Add((Model '图3-4_知识库治理生命周期图' '图3-4 医学知识库治理生命周期图' 11.7 8.3 $ns $es)) | Out-Null

# 8. Ordinary consultation flow.
$ns=@((Node 'start' '输入症状' 0.9 4.3 1.2 0.7 'start' $flow 'Start/End' $white 1 10),(Node 'screen' '红旗安全筛查' 2.55 4.3 1.55 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'extract' '症状结构化抽取' 4.55 4.3 1.7 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'enough' '信息充分？' 6.65 4.3 1.35 0.7 'decision' $flow 'Decision' $white 1 10),(Node 'follow' '一次追问一个缺口' 6.65 6.15 1.75 0.7 'process' $flow 'Process' $white 1 9),(Node 'retrieve' '证据检索与排序' 8.65 4.3 1.65 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'compose' '安全回答编排' 10.65 4.3 1.55 0.7 'end' $flow 'Start/End' $white 1 9.5))
$es=@((Edge 'start' 'screen'),(Edge 'screen' 'extract' '未命中红旗'),(Edge 'extract' 'enough'),(Edge 'enough' 'follow' '否'),(Edge 'follow' 'screen' '补充后'),(Edge 'enough' 'retrieve' '是'),(Edge 'retrieve' 'compose')); $models.Add((Model '图4-1_普通咨询流程图' '图4-1 普通咨询流程图' 11.7 8.3 $ns $es)) | Out-Null

# 9. Red-flag fast lane.
$ns=@((Node 'input' '本轮原始文本' 1.0 4.3 1.5 0.7 'start' $flow 'Start/End' $white 1 10),(Node 'rules' '规则匹配\n危险信号 + 否定表达' 3.15 4.3 2.0 0.7 'process' $flow 'Process' $white 1 9),(Node 'hit' '命中红旗？' 5.8 4.3 1.45 0.7 'decision' $flow 'Decision' $white 1 10),(Node 'fast' '高风险快速通道\n急诊 / 急救提示' 8.25 5.85 2.05 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'normal' '普通路径\n进入结构化抽取' 8.25 2.75 2.05 0.8 'process' $basic 'Rectangle' $white 1 9.5),(Node 'audit' '规则命中原因\n写入 Trace 与审计' 10.65 5.85 1.65 0.8 'end' $flow 'Start/End' $white 1 8.5))
$es=@((Edge 'input' 'rules'),(Edge 'rules' 'hit'),(Edge 'hit' 'fast' '是'),(Edge 'hit' 'normal' '否'),(Edge 'fast' 'audit')); $models.Add((Model '图4-2_红旗高风险快速通道图' '图4-2 红旗高风险快速通道图' 11.7 8.3 $ns $es)) | Out-Null

# 10. Insufficient information and follow-up loop.
$ns=@((Node 'state' '结构化症状状态' 1.0 4.3 1.75 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'check' '检查必需字段\n持续时间 / 严重程度 / 伴随症状' 3.65 4.3 2.35 0.7 'process' $basic 'Rectangle' $white 1 8.5),(Node 'decision' '存在关键缺口？' 6.55 4.3 1.7 0.7 'decision' $flow 'Decision' $white 1 9.5),(Node 'ask' '生成一个关键追问' 6.55 6.05 1.7 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'wait' '等待用户补充' 8.8 6.05 1.7 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'ready' '进入检索与分诊' 8.8 2.7 1.7 0.7 'end' $flow 'Start/End' $white 1 9.5))
$es=@((Edge 'state' 'check'),(Edge 'check' 'decision'),(Edge 'decision' 'ask' '是'),(Edge 'ask' 'wait'),(Edge 'wait' 'state' '补充文本'),(Edge 'decision' 'ready' '否')); $models.Add((Model '图4-3_信息不足与主动追问流程图' '图4-3 信息不足与主动追问流程图' 11.7 8.3 $ns $es)) | Out-Null

# 11. RAG flow.
$ns=@((Node 'docs' '审核通过文档' 0.9 5.8 1.55 0.7 'document' $flow 'Document' $white 1 9.5),(Node 'chunk' '切分 + 元数据' 2.8 5.8 1.45 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'embed' 'bge-m3 向量化' 4.7 5.8 1.55 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'index' 'FAISS 版本索引' 6.7 5.8 1.65 0.7 'database' $flow 'Database' $white 1 9.5),(Node 'query' '用户症状查询' 0.9 2.55 1.55 0.7 'start' $flow 'Start/End' $white 1 9.5),(Node 'hybrid' '向量 + 词法融合' 3.25 2.55 1.75 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'evidence' 'RankedEvidence\n引用快照' 5.75 2.55 1.75 0.7 'process' $basic 'Rectangle' $white 1 9),(Node 'answer' '证据约束回答\n禁止诊断与处方' 8.4 2.55 2.0 0.8 'end' $basic 'Rectangle' $white 1 9))
$es=@((Edge 'docs' 'chunk'),(Edge 'chunk' 'embed'),(Edge 'embed' 'index'),(Edge 'query' 'hybrid'),(Edge 'index' 'hybrid'),(Edge 'hybrid' 'evidence'),(Edge 'evidence' 'answer')); $models.Add((Model '图4-4_医学RAG检索增强流程图' '图4-4 医学 RAG 检索增强流程图' 11.7 8.3 $ns $es)) | Out-Null

# 12. Evidence weighted triage.
$ns=@((Node 'symptoms' '结构化症状' 0.9 4.3 1.55 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'evidence' '证据集合\ncitation_id / score / source' 3.2 4.3 2.0 0.7 'process' $basic 'Rectangle' $white 1 9),(Node 'weight' '科室与风险加权' 5.95 4.3 1.8 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'classify' '辅助分诊分类\n科室 / 风险 / 时效' 8.45 4.3 2.0 0.8 'process' $basic 'Rectangle' $white 1 9),(Node 'abstain' '证据不足\nabstained / 线下分诊' 8.45 6.1 2.0 0.75 'end' $flow 'Start/End' $gray 1 9),(Node 'output' '受限输出\n引用 + 边界声明' 10.65 2.55 1.55 0.75 'end' $flow 'Start/End' $white 1 9))
$es=@((Edge 'symptoms' 'evidence'),(Edge 'evidence' 'weight'),(Edge 'weight' 'classify'),(Edge 'classify' 'output' '证据充分'),(Edge 'classify' 'abstain' '证据不足')); $models.Add((Model '图4-5_证据加权与辅助分诊流程图' '图4-5 证据加权与辅助分诊流程图' 11.7 8.3 $ns $es)) | Out-Null

# 13. Clinical review safety gate.
$ns=@((Node 'ai' 'AI 原始结果\n只读保存' 1.0 4.3 1.75 0.75 'process' $flow 'Process' $white 1 9.5),(Node 'relation' '医疗关系校验' 3.35 4.3 1.65 0.75 'process' $flow 'Process' $white 1 9.5),(Node 'campus' '院区匹配' 5.55 4.3 1.45 0.75 'process' $flow 'Process' $white 1 9.5),(Node 'mfa' 'MFA ≥ 2' 7.55 4.3 1.2 0.75 'process' $flow 'Process' $white 1 10),(Node 'claim' '领取复核\n禁止自复核' 9.45 5.95 1.7 0.75 'process' $basic 'Rectangle' $white 1 9),(Node 'decide' '人工决定\n确认 / 修改 / 升级' 9.45 2.7 1.9 0.8 'process' $basic 'Rectangle' $white 1 9),(Node 'fail' '任一失败\nfail-closed + 审计' 6.45 1.2 2.0 0.75 'end' $flow 'Start/End' $gray 1 9))
$es=@((Edge 'ai' 'relation'),(Edge 'relation' 'campus'),(Edge 'campus' 'mfa'),(Edge 'mfa' 'claim' '通过'),(Edge 'claim' 'decide'),(Edge 'relation' 'fail' '失败'),(Edge 'campus' 'fail' '失败'),(Edge 'mfa' 'fail' '失败')); $models.Add((Model '图4-6_医生复核安全闸门图' '图4-6 医生复核安全闸门图' 11.7 8.3 $ns $es)) | Out-Null

# 14. NDJSON event lifecycle and failure termination.
$ns=@((Node 'node' '节点 started' 0.9 4.3 1.45 0.7 'start' $flow 'Start/End' $white 1 9.5),(Node 'event' 'EventEmitter\nsequence + node + phase' 2.85 4.3 2.0 0.7 'process' $basic 'Rectangle' $white 1 8.5),(Node 'stream' 'NDJSON 流' 5.45 4.3 1.45 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'ui' '前端状态更新' 7.45 4.3 1.65 0.7 'process' $flow 'Process' $white 1 9.5),(Node 'done' 'done 终止事件' 9.7 4.3 1.65 0.7 'end' $flow 'Start/End' $white 1 9.5),(Node 'error' 'error 失败终止\n保存失败 Trace' 7.45 1.8 1.95 0.75 'end' $flow 'Start/End' $gray 1 9))
$es=@((Edge 'node' 'event'),(Edge 'event' 'stream'),(Edge 'stream' 'ui'),(Edge 'ui' 'done'),(Edge 'event' 'error' '异常'),(Edge 'stream' 'error' '断流')); $models.Add((Model '图4-7_NDJSON事件生命周期与失败终止图' '图4-7 NDJSON 事件生命周期与失败终止图' 11.7 8.3 $ns $es)) | Out-Null

# 15. Deployment/service boundaries.
$ns=@((Node 'browser' '浏览器' 0.9 4.3 1.2 0.7 'start' $flow 'Start/End' $white 1 10),(Node 'frontend' 'Frontend\nVite / Vue' 2.65 4.3 1.6 0.7 'process' $basic 'Rectangle' $white 1 10),(Node 'backend' 'Backend\nSpring Boot :8080' 4.8 4.3 1.8 0.7 'process' $basic 'Rectangle' $white 1 9.5),(Node 'ai' 'AI Service\nFastAPI :8000' 7.15 4.3 1.8 0.7 'process' $basic 'Rectangle' $white 1 9.5),(Node 'mysql' 'MySQL 8' 4.8 1.95 1.8 0.7 'database' $basic 'Can' $white 1 10),(Node 'ollama' 'Ollama\nqwen2.5 + bge-m3' 7.15 1.95 1.8 0.7 'process' $basic 'Rectangle' $white 1 9),(Node 'faiss' 'FAISS Index Store' 9.65 4.3 1.8 0.7 'database' $basic 'Can' $white 1 9),(Node 'guard' '服务边界\n浏览器不直连 AI；内部 token 仅服务端使用' 5.85 6.45 5.8 0.7 'process' $basic 'Rectangle' $gray 1 9))
$es=@((Edge 'browser' 'frontend'),(Edge 'frontend' 'backend'),(Edge 'backend' 'ai'),(Edge 'backend' 'mysql'),(Edge 'ai' 'ollama'),(Edge 'ai' 'faiss'),(Edge 'backend' 'guard' '' 'orthogonal' 0.5 0 0.5 1 0)); $models.Add((Model '图5-1_系统部署与服务边界图' '图5-1 系统部署与服务边界图' 11.7 8.3 $ns $es)) | Out-Null

# 16. Trace monitoring, audit and replay.
$ns=@((Node 'request' '咨询请求' 0.9 4.3 1.35 0.7 'start' $flow 'Start/End' $white 1 10),(Node 'trace' 'Trace 事件\nsequence / elapsed_ms' 2.85 4.3 1.8 0.7 'process' $basic 'Rectangle' $white 1 9),(Node 'persist' '持久化\nconsultation_traces' 5.3 4.3 1.8 0.7 'database' $basic 'Can' $white 1 9),(Node 'monitor' '监控面板\n节点状态 / 延迟 / 错误' 7.8 4.3 2.0 0.7 'process' $basic 'Rectangle' $white 1 9),(Node 'audit' '审计日志\n操作者 / 资源 / 动作' 10.25 5.95 1.7 0.75 'process' $basic 'Rectangle' $white 1 9),(Node 'replay' '回溯复盘\n版本 + 证据快照' 10.25 2.7 1.7 0.75 'end' $flow 'Start/End' $white 1 9))
$es=@((Edge 'request' 'trace'),(Edge 'trace' 'persist'),(Edge 'persist' 'monitor'),(Edge 'monitor' 'audit'),(Edge 'monitor' 'replay')); $models.Add((Model '图5-2_Trace监控审计与回溯流程图' '图5-2 Trace 监控、审计与回溯流程图' 11.7 8.3 $ns $es)) | Out-Null

# 17. Native UML use-case view following the user's actor/oval/include template.
$ns = @(
  (Node 'uc_patient' '患者' 0.9 5.1 0.75 1.15 'process' $umlUsecase 'Actor' $white 1 10),
  (Node 'uc_doctor' '医生' 0.9 8.4 0.75 1.15 'process' $umlUsecase 'Actor' $white 1 10),
  (Node 'uc_admin' '管理员/编辑者' 0.9 1.9 0.9 1.15 'process' $umlUsecase 'Actor' $white 1 9),
  (Node 'uc_home' '查看首页' 3.1 8.8 2.0 0.7 'process' $umlUsecase 'Use Case' $white 1 10),
  (Node 'uc_auth' '登录 / 注册' 3.1 7.3 2.0 0.7 'process' $umlUsecase 'Use Case' $white 1 10),
  (Node 'uc_consult' '发起智能问诊' 3.2 5.7 2.15 0.72 'process' $umlUsecase 'Use Case' $white 1 9.5),
  (Node 'uc_record' '查看问诊记录' 3.1 4.1 2.05 0.7 'process' $umlUsecase 'Use Case' $white 1 9.5),
  (Node 'uc_profile' '维护健康档案' 3.1 2.6 2.05 0.7 'process' $umlUsecase 'Use Case' $white 1 9.5),
  (Node 'uc_screen' '红旗安全筛查' 6.0 6.9 2.05 0.7 'process' $umlUsecase 'Use Case' $white 1 9.5),
  (Node 'uc_follow' '主动追问' 6.0 5.35 1.9 0.7 'process' $umlUsecase 'Use Case' $white 1 10),
  (Node 'uc_retrieve' '医学证据检索' 8.65 6.9 2.05 0.7 'process' $umlUsecase 'Use Case' $white 1 9.5),
  (Node 'uc_triage' '辅助分诊输出' 11.25 6.9 2.05 0.7 'process' $umlUsecase 'Use Case' $white 1 9.5),
  (Node 'uc_evidence' '查看证据引用' 11.25 5.35 2.05 0.7 'process' $umlUsecase 'Use Case' $white 1 9.5),
  (Node 'uc_review' '医生复核' 8.65 8.8 1.9 0.7 'process' $umlUsecase 'Use Case' $white 1 10),
  (Node 'uc_knowledge' '知识库治理' 11.3 8.8 2.0 0.7 'process' $umlUsecase 'Use Case' $white 1 10),
  (Node 'uc_audit' 'Trace 审计' 14.0 8.8 1.8 0.7 'process' $umlUsecase 'Use Case' $white 1 10)
)
$es = @(
  (AssociationEdge 'uc_patient' 'uc_home'), (AssociationEdge 'uc_patient' 'uc_auth'),
  (AssociationEdge 'uc_patient' 'uc_consult'), (AssociationEdge 'uc_patient' 'uc_record'),
  (AssociationEdge 'uc_patient' 'uc_profile'), (AssociationEdge 'uc_doctor' 'uc_review'),
  (AssociationEdge 'uc_admin' 'uc_knowledge'), (AssociationEdge 'uc_admin' 'uc_audit'),
  (IncludeEdge 'uc_consult' 'uc_screen'), (IncludeEdge 'uc_consult' 'uc_follow'),
  (IncludeEdge 'uc_consult' 'uc_retrieve'), (IncludeEdge 'uc_consult' 'uc_triage'),
  (IncludeEdge 'uc_screen' 'uc_retrieve'), (IncludeEdge 'uc_retrieve' 'uc_triage'),
  (IncludeEdge 'uc_triage' 'uc_evidence'), (IncludeEdge 'uc_record' 'uc_evidence'),
  (IncludeEdge 'uc_review' 'uc_triage'), (IncludeEdge 'uc_knowledge' 'uc_audit')
)
$models.Add((Model '图2-4_用户用例图' '图2-4 MedPilot 用户用例图' 16.0 10.0 $ns $es)) | Out-Null

# 18. Native DBUML entity/attribute/relationship view following the user's ER template.
$ns = @(
  (Node 'er_users' 'users\n用户账户' 2.2 7.9 1.65 0.72 'process' $dbuml 'Entity' $white 1 9.5),
  (Node 'er_profile' 'health_profiles\n健康档案' 2.2 3.8 1.85 0.72 'process' $dbuml 'Entity' $white 1 9),
  (Node 'er_session' 'consultation_sessions\n问诊会话' 6.0 7.9 2.0 0.72 'process' $dbuml 'Entity' $white 1 9),
  (Node 'er_messages' 'consultation_messages\n问诊消息' 6.0 3.8 2.0 0.72 'process' $dbuml 'Entity' $white 1 9),
  (Node 'er_record' 'consultation_records\n问诊记录' 10.0 7.9 1.85 0.72 'process' $dbuml 'Entity' $white 1 9),
  (Node 'er_review' 'clinical_reviews\n临床复核' 10.0 3.8 1.75 0.72 'process' $dbuml 'Entity' $white 1 9),
  (Node 'er_knowledge' 'knowledge_documents\n知识文档' 14.0 7.9 1.85 0.72 'process' $dbuml 'Entity' $white 1 9),
  (Node 'er_trace' 'consultation_traces\n执行轨迹' 14.0 3.8 1.85 0.72 'process' $dbuml 'Entity' $white 1 9),
  (Node 'a_user_id' 'PK user_id' 0.85 8.9 1.45 0.48 'process' $dbuml 'Primary Key Attribute' $white 0 8.5),
  (Node 'a_role' 'role' 0.85 7.25 1.15 0.45 'process' $dbuml 'Attribute' $white 0 9),
  (Node 'a_consent' 'consent_version' 0.75 4.5 1.65 0.45 'process' $dbuml 'Attribute' $white 0 8),
  (Node 'a_conditions' 'conditions' 0.85 2.9 1.35 0.45 'process' $dbuml 'Attribute' $white 0 8.5),
  (Node 'a_session_id' 'PK session_id' 6.0 8.95 1.7 0.48 'process' $dbuml 'Primary Key Attribute' $white 0 8.5),
  (Node 'a_status' 'status' 6.0 6.95 1.1 0.45 'process' $dbuml 'Attribute' $white 0 9),
  (Node 'a_message_id' 'PK message_id' 6.0 2.85 1.65 0.48 'process' $dbuml 'Primary Key Attribute' $white 0 8.5),
  (Node 'a_message_role' 'role' 4.55 3.8 1.05 0.45 'process' $dbuml 'Attribute' $white 0 9),
  (Node 'a_record_id' 'PK record_id' 10.0 8.95 1.5 0.48 'process' $dbuml 'Primary Key Attribute' $white 0 8.5),
  (Node 'a_risk' 'risk_level' 10.0 6.95 1.3 0.45 'process' $dbuml 'Attribute' $white 0 8.5),
  (Node 'a_review_id' 'PK review_id' 10.0 2.85 1.45 0.48 'process' $dbuml 'Primary Key Attribute' $white 0 8.5),
  (Node 'a_decision' 'decision' 11.55 3.8 1.2 0.45 'process' $dbuml 'Attribute' $white 0 8.5),
  (Node 'a_doc_id' 'PK document_id' 14.0 8.95 1.65 0.48 'process' $dbuml 'Primary Key Attribute' $white 0 8.5),
  (Node 'a_version' 'version' 15.45 7.9 1.15 0.45 'process' $dbuml 'Attribute' $white 0 8.5),
  (Node 'a_trace_id' 'PK trace_id' 14.0 2.85 1.35 0.48 'process' $dbuml 'Primary Key Attribute' $white 0 8.5),
  (Node 'a_terminal' 'terminal_phase' 15.45 3.8 1.55 0.45 'process' $dbuml 'Attribute' $white 0 8)
)
$ns += @(
  (Node 'rel_owns' '拥有' 4.0 5.85 1.0 0.58 'process' $dbuml 'Relationship' $white 1 9),
  (Node 'rel_starts' '发起' 4.0 7.9 1.0 0.58 'process' $dbuml 'Relationship' $white 1 9),
  (Node 'rel_contains' '包含' 8.0 5.85 1.0 0.58 'process' $dbuml 'Relationship' $white 1 9),
  (Node 'rel_produces' '生成' 8.0 7.9 1.0 0.58 'process' $dbuml 'Relationship' $white 1 9),
  (Node 'rel_reviewed' '复核' 10.0 5.85 1.0 0.58 'process' $dbuml 'Relationship' $white 1 9),
  (Node 'rel_traced' '追踪' 12.0 5.85 1.0 0.58 'process' $dbuml 'Relationship' $white 1 9),
  (Node 'rel_indexed' '索引' 14.0 5.85 1.0 0.58 'process' $dbuml 'Relationship' $white 1 9)
)
$es = @(
  (RelationEdge 'er_users' 'rel_owns' '1'), (RelationEdge 'rel_owns' 'er_profile' 'n'),
  (RelationEdge 'er_users' 'rel_starts' '1'), (RelationEdge 'rel_starts' 'er_session' 'n'),
  (RelationEdge 'er_session' 'rel_contains' '1'), (RelationEdge 'rel_contains' 'er_messages' 'n'),
  (RelationEdge 'er_session' 'rel_produces' '1'), (RelationEdge 'rel_produces' 'er_record' 'n'),
  (RelationEdge 'er_record' 'rel_reviewed' '1'), (RelationEdge 'rel_reviewed' 'er_review' 'n'),
  (RelationEdge 'er_record' 'rel_traced' '1'), (RelationEdge 'rel_traced' 'er_trace' 'n'),
  (RelationEdge 'er_knowledge' 'rel_indexed' '1'), (RelationEdge 'rel_indexed' 'er_trace' 'n'),
  (AssociationEdge 'a_user_id' 'er_users'), (AssociationEdge 'a_role' 'er_users'),
  (AssociationEdge 'a_consent' 'er_profile'), (AssociationEdge 'a_conditions' 'er_profile'),
  (AssociationEdge 'a_session_id' 'er_session'), (AssociationEdge 'a_status' 'er_session'),
  (AssociationEdge 'a_message_id' 'er_messages'), (AssociationEdge 'a_message_role' 'er_messages'),
  (AssociationEdge 'a_record_id' 'er_record'), (AssociationEdge 'a_risk' 'er_record'),
  (AssociationEdge 'a_review_id' 'er_review'), (AssociationEdge 'a_decision' 'er_review'),
  (AssociationEdge 'a_doc_id' 'er_knowledge'), (AssociationEdge 'a_version' 'er_knowledge'),
  (AssociationEdge 'a_trace_id' 'er_trace'), (AssociationEdge 'a_terminal' 'er_trace')
)
$models.Add((Model '图3-5_核心业务实体关系图' '图3-5 MedPilot 核心业务实体关系图（ER图）' 16.0 10.0 $ns $es)) | Out-Null

$selectedModels = if ($Only) { @($models | Where-Object { $_.name -like "*$Only*" }) } else { @($models) }
if ($selectedModels.Count -eq 0) { throw "No diagram matched -Only '$Only'." }
$results = foreach ($m in $selectedModels) { Save-Model $m }
$results | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $root 'outputs\visio-diagrams-v2\manifest.json') -Encoding UTF8
Write-Output ("Generated {0} Visio diagrams" -f $results.Count)

