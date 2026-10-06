#Requires -Version 5.1
<#
.SYNOPSIS
    GitHub 推送失败（GH013: Push cannot contain secrets）的一体化处置脚本。

.DESCRIPTION
    解决两类推送失败：
      1. remote: error: GH013 - Push cannot contain secrets   （密钥被 Push Protection 拦截）
      2. Updates were rejected because ... (non-fast-forward) （本地与远程分叉）

    模式（默认只体检，不改动任何文件）：
      Scan     体检：扫描已跟踪文件、全部历史提交、.gitignore 覆盖情况、远程配置
      Protect  脱敏：把配置里的真实密钥换成 ${ENV:} 占位符，密钥汇总成"本地私有配置"，
               写入 .gitignore 并从索引中移除
      Rebuild  清历史：重建为一条干净提交（执行前自动 git bundle 备份）
      Push     推送：添加/更新远程后 push（默认不加 -f）
      All      Protect -> Rebuild -> Push

.EXAMPLE
    # 1) 先体检，什么都不改
    .\git-secret-guard.ps1 Scan

    # 2) 脱敏 + 清历史 + 推送
    .\git-secret-guard.ps1 All -RemoteUrl https://github.com/wordsd/zjj-picture.git

    # 3) 只脱敏，保留提交历史，并提交改动
    .\git-secret-guard.ps1 Protect -Commit

    # 4) 历史已重建、需要覆盖远程时
    .\git-secret-guard.ps1 Push -Force

.NOTES
    脚本不会、也无法替你轮换密钥。密钥一旦写进过 Git 历史，或出现在聊天/截图里，
    请务必到云控制台作废重建。
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('Scan', 'Protect', 'Rebuild', 'Push', 'All')]
    [string]$Mode = 'Scan',

    # 项目根目录（Git 仓库所在目录）
    [string]$RepoPath = '.',

    # 本地私有配置文件（相对仓库根目录）。第一个会被当作"密钥落地文件"
    [string[]]$LocalConfigFiles = @(
        'src/main/resources/application-local.yml',
        'application-local.yml'
    ),

    # 远程仓库
    [string]$RemoteName = 'origin',
    [string]$RemoteUrl = '',
    [string]$Branch = '',
    [switch]$Force,

    [string]$CommitMessage = 'init: 项目初始化（已移除硬编码密钥）',
    [switch]$Commit,

    # 备份目录，默认放在仓库的上一级
    [string]$BackupDir = '',

    # 跳过所有交互确认（谨慎使用）
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'

# ============================================================
# 常量
# ============================================================

# 疑似密钥的特征。注意：占位符形如 ${COS_SECRET_ID:} 不会被这些规则命中
$SecretPatterns = @(
    'AKID[A-Za-z0-9]{16,}',
    'ark-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',
    '(?i)\b(secret[_-]?key|secret[_-]?id|access[_-]?key([_-]?(id|secret))?|api[_-]?key|apikey|app[_-]?secret|token)\b\s*[:=]\s*["'']?[A-Za-z0-9+/_\-\.]{16,}'
)

# 需要写进 .gitignore 的行
$GitignoreLines = @(
    'application-local.yml',
    '*-local.yml',
    '*.csv',
    '*.bak',
    'secrets.txt'
)
$GitignoreMarker = '### git-secret-guard ###'

# 会被 Protect 扫描并改写的配置文件类型
$ConfigGlobs = @('*.yml', '*.yaml', '*.properties')

$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# ============================================================
# 基础工具
# ============================================================

function Write-Step { param([string]$Text) Write-Host "`n=== $Text ===" -ForegroundColor Cyan }
function Write-Ok { param([string]$Text) Write-Host "  [OK]   $Text" -ForegroundColor Green }
function Write-Warn { param([string]$Text) Write-Host "  [警告] $Text" -ForegroundColor Yellow }
function Write-Bad { param([string]$Text) Write-Host "  [问题] $Text" -ForegroundColor Red }
function Write-Info { param([string]$Text) Write-Host "         $Text" -ForegroundColor DarkGray }

function Invoke-Git {
    param([Parameter(Mandatory)][string[]]$GitArgs)
    $raw = & git @GitArgs 2>&1
    $code = $LASTEXITCODE
    $lines = @()
    if ($null -ne $raw) { $lines = @($raw | ForEach-Object { [string]$_ }) }
    [pscustomobject]@{ Code = $code; Output = $lines }
}

