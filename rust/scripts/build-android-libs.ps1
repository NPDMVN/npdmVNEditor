# Build Rust Core (libdmedit_core.so) + Rust Root Helper (dmedit-helper) for all 4 Android ABIs
# Uses Android NDK native clang.exe directly (avoids Windows .cmd quoting bugs, requires no cargo-ndk).

param(
    [string]$NdkDir = "",
    [string]$Toolchain = "stable-x86_64-pc-windows-gnu"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($NdkDir)) {
    $sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
    $ndkParent = Join-Path $sdkRoot "ndk"
    if (Test-Path $ndkParent) {
        $latestNdk = Get-ChildItem -Path $ndkParent -Directory | Sort-Object Name -Descending | Select-Object -First 1
        if ($latestNdk) { $NdkDir = $latestNdk.FullName }
    }
}
if ([string]::IsNullOrWhiteSpace($NdkDir)) { throw "Android NDK not found. Pass -NdkDir or set ANDROID_HOME." }

$RustDir = Split-Path -Parent $PSScriptRoot
$RepoRoot = Split-Path -Parent $RustDir
$JniLibsDir = Join-Path $RepoRoot "core\native-bridge\src\main\jniLibs"
$AssetsBinDir = Join-Path $RepoRoot "app\src\main\assets\bin"
$NdkBin = Join-Path $NdkDir "toolchains\llvm\prebuilt\windows-x86_64\bin"
$ClangExe = Join-Path $NdkBin "clang.exe"
$ArExe = Join-Path $NdkBin "llvm-ar.exe"

if (-not (Test-Path $ClangExe)) { throw "NDK clang.exe not found at $ClangExe" }

$rustupHome = if ($env:RUSTUP_HOME) { $env:RUSTUP_HOME } else { Join-Path $env:USERPROFILE ".rustup" }
$cargoBin = if ($env:CARGO_HOME) { Join-Path $env:CARGO_HOME "bin" } else { Join-Path $env:USERPROFILE ".cargo\bin" }
$selfContained = Join-Path $rustupHome "toolchains\$Toolchain\lib\rustlib\x86_64-pc-windows-gnu\bin\self-contained"
$env:PATH = "$selfContained;$cargoBin;" + $env:PATH

New-Item -ItemType Directory -Force -Path $JniLibsDir | Out-Null
New-Item -ItemType Directory -Force -Path $AssetsBinDir | Out-Null

$targets = @(
    @{ Abi = "x86_64";      Triple = "x86_64-linux-android";    ClangTarget = "x86_64-linux-android24";    EnvKey = "X86_64_LINUX_ANDROID";     HelperAsset = "dmedit-helper-x86_64" },
    @{ Abi = "arm64-v8a";   Triple = "aarch64-linux-android";   ClangTarget = "aarch64-linux-android24";   EnvKey = "AARCH64_LINUX_ANDROID";    HelperAsset = "dmedit-helper-aarch64" },
    @{ Abi = "x86";         Triple = "i686-linux-android";      ClangTarget = "i686-linux-android24";      EnvKey = "I686_LINUX_ANDROID";       HelperAsset = "dmedit-helper-x86" },
    @{ Abi = "armeabi-v7a"; Triple = "armv7-linux-androideabi"; ClangTarget = "armv7a-linux-androideabi24"; EnvKey = "ARMV7_LINUX_ANDROIDEABI"; HelperAsset = "dmedit-helper-armv7m" }
)

Push-Location $RustDir
try {
    foreach ($t in $targets) {
        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($t.EnvKey)_LINKER", $ClangExe, "Process")
        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($t.EnvKey)_AR", $ArExe, "Process")
        [Environment]::SetEnvironmentVariable("CARGO_TARGET_$($t.EnvKey)_RUSTFLAGS", "-C link-arg=--target=$($t.ClangTarget)", "Process")

        Write-Host "=== Building $($t.Abi) ($($t.Triple)) ===" -ForegroundColor Cyan
        cargo "+$Toolchain" build -p dmedit-jni -p dmedit-root-helper --release --target $t.Triple
        if ($LASTEXITCODE -ne 0) { throw "Cargo build failed for $($t.Triple)" }

        $abiOutDir = Join-Path $JniLibsDir $t.Abi
        New-Item -ItemType Directory -Force -Path $abiOutDir | Out-Null
        Copy-Item -Force (Join-Path $RustDir "target\$($t.Triple)\release\libdmedit_core.so") (Join-Path $abiOutDir "libdmedit_core.so")
        Copy-Item -Force (Join-Path $RustDir "target\$($t.Triple)\release\dmedit-helper") (Join-Path $AssetsBinDir $t.HelperAsset)
    }

    Write-Host "=== ALL 4 ABIs BUILT SUCCESSFULLY ===" -ForegroundColor Green
    Get-ChildItem $JniLibsDir -Recurse -Filter "*.so" | Select-Object FullName, Length | Format-Table -AutoSize
    Get-ChildItem $AssetsBinDir | Select-Object Name, Length | Format-Table -AutoSize
} finally {
    Pop-Location
}
