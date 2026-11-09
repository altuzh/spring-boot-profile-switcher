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

function Open-PortListeners {
    $opened = @()
    try {
        foreach ($address in @([Net.IPAddress]::Any, [Net.IPAddress]::IPv6Any)) {
            $listener = [Net.Sockets.TcpListener]::new($address, $Port)
            $listener.Server.ExclusiveAddressUse = $true
            if ($address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetworkV6) {
                $listener.Server.DualMode = $false
            }
            $opened += $listener
            $listener.Start()
        }
        return $opened
    } catch {
        foreach ($listener in $opened) { $listener.Stop() }
        throw
    }
}

$restartNat = $false
$held = @()
try {
    if ($PlanOnly) {
        foreach ($family in @('ipv4', 'ipv6')) {
            Get-ExcludedRanges $family 'active' | Where-Object { $_.Start -le $Port -and $Port -le $_.End } |
                ForEach-Object { "PLAN $family $($_.Start)-$($_.End): check port $Port; restart WinNAT only if blocked" }
        }
        exit 0
    }

    # An administered exclusion can still be bindable. Socket binding is the success criterion.
    try {
        $held = @(Open-PortListeners)
    } catch {
        foreach ($family in @('ipv4', 'ipv6')) {
            $persistent = @(Get-ExcludedRanges $family 'persistent' | Where-Object { $_.Start -le $Port -and $Port -le $_.End })
            if ($persistent.Count -gt 0) { throw "Port $Port is persistently reserved in $family." }
            $active = @(Get-ExcludedRanges $family 'active' | Where-Object { $_.Start -le $Port -and $Port -le $_.End })
            if (@($active | Where-Object { $_.Administered }).Count -gt 0) {
                throw "Port $Port has an administered exclusion and cannot bind in $family."
            }
        }
        if ((Get-Service WinNAT).Status -ne 'Running') { throw "Port $Port cannot bind and WinNAT is not running." }
        $restartNat = $true
        Stop-Service WinNAT -ErrorAction Stop
        # Hold the requested socket while WinNAT recreates its dynamic allocations.
        $held = @(Open-PortListeners)
        Start-Service WinNAT -ErrorAction Stop
        $restartNat = $false
        foreach ($listener in $held) { $listener.Stop() }
        $held = @(Open-PortListeners)
    }
    foreach ($listener in $held) { $listener.Stop() }
    $held = @()
    Set-Content -LiteralPath $ResultFile -Value "Port $Port is available on IPv4 and IPv6. You can start the application." -Encoding UTF8
    exit 0
} catch {
    Set-Content -LiteralPath $ResultFile -Value "Failed to free port $Port. $($_.Exception.Message)" -Encoding UTF8
    exit 1
} finally {
    foreach ($listener in $held) { $listener.Stop() }
    if ($restartNat) {
        try { Start-Service WinNAT -ErrorAction Stop } catch {
            Add-Content -LiteralPath $ResultFile -Value "WinNAT could not be restarted: $($_.Exception.Message)" -Encoding UTF8
        }
    }
}