function Test-IsGitRepo {
    $r = Invoke-Git @('rev-parse', '--is-inside-work-tree')
    return (($r.Code -eq 0) -and (($r.Output -join '') -match 'true'))
}

function Read-Lines {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    return [System.IO.File]::ReadAllLines($Path, [System.Text.Encoding]::UTF8)
}

function Write-Text {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom)
}

function Confirm-Action {
    param([string]$Prompt)
    if ($Yes) { return $true }
    $ans = Read-Host "  $Prompt [y/N]"
    return ($ans -match '^(y|yes)$')
}

function Get-TrackedSecretHits {
    $hits = @()
    foreach ($p in $SecretPatterns) {
        $r = Invoke-Git @('grep', '-n', '-I', '-E', '-e', $p, '--')
        if ($r.Code -eq 0) {
            foreach ($line in $r.Output) {
                if ($line.Trim()) { $hits += [pscustomobject]@{ Pattern = $p; Text = $line } }
            }
        }
    }
    return @($hits)
}

function Get-HistorySecretCommits {
    $found = New-Object System.Collections.Generic.List[string]
    foreach ($p in $SecretPatterns) {
        $r = Invoke-Git @('log', '--all', '--oneline', '--pickaxe-regex', "-S$p")
        if ($r.Code -eq 0) {
            foreach ($line in $r.Output) {
                $t = $line.Trim()
                if ($t -and -not $found.Contains($t)) { $found.Add($t) }
            }
        }
    }
    return $found
}

function Test-Ignored {
    param([string]$RelativePath)
    $full = Join-Path $repoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $full)) { return '' }
    $r = Invoke-Git @('check-ignore', '-v', '--', $RelativePath)
    if ($r.Code -eq 0 -and $r.Output.Count -gt 0) { return $r.Output[0] }
    return ''
}

function Test-Tracked {
    param([string]$RelativePath)
    $r = Invoke-Git @('ls-files', '--error-unmatch', '--', $RelativePath)
    return ($r.Code -eq 0)
}

# ============================================================
# Scan：体检
# ============================================================

function Invoke-Scan {
    $script:scanTracked = @(Get-TrackedSecretHits)
    $script:scanHistory = Get-HistorySecretCommits

    Write-Step "1. 仓库概况"
    $root = (Invoke-Git @('rev-parse', '--show-toplevel')).Output
    Write-Info "仓库根目录 : $root"
    Write-Info "当前分支   : $Branch"
    Write-Info "提交总数   : $((Invoke-Git @('rev-list', '--count', '--all')).Output)"

    Write-Step "2. 已跟踪文件中的疑似密钥（git grep）"
    if ($script:scanTracked.Count -eq 0) {
        Write-Ok "已跟踪文件里没有发现真实密钥"
    } else {
        foreach ($h in $script:scanTracked) { Write-Bad $h.Text }
        Write-Warn "这些内容会被 GitHub Push Protection 拦截（GH013）"
    }

    Write-Step "3. 历史提交中的疑似密钥（git log --pickaxe-regex）"
    if ($script:scanHistory.Count -eq 0) {
        Write-Ok "全部历史提交里没有发现真实密钥"
    } else {
        foreach ($c in $script:scanHistory) { Write-Bad $c }
        Write-Warn "只要这些提交还在历史里，仅修改当前文件再推送仍会被拦截"
    }

    Write-Step "4. 本地私有配置的忽略与跟踪状态"
    $needIgnore = $false
    foreach ($f in $LocalConfigFiles) {
        $full = Join-Path $repoRoot $f
        if (-not (Test-Path -LiteralPath $full)) {
            Write-Info "$f : 不存在（可忽略）"
            continue
        }
        $rule = Test-Ignored $f
        if ($rule) { Write-Ok "$f : 已被忽略 -> $rule" }
        else { Write-Bad "$f : 未被忽略（.gitignore 缺少规则）"; $needIgnore = $true }

        if (Test-Tracked $f) { Write-Bad "$f : 仍在 Git 索引中，必须 git rm --cached" }
        else { Write-Ok "$f : 不在索引中" }
    }

    Write-Step "5. 远程配置"
    $remotes = (Invoke-Git @('remote', '-v')).Output
    if ($remotes.Count -eq 0) { Write-Warn "没有配置任何远程" }
    else { foreach ($l in $remotes) { Write-Info $l } }

    Write-Step "6. 结论"
    $needProtect = ($script:scanTracked.Count -gt 0)
    $needRebuild = ($script:scanHistory.Count -gt 0)

    if (-not ($needProtect -or $needRebuild -or $needIgnore)) {
        Write-Ok "未发现会触发拦截的问题，可以直接推送"
        $script:issuesFound = $false
        return
    }
    $script:issuesFound = $true
    if ($needProtect -or $needIgnore) { Write-Warn "下一步：.\git-secret-guard.ps1 Protect" }
    if ($needRebuild) { Write-Warn "下一步：.\git-secret-guard.ps1 Rebuild" }
    Write-Info "也可以一条命令走完：.\git-secret-guard.ps1 All -RemoteUrl <仓库地址>"
}

