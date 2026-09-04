param([string]$Only = '')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'visio_helpers_utf8.ps1')

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'outputs\visio-reference-style'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$content2052 = Join-Path ${env:ProgramFiles} 'Microsoft Office\root\Office16\Visio Content\2052'
$basicPath = Join-Path $content2052 'BASIC_M.VSSX'
$umlPath = Join-Path $content2052 'UML_USECASE_M.VSTX'
$dbPath = Join-Path $content2052 'DBUML_M.VSSX'
$black = 'RGB(0,0,0)'
$white = 'RGB(255,255,255)'

function Set-ShapeStyle {
  param($Shape,[double]$Size = 10,[int]$Bold = 0,[string]$Fill = $white,[string]$Line = $black,[bool]$Transparent = $false)
  try { $Shape.CellsU('Char.Color').FormulaU = $black } catch {}
  try { $Shape.CellsU('Char.Size').FormulaU = "$Size pt" } catch {}
  try { $Shape.CellsU('Char.Style').FormulaU = "$Bold" } catch {}
  try { $Shape.CellsU('Para.HorzAlign').FormulaU = '1' } catch {}
  try { $Shape.CellsU('VerticalAlign').FormulaU = '1' } catch {}
  try { $Shape.CellsU('Char.Font').FormulaU = 'FONT(\"宋体\")' } catch {}
  if ($Transparent) {
    try { $Shape.CellsU('FillPattern').FormulaU = '0' } catch {}
    try { $Shape.CellsU('LinePattern').FormulaU = '0' } catch {}
  } else {
    try { $Shape.CellsU('FillForegnd').FormulaU = $Fill } catch {}
    try { $Shape.CellsU('FillPattern').FormulaU = '1' } catch {}
    try { $Shape.CellsU('LineColor').FormulaU = $Line } catch {}
    try { $Shape.CellsU('LinePattern').FormulaU = '1' } catch {}
    try { $Shape.CellsU('LineWeight').FormulaU = '0.8 pt' } catch {}
  }
}

function Set-ShapeBounds {
  param($Shape,[double]$X,[double]$Y,[double]$W,[double]$H)
  try { $Shape.CellsU('Width').FormulaU = "$W in" } catch {}
  try { $Shape.CellsU('Height').FormulaU = "$H in" } catch {}
  try { $Shape.CellsU('PinX').FormulaU = "$X in" } catch {}
  try { $Shape.CellsU('PinY').FormulaU = "$Y in" } catch {}
}

function Drop-Shape {
  param($Page,$Stencil,$MasterName,[string]$Text,[double]$X,[double]$Y,[double]$W,[double]$H,[double]$Size = 10,[int]$Bold = 0,[string]$Fill = $white)
  $shape = $Page.Drop($Stencil.Masters.ItemU($MasterName), $X, $Y)
  Set-ShapeBounds $shape $X $Y $W $H
  if ($null -ne $Text) { $shape.Text = $Text -replace '\\n', "`n" }
  Set-ShapeStyle $shape $Size $Bold $Fill $black $false
  return $shape
}

function Drop-Text {
  param($Page,$Basic,[string]$Text,[double]$X,[double]$Y,[double]$W = 0.32,[double]$H = 0.22,[double]$Size = 8.5)
  $shape = Drop-Shape $Page $Basic 'Rectangle' $Text $X $Y $W $H $Size 0 $white
  Set-ShapeStyle $shape $Size 0 $white $black $true
  return $shape
}

function Convert-VerticalText {
  param([string]$Text)
  return (($Text.ToCharArray() | ForEach-Object { [string]$_ }) -join "`n")
}

function Drop-Bar {
  param($Page,$Basic,[double]$X,[double]$Y,[double]$W,[double]$H = 0.02)
  $bar = Drop-Shape $Page $Basic 'Rectangle' '' $X $Y $W $H 1 0 $black
  return $bar
}

