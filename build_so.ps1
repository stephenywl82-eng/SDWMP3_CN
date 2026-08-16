$ndkBin  = "C:\Android\ndk\android-ndk-r25c\toolchains\llvm\prebuilt\windows-x86_64\bin"
$sysroot = "$ndkBin\..\sysroot"
$srcDir  = "E:\SDWMP3\app\src\main\cpp"
$outDir  = "E:\SDWMP3\app\src\main\jniLibs\arm64-v8a"

$cxx   = "$ndkBin\aarch64-linux-android26-clang++.cmd"
$strip = "$ndkBin\llvm-strip.exe"

$flags = @(
    "-std=c++17",
    "-fPIC",
    "-O2",
    "-DNDEBUG",
    "-fno-exceptions",
    "-I$srcDir"
)

$objs = @()
foreach ($src in @("oboe_bridge.cpp", "usb_audio_driver.cpp", "usb_audio_jni.cpp", "flac_decoder_jni.cpp")) {
    $obj = "$env:TEMP\${src}_tmp.o"
    $objs += $obj
    Write-Host "Compiling $src ..."
    & $cxx @flags "-c" "$srcDir\$src" "-o" $obj 2>&1
    if ($LASTEXITCODE -ne 0) { Write-Host "FAILED: $src"; exit 1 }
    Write-Host "  OK ($((Get-Item $obj).Length) bytes)"
}

$outSo = "$outDir\liboboe_bridge.so"
if (Test-Path $outSo) { Copy-Item $outSo "$outSo.bak" -Force }
Write-Host "Linking ..."
& $cxx @flags "-shared" "-fPIC" "-L$outDir" "-loboe" "-llog" "-landroid" "-lmediandk" "-lc++_shared" $objs "-o" $outSo 2>&1
if ($LASTEXITCODE -ne 0) { Write-Host "LINK FAILED"; exit 1 }

Write-Host "Stripping ..."
& $strip --strip-all $outSo
Write-Host "DONE: $((Get-Item $outSo).Length) bytes"
