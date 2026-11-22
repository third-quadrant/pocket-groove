$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

if (-not $env:JAVA_HOME) {
    $jdk = Get-ChildItem "$env:LOCALAPPDATA\Programs\Eclipse Adoptium\jdk-*" -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
}
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\include\jni.h")) {
    throw 'Set JAVA_HOME to JDK 17 or later so the audio bridge can be built.'
}

$cmakeCommand = Get-Command cmake.exe -ErrorAction SilentlyContinue
if ($cmakeCommand) { $cmake = $cmakeCommand.Source }
else {
    $vswhere = "${env:ProgramFiles(x86)}\Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) { throw 'CMake and Visual Studio Build Tools were not found.' }
    $vs = & $vswhere -latest -products '*' -property installationPath
    $cmake = Join-Path $vs 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
    if (-not (Test-Path $cmake)) { throw 'Install the CMake component for Visual Studio Build Tools.' }
}

$portAudio = Join-Path $PWD '.tools/portaudio-19.7.0'
if (-not (Test-Path (Join-Path $portAudio 'CMakeLists.txt'))) {
    New-Item -ItemType Directory -Force .tools | Out-Null
    $zip = Join-Path $PWD '.tools/portaudio-v19.7.0.zip'
    $url = 'https://codeload.github.com/PortAudio/portaudio/zip/refs/tags/v19.7.0'
    if (-not (Test-Path $zip) -or (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'ce1e7b27ad362cb9745de7da3d266a996b085ca75a12ac62c881f88ab6894acf') {
        Invoke-WebRequest $url -OutFile $zip
    }
    if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'ce1e7b27ad362cb9745de7da3d266a996b085ca75a12ac62c881f88ab6894acf') {
        throw 'PortAudio 19.7.0 checksum mismatch.'
    }
    $unpack = Join-Path $PWD '.tools/portaudio-unpack'
    if (Test-Path $unpack) { Remove-Item $unpack -Recurse -Force }
    Expand-Archive $zip $unpack
    Move-Item (Join-Path $unpack 'portaudio-19.7.0') $portAudio
    Remove-Item $unpack -Recurse -Force
}

$build = Join-Path $PWD 'target/native-build'
& $cmake -S native -B $build -A x64 -DGROOVE_CORE_ONLY=OFF "-DFETCHCONTENT_SOURCE_DIR_PORTAUDIO=$portAudio"
if ($LASTEXITCODE -ne 0) { throw 'Native audio configuration failed.' }
& $cmake --build $build --config Release
if ($LASTEXITCODE -ne 0) { throw 'Native audio build failed.' }
$ctest = Join-Path (Split-Path $cmake) 'ctest.exe'
& $ctest --test-dir $build -C Release --output-on-failure
if ($LASTEXITCODE -ne 0) { throw 'Native audio tests failed.' }
New-Item -ItemType Directory -Force target/native | Out-Null
Copy-Item (Join-Path $build 'bin/Release/pocketgroove_audio.dll') target/native/pocketgroove_audio.dll -Force
Write-Host 'Native audio mixer built and tested.'
