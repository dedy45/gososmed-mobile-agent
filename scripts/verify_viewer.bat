@echo off
setlocal
cd /d "%~dp0\.."

echo === Validasi Sintaks JS (Assets) ===
node --check app\src\main\assets\html\js\stream.js
if errorlevel 1 goto error
echo  [^v] app\src\main\assets\html\js\stream.js OK

node --check app\src\main\assets\html\js\app.js
if errorlevel 1 goto error
echo  [^v] app\src\main\assets\html\js\app.js OK

node --check app\src\main\assets\html\js\control.js
if errorlevel 1 goto error
echo  [^v] app\src\main\assets\html\js\control.js OK

echo === Validasi Sintaks JS (Tools Browser Viewer) ===
node --check tools\browser-viewer\js\stream.js
if errorlevel 1 goto error
echo  [^v] tools\browser-viewer\js\stream.js OK

node --check tools\browser-viewer\js\app.js
if errorlevel 1 goto error
echo  [^v] tools\browser-viewer\js\app.js OK

node --check tools\browser-viewer\js\control.js
if errorlevel 1 goto error
echo  [^v] tools\browser-viewer\js\control.js OK

echo === Semua file JS VALID ===
exit /b 0

:error
echo [X] Validasi Sintaks JS GAGAL!
exit /b 1
