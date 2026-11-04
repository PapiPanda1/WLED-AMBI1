@echo off
REM Builds a single-file WLED-Ambilight.exe (output: dist\WLED-Ambilight.exe)
pip install -r requirements.txt pyinstaller
pyinstaller --onefile --noconsole --name WLED-Ambilight ambilight.py
pause
