"""A keyboard shortcut that works anywhere, even while a game has focus (for the overlay).

Shortcuts are written like "Ctrl+Shift+J": any of Ctrl, Shift, Alt, Meta (Windows key / Cmd),
then one key (a letter, a digit, F1-F12, Space, Tab, `, or the like).
  Windows: pynput.
  macOS: the system's own hotkeys (Carbon RegisterEventHotKey), on the main thread. Not pynput:
         its listener calls keyboard APIs off the main thread, and macOS kills the app for it
         (pynput issues #424, #510). This also needs no Accessibility permission.
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
        if sys.platform.startswith("linux"):
            self._stop = _x11(mods, key, self.pressed.emit)
        elif sys.platform == "darwin":
            self._stop = _carbon(mods, key, self.pressed.emit)
        else:
            self._stop = _pynput(mods, key, self.pressed.emit)

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


# macOS virtual key codes (Carbon's kVK_*)
_MAC_KEYS = {
    "a": 0x00, "s": 0x01, "d": 0x02, "f": 0x03, "h": 0x04, "g": 0x05, "z": 0x06, "x": 0x07, "c": 0x08, "v": 0x09,
    "b": 0x0B, "q": 0x0C, "w": 0x0D, "e": 0x0E, "r": 0x0F, "y": 0x10, "t": 0x11, "1": 0x12, "2": 0x13, "3": 0x14,
    "4": 0x15, "6": 0x16, "5": 0x17, "=": 0x18, "9": 0x19, "7": 0x1A, "-": 0x1B, "8": 0x1C, "0": 0x1D, "]": 0x1E,
    "o": 0x1F, "u": 0x20, "[": 0x21, "i": 0x22, "p": 0x23, "l": 0x25, "j": 0x26, "'": 0x27, "k": 0x28, ";": 0x29,
    "\\": 0x2A, ",": 0x2B, "/": 0x2C, "n": 0x2D, "m": 0x2E, ".": 0x2F, "tab": 0x30, "space": 0x31, "`": 0x32,
    "escape": 0x35, "f1": 0x7A, "f2": 0x78, "f3": 0x63, "f4": 0x76, "f5": 0x60, "f6": 0x61, "f7": 0x62, "f8": 0x64,
    "f9": 0x65, "f10": 0x6D, "f11": 0x67, "f12": 0x6F,
}


def _carbon(mods, key, fire):
    import ctypes
    from ctypes import POINTER, byref, c_int32, c_uint32, c_ulong, c_void_p

    if key not in _MAC_KEYS:
        raise ValueError(f"{key.upper()} can't be used for a shortcut on macOS")
    carbon = ctypes.cdll.LoadLibrary("/System/Library/Frameworks/Carbon.framework/Carbon")

    class HotKeyID(ctypes.Structure):
        _fields_ = [("signature", c_uint32), ("id", c_uint32)]

    class EventTypeSpec(ctypes.Structure):
        _fields_ = [("eventClass", c_uint32), ("eventKind", c_uint32)]

    handler_type = ctypes.CFUNCTYPE(c_int32, c_void_p, c_void_p, c_void_p)
    carbon.GetApplicationEventTarget.restype = c_void_p
    carbon.GetApplicationEventTarget.argtypes = []
    carbon.InstallEventHandler.restype = c_int32
    carbon.InstallEventHandler.argtypes = [c_void_p, handler_type, c_ulong, POINTER(EventTypeSpec), c_void_p, POINTER(c_void_p)]
    carbon.RegisterEventHotKey.restype = c_int32
    carbon.RegisterEventHotKey.argtypes = [c_uint32, c_uint32, HotKeyID, c_void_p, c_uint32, POINTER(c_void_p)]
    carbon.UnregisterEventHotKey.restype = c_int32
    carbon.UnregisterEventHotKey.argtypes = [c_void_p]
    carbon.RemoveEventHandler.restype = c_int32
    carbon.RemoveEventHandler.argtypes = [c_void_p]

    def four(code: str) -> int:
        return int.from_bytes(code.encode(), "big")

    def on_event(_call, _event, _data):
        try:
            fire()
        except Exception:  # noqa: BLE001 - never let an exception reach Carbon
            pass
        return 0

    handler = handler_type(on_event)                      # kept alive by stop() below
    target = carbon.GetApplicationEventTarget()
    spec = EventTypeSpec(four("keyb"), 5)                 # kEventClassKeyboard, kEventHotKeyPressed
    handler_ref = c_void_p()
    err = carbon.InstallEventHandler(target, handler, 1, byref(spec), None, byref(handler_ref))
    if err:
        raise RuntimeError(f"Couldn't set the shortcut (macOS error {err})")
    mask = 0
    for m, bit in (("meta", 1 << 8), ("shift", 1 << 9), ("alt", 1 << 11), ("ctrl", 1 << 12)):   # cmdKey, shiftKey, optionKey, controlKey
        if m in mods:
            mask |= bit
    hotkey_ref = c_void_p()
    err = carbon.RegisterEventHotKey(_MAC_KEYS[key], mask, HotKeyID(four("jace"), 1), target, 0, byref(hotkey_ref))
    if err:
        carbon.RemoveEventHandler(handler_ref)
        raise RuntimeError("Another app already uses that shortcut" if err == -9878 else f"Couldn't set the shortcut (macOS error {err})")

    def stop():
        carbon.UnregisterEventHotKey(hotkey_ref)
        carbon.RemoveEventHandler(handler_ref)
        stop.handler = None                               # let the callback go only once it's unregistered
    stop.handler = handler
    return stop
