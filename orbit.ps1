$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'scripts/setup.ps1') @args
exit $LASTEXITCODE
