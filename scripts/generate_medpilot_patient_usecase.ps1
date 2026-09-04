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
  # Invisible anchor: associations remain glued and the actor stays editable.
  $anchor = $Page.DrawRectangle($X - 0.38, $Y - 0.84, $X + 0.38, $Y + 0.78)
  try { $anchor.CellsU('FillPattern').FormulaU = '0' } catch {}
  try { $anchor.CellsU('LinePattern').FormulaU = '0' } catch {}

  # Supplied reference style: circular head, horizontal arms, vertical body,
  # and two diagonal legs. Primitives are intentionally left independent.
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

function Set-PatientShapeStyle {
  param($Shape,[double]$Size = 10,[int]$Bold = 0)
  try { $Shape.CellsU('Char.Color').FormulaU = $black } catch {}
  try { $Shape.CellsU('Char.Size').FormulaU = "$Size pt" } catch {}
  try { $Shape.CellsU('Char.Style').FormulaU = "$Bold" } catch {}
  try { $Shape.CellsU('Char.Font').FormulaU = 'FONT("宋体")' } catch {}
  try { $Shape.CellsU('Para.HorzAlign').FormulaU = '1' } catch {}
  try { $Shape.CellsU('VerticalAlign').FormulaU = '1' } catch {}
  try { $Shape.CellsU('FillForegnd').FormulaU = $white } catch {}
  try { $Shape.CellsU('FillPattern').FormulaU = '1' } catch {}
  try { $Shape.CellsU('LineColor').FormulaU = $black } catch {}
  try { $Shape.CellsU('LinePattern').FormulaU = '1' } catch {}
  try { $Shape.CellsU('LineWeight').FormulaU = '0.8 pt' } catch {}
}

function Set-PatientShapeBounds {
  param($Shape,[double]$X,[double]$Y,[double]$W,[double]$H)
  try { $Shape.CellsU('Width').FormulaU = "$W in" } catch {}
  try { $Shape.CellsU('Height').FormulaU = "$H in" } catch {}
  try { $Shape.CellsU('PinX').FormulaU = "$X in" } catch {}
  try { $Shape.CellsU('PinY').FormulaU = "$Y in" } catch {}
}

function Drop-PatientShape {
  param($Page,$Stencil,[string]$Master,[string]$Text,[double]$X,[double]$Y,[double]$W,[double]$H,[double]$Size = 10)
  $shape = $Page.Drop($Stencil.Masters.ItemU($Master), $X, $Y)
  Set-PatientShapeBounds $shape $X $Y $W $H
  if ($null -ne $Text) { $shape.Text = $Text }
  Set-PatientShapeStyle $shape $Size 0
  return $shape
}

function Connect-PatientShapes {
  param($Page,$ConnectorMaster,$From,$To,[double]$FromX,[double]$FromY,[double]$ToX,[double]$ToY,[string]$Text = '',[bool]$Dashed = $false,[int]$Arrow = 0)
  $connector = $Page.Drop($ConnectorMaster, 0, 0)
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
    try { $connector.CellsU('Char.Color').FormulaU = $black } catch {}
    try { $connector.CellsU('Char.Size').FormulaU = '8 pt' } catch {}
    try { $connector.CellsU('Char.Font').FormulaU = 'FONT("宋体")' } catch {}
    try { $connector.CellsU('Para.HorzAlign').FormulaU = '1' } catch {}
    try { $connector.CellsU('VerticalAlign').FormulaU = '1' } catch {}
  }
  return $connector
}

function Connect-PatientAuto {
  param($Page,$ConnectorMaster,$From,$To,[string]$Text = '',[bool]$Dashed = $false,[int]$Arrow = 0)
  $glue = Resolve-VisioConnectorGluePoints -From $From -To $To
  return Connect-PatientShapes $Page $ConnectorMaster $From $To $glue.FromX $glue.FromY $glue.ToX $glue.ToY $Text $Dashed $Arrow
}

