param([switch]$Package, [switch]$UiTest)
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
$arguments = @('clean', 'verify')
if ($UiTest) { $arguments += '-Dgroove.uiTest=true' }
& $mvn @arguments
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed.' }
if (-not $Package) { exit 0 }
New-Item -ItemType Directory -Force target/package-input, dist | Out-Null
Copy-Item target/pocket-groove-0.1.0.jar target/package-input/
Copy-Item target/lib target/package-input/lib -Recurse
Copy-Item LICENSE,THIRD_PARTY_NOTICES.md,README.md target/package-input/
Copy-Item src/main/resources/fonts/*OFL.txt target/package-input/
Copy-Item licenses target/package-input/licenses -Recurse
if (Test-Path dist/PocketGroove) { Remove-Item dist/PocketGroove -Recurse -Force }
& "$env:JAVA_HOME/bin/jpackage.exe" --type app-image --name PocketGroove --app-version 0.1.0 --vendor 'Pocket Groove contributors' --input target/package-input --main-jar pocket-groove-0.1.0.jar --main-class io.pocketgroove.Launcher --dest dist --add-modules java.desktop,java.logging,java.xml,jdk.unsupported,jdk.charsets --java-options '--module-path=$APPDIR/lib' --java-options '--add-modules=javafx.controls,javafx.media'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
Copy-Item LICENSE,THIRD_PARTY_NOTICES.md,README.md dist/PocketGroove/
Compress-Archive -Path dist/PocketGroove -DestinationPath dist/PocketGroove-windows-x64.zip -Force
if (-not (Test-Path dist/PocketGroove/PocketGroove.exe) -or -not (Test-Path dist/PocketGroove/runtime/bin/server/jvm.dll)) { throw 'Missing executable or bundled runtime.' }
Get-FileHash dist/PocketGroove-windows-x64.zip -Algorithm SHA256 | Format-List
Write-Host 'Ready: dist/PocketGroove/PocketGroove.exe (distribute the entire folder or ZIP).'