# ============================================================
# Protect：脱敏
# ============================================================

# 按缩进层级解析 YAML，找出「键路径 + 值命中密钥特征」的行
function Get-YamlSecretLines {
    param([string]$Path)
    $lines = Read-Lines $Path
    $stack = @()
    $result = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -match '^\s*#') { continue }
        if ($line -notmatch '^(\s*)([A-Za-z0-9_.\-]+)\s*:\s*(.*)$') { continue }
        $indent = $Matches[1].Length
        $key = $Matches[2]
        $value = $Matches[3].Trim()
        while ($stack.Count -gt 0 -and $stack[$stack.Count - 1].Indent -ge $indent) {
            if ($stack.Count -eq 1) { $stack = @() } else { $stack = $stack[0..($stack.Count - 2)] }
        }
        if ($value -eq '') {
            $stack += [pscustomobject]@{ Indent = $indent; Key = $key }
            continue
        }
        foreach ($p in $SecretPatterns) {
            if ($value -match $p) {
                $pathKeys = @($stack | ForEach-Object { $_.Key }) + $key
                $result += [pscustomobject]@{
                    LineNumber = $i + 1
                    KeyPath    = ($pathKeys -join '.')
                    Keys       = $pathKeys
                    Value      = $value
                    Indent     = $indent
                    Key        = $key
                }
                break
            }
        }
    }
    return @($result)
}

function Set-NestedValue {
    param([System.Collections.IDictionary]$Root, [string[]]$Keys, [string]$Value)
    $cur = $Root
    for ($i = 0; $i -lt $Keys.Count - 1; $i++) {
        $k = $Keys[$i]
        if (-not $cur.Contains($k)) { $cur[$k] = [ordered]@{} }
        $cur = $cur[$k]
    }
    $cur[$Keys[$Keys.Count - 1]] = $Value
}

function ConvertTo-YamlText {
    param([System.Collections.IDictionary]$Map, [int]$Indent = 0)
    $sb = New-Object System.Text.StringBuilder
    foreach ($k in $Map.Keys) {
        $v = $Map[$k]
        $pad = ' ' * $Indent
        if ($v -is [System.Collections.IDictionary]) {
            [void]$sb.AppendLine("$pad${k}:")
            [void]$sb.Append((ConvertTo-YamlText -Map $v -Indent ($Indent + 2)))
        } else {
            [void]$sb.AppendLine("$pad${k}: $v")
        }
    }
    return $sb.ToString()
}

function Add-GitignoreLines {
    $path = Join-Path $repoRoot '.gitignore'
    $lines = @(Read-Lines $path)

    $existing = @{}
    foreach ($l in $lines) { $existing[$l.Trim()] = $true }

    $missing = @()
    foreach ($l in $GitignoreLines) { if (-not $existing.ContainsKey($l)) { $missing += $l } }
    if ($missing.Count -eq 0) {
        Write-Ok ".gitignore 已包含全部规则"
        return
    }
    $out = @($lines)
    if (-not $existing.ContainsKey($GitignoreMarker)) { $out += ''; $out += $GitignoreMarker }
    $out += $missing
    Write-Text -Path $path -Text (($out -join "`r`n") + "`r`n")
    Write-Ok ".gitignore 追加了：$($missing -join ', ')"
}

