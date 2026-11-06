$ErrorActionPreference = "Stop"
# 解压 ComfyUI 便携版、放置模型、安装固定版本的自定义节点、导入 BFS 工作流（NAS 运维生成，2026-11-02）
$Root = "C:\llm\comfyui"; $Dl = "$Root\downloads"; $Log = "$Root\install.log"
$App = "$Root\ComfyUI_windows_portable"; $Py = "$App\python_embeded\python.exe"; $Proxy = "http://127.0.0.1:10808"
function Step($m) { Add-Content $Log ((Get-Date -Format s) + " " + $m) }
function Check($what) { if ($LASTEXITCODE -ne 0) { Step "FAIL $what ($LASTEXITCODE)"; throw "$what failed" } }
Step "START"
if (-not (Test-Path $App)) {
  & tar.exe -xf "$Dl\ComfyUI_windows_portable_nvidia.7z" -C $Root; Check "extract"
  Step "extracted"
}
# 模型：downloads\models\<类型> -> ComfyUI\models\<类型>
Get-ChildItem "$Dl\models" -Directory | ForEach-Object {
  $dst = "$App\ComfyUI\models\$($_.Name)"; New-Item -ItemType Directory -Force $dst | Out-Null
  Get-ChildItem $_.FullName -File | ForEach-Object { Move-Item -Force $_.FullName $dst }
}
Step "models moved"
# 自定义节点，固定提交
$Nodes = @(
  @("ComfyUI-KJNodes", "https://github.com/kijai/ComfyUI-KJNodes", "ab72eda7ffad6280422d71ab7d54d3fb87c0ba18"),
  @("rgthree-comfy", "https://github.com/rgthree/rgthree-comfy", "bc48c6aaeca87bedbc14245d89f9bc6be37a630e"),
  @("RES4LYF", "https://github.com/ClownsharkBatwing/RES4LYF", "057a913ed3c04cbfb756e1a3dbfc398a6bd4044f")
)
foreach ($n in $Nodes) {
  $dir = "$App\ComfyUI\custom_nodes\$($n[0])"
  if (-not (Test-Path $dir)) { & git -c http.proxy=$Proxy clone -q $n[1] $dir; Check "clone $($n[0])" }
  & git -C $dir -c http.proxy=$Proxy fetch -q origin $n[2]; Check "fetch $($n[0])"
  & git -C $dir checkout -q $n[2]; Check "checkout $($n[0])"
  if (Test-Path "$dir\requirements.txt") {
    & $Py -s -m pip install -q -i https://pypi.tuna.tsinghua.edu.cn/simple -r "$dir\requirements.txt"; Check "pip $($n[0])"
  }
  Step "node $($n[0]) @ $($n[2].Substring(0,12))"
}
# BFS 工作流：官方文件中的 Lightning LoRA 文件名改为 Comfy-Org 仓库中的同一 LoRA
$wfDir = "$App\ComfyUI\user\default\workflows"; New-Item -ItemType Directory -Force $wfDir | Out-Null
$wf = Get-Content -Raw -Encoding UTF8 "$Root\workflows\Head Swap V1 Qwen 2.1 Workflow.json"
$wf = $wf.Replace("p_qwen_image_2.1_8step_v0.1.safetensors", "p_q_image_2.1_8_steps_v0.1_comfyui_bf16.safetensors")
[IO.File]::WriteAllText("$wfDir\BFS Head v1.1 - Qwen Image 2.1.json", $wf, (New-Object Text.UTF8Encoding $false))
Step "workflow installed"
& $Py -s -m pip check | Out-File -Encoding utf8 "$Root\pip-check.txt"
Step "DONE"