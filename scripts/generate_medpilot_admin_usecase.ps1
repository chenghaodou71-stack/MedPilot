$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'visio_helpers_utf8.ps1')

$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'outputs\visio-reference-style'
New-Item -ItemType Directory -Path $outDir -Force | Out-Null

$content2052 = Join-Path ${env:ProgramFiles} 'Microsoft Office\root\Office16\Visio Content\2052'
$basicPath = Join-Path $content2052 'BASIC_M.VSSX'
$umlPath = Join-Path $content2052 'UML_USECASE_M.VSTX'
$black = 'RGB(0,0,0)'
$white = 'RGB(255,255,255)'

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

function Drop-UseCaseShape {
  param($Page,$Stencil,[string]$Master,[string]$Text,[double]$X,[double]$Y,[double]$W,[double]$H,[double]$Size = 9.5)
  $shape = $Page.Drop($Stencil.Masters.ItemU($Master), $X, $Y)
  foreach ($item in @(
    @('Width',"$W in"), @('Height',"$H in"),
    @('PinX',"$X in"), @('PinY',"$Y in")
  )) { try { $shape.CellsU($item[0]).FormulaU = $item[1] } catch {} }
  $shape.Text = $Text
  foreach ($item in @(
    @('Char.Color',$black), @('Char.Size',"$Size pt"),
    @('Char.Font','FONT("宋体")'), @('Char.Style','0'),
    @('Para.HorzAlign','1'), @('VerticalAlign','1'),
    @('FillForegnd',$white), @('FillPattern','1'),
    @('LineColor',$black), @('LinePattern','1'), @('LineWeight','0.8 pt')
  )) { try { $shape.CellsU($item[0]).FormulaU = $item[1] } catch {} }
  return $shape
}

function Connect-UseCases {
  param($Page,$Master,$From,$To,[double]$FromX,[double]$FromY,[double]$ToX,[double]$ToY,[string]$Text = '',[bool]$Dashed = $false,[int]$Arrow = 0)
  $connector = $Page.Drop($Master, 0, 0)
  $connector.CellsU('BeginX').GlueToPos($From, $FromX, $FromY) | Out-Null
  $connector.CellsU('EndX').GlueToPos($To, $ToX, $ToY) | Out-Null
  try { $connector.CellsU('ShapeRouteStyle').FormulaU = '2' } catch {}
  try { $connector.CellsU('ConLineRouteExt').FormulaU = '1' } catch {}
  try { $connector.CellsU('LineColor').FormulaU = $black } catch {}
  try { $connector.CellsU('LineWeight').FormulaU = '0.8 pt' } catch {}
  try { $connector.CellsU('EndArrow').FormulaForceU = "$Arrow" } catch {}
  if ($Dashed) { try { $connector.CellsU('LinePattern').FormulaForceU = '2' } catch {} }
  if ($Text) {
    $connector.Text = $Text
    foreach ($item in @(
      @('Char.Color',$black), @('Char.Size','8 pt'),
      @('Char.Font','FONT("宋体")'), @('Para.HorzAlign','1'), @('VerticalAlign','1')
    )) { try { $connector.CellsU($item[0]).FormulaU = $item[1] } catch {} }
  }
  return $connector
}

