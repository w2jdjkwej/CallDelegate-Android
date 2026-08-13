[CmdletBinding()]
param(
    [string]$Destination = ""
)

$ErrorActionPreference = "Stop"

$downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar"
$expectedSha256 = "aa5505c0ec4f8bdaee5f214a64ba3012be64f2aecc022e82a64f33392b8dd245"
if ([string]::IsNullOrWhiteSpace($Destination)) {
    $Destination = Join-Path $PSScriptRoot "..\core\ai\libs\sherpa-onnx-1.13.2.aar"
}
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$destinationDirectory = Split-Path -Parent $destinationPath
$temporaryPath = "$destinationPath.download"

New-Item -ItemType Directory -Force -Path $destinationDirectory | Out-Null

if (Test-Path -LiteralPath $destinationPath -PathType Leaf) {
    $existingHash = (Get-FileHash -LiteralPath $destinationPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($existingHash -eq $expectedSha256) {
        Write-Host "sherpa-onnx AAR already exists and passed SHA-256 verification."
        exit 0
    }
    throw "Existing AAR has an unexpected SHA-256: $existingHash"
}

try {
    Invoke-WebRequest -Uri $downloadUrl -OutFile $temporaryPath -UseBasicParsing
    $actualHash = (Get-FileHash -LiteralPath $temporaryPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedSha256) {
        throw "Downloaded AAR failed SHA-256 verification: $actualHash"
    }

    Move-Item -LiteralPath $temporaryPath -Destination $destinationPath
    Write-Host "Installed verified AAR at $destinationPath"
} finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force
    }
}
