# ============================================================================
#  组装魔搭创空间的部署包
#
#  用法（在项目根目录双击运行，或右键「使用 PowerShell 运行」）：
#      powershell -ExecutionPolicy Bypass -File deploy\modelscope\assemble.ps1
#
#  做三件事：
#    1. 构建前端（frontend/dist）与后端（backend/target/backend-*.jar）
#    2. 把 Dockerfile / nginx.conf / entrypoint.sh 和上述产物拷到一起
#    3. 打印下一步该敲的 git 命令
#
#  产物默认落在项目**同级**的 多民族医学智能体-modelscope 目录：
#  那是交给魔搭的独立仓库，刻意放在项目之外 —— 里面有 34MB 的 jar 和一份
#  ai-service 副本，放进项目里会污染 git 历史，也容易和 GitHub 那个仓库打架。
# ============================================================================

param(
    [string]$OutDir = ""
)

$ErrorActionPreference = 'Stop'

$Here    = $PSScriptRoot                                   # deploy\modelscope
$Project = Split-Path -Parent (Split-Path -Parent $Here)   # 项目根目录

if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path (Split-Path -Parent $Project) '多民族医学智能体-modelscope'
}

function Step($msg) {
    Write-Host ''
    Write-Host ('─' * 64) -ForegroundColor DarkGray
    Write-Host "  $msg" -ForegroundColor White
    Write-Host ('─' * 64) -ForegroundColor DarkGray
}
function Ok($msg) { Write-Host "  [OK]   $msg" -ForegroundColor Green }

# ── 1. 构建前端 ─────────────────────────────────────────────────────────────
Step '1/4  构建前端'
Push-Location (Join-Path $Project 'frontend')
try {
    npm ci
    if ($LASTEXITCODE -ne 0) { throw 'npm ci 失败' }
    npm run build
    if ($LASTEXITCODE -ne 0) { throw 'npm run build 失败' }
}
finally { Pop-Location }
Ok '前端构建完成 frontend\dist'

# ── 2. 构建后端 ─────────────────────────────────────────────────────────────
Step '2/4  构建后端 jar'
$Backend = Join-Path $Project 'backend'
Push-Location $Backend
try {
    & .\mvnw.cmd -q package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw 'mvnw package 失败' }
}
finally { Pop-Location }

$Jar = Get-ChildItem (Join-Path $Backend 'target') -Filter 'backend-*.jar' |
       Where-Object { $_.Name -notlike '*sources*' -and $_.Name -notlike '*javadoc*' } |
       Select-Object -First 1
if (-not $Jar) { throw "在 backend\target 里找不到 backend-*.jar" }
Ok ("后端构建完成 {0}（{1:N1} MB）" -f $Jar.Name, ($Jar.Length / 1MB))

# ── 3. 拷贝到部署目录 ───────────────────────────────────────────────────────
Step "3/4  组装部署包到 $OutDir"
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

# 只覆盖内容，不动 .git —— 那里面是魔搭创空间的仓库
Copy-Item (Join-Path $Here 'Dockerfile')    $OutDir -Force
Copy-Item (Join-Path $Here 'nginx.conf')    $OutDir -Force
Copy-Item (Join-Path $Here 'entrypoint.sh') $OutDir -Force

Remove-Item (Join-Path $OutDir 'dist')       -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $OutDir 'ai-service') -Recurse -Force -ErrorAction SilentlyContinue
Copy-Item (Join-Path $Project 'frontend\dist') $OutDir -Recurse -Force

# 用 robocopy 而不是 Copy-Item：ai-service\.venv 有 91MB，必须排除掉。
# /XF .env —— 绝不能把 .env 打进部署包，那是真实 API Key，提交到魔搭仓库
# 就等于泄露（同 GitHub 那次）。密钥走创空间的环境变量注入。
# config.py 用的是 os.environ.setdefault，真环境变量优先于 .env，所以不影响。
# robocopy 的退出码 0-7 都算成功，>=8 才是真失败。
$rc = Start-Process robocopy -ArgumentList @(
    (Join-Path $Project 'ai-service'), (Join-Path $OutDir 'ai-service'),
    '/E', '/XD', '.venv', '__pycache__', '/XF', '.env',
    '/NFL', '/NDL', '/NJH', '/NJS'
) -Wait -PassThru -NoNewWindow
if ($rc.ExitCode -ge 8) { throw "robocopy 拷贝 ai-service 失败（退出码 $($rc.ExitCode)）" }

Copy-Item $Jar.FullName (Join-Path $OutDir 'app.jar') -Force

