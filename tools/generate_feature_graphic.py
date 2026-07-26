"""
Google Play feature graphic, 1024 x 500.

Built from the SAME drawing as the launcher icon — it imports
generate_icons.artwork — so the store banner and the user's home screen
cannot drift apart. That is why this file is composition around an import
rather than a second drawing of a target.

WHAT THE GRAPHIC HAS TO SURVIVE. Play renders it at wildly different sizes,
crops the edges on some layouts, rounds the corners, and overlays a play
button in the middle when a promo video is attached. So: nothing important
within 60 px of an edge, no text below 20 px, and the composition is weighted
left and right rather than centred, leaving the middle able to take an
overlay without losing anything.

TWO THINGS THAT BIT, both worth keeping in the comments because both produce
a plausible-looking wrong result rather than an error:

  1. The artwork is composited from an ARGB32 surface, never drawn straight
     onto the RGB24 background. The icon knocks its bullet holes out with
     OPERATOR_CLEAR, and "clear" on a surface with no alpha channel means
     BLACK, not transparent — so drawing directly gave every hole a heavy
     black ring instead of letting the green through.

  2. Play rejects a feature graphic that has an alpha channel. FORMAT_RGB24
     has none by construction, which is a stronger guarantee than
     remembering to flatten before saving.
"""
import cairo, math, sys, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_icons import GREEN, GOLD, CREAM, FONT, artwork

W, H = 1024, 500
MARGIN = 76
EDGE_SAFE = 60

# Target artwork
ART_CX, ART_CY, ART_R = 826, 190, 130
# Distribution motif
HIST_CX, HIST_BASE, HIST_W, HIST_H = 826, 404, 214, 62
# A plausible good string. Illustrative, and labelled as such in the listing.
HISTOGRAM = (("10", 14), ("9", 9), ("8", 4), ("7", 2), ("6", 1))


def text(ctx, s, x, y, size, colour, bold=False, tracking=0.0):
    ctx.select_font_face(
        FONT, cairo.FONT_SLANT_NORMAL,
        cairo.FONT_WEIGHT_BOLD if bold else cairo.FONT_WEIGHT_NORMAL
    )
    ctx.set_font_size(size)
    ctx.set_source_rgb(*colour)
    if tracking == 0.0:
        ctx.move_to(x, y)
        ctx.show_text(s)
        return ctx.text_extents(s).x_advance
    cx = x
    for ch in s:
        ctx.move_to(cx, y)
        ctx.show_text(ch)
        cx += ctx.text_extents(ch).x_advance + tracking
    return cx - x


def width_of(ctx, s, size, bold=False):
    ctx.select_font_face(
        FONT, cairo.FONT_SLANT_NORMAL,
        cairo.FONT_WEIGHT_BOLD if bold else cairo.FONT_WEIGHT_NORMAL
    )
    ctx.set_font_size(size)
    return ctx.text_extents(s).x_advance


def bullet_line(ctx, s, x, y, size):
    r = 4.0
    ctx.set_source_rgb(*GOLD)
    ctx.new_path(); ctx.arc(x + r, y - size * 0.32, r, 0, 2 * math.pi); ctx.fill()
    text(ctx, s, x + r * 2 + 12, y, size, CREAM)


def histogram(ctx):
    """Five columns, tallest normalised to the block height. A motif, not a
    chart: it exists to say 'the app tells you the shape of your string',
    which is the one claim in the description a target alone cannot carry."""
    peak = max(c for _, c in HISTOGRAM)
    n = len(HISTOGRAM)
    bw = HIST_W / (n * 1.7)
    gap = (HIST_W - n * bw) / (n - 1)
    x = HIST_CX - HIST_W / 2
    for label, count in HISTOGRAM:
        # A floor on the bar height, for the same reason the in-app histogram
        # has one: a single shot out of thirty is a two-pixel bar, and a bar
        # you cannot see reads as a ring nobody hit rather than as one hit
        # once.
        h = max(7.0, HIST_H * count / peak)
        ctx.set_source_rgb(*GOLD)
        ctx.new_path(); ctx.rectangle(x, HIST_BASE - h, bw, h); ctx.fill()
        ctx.select_font_face(FONT, cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
        ctx.set_font_size(20)
        e = ctx.text_extents(label)
        ctx.set_source_rgb(*CREAM)
        ctx.move_to(x + bw / 2 - e.width / 2 - e.x_bearing, HIST_BASE + 26)
        ctx.show_text(label)
        x += bw + gap


TITLE = "Shooting Target Scorer"
TITLE_SIZE = 44
TAGLINE = "Score any target from your camera"
BULLETS = (
    "10 m air to 1000 yd centrefire",
    "ISSF · NRA · F-Class · IPSC · PZSS · DSB",
    "Score, group and sight correction",
)


def check_layout(ctx):
    """Assert the text cannot reach the artwork, and that nothing strays into
    the edge-crop band. A collision here is invisible in code and obvious in
    the store, which is exactly the kind of mistake worth automating."""
    problems = []
    art_left = ART_CX - ART_R * 1.02
    for s, size, bold in ((TITLE, TITLE_SIZE, True), (TAGLINE, 26, False)):
        right = MARGIN + width_of(ctx, s, size, bold)
        if right > art_left - 20:
            problems.append(f"'{s[:24]}…' reaches x={right:.0f}, artwork starts at {art_left:.0f}")
    for s in BULLETS:
        right = MARGIN + 20 + width_of(ctx, s, 23, False)
        if right > art_left - 20:
            problems.append(f"bullet '{s[:24]}…' reaches x={right:.0f}")
    if ART_CX + ART_R * 1.02 > W - EDGE_SAFE:
        problems.append("artwork crosses the right edge-safe band")
    if HIST_BASE + 32 > H - EDGE_SAFE:
        problems.append("histogram labels cross the bottom edge-safe band")
    return problems


def build(path):
    surf = cairo.ImageSurface(cairo.FORMAT_RGB24, W, H)   # RGB24: no alpha, by construction
    ctx = cairo.Context(surf)
    ctx.set_source_rgb(*GREEN); ctx.paint()

    problems = check_layout(ctx)
    if problems:
        raise SystemExit("layout problems:\n  " + "\n  ".join(problems))

    text(ctx, "S T S", MARGIN, 132, 34, GOLD, bold=True, tracking=2.0)
    text(ctx, TITLE, MARGIN, 194, TITLE_SIZE, CREAM, bold=True)
    text(ctx, TAGLINE, MARGIN, 238, 26, GOLD)
    for i, b in enumerate(BULLETS):
        bullet_line(ctx, b, MARGIN, 306 + i * 42, 23)

    # Composited, not drawn directly — see the note at the top of the file.
    art = artwork(W, ART_R)
    ctx.set_source_surface(art, ART_CX - W / 2, ART_CY - W / 2)
    ctx.paint()

    histogram(ctx)

    surf.write_to_png(path)
    return path


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "feature_graphic.png"
    print("written:", build(out))