function Set-StickLineStyle {
  param($Shape,[double]$Weight = 1.0)
  try { $Shape.CellsU('LineColor').FormulaU = $black } catch {}
  try { $Shape.CellsU('LinePattern').FormulaU = '1' } catch {}
  try { $Shape.CellsU('LineWeight').FormulaU = "$Weight pt" } catch {}
  try { $Shape.CellsU('BeginArrow').FormulaU = '0' } catch {}
  try { $Shape.CellsU('EndArrow').FormulaU = '0' } catch {}
  try { $Shape.CellsU('FillPattern').FormulaU = '0' } catch {}
}

function Set-StickTextStyle {
  param($Shape,[double]$Size = 10)
  try { $Shape.CellsU('Char.Color').FormulaU = $black } catch {}
  try { $Shape.CellsU('Char.Size').FormulaU = "$Size pt" } catch {}
  try { $Shape.CellsU('Char.Font').FormulaU = 'FONT("宋体")' } catch {}
  try { $Shape.CellsU('Char.Style').FormulaU = '0' } catch {}
  try { $Shape.CellsU('Para.HorzAlign').FormulaU = '1' } catch {}
  try { $Shape.CellsU('VerticalAlign').FormulaU = '1' } catch {}
}

function New-StickActor {
  param($Page,$Text,[double]$X,[double]$Y)
  # Invisible anchor preserves glued associations while the visible actor is
  # composed from native, independently editable primitives.
  $anchor = $Page.DrawRectangle($X - 0.38, $Y - 0.84, $X + 0.38, $Y + 0.78)
  try { $anchor.CellsU('FillPattern').FormulaU = '0' } catch {}
  try { $anchor.CellsU('LinePattern').FormulaU = '0' } catch {}

  $head = $Page.DrawOval($X - 0.17, $Y + 0.37, $X + 0.17, $Y + 0.71)
  Set-StickLineStyle $head 1.0
  $body = $Page.DrawLine($X, $Y + 0.37, $X, $Y - 0.18)
  Set-StickLineStyle $body 1.0
  $arms = $Page.DrawLine($X - 0.32, $Y + 0.18, $X + 0.32, $Y + 0.18)
  Set-StickLineStyle $arms 1.0
  $leftLeg = $Page.DrawLine($X, $Y - 0.18, $X - 0.32, $Y - 0.62)
  Set-StickLineStyle $leftLeg 1.0
  $rightLeg = $Page.DrawLine($X, $Y - 0.18, $X + 0.32, $Y - 0.62)
  Set-StickLineStyle $rightLeg 1.0

  $label = $Page.DrawRectangle($X - 0.38, $Y - 0.94, $X + 0.38, $Y - 0.70)
  $label.Text = $Text
  try { $label.CellsU('FillPattern').FormulaU = '0' } catch {}
  try { $label.CellsU('LinePattern').FormulaU = '0' } catch {}
  Set-StickTextStyle $label 10
  return $anchor
}

function Connect-Straight {
  param($Page,$ConnectorMaster,$From,$To,[double]$FromX,[double]$FromY,[double]$ToX,[double]$ToY,[string]$Text = '',[bool]$Dashed = $false,[int]$Arrow = 0)
  $conn = $Page.Drop($ConnectorMaster, 0, 0)
  $conn.CellsU('BeginX').GlueToPos($From, $FromX, $FromY) | Out-Null
  $conn.CellsU('EndX').GlueToPos($To, $ToX, $ToY) | Out-Null
  try { $conn.CellsU('ShapeRouteStyle').FormulaU = '2' } catch {}
  try { $conn.CellsU('ConLineRouteExt').FormulaU = '1' } catch {}
  try { $conn.CellsU('LineColor').FormulaU = $black } catch {}
  try { $conn.CellsU('LineWeight').FormulaU = '0.8 pt' } catch {}
  try { $conn.CellsU('EndArrow').FormulaForceU = "$Arrow" } catch {}
  if ($Dashed) { try { $conn.CellsU('LinePattern').FormulaForceU = '2' } catch {} }
  if ($Text) {
    $conn.Text = $Text
    try { $conn.CellsU('Char.Size').FormulaU = '8 pt' } catch {}
    try { $conn.CellsU('Char.Color').FormulaU = $black } catch {}
    try { $conn.CellsU('Char.Font').FormulaU = 'FONT(\"宋体\")' } catch {}
    try { $conn.CellsU('Para.HorzAlign').FormulaU = '1' } catch {}
    try { $conn.CellsU('VerticalAlign').FormulaU = '1' } catch {}
  }
  return $conn
}

