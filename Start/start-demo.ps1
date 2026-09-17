# ============================================================================
#  多民族特色医学智能体 · 演示一键启动
#
#  双击同目录下的 start-demo.bat 运行（它会带着绕过执行策略的参数调用本脚本）。
#
#  做五件事：
#    1. 清掉上一轮遗留的隧道（避免同时存在两个网址，让人分不清该发哪个）
#    2. 起后端 8080、AI 8000（已经在跑就跳过，不打断）
#    3. 打包前端，用打包产物起在 5173（比 npm run dev 快很多 —— 见《部署指南》坑 3）
#    4. 起 Cloudflare 隧道，把新网址抓出来、复制到剪贴板、存成文件
#    5. 自动验证四项（首页 / 后端 / AI / 登录），打印一张结果表
#
#  注意：网址每次运行都会变，这是免费 quick tunnel 的固有特性，不是配置问题。
#        想要固定网址，需要自己的域名 + 命名隧道（域名要钱）。
# ============================================================================

$ErrorActionPreference = 'Stop'

$Root        = Split-Path -Parent $PSScriptRoot          # 项目根目录（本脚本在 Start\ 下）
$Cloudflared = Join-Path $Root 'cloudflared.exe'
$UrlFile     = Join-Path $PSScriptRoot '当前演示网址.txt'
$TunnelOut   = Join-Path $env:TEMP 'demo-tunnel.out.log'
$TunnelErr   = Join-Path $env:TEMP 'demo-tunnel.err.log'

# ── 输出小工具 ──────────────────────────────────────────────────────────────
function Head($msg) {
    Write-Host ''
    Write-Host ('─' * 64) -ForegroundColor DarkGray
    Write-Host "  $msg" -ForegroundColor White
    Write-Host ('─' * 64) -ForegroundColor DarkGray
}
function Ok($msg)   { Write-Host "  [OK]   $msg" -ForegroundColor Green }
function Skip($msg) { Write-Host "  [跳过] $msg" -ForegroundColor DarkGray }
function Warn($msg) { Write-Host "  [注意] $msg" -ForegroundColor Yellow }
function Fail($msg) { Write-Host "  [失败] $msg" -ForegroundColor Red }

