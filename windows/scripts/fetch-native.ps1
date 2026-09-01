# Fetch native OpenConnect binaries for Windows
# Prefer an existing OpenConnect-GUI install; otherwise instruct the user.

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Dst = Join-Path $Root "vendor\openconnect\bin"
New-Item -ItemType Directory -Force -Path $Dst | Out-Null

$Candidates = @(
  "${env:ProgramFiles}\OpenConnect-GUI",
  "${env:ProgramFiles(x86)}\OpenConnect-GUI"
)

$Src = $Candidates | Where-Object { Test-Path (Join-Path $_ "libopenconnect-5.dll") } | Select-Object -First 1
if (-not $Src) {
  Write-Host "OpenConnect-GUI not found."
  Write-Host "Install from https://github.com/openconnect/openconnect-gui/releases"
  Write-Host "or: choco install openconnect-gui"
  exit 1
}

$Files = @(
  "libopenconnect-5.dll","libffi-8.dll","libgcc_s_seh-1.dll","libgmp-10.dll","libgnutls-30.dll",
  "libhogweed-6.dll","libiconv-2.dll","libidn2-0.dll","libintl-8.dll","liblz4.dll","liblzma-5.dll",
  "libnettle-8.dll","libp11-kit-0.dll","libstdc++-6.dll","libstoken-1.dll","libtasn1-6.dll",
  "libunistring-5.dll","libwinpthread-1.dll","libxml2-2.dll","zlib1.dll","wintun.dll","vpnc-script.js",
  "libffi-6.dll","libhogweed-4.dll","libnettle-6.dll","iconv.dll"
)

Write-Host "Copying from $Src"
foreach ($f in $Files) {
  $p = Join-Path $Src $f
  if (Test-Path $p) {
    Copy-Item $p $Dst -Force
    Write-Host "  OK $f"
  }
}

$WintunDst = Join-Path $Root "vendor\wintun"
New-Item -ItemType Directory -Force -Path $WintunDst | Out-Null
if (Test-Path (Join-Path $Dst "wintun.dll")) {
  Copy-Item (Join-Path $Dst "wintun.dll") (Join-Path $WintunDst "wintun.dll") -Force
}

$Scripts = Join-Path $Root "vendor\scripts"
New-Item -ItemType Directory -Force -Path $Scripts | Out-Null
if (Test-Path (Join-Path $Dst "vpnc-script.js")) {
  Copy-Item (Join-Path $Dst "vpnc-script.js") (Join-Path $Scripts "vpnc-script.js") -Force
}

Write-Host "Done → $Dst"
