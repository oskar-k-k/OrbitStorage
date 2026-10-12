# Works with Windows PowerShell 5.1 and PowerShell 7.
$ErrorActionPreference = 'Stop'
if (Get-Command python -ErrorAction SilentlyContinue) {
    & python (Join-Path $PSScriptRoot 'setup.py') @args
} elseif (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3 (Join-Path $PSScriptRoot 'setup.py') @args
} else {
    throw 'Python 3.10+ is required. Install Python, then run this command again.'
}
exit $LASTEXITCODE
