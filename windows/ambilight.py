"""
WLED Ambilight for Windows (with settings window)

Captures the edges of your screen and streams matching colors to a WLED
strip over UDP (DNRGB realtime protocol, port 21324).

Run:  python ambilight.py        (or double-click run.bat)
Settings are saved to %APPDATA%\\WLED-Ambilight\\config.json.
"""
import json
import os
import socket
import sys
import threading
import time
import tkinter as tk
from tkinter import ttk

import mss
import numpy as np

WLED_UDP_PORT = 21324
CHUNK = 489  # max LEDs per DNRGB packet


def _config_path():
    """Settings live in %APPDATA%\\WLED-Ambilight so they survive as a packaged .exe."""
    fallback = os.path.dirname(os.path.abspath(sys.argv[0]))
    base = os.environ.get("APPDATA") or fallback
    folder = os.path.join(base, "WLED-Ambilight")
    try:
        os.makedirs(folder, exist_ok=True)
    except OSError:
        folder = fallback
    return os.path.join(folder, "config.json")


CONFIG_PATH = _config_path()

DEFAULTS = {
    "ip": "192.168.1.50",
    "left": 30, "top": 60, "right": 30, "bottom": 0,
    "start": "Bottom-left",
    "direction": "Clockwise",
    "offset": 0,
    "monitor": 1,
    "depth": 0.12,
    "fps": 30,
    "smooth": 0.5,
    "saturation": 1.3,
    "brightness": 1.0,
    "gamma": 1.0,
    "black_threshold": 0,
    "autostart": False,
}

SIDE_KEYS = {"T": "top", "R": "right", "B": "bottom", "L": "left"}
SIDE_NAMES = {"T": "Top", "R": "Right", "B": "Bottom", "L": "Left"}
TEST_COLORS = {"T": (255, 0, 0), "R": (0, 255, 0), "B": (0, 80, 255), "L": (255, 200, 0)}

# Sides in clockwise order as seen from the front of the TV.
# Index of the corner where each side begins (clockwise):
CW_CORNERS = {"Top-left": 0, "Top-right": 1, "Bottom-right": 2, "Bottom-left": 3}
# Counter-clockwise order is L, B, R, T, each running the opposite way:
CCW_CORNERS = {"Top-left": 0, "Bottom-left": 1, "Bottom-right": 2, "Top-right": 3}


# --------------------------------------------------------------------------
# Strip layout
# --------------------------------------------------------------------------
def strip_plan(cfg):
    """Return [(side, reversed?)] in the order the strip passes through them."""
    sides = ["T", "R", "B", "L"]
    if cfg["direction"] == "Counter-clockwise":
        plan = [(s, True) for s in reversed(sides)]
        corners = CCW_CORNERS
    else:
        plan = [(s, False) for s in sides]
        corners = CW_CORNERS
    i = corners.get(cfg["start"], 0)
    return plan[i:] + plan[:i]


def assemble(side_colors, cfg):
    """side_colors: dict side -> (n,3) array, each given in clockwise order."""
    parts = [side_colors[s][::-1] if rev else side_colors[s]
             for s, rev in strip_plan(cfg)]
    parts = [p for p in parts if len(p)]
    if not parts:
        return np.zeros((0, 3), dtype=np.float32)
    colors = np.concatenate(parts, axis=0)
    if cfg["offset"]:
        colors = np.roll(colors, int(cfg["offset"]), axis=0)
    return colors


def describe_order(cfg):
    items = []
    for s, _ in strip_plan(cfg):
        n = cfg[SIDE_KEYS[s]]
        if n > 0:
            items.append(f"{SIDE_NAMES[s]} ({n})")
    total = sum(cfg[k] for k in SIDE_KEYS.values())
    return f"{' -> '.join(items) if items else 'no LEDs set'}   |   total {total} LEDs"


# --------------------------------------------------------------------------
# Capture + streaming
# --------------------------------------------------------------------------
def edge_segments(region, count, horizontal):
    """Average a captured edge strip into `count` colors (natural screen order)."""
    rgb = region[::3, ::3, :3][:, :, ::-1].astype(np.float32)  # BGRA -> RGB
    axis_len = rgb.shape[1] if horizontal else rgb.shape[0]
    idx = np.array_split(np.arange(axis_len), count)
    out = np.zeros((count, 3), dtype=np.float32)
    for i, ix in enumerate(idx):
        if len(ix) == 0:
            continue
        chunk = rgb[:, ix[0]:ix[-1] + 1] if horizontal else rgb[ix[0]:ix[-1] + 1]
        out[i] = chunk.reshape(-1, 3).mean(axis=0)
    return out


