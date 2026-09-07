@echo off
REM ============================================
REM 乐固加固包重签名脚本（debug.keystore）
REM 用法: sign_legu.bat <加固包路径>
REM 输出: 同目录 _signed.apk
REM ============================================
setlocal
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set APKSIGNER=E:\Android\Sdk\build-tools\37.0.0\apksigner.bat
set KS=E:\SDWMP3_CN\app\debug.keystore
set KS_PASS=android
set KS_ALIAS=androiddebugkey

if "%~1"=="" (
    echo 用法: sign_legu.bat ^<加固包路径^>
    exit /b 1
)

set INPUT=%~1
set OUTPUT=%~dpn1_signed.apk

"%APKSIGNER%" sign --ks "%KS%" --ks-key-alias %KS_ALIAS% --ks-pass pass:%KS_PASS% --key-pass pass:%KS_PASS% --out "%OUTPUT%" "%INPUT%"
if errorlevel 1 (
    echo 签名失败
    exit /b 1
)

"%APKSIGNER%" verify --print-certs "%OUTPUT%"
echo.
echo 签名完成: %OUTPUT%
endlocal
