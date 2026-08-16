$cc = "C:\Android\ndk\android-ndk-r25c\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android26-clang++.cmd"
$srcDir = "E:\SDWMP3_CN\app\src\main\cpp"
$outFile = "E:\SDWMP3_CN\app\src\main\jniLibs\arm64-v8a\liboboe_bridge.so"
$libDir = "E:\SDWMP3_CN\app\src\main\jniLibs\arm64-v8a"

$args = @(
    '-std=c++17', '-O2', '-fPIC', '-shared',
    '-o', $outFile,
    "$srcDir\oboe_bridge.cpp",
    "$srcDir\usb_audio_driver.cpp",
    "$srcDir\usb_audio_jni.cpp",
    "$srcDir\flac_decoder_jni.cpp",
    '-I', $srcDir,
    '-L', $libDir,
    '-lmediandk', '-landroid', '-llog', '-loboe', '-lc++_shared'
)

Write-Host "Compiling CN liboboe_bridge.so ..."
& $cc @args 2>&1
if ($LASTEXITCODE -eq 0) {
    $info = Get-Item $outFile
    Write-Host "DONE: $($info.Length) bytes"
    $strip = "C:\Android\ndk\android-ndk-r25c\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
    & $strip --strip-all $outFile
    Write-Host "STRIPPED: $((Get-Item $outFile).Length) bytes"
} else {
    Write-Host "FAILED: exit code $LASTEXITCODE"
}