function Test-Port($port) {
    return [bool](Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
}

# 双击运行时停下等回车，好让人看清结果；被脚本调用（输入已重定向）时直接返回，
# 不然自动化测试会一直卡在这里。
function Pause-Exit($prompt) {
    if ([Environment]::UserInteractive -and -not [Console]::IsInputRedirected) {
        Read-Host $prompt
    }
}

function Wait-Port($port, $seconds) {
    for ($i = 0; $i -lt $seconds; $i++) {
        if (Test-Port $port) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

# 在独立窗口里跑一个 .bat，窗口留着不关（方便看日志、也方便手动关掉）
function Start-Service-Window($title, $batPath) {
    Start-Process -FilePath 'cmd' -ArgumentList '/k', "call `"$batPath`""
}

# 有些网络的 DNS 只给 AAAA（IPv6）不给 A（IPv4），而那条网络的 IPv6 又出不了网——
# 症状是「隧道明明建好了，浏览器却打不开」，报错完全看不出是 DNS 的问题。
# （实测某台 DNS 对 *.trycloudflare.com 的 A 查询直接回 NXDOMAIN，但公共 DNS 查得到。）
#
# 返回两个信息：本机能不能解析出 IPv4、以及公网上的 IPv4 是多少。
# 后者用于验证时 --resolve 强制指定地址——这样即使本机 DNS 是坏的，也能验证
# 「隧道本身好不好」，把「隧道坏了」和「你这台机器解析不了」两件事分开报。
function Get-TunnelIPv4($hostname) {
    try {
        $r = Resolve-DnsName $hostname -Type A -ErrorAction Stop | Where-Object { $_.IPAddress }
        if ($r) { return @{ Ip = $r[0].IPAddress; Local = $true } }
    } catch { }
    foreach ($srv in @('223.5.5.5', '119.29.29.29', '114.114.114.114')) {
        try {
            $r = Resolve-DnsName $hostname -Type A -Server $srv -ErrorAction Stop | Where-Object { $_.IPAddress }
            if ($r) { return @{ Ip = $r[0].IPAddress; Local = $false; Server = $srv } }
        } catch { }
    }
    return $null
}

# 验证用的 curl 附加参数：本机 DNS 不可用时，强制把域名指到公网 IPv4
$script:ResolveArgs = @()
# 本机 DNS 是不是坏的（坏的只影响「你这台电脑能不能打开」，别人的手机不受影响）
$script:DnsBroken = $false

# ============================================================================
Write-Host ''
Write-Host '  多民族特色医学智能体 · 演示一键启动' -ForegroundColor Cyan
Write-Host '  前端 5173 · 后端 8080 · AI 8000 · Cloudflare 隧道' -ForegroundColor DarkGray

# ── 0. 前置检查 ────────────────────────────────────────────────────────────
if (-not (Test-Path $Cloudflared)) {
    Fail "找不到 cloudflared.exe：$Cloudflared"
    Write-Host ''
    Write-Host '  请先下载（单文件、免安装，放进项目根目录即可）：' -ForegroundColor Yellow
    Write-Host '    https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -ForegroundColor Cyan
    Write-Host '  下载后重命名为 cloudflared.exe，放到：' -ForegroundColor Yellow
    Write-Host "    $Root" -ForegroundColor Cyan
    Write-Host ''
    Pause-Exit '  按回车退出'
    exit 1
}

# ── 1. 清掉遗留隧道 ────────────────────────────────────────────────────────
Head '1 / 5  清理上一轮遗留的隧道'
$old = Get-Process cloudflared -ErrorAction SilentlyContinue
if ($old) {
    $old | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    Ok "已停掉 $($old.Count) 个旧的 cloudflared 进程（否则会出现两个网址，分不清该发哪个）"
} else {
    Skip '没有遗留的隧道进程'
}

# ── 2. 起后端与 AI ─────────────────────────────────────────────────────────
Head '2 / 5  启动后端与 AI 服务'

if (Test-Port 8080) {
    Skip '后端 8080 已经在跑，保持不动'
} else {
    Start-Service-Window 'backend-8080' (Join-Path $Root 'backend\restart-backend.bat')
    Ok '后端 8080 启动中（新窗口 backend-8080）'
}

if (Test-Port 8000) {
    Skip 'AI 8000 已经在跑，保持不动'
} else {
    Start-Service-Window 'ai-8000' (Join-Path $Root 'ai-service\restart-ai.bat')
    Ok 'AI 8000 启动中（新窗口 ai-8000）'
}

# ── 3. 打包前端并用 preview 起在 5173 ──────────────────────────────────────
Head '3 / 5  打包前端并启动（用打包版，比 dev 模式快很多）'

# 5173 必须是「打包版」的 preview 服务。dev 模式虽然也能打开，
# 但会把前端拆成几百个模块逐个请求，走海外隧道时第一次加载要几十秒。
# 所以这里直接把 5173 上的旧进程收掉，换成 preview。
if (Test-Port 5173) {
    $pids = Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($p in $pids) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Milliseconds 900
    Warn '5173 上原有进程已被替换为打包版（原来那个窗口如果开着，现在已经是空的，可以关掉）'
}

Write-Host '  正在打包（约 1～3 秒）…' -ForegroundColor DarkGray
Push-Location (Join-Path $Root 'frontend')
try {
    $buildLog = cmd /c 'npm run build-only 2>&1' | Out-String
} finally {
    Pop-Location
}
if ($buildLog -match 'built in') {
    Ok '打包完成，产物在 frontend\dist\'
} else {
    Fail '打包似乎没成功，输出如下：'
    Write-Host $buildLog -ForegroundColor DarkGray
    Pause-Exit '  按回车退出'
    exit 1
}

$feCmd = "cd /d `"$Root\frontend`" && npm run preview"
Start-Process -FilePath 'cmd' -ArgumentList '/k', $feCmd
Write-Host '  正在等待前端就绪…' -ForegroundColor DarkGray
if (Wait-Port 5173 60) {
    Ok '前端 5173 已就绪（新窗口 frontend-5173-preview）'
} else {
    Fail '前端 5173 在 60 秒内没有起来，请查看那个新窗口里的报错'
    Pause-Exit '  按回车退出'
    exit 1
}

# 等后端和 AI 也就绪（打包期间它们可能还在启动）
Write-Host '  正在等待后端与 AI 就绪…' -ForegroundColor DarkGray
$beUp = Wait-Port 8080 90
$aiUp = Wait-Port 8000 90
if ($beUp) { Ok '后端 8080 已就绪' } else { Warn '后端 8080 仍未就绪，稍后验证可能会失败' }
if ($aiUp) { Ok 'AI 8000 已就绪' }   else { Warn 'AI 8000 仍未就绪，稍后验证可能会失败' }

# ── 4. 起隧道并抓网址 ──────────────────────────────────────────────────────
Head '4 / 5  启动 Cloudflare 隧道'

Remove-Item $TunnelOut, $TunnelErr -Force -ErrorAction SilentlyContinue
$tunnel = Start-Process -FilePath $Cloudflared `
    -ArgumentList 'tunnel', '--url', 'http://localhost:5173' `
    -RedirectStandardOutput $TunnelOut -RedirectStandardError $TunnelErr `
    -PassThru -WindowStyle Hidden

Write-Host '  正在申请公网地址…' -ForegroundColor DarkGray
$url = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 700
    $text = ''
    foreach ($f in @($TunnelOut, $TunnelErr)) {
        if (Test-Path $f) { $text += (Get-Content $f -Raw -ErrorAction SilentlyContinue) }
    }
    if ($text -match 'https://[a-z0-9-]+\.trycloudflare\.com') { $url = $Matches[0]; break }
    if ($tunnel.HasExited) { break }
}

if (-not $url) {
    Fail '没能拿到公网地址。隧道日志：'
    foreach ($f in @($TunnelErr, $TunnelOut)) {
        if (Test-Path $f) { Get-Content $f -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray } }
    }
    Write-Host ''
    Write-Host '  常见原因：网络不通，或这条网络下换了协议。用默认的 QUIC 即可（本脚本没有指定 --protocol）。' -ForegroundColor Yellow
    Pause-Exit '  按回车退出'
    exit 1
}
Ok '隧道已建立'

# ── DNS 可达性自检 ─────────────────────────────────────────────────────────
# 把「隧道坏了」和「你这台机器解析不了」分开——两者表现一模一样（浏览器打不开），
# 但修法完全不同：前者要查隧道，后者要改 DNS。没有这一步就只能靠猜。
$tunnelHost = $url -replace '^https://', ''

# 新隧道的 DNS 记录不是瞬间就有的：Cloudflare 建好之后要几秒到几十秒才在各地解析器上
# 可见（实测同一时刻 223.5.5.5 已经能查到、119.29.29.29 还查不到，进度并不一致）。
# 所以这里必须重试——只查一次会把「还没生效」误判成「解析不了」，得出完全相反的结论。
Write-Host '  等待域名解析生效…' -ForegroundColor DarkGray
$dns = $null
for ($i = 0; $i -lt 20; $i++) {
    $dns = Get-TunnelIPv4 $tunnelHost
    if ($dns) { break }
    Start-Sleep -Seconds 3
}

if (-not $dns) {
    Warn '域名解析不出 IPv4，公共 DNS 也查不到 —— 隧道可能还没生效，继续验证看看'
} elseif ($dns.Local) {
    Ok "域名解析正常（$($dns.Ip)）"
} else {
    $script:DnsBroken = $true
    $script:ResolveArgs = @('--resolve', "${tunnelHost}:443:$($dns.Ip)")
    Warn '本机 DNS 解析不出这个域名的 IPv4 —— 但公网记录是有的'
    Write-Host "     公网地址 $($dns.Ip)（由公共 DNS $($dns.Server) 查到），IPv4 实测通的。" -ForegroundColor Yellow
    Write-Host '     所以：只有你这台电脑打不开网址，别人的手机不受影响。' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '     常见原因：这条网络的 DNS 只肯给 IPv6 记录，而这条网络的 IPv6 又出不了网。' -ForegroundColor DarkGray
    Write-Host '     修法（需要「以管理员身份运行」的 PowerShell，改完就能正常打开）：' -ForegroundColor Yellow
    Write-Host '       Set-DnsClientServerAddress -InterfaceAlias WLAN -ServerAddresses 223.5.5.5, 119.29.29.29' -ForegroundColor Cyan
    Write-Host '       Clear-DnsClientCache' -ForegroundColor Cyan
    Write-Host '     （想改回去：Set-DnsClientServerAddress -InterfaceAlias WLAN -ResetServerAddresses）' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '     下面的验证会自动绕过 DNS 强制走 IPv4，所以结果依然可信。' -ForegroundColor DarkGray
}

# ── 5. 自动验证 ────────────────────────────────────────────────────────────
Head '5 / 5  验证（穿过隧道真打一遍）'

# 这里一律用 curl.exe，不用 Invoke-WebRequest。
#
# 原因：新隧道的域名在 Cloudflare 那边常常只有 AAAA 记录（IPv6）。.NET 的解析器
# （Invoke-WebRequest 底层）在某些网络下解析不了这种「只有 AAAA」的域名，会报
# 「远程名称无法解析」——但隧道其实是好的，浏览器和 curl 都能正常访问。
# 用它做验证会把好隧道误判成坏的（我们真踩过这个坑）。
#
# 附带好处：curl 的 --data-binary @文件 是原样发送字节，
# 中文请求体不用担心被按 Latin-1 编码（PowerShell 字符串 body 的老毛病）。
$HasCurl = [bool](Get-Command curl.exe -ErrorAction SilentlyContinue)
if (-not $HasCurl) { Warn ' 找不到 curl.exe，改用 Invoke-WebRequest —— 可能误报，仅供参考' }

function Curl-Get($uri) {
    $a = @('-s', '-w', "`n__STATUS__%{http_code}", '--max-time', '40') + $script:ResolveArgs + @($uri)
    $out = & curl.exe @a 2>$null
    $text = ($out -join "`n")
    $code = 0
    if ($text -match '__STATUS__(\d+)') { $code = [int]$Matches[1]; $text = $text -replace "`n__STATUS__\d+", '' }
    return [pscustomobject]@{ Status = $code; Body = $text }
}

function Curl-PostJson($uri, $json, $origin) {
    $tmp = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding $false))
        $a = @('-s', '-X', 'POST',
               '-H', 'Content-Type: application/json; charset=utf-8',
               '--data-binary', "@$tmp",
               '-w', "`n__STATUS__%{http_code}", '--max-time', '40')
        # Origin 必须带上：浏览器对 POST 一定会发这个头，缺了它测不出 CORS 问题
        if ($origin) { $a += @('-H', "Origin: $origin") }
        # 本机 DNS 坏掉时，强制把域名指到公网 IPv4（见 Get-TunnelIPv4 的说明）
        $a += $script:ResolveArgs
        $a += $uri
        $out = & curl.exe @a 2>$null
        $text = ($out -join "`n")
        $code = 0
        if ($text -match '__STATUS__(\d+)') { $code = [int]$Matches[1]; $text = $text -replace "`n__STATUS__\d+", '' }
        return [pscustomobject]@{ Status = $code; Body = $text }
    } finally {
        Remove-Item $tmp -Force -ErrorAction SilentlyContinue
    }
}

