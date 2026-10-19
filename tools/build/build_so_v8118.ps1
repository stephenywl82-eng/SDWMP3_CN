$ErrorActionPreference = "Stop"
# [V8.118] Build profile: -O2 -DNDEBUG + hidden visibility + section GC
# Hidden visibility matters: without it the linker exports ~700 libc++ template
# symbols (1100 dyn syms) and the file balloons from ~480 KB to ~770 KB.
#
# NOTE (-fno-exceptions / -fno-rtti REMOVED):
# These were correct back in V8.118, but the code base has since grown
# exception-based paths:
#   * oboe_bridge.cpp  - codecCallTimed/codecCallVoid wrap AMediaCodec calls in
#                        try/catch(...) for timeout safety (V8.392 area)
#   * rubberband       - Allocators.h uses throw / std::bad_alloc
# Keeping -fno-exceptions made the build fail outright
# ("cannot use 'try'/'throw' with exceptions disabled"). Exceptions are off the
# hot path (decoder open/stop), so the size cost is negligible.
$ndk = "E:\Android\Sdk\ndk\25.1.8937393"
$cmake = "E:\Android\Sdk\cmake\3.22.1\bin\cmake.exe"
$ninja = "E:\Android\Sdk\cmake\3.22.1\bin\ninja.exe"
$src = "E:\SDWMP3_CN\app\src\main\cpp"
$build = "E:\SDWMP3_CN\cpp_build_v8118b"
$out = "E:\SDWMP3_CN\app\src\main\jniLibs\arm64-v8a\liboboe_bridge.so"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Path $build -Force | Out-Null

$cxxFlags = "-O2 -DNDEBUG -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections"
$cFlags   = "-O2 -DNDEBUG -fvisibility=hidden -ffunction-sections -fdata-sections"

& $cmake -S $src -B $build -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$ninja" `
  "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
  "-DANDROID_NDK=$ndk" `
  "-DANDROID_ABI=arm64-v8a" `
  "-DANDROID_PLATFORM=android-24" `
  "-DANDROID_STL=c++_shared" `
  "-DCMAKE_BUILD_TYPE=Release" `
  "-DCMAKE_CXX_FLAGS=$cxxFlags" `
  "-DCMAKE_C_FLAGS=$cFlags" `
  "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,--gc-sections"
if ($LASTEXITCODE -ne 0) { Write-Output "CMAKE CONFIGURE FAILED"; exit 1 }

& $cmake --build $build --target oboe_bridge -j 2
if ($LASTEXITCODE -ne 0) { Write-Output "BUILD FAILED"; exit 1 }

$strip = "$ndk\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"
Copy-Item "$build\liboboe_bridge.so" $out -Force
& $strip --strip-all $out
Write-Output "==== DONE ===="
Get-Item $out | Select-Object FullName, Length, LastWriteTime