function Connect-Auto {
  param($Page,$ConnectorMaster,$From,$To,[string]$Text = '',[bool]$Dashed = $false,[int]$Arrow = 0)
  $glue = Resolve-VisioConnectorGluePoints -From $From -To $To
  return Connect-Straight $Page $ConnectorMaster $From $To $glue.FromX $glue.FromY $glue.ToX $glue.ToY $Text $Dashed $Arrow
}

function New-Document {
  param($Visio,[double]$W,[double]$H)
  $doc = $Visio.Documents.Add('')
  $page = $Visio.ActivePage
  $page.PageSheet.CellsU('PageWidth').FormulaU = "$W in"
  $page.PageSheet.CellsU('PageHeight').FormulaU = "$H in"
  return [pscustomobject]@{ Doc = $doc; Page = $page }
}

function Save-Document {
  param($DocInfo,[string]$Name)
  $vsdx = Join-Path $outDir "$Name.vsdx"
  $png = Join-Path $outDir "$Name.png"
  if (Test-Path $vsdx) { Remove-Item -LiteralPath $vsdx -Force }
  if (Test-Path $png) { Remove-Item -LiteralPath $png -Force }
  $DocInfo.Doc.SaveAs($vsdx) | Out-Null
  $DocInfo.Page.Export($png) | Out-Null
  return [pscustomobject]@{ name = $Name; vsdx = $vsdx; png = $png }
}

function Build-Hierarchy {
  param($Visio,$Basic,$Connector,[string]$Name,[string]$RootText,[array]$Groups,[double]$W,[double]$H,[switch]$Flat)
  $d = New-Document $Visio $W $H
  $p = $d.Page
  if ($Flat) {
    $root = Drop-Shape $p $Basic 'Rectangle' $RootText ($W / 2) ($H - 0.75) 3.0 0.58 12 0 $white
    $labels = @($Groups)
    $left = 0.7; $gap = ($W - 1.4) / $labels.Count
    $barY = $H - 1.45
    $bar = Drop-Bar $p $Basic ($W / 2) $barY ($W - 1.2) 0.02
    Connect-Straight $p $Connector $root $bar 0.5 0 0.5 0.5 | Out-Null
    for ($i = 0; $i -lt $labels.Count; $i++) {
      $x = $left + ($gap * ($i + 0.5))
      $box = Drop-Shape $p $Basic 'Rectangle' (Convert-VerticalText $labels[$i]) $x 1.65 0.62 2.15 8.5 0 $white
      $busX = ($x - 0.6) / ($W - 1.2)
      Connect-Straight $p $Connector $bar $box $busX 0.5 0.5 1 | Out-Null
    }
  } else {
    $root = Drop-Shape $p $Basic 'Rectangle' $RootText ($W / 2) ($H - 0.75) 2.5 0.58 12 0 $white
    $busY = $H - 1.55
    $wideBar = Drop-Bar $p $Basic ($W / 2) $busY ($W - 1.0) 0.02
    Connect-Straight $p $Connector $root $wideBar 0.5 0 0.5 0.5 | Out-Null
    $groupCenters = @()
    $groupGap = ($W - 1.0) / $Groups.Count
    for ($g = 0; $g -lt $Groups.Count; $g++) {
      $group = $Groups[$g]
      $gx = 0.5 + $groupGap * ($g + 0.5)
      $groupCenters += $gx
      $module = Drop-Shape $p $Basic 'Rectangle' $group.title $gx ($H - 3.0) 1.25 0.58 10 0 $white
      $busX = ($gx - 0.5) / ($W - 1.0)
      Connect-Straight $p $Connector $wideBar $module $busX 0.5 0.5 1 | Out-Null
      $items = @($group.items)
      $itemGap = 0.64
      $itemSpan = ($items.Count - 1) * $itemGap
      $groupBarY = $H - 3.65
      $groupBar = Drop-Bar $p $Basic $gx $groupBarY ($itemSpan + 0.25) 0.02
      Connect-Straight $p $Connector $module $groupBar 0.5 0 0.5 0.5 | Out-Null
      for ($i = 0; $i -lt $items.Count; $i++) {
        $ix = $gx - ($itemSpan / 2) + ($itemGap * $i)
        $item = Drop-Shape $p $Basic 'Rectangle' (Convert-VerticalText $items[$i]) $ix 1.65 0.5 2.0 8.5 0 $white
        $itemBusX = if ($itemSpan -gt 0) { (($ix - ($gx - (($itemSpan + 0.25) / 2))) / ($itemSpan + 0.25)) } else { 0.5 }
        Connect-Straight $p $Connector $groupBar $item $itemBusX 0.5 0.5 1 | Out-Null
      }
    }
  }
  return Save-Document $d $Name
}