# 隧道刚建好时，DNS 生效 + 边缘节点就绪还要几秒，先等它真的能出 200
#
# 但**一个 IPv4 都拿不到时直接跳过**：那种情况下每次 curl 都要等满 --max-time（DNS 只给
# AAAA 的话会卡在 IPv6 上），25 次重试能磨掉十几分钟，而结论在第 1 秒就已经定了。
$rows = @()
if (-not $dns) {
    Write-Host '  跳过验证：域名一个地址都解析不出来，重试也不会通。' -ForegroundColor DarkGray
    foreach ($n in @('首页', '后端', 'AI 服务', '登录 (POST)')) { $rows += ,@($n, '跳过', '解析不出地址') }
} else {
Write-Host '  等待隧道可达…' -ForegroundColor DarkGray
$reachable = $false
for ($i = 0; $i -lt 25; $i++) {
    $probe = Curl-Get "$url/"
    if ($probe.Status -eq 200) { $reachable = $true; break }
    Start-Sleep -Seconds 3
}

    if ($reachable) {
        $rows += ,@('首页', 'OK', 'HTTP 200')
    } else {
        $rows += ,@('首页', '失败', $(if ($HasCurl) { "HTTP $($probe.Status)" } else { '连不上' }))
    }

    # ② 后端健康
    $r = Curl-Get "$url/api/health"
    $okBe = $r.Body -match '"status"\s*:\s*"UP"'
    $rows += ,@('后端', $(if ($okBe) { 'OK' } else { '失败' }), $(if ($okBe) { 'UP' } else { "HTTP $($r.Status)" }))

    # ③ AI 健康
    $r = Curl-Get "$url/api/ai/health"
    $okAi = $r.Body -match '"status"\s*:\s*"UP"'
    $rows += ,@('AI 服务', $(if ($okAi) { 'OK' } else { '失败' }), $(if ($okAi) { 'UP' } else { "HTTP $($r.Status)" }))

    # ④ 登录 —— 带 Origin 头，模拟浏览器的真实请求
    $r = Curl-PostJson "$url/api/auth/login" '{"username":"demo","password":"123456"}' $url
    $okLogin = $r.Status -eq 200 -and $r.Body -match '"token"'
    $rows += ,@('登录 (POST)', $(if ($okLogin) { 'OK' } else { '失败' }), $(if ($okLogin) { 'demo / 123456 通过' } else { "HTTP $($r.Status)" }))
}


