# 批量翻译脚本 - 读 translations.txt 逐行替换
$root = "E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player"
$translations = @{}
Get-Content "E:\SDWMP3_CN\translations.txt" | Where-Object { $_ -match '^(.+)=(.+)$' -and $_ -notmatch '^#' } | ForEach-Object {
    $k = [Regex]::Match($_, '^(.+?)=(.+)$').Groups[1].Value.Trim()
    $v = [Regex]::Match($_, '^(.+?)=(.+)$').Groups[2].Value.Trim()
    if ($k -and $v -and $k -ne $v) { $translations[$k] = $v }
}
"Loaded $($translations.Count) translations"

$changed = 0
Get-ChildItem -Recurse "$root\*\*.kt","$root\*\*\*.kt","$root\*\*\*\*.kt" -ErrorAction SilentlyContinue | ForEach-Object {
    $content = Get-Content -Raw $_.FullName
    $modified = $false
    foreach ($k in $translations.Keys | Sort-Object { $_.Length } -Descending) {
        $v = $translations[$k]
        $pattern = [Regex]::Escape($k)
        if ($content -match '"' + $pattern + '"') {
            $content = $content -replace ('"' + $pattern + '"'), ('"' + $v + '"')
            $modified = $true
        }
    }
    if ($modified) {
        Set-Content -Path $_.FullName -Value $content -NoNewline
        $changed++
        Write-Host "  $_" -ForegroundColor Gray
    }
}
"Changed $changed files"