function Save-PatientDiagram {
  param($Doc,$Page)
  $vsdx = Join-Path $outDir '图2-4_患者角色用例图.vsdx'
  $png = Join-Path $outDir '图2-4_患者角色用例图.png'
  foreach ($path in @($vsdx,$png)) { if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force } }
  $Doc.SaveAs($vsdx) | Out-Null
  $Page.Export($png) | Out-Null
  [pscustomobject]@{ name = '图2-4_患者角色用例图'; vsdx = $vsdx; png = $png }
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

  # The actor remains at the far left, matching the reference image.
  $patient = New-StickActor $page '患者' 0.62 5.0

  # Primary patient-facing use cases, arranged from left to right.
  $main = @{}
  $main.home = Drop-PatientShape $page $uml 'Use Case' '首页' 2.45 8.65 1.55 0.62 9.5
  $main.login = Drop-PatientShape $page $uml 'Use Case' '登录' 2.45 7.55 1.55 0.62 9.5
  $main.register = Drop-PatientShape $page $uml 'Use Case' '注册' 2.45 6.45 1.55 0.62 9.5
  $main.consult = Drop-PatientShape $page $uml 'Use Case' '发起智能问诊' 2.45 5.05 1.95 0.68 9.5
  $main.records = Drop-PatientShape $page $uml 'Use Case' '查看问诊记录' 2.45 3.65 1.95 0.68 9.5
  $main.profile = Drop-PatientShape $page $uml 'Use Case' '维护健康档案' 2.45 2.25 1.95 0.68 9.5

  # Included patient-side functions.
  $included = @{}
  $included.screen = Drop-PatientShape $page $uml 'Use Case' '红旗安全筛查' 5.25 7.45 1.9 0.66 9.5
  $included.follow = Drop-PatientShape $page $uml 'Use Case' '主动追问' 5.25 6.15 1.65 0.66 9.5
  $included.retrieve = Drop-PatientShape $page $uml 'Use Case' '医学证据检索' 5.25 4.85 1.9 0.66 9.5
  $included.triage = Drop-PatientShape $page $uml 'Use Case' '辅助分诊输出' 5.25 3.55 1.9 0.66 9.5
  $included.evidence = Drop-PatientShape $page $uml 'Use Case' '查看证据引用' 5.25 2.25 1.9 0.66 9.5

  # Final detail functions, kept in a third column like the reference image.
  $detail = @{}
  $detail.emergency = Drop-PatientShape $page $uml 'Use Case' '急诊提示' 8.35 7.45 1.65 0.66 9.5
  $detail.followup = Drop-PatientShape $page $uml 'Use Case' '补充症状信息' 8.35 6.15 1.9 0.66 9.5
  $detail.source = Drop-PatientShape $page $uml 'Use Case' '查看引用来源' 8.35 4.85 1.9 0.66 9.5
  $detail.department = Drop-PatientShape $page $uml 'Use Case' '查看建议科室' 8.35 3.55 1.9 0.66 9.5
  $detail.risk = Drop-PatientShape $page $uml 'Use Case' '查看风险等级' 8.35 2.25 1.9 0.66 9.5

  # Solid actor associations are intentionally limited to avoid line crossings.
  Connect-PatientShapes $page $connectorMaster $patient $main.login 1 0.72 0 0.5 | Out-Null
  Connect-PatientShapes $page $connectorMaster $patient $main.consult 1 0.52 0 0.5 | Out-Null
  Connect-PatientShapes $page $connectorMaster $patient $main.records 1 0.36 0 0.5 | Out-Null
  Connect-PatientShapes $page $connectorMaster $patient $main.profile 1 0.2 0 0.5 | Out-Null

  # Main use case -> included use case.
  Connect-PatientShapes $page $connectorMaster $main.consult $included.screen 1 0.7 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $main.consult $included.follow 1 0.58 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $main.consult $included.retrieve 1 0.4 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $main.consult $included.triage 1 0.25 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $main.records $included.evidence 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null

  # Included use case -> detail use case.
  Connect-PatientShapes $page $connectorMaster $included.screen $detail.emergency 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $included.follow $detail.followup 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $included.retrieve $detail.source 1 0.5 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $included.triage $detail.department 1 0.58 0 0.5 '<<include>>' $true 4 | Out-Null
  Connect-PatientShapes $page $connectorMaster $included.triage $detail.risk 1 0.42 0 0.5 '<<include>>' $true 4 | Out-Null

  $result = Save-PatientDiagram $doc $page
  $result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $outDir '患者角色用例图-manifest.json') -Encoding UTF8
  Write-Output ("Generated patient use-case diagram: {0}" -f $result.png)
} finally {
  if ($null -ne $basic) { try { $basic.Close() | Out-Null } catch {} }
  if ($null -ne $uml) { try { $uml.Close() | Out-Null } catch {} }
  if ($null -ne $doc) { try { $doc.Close() | Out-Null } catch {} }
  try { $visio.Quit() | Out-Null } catch {}
}
