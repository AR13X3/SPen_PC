"""
Headless S Pen Pointer listener (run from a terminal).

    python spen_listener.py

For a no-terminal system-tray version, run tray_app.py instead (or double-click
"Start S Pen Tray.vbs").
"""

from pointer_server import run_blocking

if __name__ == "__main__":
    run_blocking()