def send_frame(sock, addr, colors, timeout=2):
    data = np.clip(colors, 0, 255).astype(np.uint8)
    for start in range(0, len(data), CHUNK):
        part = data[start:start + CHUNK]
        header = bytes([4, timeout, (start >> 8) & 0xFF, start & 0xFF])
        sock.sendto(header + part.tobytes(), addr)


def worker(get_cfg, stop, set_status, test=False):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    total = 0
    try:
        with mss.mss() as sct:
            prev = None
            set_status("Testing pattern..." if test else "Running")
            while not stop.is_set():
                t0 = time.perf_counter()
                cfg = get_cfg()
                addr = (cfg["ip"], WLED_UDP_PORT)
                counts = {s: max(0, int(cfg[k])) for s, k in SIDE_KEYS.items()}
                total = sum(counts.values())
                if total == 0:
                    set_status("Set at least one LED count")
                    time.sleep(0.3)
                    continue

                side_colors = {}
                if test:
                    for s, n in counts.items():
                        side_colors[s] = np.tile(np.array(TEST_COLORS[s], dtype=np.float32), (n, 1))
                    colors = assemble(side_colors, cfg)
                    if len(colors):
                        colors[0] = (255, 255, 255)  # first LED on the strip = white
                    prev = None
                    send_frame(sock, addr, colors)
                    time.sleep(0.1)
                    continue

                if cfg["monitor"] >= len(sct.monitors) or cfg["monitor"] < 1:
                    set_status(f"Monitor {cfg['monitor']} not found (have {len(sct.monitors) - 1})")
                    time.sleep(0.5)
                    continue
                mon = sct.monitors[cfg["monitor"]]
                W, H = mon["width"], mon["height"]
                dx = max(8, int(W * cfg["depth"]))
                dy = max(8, int(H * cfg["depth"]))
                regions = {
                    "L": {"left": mon["left"], "top": mon["top"], "width": dx, "height": H},
                    "R": {"left": mon["left"] + W - dx, "top": mon["top"], "width": dx, "height": H},
                    "T": {"left": mon["left"], "top": mon["top"], "width": W, "height": dy},
                    "B": {"left": mon["left"], "top": mon["top"] + H - dy, "width": W, "height": dy},
                }
                for s, n in counts.items():
                    if n <= 0:
                        side_colors[s] = np.zeros((0, 3), dtype=np.float32)
                        continue
                    img = np.asarray(sct.grab(regions[s]))
                    seg = edge_segments(img, n, s in "TB")
                    # Clockwise: top L->R, right T->B, bottom R->L, left B->T
                    if s in "BL":
                        seg = seg[::-1]
                    side_colors[s] = seg
                colors = assemble(side_colors, cfg)

                sat = cfg["saturation"]
                if sat != 1.0:
                    gray = colors.mean(axis=1, keepdims=True)
                    colors = np.clip(gray + (colors - gray) * sat, 0, 255)
                if cfg["gamma"] != 1.0:
                    lut = (255.0 * (np.arange(256) / 255.0) ** cfg["gamma"]).astype(np.float32)
                    colors = lut[np.clip(colors, 0, 255).astype(np.uint8)]
                if cfg["black_threshold"] > 0:
                    colors[colors.max(axis=1) < cfg["black_threshold"]] = 0
                colors = colors * max(0.0, min(cfg["brightness"], 1.0))

                smooth = min(max(cfg["smooth"], 0.0), 0.95)
                if prev is None or prev.shape != colors.shape:
                    prev = colors
                else:
                    prev = prev * smooth + colors * (1.0 - smooth)
                send_frame(sock, addr, prev)

                sleep = 1.0 / max(1, cfg["fps"]) - (time.perf_counter() - t0)
                if sleep > 0:
                    time.sleep(sleep)
    except Exception as e:  # noqa: BLE001
        set_status(f"Error: {e}")
    finally:
        try:
            if total:
                send_frame(sock, (get_cfg()["ip"], WLED_UDP_PORT),
                           np.zeros((total, 3)), timeout=1)
        except Exception:  # noqa: BLE001
            pass
        sock.close()


