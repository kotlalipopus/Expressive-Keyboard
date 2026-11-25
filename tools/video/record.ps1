# Запись экрана телефона для вставки в видео.
# Использование: .\record.ps1 demo   -> clips\demo.mp4 (остановка: Ctrl+C)
# Телефон должен быть подключён (adb devices -> device). Для Wi-Fi: adb connect IP:PORT
param([string]$name = "demo")
New-Item -ItemType Directory -Force -Path "$PSScriptRoot\clips" | Out-Null
& "C:\scrcpy\scrcpy-win64-v3.3.3\scrcpy.exe" --record "$PSScriptRoot\clips\$name.mp4" --max-size 1280 --no-audio
