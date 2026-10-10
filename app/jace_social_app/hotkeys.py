"""A keyboard shortcut that works anywhere, even while a game has focus (for the overlay).

Shortcuts are written like "Ctrl+Shift+J": any of Ctrl, Shift, Alt, Meta (Windows key / Cmd),
then one key (a letter, a digit, F1-F12, Space, Tab, `, or the like).
  Windows, macOS: pynput (macOS asks for Accessibility permission the first time).
  Linux: X11 (XGrabKey). Wayland has no global shortcuts for apps; games running through
         XWayland (most of them, Proton included) still see it.
"""
from __future__ import annotations

import os
import sys
import select
import threading

from PySide6.QtCore import QObject, Signal

MODS = ("ctrl", "shift", "alt", "meta")
NAMED = {"space": "space", "tab": "tab", "`": "grave", "backquote": "grave", "escape": "escape",
         **{f"f{i}": f"f{i}" for i in range(1, 13)}}


def parse(combo: str) -> tuple[set[str], str]:
    """"Ctrl+Shift+J" -> ({"ctrl", "shift"}, "j"). Raises ValueError if it isn't a usable shortcut."""
    parts = [p.strip().lower() for p in combo.replace(" ", "").split("+") if p.strip()]
    mods = {{"control": "ctrl", "option": "alt", "cmd": "meta", "command": "meta", "win": "meta", "super": "meta"}.get(p, p)
            for p in parts[:-1]}
    key = parts[-1] if parts else ""
    if not mods or not mods <= set(MODS):
        raise ValueError("Use at least one of Ctrl, Shift, Alt or Meta, then a key - like Ctrl+Shift+J")
    if not (len(key) == 1 and (key.isalnum() or key in "`-=[];',./\\") or key in NAMED):
        raise ValueError(f"{parts[-1] if parts else 'That key'} can't be used for a shortcut")
    return mods, key


def unsupported_reason() -> str | None:
    if sys.platform.startswith("linux") and not os.environ.get("DISPLAY"):
        return "Shortcuts need X11 (Wayland doesn't let apps listen for them)"
    return None


class HotKey(QObject):
    """One global shortcut; `pressed` fires on the UI thread."""
    pressed = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self._stop = None

    def set(self, combo: str | None):
        """Listen for this shortcut instead (None or "": none). Raises ValueError / RuntimeError."""
        self.stop()
        if not combo:
            return
        mods, key = parse(combo)
        why = unsupported_reason()
        if why:
            raise RuntimeError(why)
        self._stop = _x11(mods, key, self.pressed.emit) if sys.platform.startswith("linux") \
            else _pynput(mods, key, self.pressed.emit)

    def stop(self):
        if self._stop:
            stop, self._stop = self._stop, None
            stop()


def _pynput(mods, key, fire):
    from pynput import keyboard

    names = {"ctrl": "<ctrl>", "shift": "<shift>", "alt": "<alt>", "meta": "<cmd>"}
    k = f"<{NAMED[key]}>" if key in NAMED and key != "`" else key
    listener = keyboard.GlobalHotKeys({"+".join([names[m] for m in MODS if m in mods] + [k]): fire})
    listener.daemon = True
    listener.start()
    return listener.stop


def _x11(mods, key, fire):
    from Xlib import X, XK, display, error

    d = display.Display()
    root = d.screen().root
    sym = XK.string_to_keysym(NAMED.get(key, key).capitalize() if key.startswith("f") and key[1:].isdigit()
                              else {"-": "minus", "=": "equal", "[": "bracketleft", "]": "bracketright", ";": "semicolon",
                                    "'": "apostrophe", ",": "comma", ".": "period", "/": "slash", "\\": "backslash"}.get(key, NAMED.get(key, key)))
    code = d.keysym_to_keycode(sym)
    if not code:
        d.close()
        raise RuntimeError(f"Your keyboard has no {key.upper()} key")
    mask = 0
    for m, bit in (("ctrl", X.ControlMask), ("shift", X.ShiftMask), ("alt", X.Mod1Mask), ("meta", X.Mod4Mask)):
        if m in mods:
            mask |= bit
    grabbed = []
    catch = error.CatchError(error.BadAccess)
    for extra in (0, X.LockMask, X.Mod2Mask, X.LockMask | X.Mod2Mask):      # with Caps Lock / Num Lock on too
        root.grab_key(code, mask | extra, True, X.GrabModeAsync, X.GrabModeAsync, onerror=catch)
        grabbed.append(mask | extra)
    d.sync()
    if catch.get_error():
        for m in grabbed:
            root.ungrab_key(code, m)
        d.close()
        raise RuntimeError("Another app already uses that shortcut")
    running = threading.Event()
    running.set()

    def loop():
        while running.is_set():
            try:
                # wait on the X connection itself (pending_events alone can miss new ones for a while)
                select.select([d], [], [], 0.2)
                while d.pending_events():
                    ev = d.next_event()
                    if ev.type == X.KeyPress and ev.detail == code:
                        fire()
            except Exception:  # noqa: BLE001 - display gone
                return

    t = threading.Thread(target=loop, name="hotkey", daemon=True)
    t.start()

    def stop():
        running.clear()
        t.join(1)
        try:
            for m in grabbed:
                root.ungrab_key(code, m)
            d.sync()
            d.close()
        except Exception:  # noqa: BLE001
            pass
    return stop