Write-Host ''
foreach ($row in $rows) {
    $color = if ($row[1] -eq 'OK') { 'Green' } elseif ($row[1] -eq '跳过') { 'DarkGray' } else { 'Red' }
    Write-Host ('    {0,-12} {1,-6} {2}' -f $row[0], $row[1], $row[2]) -ForegroundColor $color
}

$allOk = ($rows | Where-Object { $_[1] -ne 'OK' }).Count -eq 0

# ── 结果 ───────────────────────────────────────────────────────────────────
Write-Host ''
Write-Host ('═' * 64) -ForegroundColor Cyan
Write-Host ''
Write-Host '   演示网址（已复制到剪贴板，直接粘贴发给别人）：' -ForegroundColor White
Write-Host ''
Write-Host "   $url" -ForegroundColor Yellow
Write-Host ''
Write-Host '   登录账号： demo / 123456' -ForegroundColor White
Write-Host ''
Write-Host ('═' * 64) -ForegroundColor Cyan

try { Set-Clipboard -Value $url } catch { Warn '复制到剪贴板失败，请手动选中上面的网址复制' }

# 存一份，关了窗口也能找回来
try {
    @(
        "演示网址：$url"
        "登录账号：demo / 123456"
        "生成时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
        ""
        "此网址只在本次开机期间有效。电脑关机后立即失效，"
        "下次启动会是一个全新的网址，重新双击 start-demo.bat 即可。"
    ) | Set-Content -Path $UrlFile -Encoding UTF8
    Write-Host ''
    Ok "网址已存档到：$UrlFile"
} catch {
    Warn '网址存档失败（不影响使用）'
}

