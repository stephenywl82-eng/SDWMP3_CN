$cc = "E:\Android\Sdk\ndk\25.1.8937393\toolchains\llvm\prebuilt\windows-x86_64\bin\aarch64-linux-android21-clang++.cmd"
$srcDir = "E:\SDWMP3\app\src\main\cpp"
$outFile = "E:\SDWMP3\app\src\main\jniLibs\arm64-v8a\liboboe_bridge.so"

$args = @(
    '-std=c++17', '-O3', '-fPIC', '-shared',
    '-o', $outFile,
    '-Wl', '--gc-sections',
    "$srcDir\oboe_bridge.cpp",
    "$srcDir\usb_audio_driver.cpp",
    "$srcDir\usb_audio_jni.cpp",
    "$srcDir\flac_decoder_jni.cpp",
    '-I', $srcDir,
    '-lmediandk', '-landroid', '-llog', '-lOpenSLES', '-loboe',
    '-Wl', '-soname,liboboe_bridge.so'
)

Write-Host "Compiling..."
& $cc @args
if ($LASTEXITCODE -eq 0) {
    $info = Get-Item $outFile
    Write-Host "DONE: $($info.Length) bytes"
} else {
    Write-Host "FAILED: exit code $LASTEXITCODE"
}
