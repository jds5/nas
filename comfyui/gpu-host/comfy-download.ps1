$ErrorActionPreference = "Stop"
# ComfyUI + Qwen-Image-2.1 + BFS Head v1.1 下载与校验（NAS 运维生成，2026-11-02）
# 用法：comfy-download.ps1 portable（经本机系统代理 127.0.0.1:10808 下载 GitHub 发布包）| models（直连 Hugging Face）
$Mode = $args[0]
$Root = "C:\llm\comfyui"; $Dl = "$Root\downloads"; $Log = "$Root\download-$Mode.log"
$ExtraArgs = @(); if ($Mode -eq "portable") { $ExtraArgs = @("-x", "http://127.0.0.1:10808") }
New-Item -ItemType Directory -Force $Dl, "$Root\workflows" | Out-Null
function Get-Verified($Url, $Out, $Sha) {
  if ((Test-Path $Out) -and $Sha -and ((Get-FileHash -Algorithm SHA256 $Out).Hash.ToLower() -eq $Sha)) { Add-Content $Log "SKIP ok $Out"; return }
  & curl.exe @ExtraArgs -L --fail --retry 20 --retry-delay 10 --retry-all-errors -C - -s -S -o $Out $Url
  if ($LASTEXITCODE -ne 0) { Add-Content $Log "FAIL curl $LASTEXITCODE $Out"; throw "download failed $Out" }
  if ($Sha) { $h = (Get-FileHash -Algorithm SHA256 $Out).Hash.ToLower(); if ($h -ne $Sha) { Add-Content $Log "FAIL sha $Out $h"; throw "sha mismatch $Out" } }
  Add-Content $Log ("OK " + (Get-Date -Format s) + " $Out")
}
Add-Content $Log ("START " + (Get-Date -Format s))
if ($Mode -eq "portable") {
  Get-Verified "https://github.com/Comfy-Org/ComfyUI/releases/download/v0.43.0/ComfyUI_windows_portable_nvidia.7z" "$Dl\ComfyUI_windows_portable_nvidia.7z" "4928f88e6a8407c4574d4f7d92cfeba020d782edba4a076b7d107ecc98955e50"
} else {
  New-Item -ItemType Directory -Force "$Dl\models\diffusion_models" | Out-Null
  Get-Verified "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/aacf31a6d48e707f352ecba714cee0680da3479b/diffusion_models/qwen_image_2.1_int8_convrot.safetensors" "$Dl\models\diffusion_models\qwen_image_2.1_int8_convrot.safetensors" "cb74113cb03faecd79611b01fd7fd642f0aa60d6f0b95086abee214d75eaa57d"
  New-Item -ItemType Directory -Force "$Dl\models\loras" | Out-Null
  Get-Verified "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/aacf31a6d48e707f352ecba714cee0680da3479b/loras/p_q_image_2.1_8_steps_v0.1_comfyui_bf16.safetensors" "$Dl\models\loras\p_q_image_2.1_8_steps_v0.1_comfyui_bf16.safetensors" "0c9ff10dc5f8007ed53db3e9084f7082b60d5c82d4e2eb74326048e0d55740eb"
  New-Item -ItemType Directory -Force "$Dl\models\text_encoders" | Out-Null
  Get-Verified "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/aacf31a6d48e707f352ecba714cee0680da3479b/text_encoders/qwen3vl_8b_int8_convrot.safetensors" "$Dl\models\text_encoders\qwen3vl_8b_int8_convrot.safetensors" "8bfd0f6e12abf2d2d697ecc888e5e90b0d6741d6708f05799f53afa560452e8f"
  New-Item -ItemType Directory -Force "$Dl\models\vae" | Out-Null
  Get-Verified "https://huggingface.co/Comfy-Org/Qwen-Image-2.1/resolve/aacf31a6d48e707f352ecba714cee0680da3479b/vae/qwen_image_2.1_vae_bf16.safetensors" "$Dl\models\vae\qwen_image_2.1_vae_bf16.safetensors" "bb21f7473051e1ac368515dd3f2e15cd44d7a11748ee8823e1ddca3e4876b7c9"
  New-Item -ItemType Directory -Force "$Dl\models\loras" | Out-Null
  Get-Verified "https://huggingface.co/Alissonerdx/BFS-Best-Face-Swap/resolve/0ca3913ade4b4ada458d60c232354e8586c4c181/bfs_head_v1.1_alternative_qwen_2.1.safetensors" "$Dl\models\loras\bfs_head_v1.1_alternative_qwen_2.1.safetensors" "c5332bbc2f826856e7a09bce9740e97d39514b849368212e1f2c3c2c2d81c217"
  New-Item -ItemType Directory -Force "$Dl\models\loras" | Out-Null
  Get-Verified "https://huggingface.co/Alissonerdx/BFS-Best-Face-Swap/resolve/0ca3913ade4b4ada458d60c232354e8586c4c181/bfs_head_v1.1_qwen_2.1.safetensors" "$Dl\models\loras\bfs_head_v1.1_qwen_2.1.safetensors" "d1d748d5601077f3b6d05766f6823510e901970d916afa404a97e85dc92fa88e"
  Get-Verified "https://huggingface.co/Alissonerdx/BFS-Best-Face-Swap/resolve/0ca3913ade4b4ada458d60c232354e8586c4c181/workflows/Head%20Swap%20V1%20Qwen%202.1%20Workflow.json" "$Root\workflows\Head Swap V1 Qwen 2.1 Workflow.json" $null
}
Add-Content $Log ("DONE " + (Get-Date -Format s))
