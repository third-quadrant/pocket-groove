param([switch]$Package, [switch]$UiTest, [switch]$AudioSmoke)
$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-*" -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\jpackage.exe")) { throw 'Set JAVA_HOME to a redistributable JDK 17+ (Eclipse Temurin recommended).' }
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$maven = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($maven) { $mvn = $maven.Source } else {
    $mvn = Join-Path $PWD '.tools/apache-maven-3.9.9/bin/mvn.cmd'
    if (-not (Test-Path $mvn)) {
        New-Item -ItemType Directory -Force .tools | Out-Null
        $url = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip'
        Invoke-WebRequest $url -OutFile .tools/maven.zip
        $expected = (Invoke-WebRequest "$url.sha512").Content.Trim()
        if ((Get-FileHash .tools/maven.zip -Algorithm SHA512).Hash -ne $expected) { throw 'Maven checksum mismatch.' }
        Expand-Archive .tools/maven.zip .tools -Force
    }
}
& $mvn clean
if ($LASTEXITCODE -ne 0) { throw 'Could not clear the generated Java build output.' }
& "$PSScriptRoot/build-native.ps1"
if ($LASTEXITCODE -ne 0) { throw 'Native audio build failed.' }
if ($AudioSmoke) {
    & $mvn '-Dgroove.audioTest=true' '-Dgroove.native.path=target/native/pocketgroove_audio.dll' '-Dtest=AudioEngineTest' test
    if ($LASTEXITCODE -ne 0) { throw 'WASAPI audio smoke test failed.' }
}
$arguments = @('verify', '-Dgroove.native.path=target/native/pocketgroove_audio.dll')
if ($UiTest) { $arguments += '-Dgroove.uiTest=true' }
Write-Host "Running checks: $($arguments -join ' ')"
& $mvn @arguments
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
if (-not $Package) { exit 0 }

$name = 'PocketGroove-0.6.2'
$inputDir = Join-Path $PWD 'target/package-input'
$distDir = Join-Path $PWD "dist/$name"
$appDir = Join-Path $distDir 'PocketGroove'
$zipPath = Join-Path $PWD "dist/$name-windows-x64.zip"
if (Test-Path $inputDir) { Remove-Item $inputDir -Recurse -Force }
New-Item -ItemType Directory -Force $inputDir, dist | Out-Null
Copy-Item target/pocket-groove-0.6.2.jar $inputDir/
Copy-Item target/lib $inputDir/lib -Recurse
Copy-Item target/native/pocketgroove_audio.dll $inputDir/
Copy-Item LICENSE,THIRD_PARTY_NOTICES.md,README.md $inputDir/
Copy-Item src/main/resources/fonts/*OFL.txt $inputDir/
Copy-Item licenses $inputDir/licenses -Recurse
if (Test-Path $distDir) { Remove-Item $distDir -Recurse -Force }
New-Item -ItemType Directory -Force $distDir | Out-Null
& "$env:JAVA_HOME/bin/jpackage.exe" --type app-image --name PocketGroove --app-version 0.6.2 --vendor 'Pocket Groove contributors' --input $inputDir --main-jar pocket-groove-0.6.2.jar --main-class io.pocketgroove.Launcher --dest $distDir --add-modules java.desktop,java.logging,java.xml,jdk.unsupported,jdk.charsets --java-options '--module-path=$APPDIR/lib' --java-options '--add-modules=javafx.controls'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
Copy-Item LICENSE,THIRD_PARTY_NOTICES.md,README.md $appDir/
Compress-Archive -Path $appDir -DestinationPath $zipPath -Force
if (-not (Test-Path "$appDir/PocketGroove.exe") -or -not (Test-Path "$appDir/runtime/bin/server/jvm.dll") -or -not (Test-Path "$appDir/app/pocketgroove_audio.dll")) { throw 'The EXE, bundled runtime, or audio engine is missing.' }
Get-FileHash $zipPath -Algorithm SHA256 | Format-List
Write-Host "Ready: dist/$name/PocketGroove.exe (distribute the entire folder or ZIP)."