function Build-UseCase {
  param($Visio,$Uml,$Basic,$Connector)
  $d = New-Document $Visio 12.5 10.2
  $p = $d.Page
  $actor = New-StickActor $p '患者' 1.0 5.65
  $doctor = New-StickActor $p '医生' 6.85 9.05
  $admin = New-StickActor $p '管理员' 9.55 5.95
  $ucs = @{}
  $specs = @(
    @('home','首页',3.0,9.15,1.55,0.62), @('login','登录',3.0,8.05,1.55,0.62), @('register','注册',3.0,6.95,1.55,0.62),
    @('consult','发起智能问诊',3.05,5.65,1.9,0.68), @('records','查看问诊记录',3.05,4.15,1.9,0.68), @('profile','维护健康档案',3.05,2.75,1.9,0.68),
    @('screen','红旗安全筛查',5.85,8.75,1.8,0.66), @('follow','主动追问',5.85,7.35,1.65,0.66), @('retrieve','医学证据检索',5.85,5.95,1.8,0.66),
    @('triage','辅助分诊输出',8.2,7.35,1.8,0.66), @('evidence','查看证据引用',8.2,5.95,1.8,0.66), @('review','医生复核',8.2,9.05,1.65,0.66),
    @('knowledge','知识库治理',10.75,9.05,1.8,0.66), @('audit','Trace 审计',10.75,7.35,1.7,0.66), @('useradmin','用户与权限管理',11.15,5.95,2.0,0.66)
  )
  foreach ($s in $specs) { $ucs[$s[0]] = Drop-Shape $p $Uml 'Use Case' $s[1] ([double]$s[2]) ([double]$s[3]) ([double]$s[4]) ([double]$s[5]) 9.5 0 $white }
  Connect-Straight $p $Connector $actor $ucs.consult 1 0.5 0 0.5 | Out-Null
  Connect-Straight $p $Connector $doctor $ucs.review 1 0.5 0 0.5 | Out-Null
  Connect-Straight $p $Connector $admin $ucs.useradmin 1 0.5 0 0.5 | Out-Null
  Connect-Straight $p $Connector $ucs.consult $ucs.screen 1 0.55 0 0.45 '<<include>>' $true 4 | Out-Null
  Connect-Straight $p $Connector $ucs.consult $ucs.follow 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-Straight $p $Connector $ucs.consult $ucs.retrieve 1 0.45 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-Straight $p $Connector $ucs.consult $ucs.triage 1 0.4 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-Straight $p $Connector $ucs.records $ucs.evidence 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-Straight $p $Connector $ucs.review $ucs.triage 0.9 0 0.1 1 '<<include>>' $true 4 | Out-Null
  Connect-Straight $p $Connector $ucs.knowledge $ucs.audit 0.5 0 0.5 1 '<<include>>' $true 4 | Out-Null
  return Save-Document $d '图2-4_用户用例图'
}

