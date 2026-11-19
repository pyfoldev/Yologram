"""Draws icon_out/play/ic_launcher-512.png: violet square, white hammer."""
import os
from PIL import Image, ImageDraw
S = 512
K = S / 108
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((0, 0, S - 1, S - 1), radius=S * 0.22, fill=(0x7C, 0x4D, 0xFF, 255))
d.rounded_rectangle((28 * K, 28 * K, 80 * K, 50 * K), radius=2 * K, fill="white")
d.rounded_rectangle((48 * K, 48 * K, 60 * K, 82 * K), radius=2 * K, fill="white")
out = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "icon_out", "play", "ic_launcher-512.png")
img.save(out)
