@echo off
setlocal
set JAVA_HOME=C:\qclaw-tools\jdk-17
set GRADLE_HOME=C:\qclaw-tools\gradle-8.5
set DEFAULT_JVM_OPTS="-Xmx2048m"
set CLASSPATH=%~dp0gradle\wrapper\gradle-wrapper.jar

%JAVA_HOME%\bin\java.exe %DEFAULT_JVM_OPTS% -classpath %CLASSPATH% org.gradle.wrapper.GradleWrapperMain %*
endlocal
