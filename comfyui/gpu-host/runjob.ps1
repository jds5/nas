# 提交一个 ComfyUI API 任务并等待完成，输出耗时、显存峰值与结果文件
$f = "C:\llm\comfyui\$($args[0]).json"
$sw = [Diagnostics.Stopwatch]::StartNew()
$r = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8188/prompt -ContentType "application/json" -Body ([IO.File]::ReadAllText($f))
$id = $r.prompt_id; $peak = 0
while ($true) {
  $mem = [int](& nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits); if ($mem -gt $peak) { $peak = $mem }
  $h = Invoke-RestMethod -Uri "http://127.0.0.1:8188/history/$id"
  if ($h.$id) { break }
  if ($sw.Elapsed.TotalSeconds -gt 900) { "TIMEOUT"; break }
  Start-Sleep -Milliseconds 1000
}
$st = $h.$id.status
"{0} status={1} elapsed={2:N1}s peakVRAM={3}MiB" -f $args[0], $st.status_str, $sw.Elapsed.TotalSeconds, $peak
$h.$id.outputs.save.images | ForEach-Object { "output: " + $_.filename }
if ($st.status_str -ne "success") { $st.messages | ConvertTo-Json -Depth 6 }
