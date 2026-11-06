@echo off
rem ComfyUI（Qwen-Image-2.1 + BFS Head v1.1）启动脚本：仅监听 127.0.0.1:8188，启动后自动打开浏览器
rem 与 Qwen3.8 大模型（Docker）不能同时运行：两者都需要几乎全部 16 GB 显存
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
cd /d C:\llm\comfyui\ComfyUI_windows_portable
.\python_embeded\python.exe -s ComfyUI\main.py --windows-standalone-build --listen 127.0.0.1 --port 8188
pause
