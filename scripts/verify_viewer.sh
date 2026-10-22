#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$SCRIPT_DIR"

echo "=== Validasi Sintaks JS (Assets) ==="
node --check app/src/main/assets/html/js/stream.js && echo " [✓] app/src/main/assets/html/js/stream.js OK"
node --check app/src/main/assets/html/js/app.js && echo " [✓] app/src/main/assets/html/js/app.js OK"
node --check app/src/main/assets/html/js/control.js && echo " [✓] app/src/main/assets/html/js/control.js OK"

echo "=== Validasi Sintaks JS (Tools Browser Viewer) ==="
node --check tools/browser-viewer/js/stream.js && echo " [✓] tools/browser-viewer/js/stream.js OK"
node --check tools/browser-viewer/js/app.js && echo " [✓] tools/browser-viewer/js/app.js OK"
node --check tools/browser-viewer/js/control.js && echo " [✓] tools/browser-viewer/js/control.js OK"

echo "=== Semua file JS VALID (0.1s check passed) ==="
