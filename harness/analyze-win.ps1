# PowerShell stand-in for harness/analyze.py on a machine without Python: route-window frame-time
# tail, chunk latency, per-thread CPU and sysmon utilization for one or more run directories.
#
#   harness/analyze-win.ps1 <run-dir> [<run-dir> ...]
param([Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)][string[]]$Runs)

function Pct([long[]]$sorted, [double]$p) {
  if ($sorted.Count -eq 0) { return 0 }
  $k = ($sorted.Count - 1) * $p / 100.0
  $lo = [int][math]::Floor($k); $hi = [math]::Min($lo + 1, $sorted.Count - 1)
  return $sorted[$lo] + ($sorted[$hi] - $sorted[$lo]) * ($k - $lo)
}
function Summarize([string]$run) {
  $r = [ordered]@{ run = (Split-Path -Leaf $run) }
  $benchFile = Join-Path $run "pzopt-bench.out"
  $bench = @{}
  if (Test-Path $benchFile) {
    foreach ($l in Get-Content $benchFile) { if ($l -match '^([^=]+)=(.*)$') { $bench[$matches[1]] = $matches[2] } }
    $r.route_status = $bench["route_status"]; $r.zoom = $bench["zoom"]; $r.resolution = $bench["resolution"]
    $r.route_seconds = $bench["route_seconds"]; $r.chunks_loaded = $bench["chunks_loaded"]; $r.settings = $bench["settings"]
  }
  # frames between the route markers (microseconds, one per line)
  $framesFile = Join-Path $run "pzopt-frames.out"
  if (Test-Path $framesFile) {
    $frames = New-Object System.Collections.Generic.List[long]
    $inRoute = $null
    foreach ($l in [System.IO.File]::ReadLines($framesFile)) {
      if ($l.StartsWith("#")) {
        $label = ($l -split '\s+')[1]
        if ($label -eq "route-start") { $frames.Clear(); $inRoute = $true }
        elseif ($label -eq "route-end") { $inRoute = $false }
        continue
      }
      if ($inRoute -eq $false) { continue }
      if ($l.Trim()) { $frames.Add([long]$l) }
    }
    if ($null -eq $inRoute) {
      # no markers: drop the first 20 s like analyze.py
      $acc = 0; $i = 0
      while ($i -lt $frames.Count -and $acc -lt 20e6) { $acc += $frames[$i]; $i++ }
      $frames = [System.Collections.Generic.List[long]]$frames.GetRange($i, $frames.Count - $i)
    }
    if ($frames.Count) {
      $sorted = [long[]]($frames | Sort-Object)
      $total = ($sorted | Measure-Object -Sum).Sum / 1e6
      $mean = ($sorted | Measure-Object -Average).Average
      $sq = 0.0; foreach ($f in $sorted) { $sq += ($f - $mean) * ($f - $mean) }
      $r.frames = [ordered]@{
        count = $sorted.Count; seconds = [math]::Round($total, 1); fps_mean = [math]::Round($sorted.Count / $total, 1)
        mean_ms = [math]::Round($mean / 1000, 2); p50_ms = [math]::Round((Pct $sorted 50) / 1000, 2); p90_ms = [math]::Round((Pct $sorted 90) / 1000, 2)
        p99_ms = [math]::Round((Pct $sorted 99) / 1000, 2); p99_9_ms = [math]::Round((Pct $sorted 99.9) / 1000, 2); max_ms = [math]::Round($sorted[-1] / 1000, 2)
        jitter_sd_ms = [math]::Round([math]::Sqrt($sq / $sorted.Count) / 1000, 2)
        over_16_7ms = ($sorted | Where-Object { $_ -gt 16667 }).Count; over_33ms = ($sorted | Where-Object { $_ -gt 33333 }).Count
        over_50ms = ($sorted | Where-Object { $_ -gt 50000 }).Count; over_100ms = ($sorted | Where-Object { $_ -gt 100000 }).Count
      }
    }
  }
  # chunk latency inside the route window
  $chunksFile = Join-Path $run "pzopt-chunks.out"
  if (Test-Path $chunksFile) {
    $lines = [System.IO.File]::ReadAllLines($chunksFile)
    $header = $lines[0] -split "`t"
    $marks = @{}; $rows = @()
    foreach ($l in $lines[1..($lines.Count - 1)]) {
      if ($l.StartsWith("#")) { $p = $l -split '\s+'; $marks[$p[1]] = [long]$p[2]; continue }
      $parts = $l -split "`t"; if ($parts.Count -ne $header.Count) { continue }
      $o = @{}; for ($i = 0; $i -lt $header.Count; $i++) { $o[$header[$i]] = $parts[$i] }; $rows += , $o
    }
    if ($marks.ContainsKey("route-start") -and $marks.ContainsKey("route-end") -and $rows.Count -and $rows[0].ContainsKey("tEnqueueUs")) {
      $rows = $rows | Where-Object { [long]$_["tEnqueueUs"] -ge $marks["route-start"] -and [long]$_["tEnqueueUs"] -le $marks["route-end"] }
    }
    if ($rows.Count) {
      $load = [long[]]($rows | ForEach-Object { [long]$_["loadUs"] } | Sort-Object)
      $wait = [long[]]($rows | ForEach-Object { [long]$_["queueWaitUs"] } | Sort-Object)
      $recalc = [long[]]($rows | Where-Object { $_["recalcUs"] -ne "0" -or $_["loadUs"] -ne "0" } | ForEach-Object { [long]$_["recalcUs"] } | Sort-Object)
      $r.chunks = [ordered]@{
        count = $rows.Count
        load_ms = [ordered]@{ mean = [math]::Round(($load | Measure-Object -Average).Average / 1000, 2); p50 = [math]::Round((Pct $load 50) / 1000, 2); p99 = [math]::Round((Pct $load 99) / 1000, 2); max = [math]::Round($load[-1] / 1000, 2) }
        recalc_ms = [ordered]@{ mean = [math]::Round(($recalc | Measure-Object -Average).Average / 1000, 2); p99 = [math]::Round((Pct $recalc 99) / 1000, 2); max = [math]::Round($recalc[-1] / 1000, 2) }
        queue_wait_ms = [ordered]@{ mean = [math]::Round(($wait | Measure-Object -Average).Average / 1000, 2); p99 = [math]::Round((Pct $wait 99) / 1000, 2); max = [math]::Round($wait[-1] / 1000, 2) }
      }
    }
  }
  # per-thread CPU over the route
  $threadsFile = Join-Path $run "pzopt-threads.out"
  if (Test-Path $threadsFile) {
    $tl = Get-Content $threadsFile
    $r.threads = [ordered]@{ header = ($tl | Where-Object { $_.StartsWith("#") }) -join " | " }
    $r.threads.top = ($tl | Where-Object { -not $_.StartsWith("#") -and -not $_.StartsWith("thread") } | Select-Object -First 8 | ForEach-Object { $p = $_ -split "`t"; "$($p[0])=$($p[1])ms($($p[2]))" }) -join ", "
  }
  # sysmon rows inside the route window
  $sysFile = Join-Path $run "sysmon.csv"
  if ((Test-Path $sysFile) -and $bench["route_start_epoch_ms"]) {
    $t0 = [long]$bench["route_start_epoch_ms"]; $t1 = [long]$bench["route_end_epoch_ms"]
    $rows = Import-Csv $sysFile | Where-Object { [long]$_.epoch_ms -ge $t0 -and [long]$_.epoch_ms -le $t1 }
    if ($rows.Count) {
      $avg = { param($n) $v = $rows | Where-Object { $_.$n -ne "" } | ForEach-Object { [double]$_.$n }; if ($v) { [math]::Round(($v | Measure-Object -Average).Average, 1) } else { $null } }
      $r.sysmon = [ordered]@{
        samples = $rows.Count; cpu_pct = (& $avg "cpu_pct"); cpu_busiest_core_pct = (& $avg "cpu_busiest_core_pct"); game_cpu_pct = (& $avg "game_cpu_pct")
        gpu_pct = (& $avg "gpu_pct"); gpu_sm_mhz = (& $avg "gpu_sm_mhz"); gpu_w = (& $avg "gpu_w"); vram_mib = (& $avg "vram_mib")
      }
    }
  }
  $console = Join-Path $run "console.txt"
  if (Test-Path $console) {
    $r.environment = [ordered]@{}
    foreach ($pat in "OpenGL version", "Desktop resolution", "Screen resolution", "GPU: NVIDIA", "\[pzopt\] frame cap") {
      $m = Select-String -Path $console -Pattern $pat | Select-Object -First 1
      if ($m) { $r.environment[$pat] = $m.Line -replace '^LOG  : General      f:0> ', '' }
    }
  }
  return $r
}
foreach ($run in $Runs) {
  $s = Summarize (Resolve-Path $run).Path
  $s | ConvertTo-Json -Depth 5
  $s | ConvertTo-Json -Depth 5 | Out-File (Join-Path $run "analysis.json") -Encoding utf8
}
