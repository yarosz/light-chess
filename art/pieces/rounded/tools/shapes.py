# Set 2 core geometry (final, round 6). gen.py dilates the core 1.7 on black pieces.
BASE = "M13 35A2 2 0 0 1 15 33H30A2 2 0 0 1 32 35V37H13Z"

PIECES["P"] = {"body": [
    ("fill", circle(22.5, 15, 4.5)),
    ("fill", "M20.5 18H24.5V25H20.5Z"),
    ("fill", "M15 37V31A7.5 7.5 0 0 1 30 31V37Z"),
]}

PIECES["R"] = {"body": [
    ("fill", BASE),
    ("fill", "M12 20V11H13.8V13.5A3.9 3.9 0 0 0 21.6 13.5V11H23.4V13.5A3.9 3.9 0 0 0 31.2 13.5V11H33V20Q30.5 20 30.5 22.5V34H14.5V22.5Q14.5 20 12 20Z"),
]}

PIECES["B"] = {"body": [
    ("fill", BASE),
    ("fill", circle(22.5, 7, 1.8)),
    ("fill", "M22.5 10C26.5 13 29 17 29 20.5C29 24 26.5 26.5 22.5 26.5C18.5 26.5 16 24 16 20.5C16 17 18.5 13 22.5 10Z"),
    ("fill", "M19.5 26H25.5L27 34H18Z"),
], "cuts": [("line", "M25 15.5L21.5 20")]}

PIECES["N"] = {"body": [
    ("fill", BASE),
    ("fill", "M16 34C16.5 29.5 19.5 27 22 25.5L23 11C29 12 32.5 19 32.5 26V34Z"),
    ("line", "M13 21L22 14", 7),
    ("line", "M22.5 11.5L24 5.5", 2),
], "cuts": [("dot", 19.4, 15.4, 1.5)]}

PIECES["Q"] = {"body": [
    ("fill", BASE),
    ("fill", "M16 34L13.5 16L18.5 23L22.5 11L26.5 23L31.5 16L29 34Z"),
    ("fill", circle(13.5, 14.5, 2.2)),
    ("fill", circle(22.5, 9.5, 2.2)),
    ("fill", circle(31.5, 14.5, 2.2)),
]}

PIECES["K"] = {"body": [
    ("fill", BASE),
    ("fill", "M15.5 34L13.5 19A1.5 1.5 0 0 1 15 17.5Q22.5 15.5 30 17.5A1.5 1.5 0 0 1 31.5 19L29.5 34Z"),
    ("line", "M22.5 4V15", 1.8),
    ("line", "M17.5 8.5H27.5", 1.8),
]}