# --------------------------------------------------------------------------
# GUI
# --------------------------------------------------------------------------
class App:
    def __init__(self, root):
        self.root = root
        root.title("WLED Ambilight")
        root.resizable(False, False)
        self.thread = None
        self.stop = threading.Event()
        self.status = "Stopped"
        self.vars = {}
        self.types = {}

        cfg = dict(DEFAULTS)
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg.update(json.load(f))
        except (OSError, ValueError):
            pass

        pad = {"padx": 8, "pady": 4}
        main = ttk.Frame(root, padding=10)
        main.grid()

        # Connection
        box = ttk.LabelFrame(main, text="WLED device")
        box.grid(row=0, column=0, sticky="ew", **pad)
        self.add_entry(box, "IP address / hostname", "ip", str, cfg, 0, 0, width=22)

        # LED counts
        box = ttk.LabelFrame(main, text="LEDs on each side (0 = none)")
        box.grid(row=1, column=0, sticky="ew", **pad)
        self.add_spin(box, "Top", "top", int, cfg, 0, 0, 0, 1000)
        self.add_spin(box, "Right", "right", int, cfg, 0, 2, 0, 1000)
        self.add_spin(box, "Bottom", "bottom", int, cfg, 1, 0, 0, 1000)
        self.add_spin(box, "Left", "left", int, cfg, 1, 2, 0, 1000)

        # Start / direction
        box = ttk.LabelFrame(main, text="Where the strip starts (viewed from the front)")
        box.grid(row=2, column=0, sticky="ew", **pad)
        self.add_combo(box, "Start corner", "start",
                       ["Bottom-left", "Top-left", "Top-right", "Bottom-right"], cfg, 0, 0)
        self.add_combo(box, "Direction", "direction",
                       ["Clockwise", "Counter-clockwise"], cfg, 1, 0)
        self.add_spin(box, "Shift LEDs by", "offset", int, cfg, 2, 0, -1000, 1000)
        ttk.Label(box, text="(fine-tune if the start isn't exactly at a corner)",
                  foreground="#666").grid(row=2, column=2, columnspan=2, sticky="w")
        self.order_label = ttk.Label(box, text="", wraplength=380, foreground="#0a5")
        self.order_label.grid(row=3, column=0, columnspan=4, sticky="w", padx=6, pady=(6, 4))

        # Look
        box = ttk.LabelFrame(main, text="Look")
        box.grid(row=3, column=0, sticky="ew", **pad)
        self.add_spin(box, "Monitor", "monitor", int, cfg, 0, 0, 1, 10)
        self.add_spin(box, "FPS", "fps", int, cfg, 0, 2, 1, 60)
        self.add_spin(box, "Smoothing (0-0.95)", "smooth", float, cfg, 1, 0, 0, 0.95, 0.05)
        self.add_spin(box, "Saturation", "saturation", float, cfg, 1, 2, 0.5, 3, 0.1)
        self.add_spin(box, "Brightness (0-1)", "brightness", float, cfg, 2, 0, 0, 1, 0.05)
        self.add_spin(box, "Gamma", "gamma", float, cfg, 2, 2, 0.5, 3, 0.1)
        self.add_spin(box, "Sample depth", "depth", float, cfg, 3, 0, 0.02, 0.5, 0.02)
        self.add_spin(box, "Black cutoff (0-255)", "black_threshold", int, cfg, 3, 2, 0, 255)

        # Buttons
        bar = ttk.Frame(main)
        bar.grid(row=4, column=0, sticky="ew", **pad)
        self.start_btn = ttk.Button(bar, text="Start", command=self.toggle)
        self.start_btn.grid(row=0, column=0, padx=(0, 6))
        self.test_btn = ttk.Button(bar, text="Test pattern", command=self.test)
        self.test_btn.grid(row=0, column=1, padx=(0, 6))
        self.autostart = tk.BooleanVar(value=bool(cfg["autostart"]))
        self.vars["autostart"] = self.autostart
        self.types["autostart"] = bool
        ttk.Checkbutton(bar, text="Start automatically on launch",
                        variable=self.autostart).grid(row=0, column=2, padx=6)
        self.status_label = ttk.Label(main, text="Status: Stopped")
        self.status_label.grid(row=5, column=0, sticky="w", **pad)
        ttk.Label(main, foreground="#666", wraplength=420, text=(
            "Test pattern: first LED is white; Top = red, Right = green, "
            "Bottom = blue, Left = yellow. Use it to check the start corner "
            "and direction. Changes apply live while running.")
        ).grid(row=6, column=0, sticky="w", **pad)

        self.snap = dict(cfg)
        self.refresh()
        for var in self.vars.values():
            var.trace_add("write", lambda *_: self.refresh())

        root.protocol("WM_DELETE_WINDOW", self.close)
        root.after(200, self.poll)
        if cfg["autostart"]:
            root.after(500, self.toggle)

    # --- widget helpers ---
    def add_entry(self, parent, label, key, typ, cfg, r, c, width=10):
        ttk.Label(parent, text=label).grid(row=r, column=c, sticky="w", padx=6, pady=3)
        var = tk.StringVar(value=str(cfg[key]))
        ttk.Entry(parent, textvariable=var, width=width).grid(row=r, column=c + 1, padx=6, pady=3)
        self.vars[key], self.types[key] = var, typ

    def add_spin(self, parent, label, key, typ, cfg, r, c, lo, hi, step=1):
        ttk.Label(parent, text=label).grid(row=r, column=c, sticky="w", padx=6, pady=3)
        var = tk.StringVar(value=str(cfg[key]))
        ttk.Spinbox(parent, from_=lo, to=hi, increment=step, textvariable=var,
                    width=8).grid(row=r, column=c + 1, padx=6, pady=3)
        self.vars[key], self.types[key] = var, typ

    def add_combo(self, parent, label, key, values, cfg, r, c):
        ttk.Label(parent, text=label).grid(row=r, column=c, sticky="w", padx=6, pady=3)
        var = tk.StringVar(value=str(cfg[key]))
        ttk.Combobox(parent, textvariable=var, values=values, state="readonly",
                     width=18).grid(row=r, column=c + 1, columnspan=2, sticky="w", padx=6, pady=3)
        self.vars[key], self.types[key] = var, str

    # --- config ---
    def read_cfg(self):
        cfg = {}
        for key, var in self.vars.items():
            try:
                cfg[key] = self.types[key](var.get())
            except (ValueError, tk.TclError):
                cfg[key] = self.snap.get(key, DEFAULTS[key])
        cfg["ip"] = str(cfg["ip"]).strip()
        return cfg

    def refresh(self):
        self.snap = self.read_cfg()  # worker thread reads this dict (atomic swap)
        self.order_label.config(text="Strip order: " + describe_order(self.snap))

    def save(self):
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(self.snap, f, indent=2)
        except OSError:
            pass

    def get_cfg(self):
        return self.snap

    # --- running ---
    def set_status(self, text):
        self.status = text

    def running(self):
        return self.thread is not None and self.thread.is_alive()

    def launch(self, test):
        self.halt()
        self.save()
        self.stop = threading.Event()
        self.thread = threading.Thread(
            target=worker, args=(self.get_cfg, self.stop, self.set_status, test), daemon=True)
        self.thread.start()
        self.mode = "test" if test else "run"

    def halt(self):
        if self.running():
            self.stop.set()
            self.thread.join(timeout=2)
        self.thread = None
        self.mode = None

    mode = None

    def toggle(self):
        if self.running() and self.mode == "run":
            self.halt()
            self.set_status("Stopped")
        else:
            self.launch(test=False)

    def test(self):
        if self.running() and self.mode == "test":
            self.halt()
            self.set_status("Stopped")
        else:
            self.launch(test=True)

    def poll(self):
        if self.thread is not None and not self.thread.is_alive():
            self.thread = None
            self.mode = None
        self.status_label.config(text="Status: " + self.status)
        self.start_btn.config(text="Stop" if self.mode == "run" else "Start")
        self.test_btn.config(text="Stop test" if self.mode == "test" else "Test pattern")
        self.root.after(200, self.poll)

    def close(self):
        self.save()
        self.halt()
        self.root.destroy()


def main():
    try:  # capture real pixels on scaled (125%/150%) displays
        import ctypes
        ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception:  # noqa: BLE001
        pass
    root = tk.Tk()
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
