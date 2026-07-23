"""
Core of the S Pen Pointer PC listener: a WebSocket server that turns phone
events into mouse movement/clicks, wrapped in a start/stop controller so both
the headless CLI (spen_listener.py) and the tray app (tray_app.py) can drive it.
"""

import asyncio
import ctypes
import json
import logging
import threading
import time

import websockets
from pynput.mouse import Button, Controller

HOST = "0.0.0.0"
PORT = 8765

# --- Smoothing (see README) ---
SMOOTH_TICK = 0.005   # 200 Hz update loop
SMOOTH_ALPHA = 0.42   # fraction of remaining distance per tick (higher = snappier)

log = logging.getLogger("spen_pointer")

# High-DPI correctness + a 1 ms timer so the 200 Hz smoothing loop is smooth.
try:
    ctypes.windll.user32.SetProcessDPIAware()
    ctypes.windll.winmm.timeBeginPeriod(1)
except Exception:
    pass

mouse = Controller()

_pending_x = 0.0
_pending_y = 0.0
_carry_x = 0.0
_carry_y = 0.0


def _screen_center():
    w = ctypes.windll.user32.GetSystemMetrics(0)
    h = ctypes.windll.user32.GetSystemMetrics(1)
    return w // 2, h // 2


def handle_event(data: dict) -> None:
    global _pending_x, _pending_y, _carry_x, _carry_y, SMOOTH_ALPHA
    event_type = data.get("type")

    if event_type == "config":
        alpha = data.get("smooth_alpha")
        if alpha is not None:
            SMOOTH_ALPHA = min(1.0, max(0.05, float(alpha)))
            log.info("Smoothing alpha set to %.2f", SMOOTH_ALPHA)

    elif event_type == "motion":
        _pending_x += float(data.get("dx", 0.0))
        _pending_y += float(data.get("dy", 0.0))

    elif event_type == "button":
        action = data.get("action")
        if action == "down":
            mouse.press(Button.left)
        elif action == "up":
            mouse.release(Button.left)

    elif event_type == "center":
        _pending_x = _pending_y = 0.0
        _carry_x = _carry_y = 0.0
        mouse.position = _screen_center()
        log.info("Cursor centered")


async def smoothing_loop() -> None:
    global _pending_x, _pending_y, _carry_x, _carry_y
    while True:
        await asyncio.sleep(SMOOTH_TICK)
        step_x = _pending_x if abs(_pending_x) < 0.5 else _pending_x * SMOOTH_ALPHA
        step_y = _pending_y if abs(_pending_y) < 0.5 else _pending_y * SMOOTH_ALPHA
        _pending_x -= step_x
        _pending_y -= step_y
        _carry_x += step_x
        _carry_y += step_y
        move_x = int(_carry_x)
        move_y = int(_carry_y)
        _carry_x -= move_x
        _carry_y -= move_y
        if move_x or move_y:
            mouse.move(move_x, move_y)


class ServerController:
    """Runs the WebSocket server on a background thread; start()/stop() are safe
    to call from any thread. `on_change` fires (no args) whenever running or
    client_connected changes, so a UI can re-render."""

    def __init__(self, on_change=None):
        self.on_change = on_change
        self.running = False
        self.client_connected = False
        self.last_error = None
        self._loop = None
        self._thread = None
        self._stop_event = None

    def _emit(self):
        if self.on_change:
            try:
                self.on_change()
            except Exception:
                pass

    def start(self):
        if self.running:
            return
        self.running = True
        self.last_error = None
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        self._emit()

    def stop(self):
        if not self.running:
            return
        if self._loop and self._stop_event:
            self._loop.call_soon_threadsafe(self._stop_event.set)
        if self._thread:
            self._thread.join(timeout=3)
        self.running = False
        self.client_connected = False
        self._emit()

    def _run(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._serve())
        except OSError as e:
            self.last_error = str(e)
            log.error("Server failed to start: %s", e)
        finally:
            try:
                self._loop.close()
            except Exception:
                pass
            self.running = False
            self.client_connected = False
            self._emit()

    async def _serve(self):
        self._stop_event = asyncio.Event()
        smoothing = asyncio.create_task(smoothing_loop())
        try:
            async with websockets.serve(self._handler, HOST, PORT):
                log.info("Listening on ws://%s:%d", HOST, PORT)
                await self._stop_event.wait()
        finally:
            smoothing.cancel()

    async def _handler(self, websocket):
        self.client_connected = True
        self._emit()
        log.info("Phone connected from %s", websocket.remote_address)
        try:
            async for message in websocket:
                try:
                    data = json.loads(message)
                except json.JSONDecodeError:
                    continue
                handle_event(data)
        except websockets.exceptions.ConnectionClosed:
            pass
        finally:
            self.client_connected = False
            self._emit()
            log.info("Phone disconnected")


def run_blocking():
    """Headless CLI entry point."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    controller = ServerController()
    controller.start()
    log.info("Press Ctrl+C to stop.")
    try:
        while True:
            time.sleep(0.5)
    except KeyboardInterrupt:
        controller.stop()
