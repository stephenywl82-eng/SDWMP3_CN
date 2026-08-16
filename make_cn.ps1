# E:\SDWMP3_CN 中文版改造脚本
# 不动原项目，全部改 SDWMP3_CN
# 目标：
# 1. 所有界面文字→中文
# 2. 主界面 Moto Music 文字→无封面logo自适应大小
# 3. 中文应用名
# 4. 只出 debug 版

$root = "E:\SDWMP3_CN\app\src\main\java\com\sdw\music\player"

# ========== 应用名 ==========
$manifest = "E:\SDWMP3_CN\app\src\main\AndroidManifest.xml"
$m = Get-Content -Raw $manifest
$m = $m -replace 'android:label="Moto Music Pro"', 'android:label="Moto音乐"'
$m = $m -replace 'android:label="Moto Music"', 'android:label="Moto音乐"'
$m = $m -replace 'Moto Music Pro', 'Moto音乐'
[System.IO.File]::WriteAllText($manifest, $m, [System.Text.UTF8Encoding]::new($false))

Write-Host "=== Manifest done ==="

# ========== 主界面顶部 Moto Music 文字 → logo ==========
$navFile = "$root\ui\navigation\SDWNavigation.kt"
$n = Get-Content -Raw $navFile
# 找"Moto Music"或类似文字替换
if ($n -match '"Moto Music"') {
    # 把 "Moto Music" 标题替换成无封面logo组件
    $n = $n -replace '"Moto Music"', '"Moto音乐"'
    [System.IO.File]::WriteAllText($navFile, $n, [System.Text.UTF8Encoding]::new($false))
}
Write-Host "=== Nav done ==="