if ($allOk) {
    Write-Host ''
    if ($script:DnsBroken) {
        # 隧道和服务都是好的，只是这台机器解析不了域名——不能只说「可以开讲了」，
        # 否则用户打开浏览器发现打不开，会以为是刚才那句提示在骗人。
        Ok '四项验证全部通过 —— 隧道和服务都正常。'
        Warn '但你这台电脑的 DNS 解析不了这个域名，浏览器里仍然打不开（别人的手机不受影响）。'
        Write-Host '     按上面的命令改一下 DNS 就能正常打开；急着演示就先用 http://localhost:5173。' -ForegroundColor Yellow
    } else {
        Ok '四项验证全部通过，可以开讲了。'
    }
} else {
    Write-Host ''
    if ($script:DnsBroken) {
        # DNS 那条已经单独报过了，这里不再重复限流那套——两件事混在一起会更难判断
        Warn '验证没全过，但请先看上面的 DNS 提示：若隧道本身是好的，问题只出在这台机器的解析上。'
    } else {
        # 连域名都解析不出来，多半不是本机的问题，而是 Cloudflare 侧没给这条新隧道下发
        # DNS 记录 —— 短时间内反复重建隧道（每次调试都重启一遍）会触发限流。
        $host_ = $url -replace '^https://', ''
        $resolves = $false
        try { $d = Resolve-DnsName $host_ -ErrorAction Stop | Where-Object { $_.IPAddress }; if ($d) { $resolves = $true } } catch { }

        if (-not $resolves) {
            Warn '这条隧道的域名解析不出来 —— 很可能不是本机的问题。'
            Write-Host '     Cloudflare 对免费 quick tunnel 有频率限制：短时间内反复重建隧道' -ForegroundColor Yellow
            Write-Host '     （比如每次调试都重启一遍）会让新域名拿不到 DNS 记录。' -ForegroundColor Yellow
            Write-Host ''
            Write-Host '     处理办法：等 30～60 分钟（或重启电脑），再双击一次本脚本。' -ForegroundColor Yellow
            Write-Host '     期间不要反复重启隧道 —— 每重启一次都会加重限流。' -ForegroundColor Yellow
            Write-Host ''
            Write-Host '     本机的服务都是好的，可以先在浏览器里用 http://localhost:5173 演示。' -ForegroundColor DarkGray
        } else {
            Warn '有项目没通过 —— 先刷新页面重试；仍不行请对照《部署指南》的「故障速查」表。'
        }
    }
}

Write-Host ''
Write-Host '  提醒：全程别关机、别休眠、别切网络。' -ForegroundColor DarkGray
Write-Host '  隧道日志（出问题时看这个）：' -ForegroundColor DarkGray
Write-Host "    $TunnelErr" -ForegroundColor DarkGray
Write-Host ''
Pause-Exit '  按回车关闭本窗口（三个服务窗口和隧道会继续运行）'