function Invoke-Protect {
    $localPrimary = $LocalConfigFiles[0]
    $localPath = Join-Path $repoRoot $localPrimary

    Write-Step "Protect 1/5：收集需要处理的配置文件"
    $configFiles = @()
    $ls = Invoke-Git (@('ls-files', '--') + $ConfigGlobs)
    if ($ls.Code -eq 0) {
        foreach ($f in $ls.Output) {
            $f = $f.Trim()
            if (-not $f) { continue }
            if ($f -like '*.example') { continue }
            $skip = $false
            foreach ($lc in $LocalConfigFiles) { if ($f -eq $lc) { $skip = $true } }
            if (-not $skip) { $configFiles += $f }
        }
    }
    Write-Info "待检查：$($configFiles -join ', ')"

    $collected = [ordered]@{}
    $touched = 0

    foreach ($rel in $configFiles) {
        $full = Join-Path $repoRoot $rel
        $found = @(Get-YamlSecretLines -Path $full)
        if ($found.Count -eq 0) { continue }

        Write-Step "Protect 2/5：处理 $rel"
        $lines = @(Read-Lines $full)

        foreach ($hit in $found) {
            $suggest = (($hit.Keys -join '_').ToUpper()) -replace '[^A-Z0-9_]', '_'
            Write-Host "  第 $($hit.LineNumber) 行：$($hit.KeyPath)" -ForegroundColor White
            Write-Host "      当前值：$($hit.Value)"

            $envName = $suggest
            if (-not $Yes) {
                $answer = Read-Host "      环境变量名 [回车=$suggest，输入 skip 跳过]"
                if ($answer -eq 'skip') { Write-Warn "已跳过该行"; continue }
                if ($answer.Trim()) { $envName = $answer.Trim() }
            }

            $placeholder = '${' + $envName + ':}'
            $lines[$hit.LineNumber - 1] = (' ' * $hit.Indent) + $hit.Key + ': ' + $placeholder
            Set-NestedValue -Root $collected -Keys $hit.Keys -Value $hit.Value
            $touched++
            Write-Ok "已替换为 $placeholder"
        }

        Write-Text -Path $full -Text (($lines -join "`r`n") + "`r`n")
        Write-Ok "已改写 $rel（不写 .bak，原值仍可从 git 历史找回）"
    }

    Write-Step "Protect 3/5：生成/核对本地私有配置"
    if ($collected.Keys.Count -gt 0) {
        if (Test-Path -LiteralPath $localPath) {
            Write-Warn "$localPrimary 已存在，脚本不会覆盖"
            Write-Info "请确认下面这些值已存在于该文件中："
            Write-Host (ConvertTo-YamlText -Map $collected) -ForegroundColor Gray
        } else {
            $header = @(
                '# ============================================================',
                '# 本地私有配置：由 git-secret-guard.ps1 生成',
                '# 已在 .gitignore 中忽略，绝对不要提交',
                '# ============================================================',
                ''
            ) -join "`r`n"
            Write-Text -Path $localPath -Text ($header + (ConvertTo-YamlText -Map $collected))
            Write-Ok "已生成 $localPrimary（含真实密钥，不要提交）"
        }

        $appYml = Join-Path $repoRoot 'src/main/resources/application.yml'
        if (Test-Path -LiteralPath $appYml) {
            $appText = (Read-Lines $appYml) -join "`n"
            if ($appText -notmatch '(?m)^\s*profiles\s*:' -and $appText -notmatch 'active\s*:\s*.*local') {
                Write-Warn "application.yml 未激活 local profile，本地配置不会被加载。请在 spring: 下补三行："
                Write-Host "spring:" -ForegroundColor Gray
                Write-Host "  profiles:" -ForegroundColor Gray
                Write-Host "    active: local" -ForegroundColor Gray
            }
        }
    } else {
        Write-Info "没有从被跟踪文件里提取到需要迁移的密钥"
    }

    Write-Step "Protect 4/5：补 .gitignore 并从索引移除"
    Add-GitignoreLines
    foreach ($f in $LocalConfigFiles) {
        $full = Join-Path $repoRoot $f
        if (-not (Test-Path -LiteralPath $full)) { continue }
        if (Test-Tracked $f) {
            Invoke-Git @('rm', '--cached', '--', $f) | Out-Null
            Write-Ok "已从索引移除 $f（磁盘文件保留）"
        } else {
            Write-Ok "$f 不在索引中"
        }
    }

    Write-Step "Protect 5/5：复检"
    $again = @(Get-TrackedSecretHits)
    if ($again.Count -eq 0) {
        Write-Ok "已跟踪文件里已无真实密钥"
    } else {
        foreach ($h in $again) { Write-Bad $h.Text }
        Write-Warn "仍有残留，请手工处理（常见于 .java / .xml 里的硬编码密钥）"
    }

    if ($Commit) {
        Invoke-Git @('add', '-A') | Out-Null
        $r = Invoke-Git @('commit', '-m', 'chore: 密钥改为占位符与本地私有配置')
        if ($r.Code -eq 0) { Write-Ok "已提交" } else { Write-Warn "没有可提交的变更或提交失败：$($r.Output -join ' ')" }
    } else {
        Write-Info "改动尚未提交（加 -Commit 提交，或继续执行 Rebuild 模式）"
        Write-Info "本次替换行数：$touched"
    }
}