function Build-ER {
  param($Visio,$Db,$Basic,$Connector)
  $d = New-Document $Visio 16.5 10.2
  $p = $d.Page
  $e = @{}
  $a = @{}
  $r = @{}
  $entities = @(
    @('users','用户账户',2.0,7.8,1.45,0.58), @('profiles','健康档案',2.0,2.8,1.45,0.58),
    @('sessions','问诊会话',6.0,7.8,1.55,0.58), @('messages','问诊消息',6.0,2.8,1.55,0.58),
    @('records','问诊记录',10.0,7.8,1.55,0.58), @('reviews','临床复核',10.0,2.8,1.55,0.58),
    @('knowledge','知识文档',14.2,7.8,1.55,0.58), @('traces','执行轨迹',14.2,2.8,1.55,0.58)
  )
  foreach ($x in $entities) { $e[$x[0]] = Drop-Shape $p $Basic 'Rectangle' $x[1] ([double]$x[2]) ([double]$x[3]) ([double]$x[4]) ([double]$x[5]) 9.5 0 $white }
  $attrs = @(
    @('u_id','用户ID',1.05,9.05,1.1,0.48,'users',1), @('u_role','角色',2.35,9.15,0.9,0.48,'users',0), @('u_phone','手机号',0.65,7.3,1.1,0.48,'users',0),
    @('p_consent','同意版本',0.65,3.45,1.2,0.48,'profiles',0), @('p_condition','既往史',2.35,1.35,1.05,0.48,'profiles',0), @('p_update','更新时间',0.85,1.55,1.2,0.48,'profiles',0),
    @('s_id','会话ID',5.25,9.1,1.1,0.48,'sessions',1), @('s_status','状态',6.6,9.15,0.9,0.48,'sessions',0), @('s_time','创建时间',4.55,7.25,1.2,0.48,'sessions',0),
    @('m_id','消息ID',5.25,1.45,1.1,0.48,'messages',1), @('m_role','消息角色',4.55,3.35,1.2,0.48,'messages',0), @('m_text','消息内容',6.65,1.45,1.2,0.48,'messages',0),
    @('r_id','记录ID',9.25,9.1,1.1,0.48,'records',1), @('r_risk','风险等级',10.65,9.15,1.2,0.48,'records',0), @('r_dept','建议科室',11.45,7.25,1.2,0.48,'records',0),
    @('v_id','复核ID',9.25,1.45,1.1,0.48,'reviews',1), @('v_decision','复核结论',11.45,3.35,1.2,0.48,'reviews',0), @('v_time','复核时间',10.65,1.45,1.2,0.48,'reviews',0),
    @('k_id','文档ID',13.35,9.1,1.1,0.48,'knowledge',1), @('k_version','版本',14.75,9.15,0.9,0.48,'knowledge',0), @('k_status','状态',15.55,7.25,0.9,0.48,'knowledge',0),
    @('t_id','轨迹ID',13.35,1.45,1.1,0.48,'traces',1), @('t_node','节点',15.55,3.35,0.9,0.48,'traces',0), @('t_phase','阶段',14.75,1.45,0.9,0.48,'traces',0)
  )
  foreach ($x in $attrs) {
    $a[$x[0]] = Drop-Shape $p $Basic 'Ellipse' $x[1] ([double]$x[2]) ([double]$x[3]) ([double]$x[4]) ([double]$x[5]) 8.5 0 $white
    if ([int]$x[7] -eq 1) { try { $a[$x[0]].CellsU('Char.Style').FormulaU = '4' } catch {} }
    Connect-Auto $p $Connector $a[$x[0]] $e[$x[6]] | Out-Null
  }
  $rels = @(
    @('owns','拥有',2.0,5.25,'users','profiles','1','1',2.32,6.55,2.32,3.95),
    @('starts','发起',4.0,7.8,'users','sessions','1','n',3.05,8.12,4.95,8.12),
    @('contains','包含',6.0,5.25,'sessions','messages','1','n',6.32,6.55,6.32,3.95),
    @('produces','生成',8.0,7.8,'sessions','records','1','1',7.05,8.12,8.95,8.12),
    @('reviewed','复核',10.0,5.25,'records','reviews','1','n',10.32,6.55,10.32,3.95),
    @('traced','追踪',12.15,4.45,'records','traces','1','n',11.35,6.35,13.25,3.25),
    @('indexed','索引',14.2,5.25,'knowledge','traces','1','n',14.52,6.55,14.52,3.95)
  )
  foreach ($x in $rels) {
    $r[$x[0]] = Drop-Shape $p $Basic 'Diamond' $x[1] ([double]$x[2]) ([double]$x[3]) 0.82 0.52 8.5 0 $white
    $from = $e[$x[4]]; $to = $e[$x[5]]
    Connect-Auto $p $Connector $from $r[$x[0]] | Out-Null
    Connect-Auto $p $Connector $r[$x[0]] $to | Out-Null
    Drop-Text $p $Basic $x[6] ([double]$x[8]) ([double]$x[9]) 0.28 0.2 8 | Out-Null
    Drop-Text $p $Basic $x[7] ([double]$x[10]) ([double]$x[11]) 0.28 0.2 8 | Out-Null
  }
  return Save-Document $d '图3-5_核心业务实体关系图'
}

