[CmdletBinding()]
param(
    [string]$NrdSource,
    [string]$NrdBuild,
    [string]$ShimBuild,
    [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$config = @{}
Get-Content (Join-Path $PSScriptRoot 'nrd-version.env') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.+)$') { $config[$matches[1]] = $matches[2] }
}
if (-not $NrdSource) { $NrdSource = Join-Path $root 'build/vendor/NRD' }
if (-not $NrdBuild) { $NrdBuild = Join-Path $root 'build/vendor/NRD-build-windows' }
if (-not $ShimBuild) { $ShimBuild = Join-Path $root 'build/nrd-windows' }
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $root 'src/main/resources/caustica/natives/windows-x64'
}

if (-not (Test-Path (Join-Path $NrdSource '.git'))) {
    git clone --filter=blob:none --no-checkout https://github.com/NVIDIA-RTX/NRD.git $NrdSource
}
git -C $NrdSource fetch --depth 1 origin $config.NRD_COMMIT
git -C $NrdSource checkout --detach $config.NRD_COMMIT
if ((git -C $NrdSource rev-parse HEAD) -ne $config.NRD_COMMIT) {
    throw 'NRD checkout does not match the pinned commit'
}

cmake -S $NrdSource -B $NrdBuild -G 'Visual Studio 17 2022' -A x64 `
    -DNRD_STATIC_LIBRARY=ON -DNRD_NRI=OFF -DNRD_EMBEDS_SPIRV_SHADERS=ON `
    "-DNRD_NORMAL_ENCODING=$($config.NRD_NORMAL_ENCODING)" `
    "-DNRD_ROUGHNESS_ENCODING=$($config.NRD_ROUGHNESS_ENCODING)"
cmake --build $NrdBuild --config Release --parallel

$shaderMake = Get-ChildItem -Path $NrdBuild -Recurse -Filter ShaderMakeBlob.lib |
    Where-Object { $_.FullName -match '[\\/]Release[\\/]' } | Select-Object -First 1
if (-not $shaderMake) {
    $shaderMake = Get-ChildItem -Path $NrdBuild -Recurse -Filter ShaderMakeBlob.lib | Select-Object -First 1
}
$nrdLib = Get-ChildItem -Path (Join-Path $NrdSource '_Bin') -Recurse -Filter NRD.lib | Select-Object -First 1
if (-not $shaderMake -or -not $nrdLib) { throw 'Pinned NRD static libraries were not produced' }

cmake -S (Join-Path $root 'native/nrd') -B $ShimBuild -G 'Visual Studio 17 2022' -A x64 `
    "-DNRD_ROOT=$NrdSource" "-DSHADERMAKE_BLOB_LIB=$($shaderMake.FullName)" `
    "-DCAUSTICA_NRD_VERSION=$($config.NRD_VERSION)" `
    "-DCAUSTICA_NRD_COMMIT=$($config.NRD_COMMIT)" `
    "-DCAUSTICA_NRD_NORMAL_ENCODING=$($config.NRD_NORMAL_ENCODING)" `
    "-DCAUSTICA_NRD_ROUGHNESS_ENCODING=$($config.NRD_ROUGHNESS_ENCODING)"
cmake --build $ShimBuild --config Release --parallel

$shim = Get-ChildItem -Path $ShimBuild -Recurse -Filter nrd_caustica.dll | Select-Object -First 1
if (-not $shim) { throw 'nrd_caustica.dll was not produced' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
Copy-Item -Force $shim.FullName (Join-Path $OutputDirectory 'nrd_caustica.dll')
Write-Host "Built NRD $($config.NRD_VERSION) ($($config.NRD_COMMIT)): $OutputDirectory/nrd_caustica.dll"
