$ErrorActionPreference = "Stop"

$ndk = "E:\Android\Sdk\ndk\25.1.8937393"
$cmake = "E:\Android\Sdk\cmake\3.22.1\bin\cmake.exe"
$ninja = "E:\Android\Sdk\cmake\3.22.1\bin\ninja.exe"
$src = "E:\SDWMP3_CN\app\src\main\cpp"
$build = "E:\SDWMP3_CN\cpp_build_v83"
$out = "E:\SDWMP3_CN\app\src\main\jniLibs\arm64-v8a\liboboe_bridge.so"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $build -Force | Out-Null

& $cmake -S $src -B $build -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$ninja" `
  "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
  "-DANDROID_NDK=$ndk" `
  "-DANDROID_ABI=arm64-v8a" `
  "-DANDROID_PLATFORM=android-24" `
  "-DCMAKE_BUILD_TYPE=Release"

if ($LASTEXITCODE -ne 0) { Write-Output "CMAKE CONFIGURE FAILED"; exit 1 }

& $cmake --build $build --target oboe_bridge -j 2

if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED"; exit 1 }

# strip
$strip = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
$so = "$build\liboboe_bridge.so"
Copy-Item $so $out -Force
& $strip $out

Write-Output "==== DONE ===="
Get-Item $out | Select-Object FullName, Length, LastWriteTime