$visio = New-VisibleVisioApplication
$opened = @{}
try {
  $opened.basic = Open-VisioStencilReadOnly -Visio $visio -StencilNameOrPath $basicPath
  $opened.uml = Open-VisioStencilReadOnly -Visio $visio -StencilNameOrPath $umlPath
  $opened.db = Open-VisioStencilReadOnly -Visio $visio -StencilNameOrPath $dbPath
  $connector = $opened.uml.Masters.ItemU('Dynamic connector')
  $results = New-Object System.Collections.ArrayList
  $hierarchyUser = @(
    @{title='账户与档案';items=@('登录','注册','健康档案','个人信息')},
    @{title='智能问诊';items=@('发起问诊','红旗筛查','主动追问','查看记录')},
    @{title='证据与分诊';items=@('医学检索','证据引用','辅助分诊')},
    @{title='医生协同';items=@('医生复核','风险升级','健康建议')}
  )
  $hierarchyAdmin = @(
    @{title='用户管理';items=@('用户信息','角色权限','审计查询')},
    @{title='知识库管理';items=@('文档导入','审核发布','索引版本')},
    @{title='运行监控';items=@('节点状态','延迟监控','失败回溯')}
  )
  $all = @(
    @{name='图2-1_患者端功能结构图';kind='hier';root='患者端';groups=$hierarchyUser;w=12.5;h=9.0},
    @{name='图2-2_管理端功能结构图';kind='hier';root='管理端';groups=$hierarchyAdmin;w=10.5;h=8.0},
    @{name='图2-3_系统功能结构图';kind='flat';root='MedPilot 医疗健康咨询及辅助分诊系统';groups=@('首页','登录/注册','智能问诊','红旗筛查','主动追问','医学检索','辅助分诊','健康档案','医生复核','知识库','监控审计');w=14.5;h=7.0},
    @{name='图2-4_用户用例图';kind='usecase'},
    @{name='图3-5_核心业务实体关系图';kind='er'}
  )
  foreach ($item in $all) {
    if ($Only -and $item.name -notlike "*$Only*") { continue }
    switch ($item.kind) {
      'hier' { $results.Add((Build-Hierarchy $visio $opened.basic $connector $item.name $item.root $item.groups $item.w $item.h)) | Out-Null }
      'flat' { $results.Add((Build-Hierarchy $visio $opened.basic $connector $item.name $item.root $item.groups $item.w $item.h -Flat)) | Out-Null }
      'usecase' { $results.Add((Build-UseCase $visio $opened.uml $opened.basic $connector)) | Out-Null }
      'er' { $results.Add((Build-ER $visio $opened.db $opened.basic $connector)) | Out-Null }
    }
  }
  $results | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $outDir 'manifest.json') -Encoding UTF8
  Write-Output ("Generated {0} reference-style diagrams" -f $results.Count)
} finally {
  foreach ($s in $opened.Values) { try { $s.Close() | Out-Null } catch {} }
  try { $visio.Quit() | Out-Null } catch {}
}