$visio = New-VisibleVisioApplication
$basic = $null
$uml = $null
$doc = $null
try {
  $basic = Open-VisioStencilReadOnly -Visio $visio -StencilNameOrPath $basicPath
  $uml = Open-VisioStencilReadOnly -Visio $visio -StencilNameOrPath $umlPath
  $connectorMaster = $uml.Masters.ItemU('Dynamic connector')
  $doc = $visio.Documents.Add('')
  $page = $visio.ActivePage
  $page.PageSheet.CellsU('PageWidth').FormulaU = '11.5 in'
  $page.PageSheet.CellsU('PageHeight').FormulaU = '9.5 in'

  $admin = New-StickActor $page '管理员' 0.62 5.0

  $main = @{}
  $main.login = Drop-UseCaseShape $page $uml 'Use Case' '登录' 2.45 8.75 1.55 0.62
  $main.users = Drop-UseCaseShape $page $uml 'Use Case' '用户与权限管理' 2.45 7.25 1.95 0.68
  $main.knowledge = Drop-UseCaseShape $page $uml 'Use Case' '知识库管理' 2.45 5.45 1.8 0.68
  $main.monitor = Drop-UseCaseShape $page $uml 'Use Case' '运行监控' 2.45 3.55 1.7 0.68
  $main.audit = Drop-UseCaseShape $page $uml 'Use Case' '审计查询' 2.45 1.65 1.7 0.68

  $included = @{}
  $included.userInfo = Drop-UseCaseShape $page $uml 'Use Case' '查看用户信息' 5.25 8.05 1.85 0.66
  $included.role = Drop-UseCaseShape $page $uml 'Use Case' '配置角色权限' 5.25 6.95 1.85 0.66
  $included.import = Drop-UseCaseShape $page $uml 'Use Case' '导入医学文档' 5.25 5.85 1.85 0.66
  $included.review = Drop-UseCaseShape $page $uml 'Use Case' '审核发布文档' 5.25 4.75 1.85 0.66
  $included.index = Drop-UseCaseShape $page $uml 'Use Case' '管理索引版本' 5.25 3.65 1.85 0.66
  $included.node = Drop-UseCaseShape $page $uml 'Use Case' '查看节点状态' 5.25 2.55 1.85 0.66
  $included.trace = Drop-UseCaseShape $page $uml 'Use Case' '失败任务回溯' 5.25 1.45 1.85 0.66

  $detail = @{}
  $detail.account = Drop-UseCaseShape $page $uml 'Use Case' '启用或停用账号' 8.35 8.05 1.95 0.66
  $detail.permission = Drop-UseCaseShape $page $uml 'Use Case' '分配系统角色' 8.35 6.95 1.85 0.66
  $detail.publish = Drop-UseCaseShape $page $uml 'Use Case' '通过或驳回审核' 8.35 4.75 1.95 0.66
  $detail.rebuild = Drop-UseCaseShape $page $uml 'Use Case' '构建或切换索引' 8.35 3.65 1.95 0.66
  $detail.metrics = Drop-UseCaseShape $page $uml 'Use Case' '查看延迟指标' 8.35 2.55 1.85 0.66
  $detail.events = Drop-UseCaseShape $page $uml 'Use Case' '查看执行轨迹' 8.35 1.45 1.85 0.66

  # Actor associations.
  Connect-UseCases $page $connectorMaster $admin $main.login 1 0.82 0 0.5 | Out-Null
  Connect-UseCases $page $connectorMaster $admin $main.users 1 0.66 0 0.5 | Out-Null
  Connect-UseCases $page $connectorMaster $admin $main.knowledge 1 0.52 0 0.5 | Out-Null
  Connect-UseCases $page $connectorMaster $admin $main.monitor 1 0.36 0 0.5 | Out-Null
  Connect-UseCases $page $connectorMaster $admin $main.audit 1 0.2 0 0.5 | Out-Null

  # Main management functions.
  Connect-UseCases $page $connectorMaster $main.users $included.userInfo 1 0.58 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $main.users $included.role 1 0.42 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $main.knowledge $included.import 1 0.66 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $main.knowledge $included.review 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $main.knowledge $included.index 1 0.34 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $main.monitor $included.node 1 0.58 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $main.monitor $included.trace 1 0.42 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $main.audit $included.trace 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null

  # Detailed operations.
  Connect-UseCases $page $connectorMaster $included.userInfo $detail.account 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $included.role $detail.permission 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $included.review $detail.publish 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $included.index $detail.rebuild 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $included.node $detail.metrics 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-UseCases $page $connectorMaster $included.trace $detail.events 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null

  $vsdx = Join-Path $outDir '图2-5_管理员角色用例图.vsdx'
  $png = Join-Path $outDir '图2-5_管理员角色用例图.png'
  foreach ($path in @($vsdx,$png)) { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force } }
  $doc.SaveAs($vsdx) | Out-Null
  $page.Export($png) | Out-Null
  [pscustomobject]@{ name='图2-5_管理员角色用例图'; vsdx=$vsdx; png=$png } |
    ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outDir '管理员角色用例图-manifest.json') -Encoding UTF8
  Write-Output "Generated administrator use-case diagram: $png"
} finally {
  if ($null -ne $basic) { try { $basic.Close() | Out-Null } catch {} }
  if ($null -ne $uml) { try { $uml.Close() | Out-Null } catch {} }
  if ($null -ne $doc) { try { $doc.Close() | Out-Null } catch {} }
  try { $visio.Quit() | Out-Null } catch {}
}
