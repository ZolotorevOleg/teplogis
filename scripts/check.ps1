param([switch]$Integration, [switch]$Memory, [string]$DockerApiVersion)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Push-Location $root
$oldJavaHome = $env:JAVA_HOME
try {
    $localJdk = Get-ChildItem work/tools -Directory -Filter 'jdk-11*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName }
    $maven = 'mvn'
    if (Test-Path work/tools/apache-maven-3.9.9/bin/mvn.cmd) { $maven = (Resolve-Path work/tools/apache-maven-3.9.9/bin/mvn.cmd).Path }
    $arguments = @('-B','-f','backend/pom.xml')
    if ($DockerApiVersion) { $arguments += "-Dapi.version=$DockerApiVersion" }
    if (Test-Path work/m2) { $arguments += "-Dmaven.repo.local=$root/work/m2" }
    if (Test-Path work/maven-settings.xml) { $arguments += @('-s','work/maven-settings.xml') }
    if ($Integration) { $arguments += @('clean','verify') } else { $arguments += @('clean','package') }
    & $maven @arguments
    if ($LASTEXITCODE -ne 0) { throw "Maven failed: $LASTEXITCODE" }
    if ($Memory) {
        $core = Get-ChildItem @('work/m2', "$env:USERPROFILE/.m2/repository") -Filter jackson-core-2.17.3.jar -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
        if (!$core) { throw 'Jackson core jar not found' }
        $java = 'java'
        if ($env:JAVA_HOME) { $java = Join-Path $env:JAVA_HOME bin/java.exe }
        & $java -Xmx64m -cp "backend/target/classes;backend/target/test-classes;$($core.FullName)" ru.lct.heat.geojson.StreamingMemoryCheck 153391680
        if ($LASTEXITCODE -ne 0) { throw 'Streaming memory check failed' }
    }
} finally { $env:JAVA_HOME = $oldJavaHome; Pop-Location }
