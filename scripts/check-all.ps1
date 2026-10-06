# One-command check for what exists so far. Extended each phase.
$ErrorActionPreference = "Stop"
Push-Location "$PSScriptRoot\..\backend"; npm run typecheck; npm test; Pop-Location
