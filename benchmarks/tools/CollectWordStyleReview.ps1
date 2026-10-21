<#
.SYNOPSIS
Collects Microsoft Word observations for the owned review packet without modifying source files.
.DESCRIPTION
Requires Windows PowerShell and installed Microsoft Word. Records observed outline/list properties,
source hashes and application version; collection does not constitute human approval or parity.
#>
param(
    [Parameter(Mandatory=$true)][string]$CaseDirectory,
    [Parameter(Mandatory=$true)][string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path $CaseDirectory).Path
$manifestPath = Join-Path $root 'manifest.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$results = @()
$word = $null
$originalSettings = $null
try {
    $word = New-Object -ComObject Word.Application
    $originalSettings = @{
        DisplayAlerts = $word.DisplayAlerts
        AutomationSecurity = $word.AutomationSecurity
        UpdateLinksAtOpen = $word.Options.UpdateLinksAtOpen
    }
    $word.Visible = $false
    $word.DisplayAlerts = 0
    $word.AutomationSecurity = 3
    $word.Options.UpdateLinksAtOpen = $false
    $version = [string]$word.Version
    $build = [string]$word.Build
    foreach ($case in $manifest.cases) {
        if ([IO.Path]::GetFileName($case.file) -ne $case.file -or -not $case.file.EndsWith('.docx')) {
            throw 'Invalid review case filename'
        }
        $path = Join-Path $root $case.file
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne $case.sha256) { throw "Review case hash mismatch: $($case.file)" }
        $document = $null
        try {
            $document = $word.Documents.Open($path, $false, $true, $false)
            $table = $document.Tables.Item(1)
            $cells = @()
            for ($row = 1; $row -le $case.rows; $row++) {
                for ($column = 1; $column -le $case.columns; $column++) {
                    $paragraph = $table.Cell($row, $column).Range.Paragraphs.Item(1)
                    $text = $paragraph.Range.Text.TrimEnd([char[]]@([char]13, [char]7))
                    $cells += [ordered]@{
                        row = $row - 1; column = $column - 1; text = $text
                        outline_level = ([int]$paragraph.OutlineLevel) - 1
                        list_string = [string]$paragraph.Range.ListFormat.ListString
                    }
                }
            }
            $results += [ordered]@{ file = $case.file; sha256 = $hash; cells = $cells }
        } finally {
            if ($null -ne $document) {
                # Word declares SaveChanges as a by-reference COM argument.
                $discardChanges = 0
                $document.Close([ref]$discardChanges)
            }
        }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) {
            throw "Source changed during collection: $($case.file)"
        }
    }
    $report = [ordered]@{
        status = 'observations_collected_human_review_pending'
        manifest_sha256 = (Get-FileHash -LiteralPath $manifestPath -Algorithm SHA256).Hash.ToLowerInvariant()
        collected_at_utc = [DateTime]::UtcNow.ToString('o')
        word_version = $version; word_build = $build
        platform = [Environment]::OSVersion.VersionString
        cases = $results
    }
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
} finally {
    if ($null -ne $word) {
        try {
            if ($null -ne $originalSettings) {
                $word.DisplayAlerts = $originalSettings.DisplayAlerts
                $word.AutomationSecurity = $originalSettings.AutomationSecurity
                $word.Options.UpdateLinksAtOpen = $originalSettings.UpdateLinksAtOpen
            }
        } finally {
            $discardChanges = 0
            $word.Quit([ref]$discardChanges)
        }
    }
}