# ============================================================
# Rebuild：重建历史
# ============================================================

function Get-IndexSecretHits {
    $gitArgs = @('grep', '--cached', '-n', '-I', '-E')
    foreach ($p in $SecretPatterns) { $gitArgs += @('-e', $p) }
    $gitArgs += '--'
    $r = Invoke-Git $gitArgs
    if ($r.Code -eq 0) { return @($r.Output) }
    return @()
}

function Invoke-Rebuild {
    Write-Step "Rebuild 1/4：备份旧历史"
    if (-not $BackupDir) { $BackupDir = Split-Path -Parent $repoRoot }
    if (-not (Test-Path -LiteralPath $BackupDir)) { New-Item -ItemType Directory -Path $BackupDir | Out-Null }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $bundle = Join-Path $BackupDir ("{0}-backup-{1}.bundle" -f (Split-Path -Leaf $repoRoot), $stamp)
    $r = Invoke-Git @('bundle', 'create', $bundle, '--all')
    if ($r.Code -eq 0) { Write-Ok "历史已备份：$bundle" }
    else { Write-Bad "备份失败，已中止：$($r.Output -join ' ')"; return }

    Write-Step "Rebuild 2/4：确认"
    Write-Warn "将把所有历史压缩成 1 条提交，旧提交永久删除（可用上面的 bundle 恢复）"
    if (-not (Confirm-Action "确认继续？")) { Write-Info "已取消"; return }

    Write-Step "Rebuild 3/4：重建"
    Invoke-Git @('checkout', '--orphan', '__secret_guard_clean') | Out-Null
    Invoke-Git @('add', '-A') | Out-Null

    $staged = @(Get-IndexSecretHits)
    if ($staged.Count -gt 0) {
        Write-Bad "待提交内容里仍有密钥，已中止："
        foreach ($s in $staged) { Write-Bad $s }
        Invoke-Git @('checkout', $Branch) | Out-Null
        Invoke-Git @('branch', '-D', '__secret_guard_clean') | Out-Null
        Write-Info "请先执行 Protect 模式处理后再试"
        return
    }
    Write-Ok "待提交内容里没有命中密钥特征的内容"

    $r = Invoke-Git @('commit', '-m', $CommitMessage)
    if ($r.Code -ne 0) {
        Write-Bad "提交失败：$($r.Output -join ' ')"
        Invoke-Git @('checkout', $Branch) | Out-Null
        Invoke-Git @('branch', '-D', '__secret_guard_clean') | Out-Null
        Write-Info "已回退到 $Branch"
        return
    }
    Write-Ok "已创建干净提交"

    Invoke-Git @('branch', '-D', $Branch) | Out-Null
    Invoke-Git @('branch', '-m', $Branch) | Out-Null
    Invoke-Git @('reflog', 'expire', '--expire=now', '--all') | Out-Null
    Invoke-Git @('gc', '--prune=now', '--aggressive') | Out-Null
    Write-Ok "旧对象已回收，当前分支：$Branch"

    Write-Step "Rebuild 4/4：验证"
    $left = Get-HistorySecretCommits
    if ($left.Count -eq 0) { Write-Ok "历史里已无密钥" }
    else {
        foreach ($c in $left) { Write-Bad $c }
        Write-Warn "仍有残留，检查是否有其它分支或远程跟踪引用（git remote -v）"
    }
    Write-Info "当前历史："
    foreach ($l in (Invoke-Git @('log', '--all', '--oneline')).Output) { Write-Info "  $l" }
}

# ============================================================
# Push：推送
# ============================================================

