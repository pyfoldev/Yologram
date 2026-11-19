"""What Йолоток looks like.

A violet square with a white hammer on it. The glyph is drawn in a 108-unit
square, the viewport an adaptive icon uses, and all of it sits inside the
middle 72 units -- the safe zone, the part no launcher mask can cut off.

`assets/make_icon.py` draws the 512 px bitmap from the same rectangles; the
legacy densities are scaled down from it.
"""

VIOLET = 0xFF7C4DFF
BLACK = 0xFF121212
WHITE = 0xFFFFFFFF

VIEWPORT = 108

BACKGROUND = [(VIOLET, "M0,0 h108 v108 h-108 z")]

# head: x 28..80, y 28..50 (with a claw notch); handle: x 48..60, y 50..82
_HAMMER = ("M28,30 Q28,28 30,28 L78,28 Q80,28 80,30 L80,48 Q80,50 78,50 "
           "L60,50 L60,80 Q60,82 58,82 L50,82 Q48,82 48,80 L48,50 L30,50 "
           "Q28,50 28,48 Z")

GLYPH = [(WHITE, _HAMMER)]

# for an icon that is one drawable rather than two layers
COMBINED = BACKGROUND + GLYPH

# the bitmap the legacy densities are scaled down from
MASTER_PNG = "icon_out/play/ic_launcher-512.png"

# kept for code that still asks what the brand colour is
MINT = VIOLET