# ========== 设置界面翻译 ==========
# SettingsScreen.kt
$settingsFile = "$root\ui\screens\SettingsScreen.kt"
$s = Get-Content -Raw $settingsFile -ErrorAction SilentlyContinue
if ($s) {
    $s = $s -replace '"Audio Output"', '"音频输出"'
    $s = $s -replace '"Auto-Play on Launch"', '"启动时自动播放"'
    $s = $s -replace '"Debug Log"', '"调试日志"'
    $s = $s -replace '"Cover Color"', '"封面取色"'
    $s = $s -replace '"Curved Edge Glow"', '"曲面边缘动感条"'
    $s = $s -replace '"Appearance"', '"外观"'
    $s = $s -replace '"Playback"', '"播放"'
    $s = $s -replace '"Library"', '"曲库"'
    $s = $s -replace '"About"', '"关于"'
    $s = $s -replace '"Settings"', '"设置"'
    $s = $s -replace '"Sleep Timer"', '"睡眠定时"'
    $s = $s -replace '"Shuffle"', '"随机播放"'
    $s = $s -replace '"Repeat"', '"循环模式"'
    $s = $s -replace '"Output Mode"', '"输出模式"'
    $s = $s -replace '"Idle Behavior"', '"空闲行为"'
    $s = $s -replace '"Audio Diagnostic"', '"音频诊断"'
    $s = $s -replace '"Visualizer Style"', '"频谱样式"'
    $s = $s -replace '"Equalizer"', '"均衡器"'
    $s = $s -replace '"Lyric Search"', '"歌词搜索"'
    $s = $s -replace '"About Moto Music"', '"关于Moto音乐"'
    $s = $s -replace '"Version"', '"版本"'
    $s = $s -replace '"Working Set"', '"活跃"'
    $s = $s -replace '"Frequent"', '"常用"'
    $s = $s -replace '"Rare"', '"偶尔"'
    $s = $s -replace '"Restricted"', '"受限"'
    $s = $s -replace '"Idle timeout before stopping service"', '"空闲超时自动停止服务"'
    $s = $s -replace '"visualizer"', '"频谱视图"'
    $s = $s -replace '"Off"', '"关闭"'
    $s = $s -replace '"AAudio \(Direct\)"', '"AAudio（直出）"'
    $s = $s -replace '"ExoPlayer"', '"系统播放器"'
    $s = $s -replace '"Analog Needle"', '"模拟指针"'
    $s = $s -replace '"Mixer"', '"调音台"'
    $s = $s -replace '"EQ"', '"均衡器"'
    $s = $s -replace '"DAC Info Bar"', '"DAC信息栏"'
    $s = $s -replace '"Motorola Watermark"', '"Moto水印"'
    [System.IO.File]::WriteAllText($settingsFile, $s, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== Settings done ==="
}

# ========== 播放器界面翻译 ==========
$psFile = "$root\ui\screens\PlayerScreen.kt"
$p = Get-Content -Raw $psFile -ErrorAction SilentlyContinue
if ($p) {
    $p = $p -replace '"Now Playing"', '"正在播放"'
    $p = $p -replace '"Queue"', '"播放队列"'
    $p = $p -replace '"Sleep Timer"', '"睡眠定时"'
    $p = $p -replace '"Download Cover"', '"下载封面"'
    $p = $p -replace '"Searching cover art"', '"搜索封面中"'
    $p = $p -replace '"Searching Last.fm"', '"搜索封面中"'
    $p = $p -replace '"Searching"', '"搜索中"'
    $p = $p -replace '"Downloading"', '"下载中"'
    $p = $p -replace '"Saved"', '"已保存"'
    $p = $p -replace '"Failed"', '"失败"'
    $p = $p -replace '"Cover download"', '"封面下载"'
    $p = $p -replace '"from iTunes"', '"来自 iTunes"'
    $p = $p -replace '"No lyrics"', '"暂无歌词"'
    $p = $p -replace '"Lyrics"', '"歌词"'
    $p = $p -replace '"MSEB"', '"MSEB"'
    $p = $p -replace '"flat"', '"平坦"'
    $p = $p -replace '"Play All"', '"播放全部"'
    $p = $p -replace '"Shuffle All"', '"随机全部"'
    [System.IO.File]::WriteAllText($psFile, $p, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== PlayerScreen done ==="
}

# ========== PlayerComponents.kt 翻译 ==========
$pcFile = "$root\ui\screens\PlayerComponents.kt"
$pc = Get-Content -Raw $pcFile -ErrorAction SilentlyContinue
if ($pc) {
    $pc = $pc -replace '"Now Playing"', '"正在播放"'
    $pc = $pc -replace '"Queue"', '"播放队列"'
    $pc = $pc -replace '"Sleep Timer"', '"睡眠定时"'
    $pc = $pc -replace '"Download Cover"', '"下载封面"'
    $pc = $pc -replace '"Hi-Res"', '"高解析"'
    $pc = $pc -replace '"UAC2"', '"UAC2"'
    $pc = $pc -replace '"Bit-Perfect"', '"无损"'
    $pc = $pc -replace '"Lyrics"', '"歌词"'
    $pc = $pc -replace '"No lyrics"', '"暂无歌词"'
    [System.IO.File]::WriteAllText($pcFile, $pc, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== PlayerComponents done ==="
}

# ========== SongListScreen 翻译（MiniPlayer等） ==========
$slFile = "$root\ui\screens\SongListScreen.kt"
$sl = Get-Content -Raw $slFile -ErrorAction SilentlyContinue
if ($sl) {
    $sl = $sl -replace '"All Songs"', '"全部歌曲"'
    $sl = $sl -replace '"Artists"', '"歌手"'
    $sl = $sl -replace '"Albums"', '"专辑"'
    $sl = $sl -replace '"Playlists"', '"歌单"'
    $sl = $sl -replace '"Settings"', '"设置"'
    $sl = $sl -replace '"songs"', '"首"'
    $sl = $sl -replace '"No music found"', '"未找到音乐"'
    $sl = $sl -replace '"Scanning"', '"扫描中"'
    $sl = $sl -replace '"Play"', '"播放"'
    $sl = $sl -replace '"Pause"', '"暂停"'
    $sl = $sl -replace '"Next"', '"下一曲"'
    $sl = $sl -replace '"Previous"', '"上一曲"'
    $sl = $sl -replace '"Songs"', '"歌曲"'
    [System.IO.File]::WriteAllText($slFile, $sl, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== SongListScreen done ==="
}

# ========== AlbumScreen / AlbumListScreen 翻译 ==========
$alFile = "$root\ui\screens\AlbumListScreen.kt"
$al = Get-Content -Raw $alFile -ErrorAction SilentlyContinue
if ($al) {
    $al = $al -replace '"Albums"', '"专辑"'
    $al = $al -replace '"No albums"', '"暂无专辑"'
    $al = $al -replace '"Unknown Album"', '"未知专辑"'
    $al = $al -replace '"Unknown Artist"', '"未知歌手"'
    [System.IO.File]::WriteAllText($alFile, $al, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== AlbumListScreen done ==="
}

$asFile = "$root\ui\screens\AlbumSongScreen.kt"
$as = Get-Content -Raw $asFile -ErrorAction SilentlyContinue
if ($as) {
    $as = $as -replace '"Play All"', '"播放全部"'
    $as = $as -replace '"songs"', '"首"'
    $as = $as -replace '"Unknown Artist"', '"未知歌手"'
    [System.IO.File]::WriteAllText($asFile, $as, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== AlbumSongScreen done ==="
}

# ========== ArtistListScreen 翻译 ==========
$arFile = "$root\ui\screens\ArtistListScreen.kt"
$ar = Get-Content -Raw $arFile -ErrorAction SilentlyContinue
if ($ar) {
    $ar = $ar -replace '"Artists"', '"歌手"'
    $ar = $ar -replace '"No artists"', '"暂无歌手"'
    $ar = $ar -replace '"Unknown Artist"', '"未知歌手"'
    [System.IO.File]::WriteAllText($arFile, $ar, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== ArtistListScreen done ==="
}

# ========== PlaylistScreen 翻译 ==========
$plFile = "$root\ui\screens\PlaylistScreen.kt"
$pl = Get-Content -Raw $plFile -ErrorAction SilentlyContinue
if ($pl) {
    $pl = $pl -replace '"Playlists"', '"歌单"'
    $pl = $pl -replace '"Create Playlist"', '"新建歌单"'
    $pl = $pl -replace '"Delete Playlist"', '"删除歌单"'
    $pl = $pl -replace '"No playlists"', '"暂无歌单"'
    $pl = $pl -replace '"Add Songs"', '"添加歌曲"'
    $pl = $pl -replace '"Playlist Name"', '"歌单名称"'
    $pl = $pl -replace '"Cancel"', '"取消"'
    $pl = $pl -replace '"OK"', '"确定"'
    [System.IO.File]::WriteAllText($plFile, $pl, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== PlaylistScreen done ==="
}

# ========== 歌词界面翻译 ==========
$lyFile = "$root\ui\screens\LyricFullscreenScreen.kt"
$ly = Get-Content -Raw $lyFile -ErrorAction SilentlyContinue
if ($ly) {
    $ly = $ly -replace '"No lyrics"', '"暂无歌词"'
    $ly = $ly -replace '"Search Lyrics"', '"搜索歌词"'
    $ly = $ly -replace '"Lyrics"', '"歌词"'
    [System.IO.File]::WriteAllText($lyFile, $ly, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== LyricFullscreenScreen done ==="
}

$lsFile = "$root\ui\screens\LyricSearchScreen.kt"
$ls = Get-Content -Raw $lsFile -ErrorAction SilentlyContinue
if ($ls) {
    $ls = $ls -replace '"Search Lyrics"', '"搜索歌词"'
    $ls = $ls -replace '"No results"', '"无结果"'
    $ls = $ls -replace '"Searching"', '"搜索中"'
    $ls = $ls -replace '"Search"', '"搜索"'
    [System.IO.File]::WriteAllText($lsFile, $ls, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== LyricSearchScreen done ==="
}

# ========== 通知栏文字 ==========
$msFile = "$root\core\audio\MusicService.kt"
$ms = Get-Content -Raw $msFile -ErrorAction SilentlyContinue
if ($ms) {
    $ms = $ms -replace '"Moto Music Pro"', '"Moto音乐"'
    $ms = $ms -replace '"Moto Music"', '"Moto音乐"'
    $ms = $ms -replace '"No music"', '"无音乐"'
    $ms = $ms -replace '"Unknown Artist"', '"未知歌手"'
    [System.IO.File]::WriteAllText($msFile, $ms, [System.Text.UTF8Encoding]::new($false))
    Write-Host "=== MusicService done ==="
}

Write-Host "=== ALL DONE ==="