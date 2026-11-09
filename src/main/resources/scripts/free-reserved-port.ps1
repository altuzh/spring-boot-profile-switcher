param(
    [Parameter(Mandatory = $true)][ValidateRange(1, 65535)][int]$Port,
    [Parameter(Mandatory = $true)][string]$ResultFile,
    [switch]$PlanOnly
)

$ErrorActionPreference = 'Stop'

function Get-ExcludedRanges([string]$Family, [string]$Store) {
    $output = & netsh interface $Family show excludedportrange protocol=tcp "store=$Store" 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Could not read $Family $Store exclusions: $output" }
    foreach ($line in $output) {
        if ($line -match '^\s*(\d+)\s+(\d+)\s*(\*)?\s*$') {
            [pscustomobject]@{ Start = [int]$Matches[1]; End = [int]$Matches[2]; Administered = $Matches[3] -eq '*' }
        }
    }
}

function Invoke-RangeChange([string]$Family, [string]$Verb, [int]$Start, [int]$Count) {
    if ($Count -le 0) { return }
    $output = & netsh interface $Family $Verb excludedportrange protocol=tcp "startport=$Start" "numberofports=$Count" store=active 2>&1
    if ($LASTEXITCODE -ne 0) { throw "netsh $Family $Verb $Start-$($Start + $Count - 1) failed: $output" }
}

$changes = @()
$restartNat = $false
try {
    $plans = @()
    foreach ($family in @('ipv4', 'ipv6')) {
        $persistent = @(Get-ExcludedRanges $family 'persistent' | Where-Object { $_.Start -le $Port -and $Port -le $_.End })
        if ($persistent.Count -gt 0) { throw "Port $Port is persistently reserved in $family; refusing to alter it." }
        $active = @(Get-ExcludedRanges $family 'active' | Where-Object { $_.Start -le $Port -and $Port -le $_.End })
        if ($active.Count -gt 1) { throw "Overlapping $family exclusions contain port $Port; refusing to alter them." }
        if ($active.Count -eq 1) {
            if ($active[0].Administered) { throw "Port $Port is administratively reserved in $family; refusing to alter it." }
            $plans += [pscustomobject]@{ Family = $family; Start = $active[0].Start; End = $active[0].End; Added = @() }
        }
    }
    if ($plans.Count -eq 0) { throw "Port $Port is not in an active Windows TCP exclusion range." }
    if ($PlanOnly) {
        $plans | ForEach-Object { "PLAN $($_.Family) $($_.Start)-$($_.End): release $Port, preserve other ports" }
        exit 0
    }

    foreach ($plan in $plans) {
        try {
            Invoke-RangeChange $plan.Family 'delete' $plan.Start ($plan.End - $plan.Start + 1)
        } catch {
            # WinNAT owns dynamic exclusions and can refuse deletion while running.
            if (-not $restartNat -and (Get-Service WinNAT).Status -eq 'Running') {
                Stop-Service WinNAT -ErrorAction Stop
                $restartNat = $true
            }
            $remaining = @(Get-ExcludedRanges $plan.Family 'active' | Where-Object { $_.Start -eq $plan.Start -and $_.End -eq $plan.End })
            if ($remaining.Count -gt 0) { Invoke-RangeChange $plan.Family 'delete' $plan.Start ($plan.End - $plan.Start + 1) }
        }
        $changes += $plan
        if ($Port -gt $plan.Start) {
            Invoke-RangeChange $plan.Family 'add' $plan.Start ($Port - $plan.Start)
            $plan.Added += [pscustomobject]@{ Start = $plan.Start; Count = $Port - $plan.Start }
        }
        if ($Port -lt $plan.End) {
            Invoke-RangeChange $plan.Family 'add' ($Port + 1) ($plan.End - $Port)
            $plan.Added += [pscustomobject]@{ Start = $Port + 1; Count = $plan.End - $Port }
        }
    }

    if ($restartNat) { Start-Service WinNAT -ErrorAction Stop; $restartNat = $false }

    foreach ($family in @('ipv4', 'ipv6')) {
        if (@(Get-ExcludedRanges $family 'active' | Where-Object { $_.Start -le $Port -and $Port -le $_.End }).Count -gt 0) {
            throw "Port $Port is still reserved in $family."
        }
    }
    foreach ($address in @([Net.IPAddress]::Any, [Net.IPAddress]::IPv6Any)) {
        $listener = [Net.Sockets.TcpListener]::new($address, $Port)
        try { $listener.Start() } finally { $listener.Stop() }
    }
    Set-Content -LiteralPath $ResultFile -Value "Port $Port is free. Other ports in the original exclusions remain reserved. This active-only change may be reset by Windows networking or a reboot." -Encoding UTF8
    exit 0
} catch {
    $failure = $_.Exception.Message
    for ($i = $changes.Count - 1; $i -ge 0; $i--) {
        $plan = $changes[$i]
        foreach ($part in $plan.Added) {
            try { Invoke-RangeChange $plan.Family 'delete' $part.Start $part.Count } catch { $failure += " Rollback: $($_.Exception.Message)" }
        }
        try { Invoke-RangeChange $plan.Family 'add' $plan.Start ($plan.End - $plan.Start + 1) } catch { $failure += " Rollback: $($_.Exception.Message)" }
    }
    Set-Content -LiteralPath $ResultFile -Value "Failed to free port $Port. $failure" -Encoding UTF8
    exit 1
} finally {
    if ($restartNat) { Start-Service WinNAT -ErrorAction Continue }
}