function Invoke-Push {
    Write-Step "Push：远程与推送"
    $remotes = @((Invoke-Git @('remote')).Output | ForEach-Object { $_.Trim() })

    if ($remotes -notcontains $RemoteName) {
        if (-not $RemoteUrl) {
            Write-Bad "远程 '$RemoteName' 不存在，请用 -RemoteUrl 指定仓库地址"
            return
        }
        Invoke-Git @('remote', 'add', $RemoteName, $RemoteUrl) | Out-Null
        Write-Ok "已添加远程 $RemoteName -> $RemoteUrl"
    } elseif ($RemoteUrl) {
        Invoke-Git @('remote', 'set-url', $RemoteName, $RemoteUrl) | Out-Null
        Write-Ok "已更新远程 $RemoteName -> $RemoteUrl"
    } else {
        Write-Ok "使用已有远程 $RemoteName"
    }
    foreach ($l in (Invoke-Git @('remote', '-v')).Output) { Write-Info $l }

    $gitArgs = @('push', '-u', $RemoteName, $Branch)
    if ($Force) {
        Write-Warn "使用 --force：会覆盖远程分支"
        $gitArgs = @('push', '--force', '-u', $RemoteName, $Branch)
    }
    Write-Info "执行：git $($gitArgs -join ' ')"

    $r = Invoke-Git $gitArgs
    foreach ($l in $r.Output) { Write-Host "  $l" }

    if ($r.Code -eq 0) { Write-Ok "推送成功"; return }

    $text = $r.Output -join "`n"
    Write-Bad "推送失败"
    if ($text -match 'GH013|Push cannot contain secrets') {
        Write-Warn "仍被 Push Protection 拦截：历史里还有密钥，请执行 Rebuild 模式"
    } elseif ($text -match 'non-fast-forward|fetch first|rejected') {
        Write-Warn "远程与本地分叉：确认远程内容无用后再执行 Push -Force"
    } elseif ($text -match 'Authentication failed|Permission denied|403') {
        Write-Warn "认证问题：HTTPS 要填 Personal Access Token（不是账号密码），或改用 SSH"
    } elseif ($text -match 'Repository not found|404') {
        Write-Warn "仓库不存在或地址错误：确认 GitHub 上已建好空仓库且账号有权限"
    } elseif ($text -match 'file is \d+\.\d+ MB|exceeds GitHub') {
        Write-Warn "有大文件超过 100MB：改用 Git LFS 或从历史中剔除"
    }
}

# ============================================================
# 主流程
# ============================================================

if (-not (Test-Path -LiteralPath $RepoPath)) { throw "路径不存在：$RepoPath" }
$repoRoot = (Resolve-Path -LiteralPath $RepoPath).Path
Set-Location -LiteralPath $repoRoot

if (-not (Test-IsGitRepo)) {
    Write-Host "错误：$repoRoot 不是 Git 仓库（找不到 .git）" -ForegroundColor Red
    Write-Host "请在项目根目录执行，或用 -RepoPath 指定，例如：" -ForegroundColor Yellow
    Write-Host "  .\git-secret-guard.ps1 Scan -RepoPath D:\code\zjj-picture-backend"
    exit 1
}

if (-not $Branch) {
    $b = Invoke-Git @('rev-parse', '--abbrev-ref', 'HEAD')
    if ($b.Code -eq 0) { $Branch = $b.Output[0] } else { $Branch = 'master' }
}

Write-Host "git-secret-guard  |  模式：$Mode  |  仓库：$repoRoot  |  分支：$Branch" -ForegroundColor Cyan

switch ($Mode) {
    'Scan' { Invoke-Scan }
    'Protect' { Invoke-Protect }
    'Rebuild' { Invoke-Rebuild }
    'Push' { Invoke-Push }
    'All' {
        Invoke-Protect
        Invoke-Rebuild
        Invoke-Push
    }
}

Write-Step "收尾清单（脚本无法代劳的部分）"
Write-Info "1. 到云控制台作废本次涉及的密钥并重新生成（进过历史/聊天/截图的都算泄露）"
Write-Info "2. 新密钥只填进本地私有配置（如 $($LocalConfigFiles[0])），该文件永远不要提交"
Write-Info "3. 重启项目验证功能：例如图片上传（COS）、AI 生成简介（方舟）"
Write-Info "4. 到 GitHub 网页确认：目标配置文件里是占位符，且本地私有配置不在仓库里"

# Scan 模式下发现问题时返回非 0，可用于 pre-push 钩子拦截
if ($Mode -eq 'Scan' -and $script:issuesFound) { exit 2 }
