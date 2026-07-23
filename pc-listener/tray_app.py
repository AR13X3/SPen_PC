"""
System-tray app for the S Pen Pointer listener.

Run with no console window via "Start S Pen Tray.vbs", or directly:
    pythonw tray_app.py

Tray icon colour:
    grey  = stopped
    blue  = listening (waiting for phone)
    green = phone connected
"""

import logging
import os

import pystray
from PIL import Image, ImageDraw

from pointer_server import PORT, ServerController

STOPPED = (140, 140, 140)
LISTENING = (30, 114, 232)
CONNECTED = (60, 190, 90)

# Log to a file next to this script, since there's no console when launched hidden.
_LOG_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "spen_tray.log")
logging.basicConfig(
    level=logging.INFO,
    filename=_LOG_PATH,
    filemode="a",
    format="%(asctime)s %(message)s",
)


def _make_image(color):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse((6, 6, 58, 58), fill=color + (255,))
    # a little white "nib" mark so it reads as a pen, not just a dot
    draw.line((26, 40, 40, 24), fill=(255, 255, 255, 255), width=5)
    draw.ellipse((24, 38, 30, 44), fill=(255, 255, 255, 255))
    return img


class TrayApp:
    def __init__(self):
        self.controller = ServerController(on_change=self._on_change)
        self.icon = pystray.Icon(
            "spen_pointer",
            _make_image(STOPPED),
            "S Pen Pointer",
            menu=pystray.Menu(
                pystray.MenuItem(self._status_text, None, enabled=False),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Listening", self._toggle, checked=lambda i: self.controller.running),
                pystray.Menu.SEPARATOR,
                pystray.MenuItem("Quit", self._quit),
            ),
        )

    def _status_text(self, item):
        if not self.controller.running:
            if self.controller.last_error:
                return "Stopped (error — see log)"
            return "Stopped"
        if self.controller.client_connected:
            return "Phone connected"
        return f"Listening on port {PORT}"

    def _toggle(self, icon, item):
        if self.controller.running:
            self.controller.stop()
        else:
            self.controller.start()

    def _quit(self, icon, item):
        self.controller.stop()
        icon.stop()

    def _on_change(self):
        # Called from the server thread; update the tray icon + tooltip.
        if not self.controller.running:
            color, tip = STOPPED, "S Pen Pointer — stopped"
        elif self.controller.client_connected:
            color, tip = CONNECTED, "S Pen Pointer — phone connected"
        else:
            color, tip = LISTENING, f"S Pen Pointer — listening on {PORT}"
        try:
            self.icon.icon = _make_image(color)
            self.icon.title = tip
            self.icon.update_menu()
        except Exception:
            pass

    def _setup(self, icon):
        icon.visible = True
        self.controller.start()  # begin listening as soon as the app launches

    def run(self):
        self.icon.run(setup=self._setup)


if __name__ == "__main__":
    TrayApp().run()
