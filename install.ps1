#Requires -Version 5.1
<#
.SYNOPSIS
    Installs midra on Windows.
.DESCRIPTION
    Downloads the latest midra release from GitHub and installs it
    under $env:LOCALAPPDATA\Programs\midiraja\{version}\.
    Adds the versioned bin\ directory to the user PATH and sets
    the MIDRA_DATA environment variable for the GUS patch set.
.PARAMETER Prefix
    Installation prefix. Defaults to $env:LOCALAPPDATA\Programs.
.PARAMETER Local
    Path to a local midra-windows-amd64.zip or midra-windows-arm64.zip to install from.
.EXAMPLE
    # Install latest release:
    irm https://raw.githubusercontent.com/fupfin/midiraja/main/install.ps1 | iex
.EXAMPLE
    # Install from a locally built zip:
    .\install.ps1 -Local .\midra-windows-amd64.zip
.EXAMPLE
    # Install to a custom prefix:
    .\install.ps1 -Prefix "C:\Program Files\midiraja"
#>

param(
    [string]$Prefix = "$env:LOCALAPPDATA\Programs",
    [string]$Local  = ""
)

$ErrorActionPreference = "Stop"

$Repo      = "fupfin/midiraja"
$AssetName = if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "midra-windows-arm64.zip" } else { "midra-windows-amd64.zip" }

$TmpDir = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $TmpDir | Out-Null

try {
    # --- Download or copy ---
    if ($Local -ne "") {
        if (-not (Test-Path $Local)) {
            Write-Error "File not found: $Local"
            exit 1
        }
        Write-Host "Installing from local file: $Local"
        Copy-Item $Local "$TmpDir\midra.zip"
        $LatestTag = "(local)"
    } else {
        Write-Host "Fetching latest release..."
        $release   = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest"
        $LatestTag = $release.tag_name
        Write-Host "Latest release: $LatestTag"

        $asset = $release.assets | Where-Object { $_.name -eq $AssetName }
        if (-not $asset) {
            Write-Error ("Release $LatestTag does not contain $AssetName.`n" +
                         "Windows builds may not be available for this version yet.`n" +
                         "Download manually from: https://github.com/$Repo/releases")
            exit 1
        }
        $DownloadUrl = $asset.browser_download_url
        Write-Host "Downloading $AssetName..."
        Invoke-WebRequest -Uri $DownloadUrl -OutFile "$TmpDir\midra.zip" -UseBasicParsing
    }

    # --- Extract ---
    Expand-Archive -Path "$TmpDir\midra.zip" -DestinationPath "$TmpDir\extracted" -Force

    # GitHub Actions wraps artifacts in an extra zip — unwrap if needed
    $innerZip = Get-ChildItem "$TmpDir\extracted" -Filter "*.zip" | Select-Object -First 1
    if ($innerZip -and -not (Test-Path "$TmpDir\extracted\bin")) {
        Write-Host "Unwrapping CI artifact..."
        Expand-Archive -Path $innerZip.FullName -DestinationPath "$TmpDir\extracted2" -Force
        Remove-Item -Recurse -Force "$TmpDir\extracted"
        Rename-Item "$TmpDir\extracted2" "$TmpDir\extracted"
    }

    # --- Read version ---
    $VersionFile = "$TmpDir\extracted\VERSION"
    if (Test-Path $VersionFile) {
        $Version = (Get-Content $VersionFile -Raw).Trim()
    } else {
        $Version = $LatestTag -replace '^v', ''
    }

    # --- Install paths ---
    $InstallBase = "$Prefix\midiraja\$Version"
    $ShareDir    = "$Prefix\midiraja\share\midra"

    New-Item -ItemType Directory -Force -Path "$InstallBase\bin" | Out-Null
    New-Item -ItemType Directory -Force -Path $ShareDir          | Out-Null

    # --- Locate root of extracted archive (handle optional top-level subdirectory) ---
    $ExtractedRoot = "$TmpDir\extracted"
    if (-not (Test-Path "$ExtractedRoot\bin")) {
        $sub = Get-ChildItem $ExtractedRoot -Directory | Select-Object -First 1
        if ($sub -and (Test-Path "$($sub.FullName)\bin")) {
            $ExtractedRoot = $sub.FullName
        } else {
            Write-Host "Extracted contents:"
            Get-ChildItem $ExtractedRoot -Recurse | Select-Object -First 30 |
                ForEach-Object { Write-Host "  $($_.FullName)" }
            Write-Error "Expected 'bin\' directory not found in zip."
            exit 1
        }
    }

    Write-Host "Installing midra $Version to $InstallBase..."

    # --- Copy bin/ (exe + DLLs) ---
    Copy-Item "$ExtractedRoot\bin\*" "$InstallBase\bin\" -Force

    # --- Copy freepats (shared across versions, ~27 MB) ---
    if (Test-Path "$ExtractedRoot\share\midra") {
        Copy-Item -Recurse "$ExtractedRoot\share\midra\*" "$ShareDir\" -Force
    }

    # --- Update user PATH (remove old midiraja entries, prepend new one) ---
    $BinDir  = "$InstallBase\bin"
    $OldPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    $NewPath = ($OldPath -split ';' |
                Where-Object { $_ -ne '' -and $_ -notmatch [regex]::Escape('\midiraja\') + '[^\\]+\\bin' }) -join ';'
    [Environment]::SetEnvironmentVariable("PATH", "$BinDir;$NewPath", "User")

    # --- Set MIDRA_DATA so the GUS engine can find FreePats ---
    [Environment]::SetEnvironmentVariable("MIDRA_DATA", $ShareDir, "User")

    Write-Host ""
    Write-Host "midra $Version installed successfully."
    Write-Host "  binary   : $InstallBase\bin\midra.exe"
    Write-Host "  freepats : $ShareDir"
    Write-Host ""
    Write-Host "Restart your terminal to apply PATH changes, then run: midra --help"
} finally {
    Remove-Item -Recurse -Force $TmpDir -ErrorAction SilentlyContinue
}