# ── 知识库种子数据 ─────────────────────────────────────────────────────────
# 后端把知识库拆成两半，缺一不可：
#   multiethnic.mvdb    H2 库里的 kb_document 元数据（决定「知识库」页面列出哪些资料）
#   kb_data/            上传的原始文件（PDF/docx），DB 里存的是它的文件名
#
# 少了它们，容器里就是个全新的空库 —— 前端知识库页面只剩内置文献，
# 用户文档一片空白。这正是第一次部署遇到的问题。
#
# 扩展名是 .mvdb 不是 .mv.db：仓库的 .gitattributes 有 `*.db* filter=lfs`，
# 叫 .mv.db 会被 Git LFS 接管，仓库里只剩指针文件，构建时若没拉取 LFS 内容，
# Spring 打开的就是个一百多字节的文本，后端直接起不来。entrypoint 灌入时会改回真名。
$SeedDst = Join-Path $OutDir 'seed'
Remove-Item $SeedDst -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $SeedDst -Force | Out-Null

$DbPath = Join-Path $Project 'backend\data\multiethnic.mv.db'
if (-not (Test-Path $DbPath)) {
    throw "找不到 $DbPath —— 先本地跑一次后端，让它把知识库库文件生成出来"
}
Copy-Item $DbPath (Join-Path $SeedDst 'multiethnic.mvdb') -Force

$KbDataPath = Join-Path $Project 'backend\kb_data'
if (Test-Path $KbDataPath) {
    Copy-Item $KbDataPath (Join-Path $SeedDst 'kb_data') -Recurse -Force
    Ok ("知识库种子：库 {0:N2} MB + 原始文件 {1} 个" -f `
        ((Get-Item $DbPath).Length / 1MB), (Get-ChildItem $KbDataPath -File).Count)
} else {
    Write-Host "  [注意] 没有 backend\kb_data 目录，原始文件将缺失（上传的资料点开会 404）" -ForegroundColor Yellow
}

Ok '部署包组装完成'

# ── 4. 体积统计 + 密钥体检 ──────────────────────────────────────────────────
Step '4/4  部署包内容'
Get-ChildItem $OutDir -Force |
    Where-Object { $_.Name -ne '.git' } |
    ForEach-Object {
        $size = if ($_.PSIsContainer) {
            (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue |
             Measure-Object -Property Length -Sum).Sum
        } else { $_.Length }
        "{0,-16} {1,10:N2} MB" -f $_.Name, (($size) / 1MB)
    }

# 打包进来的文件里不该有任何真实密钥。扫一遍，有就直接停下来 ——
# 推到魔搭之后再发现，就得改写那边的历史了。
$hits = Get-ChildItem $OutDir -Recurse -File -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\\.git\\' -and $_.Length -lt 5MB } |
        Select-String -Pattern 'sk-[A-Za-z0-9]{20,}', 'figd_[A-Za-z0-9_-]{20,}' -List -ErrorAction SilentlyContinue
if ($hits) {
    Write-Host ''
    Write-Host '  [危险] 部署包里扫到疑似密钥，已中止：' -ForegroundColor Red
    $hits | ForEach-Object { Write-Host "    $($_.Path):$($_.LineNumber)" -ForegroundColor Red }
    throw '部署包含有密钥，请先清理再推送'
}
Ok '密钥体检通过（未发现 sk- / figd_ 开头的凭据）'

Write-Host ''
Write-Host '  下一步：' -ForegroundColor Cyan
Write-Host "    1. 网页上创建创空间（SDK 选 Docker）" -ForegroundColor Gray
Write-Host "    2. 在 $OutDir 里执行：" -ForegroundColor Gray
Write-Host '' -ForegroundColor Gray
Write-Host '       git init' -ForegroundColor Yellow
Write-Host '       git add -A' -ForegroundColor Yellow
Write-Host '       git commit -m "deploy: 多民族医学智能体"' -ForegroundColor Yellow
Write-Host '       git branch -M master          # 魔搭默认分支是 master，不是 main' -ForegroundColor Yellow
Write-Host '       git remote add origin https://www.modelscope.cn/studios/<用户名>/<创空间名>.git' -ForegroundColor Yellow
Write-Host '       git push -u origin master' -ForegroundColor Yellow
Write-Host ''
Write-Host '    3. 网页上配置环境变量再点「部署」：' -ForegroundColor Gray
Write-Host '       LLM_API_KEY / EMBED_API_KEY（SiliconFlow）' -ForegroundColor Gray
Write-Host ''
