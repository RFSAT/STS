"""
STS launcher / Play Store icon.

Palette and construction follow the VTB icon so the two apps read as a
family: the same dark green field, the same gold and cream, flat shapes with
no gradient or shadow, heavy confident strokes, generous negative space.

Everything is parameterised on ONE number, R, the radius of the artwork, so
the identical drawing is emitted at the size Play wants (a full-bleed 512
square) and at the much smaller fraction of the canvas an adaptive launcher
icon may occupy. Drawing it twice by hand is how a store listing and a home
screen end up subtly different from each other.

Composition: the target sits in the upper two thirds and the score below it,
in its own clear band, rather than overlaid on the rings. An earlier version
put the numerals across the rings and knocked a gap out around them; it was
legible but left ragged stubs of ring on either side of every glyph, and at
launcher size the whole thing turned to texture.
"""
import cairo, math

GREEN = (0x2E/255, 0x40/255, 0x34/255)
GOLD  = (0xCA/255, 0xA2/255, 0x4B/255)
CREAM = (0xF4/255, 0xF3/255, 0xEE/255)

FONT  = "DejaVu Sans"
SCORE = "10.9"   # the ISSF decimal maximum — to a target shooter this reads
                 # instantly as a perfect shot, and it is the figure this app
                 # exists to produce.

# Geometry as fractions of R. y is positive UP here and flipped on the way to
# cairo, because reasoning about a target upside down is how shot groups end
# up on the wrong side of the ten.
T_CY    = 0.22            # target centre, above the artwork centre
RINGS   = (0.74, 0.50)
LW      = 0.090
BULL    = 0.190
# One shot dead centre and two close to it, up and left. Three holes spaced
# evenly AROUND the centre stop reading as a shot group and start reading as
# an atom; the asymmetry is what makes them shots. The centre hole leaves a
# gold rim of bull standing around it, which is exactly what a shot-out ten
# looks like on a real card.
HOLES   = ((0.00, 0.00), (-0.29, 0.16), (-0.12, 0.30))   # about the target centre
HOLE_R  = 0.088
HOLE_GAP= 0.034           # torn-paper gap knocked out around each hole
TEXT_CY = -0.78
CAP_H   = 0.255


def draw(ctx, cx, cy, R, ink=None):
    """ink=None uses the palette; ink=(r,g,b) forces one colour, which is
    what the monochrome themed-icon layer needs."""
    gold  = ink or GOLD
    cream = ink or CREAM
    tcy = cy - T_CY * R                      # target centre in device space

    # --- rings ---
    ctx.set_source_rgb(*gold)
    ctx.set_line_width(LW * R)
    for r in RINGS:
        ctx.new_path(); ctx.arc(cx, tcy, r * R, 0, 2 * math.pi); ctx.stroke()

    # --- the bull, the dot every shot is aimed at ---
    ctx.new_path(); ctx.arc(cx, tcy, BULL * R, 0, 2 * math.pi); ctx.fill()

    # --- bullet holes ---
    # Each is knocked out first at a slightly larger radius, so whatever it
    # lands on is visibly BROKEN by it. That gap is what makes the mark read
    # as a hole punched through paper rather than a dot printed on it — and
    # because the gap is transparent, the layer beneath shows through in both
    # the store icon and the adaptive one, with no colour to keep in step.
    # Two passes, not one per hole. Clearing and filling hole by hole lets a
    # later hole's gap erase an earlier hole's fill wherever two shots land
    # close together — which is precisely when it matters, and it shows up as
    # a bite out of one hole rather than as an obvious bug.
    ctx.set_operator(cairo.OPERATOR_CLEAR)
    for hx, hy in HOLES:
        ctx.new_path()
        ctx.arc(cx + hx*R, tcy - hy*R, (HOLE_R + HOLE_GAP) * R, 0, 2*math.pi)
        ctx.fill()
    ctx.set_operator(cairo.OPERATOR_OVER)
    ctx.set_source_rgb(*cream)
    for hx, hy in HOLES:
        ctx.new_path()
        ctx.arc(cx + hx*R, tcy - hy*R, HOLE_R * R, 0, 2*math.pi)
        ctx.fill()

    # --- the score ---
    ctx.select_font_face(FONT, cairo.FONT_SLANT_NORMAL, cairo.FONT_WEIGHT_BOLD)
    ctx.set_font_size(1000)
    cap = ctx.text_extents("0").height          # digit ink height at 1000pt
    ctx.set_font_size(CAP_H * R * 1000 / cap)
    e = ctx.text_extents(SCORE)
    ctx.set_source_rgb(*cream)
    ctx.move_to(cx - e.width/2 - e.x_bearing, cy - TEXT_CY * R + e.height/2)
    ctx.show_text(SCORE)


def artwork(size, R, ink=None):
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    draw(cairo.Context(surf), size/2, size/2, R, ink)
    return surf


def play_icon(path, size=512, frac=0.84):
    """Full-bleed square for the store listing; Play rounds the corners."""
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surf)
    ctx.set_source_rgb(*GREEN); ctx.paint()
    ctx.set_source_surface(artwork(size, size*frac/2), 0, 0); ctx.paint()
    surf.write_to_png(path)


if __name__ == "__main__":
    play_icon("/tmp/icon/preview.png")
    print("ok")


# ----------------------------------------------------------------------
#  Outputs
# ----------------------------------------------------------------------
#
# The adaptive launcher icon is a 108dp canvas of which only the central
# 66dp is guaranteed visible — every OEM applies its own mask, and anything
# outside that circle can be cropped. So the artwork radius for the launcher
# is 0.30 of the canvas (60dp across), comfortably inside the guarantee,
# while the Play icon may use 0.82 of its square because Play only rounds the
# corners. Same drawing, two scales.

SAFE_FRAC = 0.285         # artwork radius as a fraction of the 108dp canvas
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def foreground(path, canvas, ink=None):
    """Adaptive-icon layer: artwork on transparency, inside the safe circle."""
    artwork(canvas, canvas * SAFE_FRAC, ink).write_to_png(path)


def contact_sheet(path, sizes=(36, 48, 72, 96, 144, 192)):
    """The launcher icon at the sizes it is actually seen at. An icon judged
    only at 512 is an icon that has not been judged.

    The scaling here is the whole point and is easy to get wrong: a launcher
    does NOT draw the 108dp canvas at the icon's display size. It scales the
    canvas so the 72dp VISIBLE area fills the display size, and the remaining
    18dp on each side is bleed that the mask throws away. So a tile drawn at
    s pixels corresponds to a canvas of s * 108/72, and artwork at
    SAFE_FRAC of that canvas comes out at 0.90 * s across — nearly filling
    the tile, which is what a launcher icon should do.
    """
    import cairo as c
    pad, gap = 24, 24
    w = pad*2 + sum(sizes) + gap*(len(sizes)-1)
    h = pad*2 + max(sizes)
    surf = c.ImageSurface(c.FORMAT_ARGB32, w, h)
    ctx = c.Context(surf)
    ctx.set_source_rgb(0.10, 0.10, 0.10); ctx.paint()
    x = pad
    for s_ in sizes:
        canvas = s_ * 108.0 / 72.0
        tile = c.ImageSurface(c.FORMAT_ARGB32, s_, s_)
        t = c.Context(tile)
        t.set_source_rgb(*GREEN)
        rad = s_ * 0.22                        # approximate the launcher squircle
        t.new_path()
        t.arc(s_-rad, rad, rad, -math.pi/2, 0); t.arc(s_-rad, s_-rad, rad, 0, math.pi/2)
        t.arc(rad, s_-rad, rad, math.pi/2, math.pi); t.arc(rad, rad, rad, math.pi, 1.5*math.pi)
        t.close_path(); t.fill()
        draw(t, s_/2, s_/2, canvas * SAFE_FRAC)   # artwork at launcher scale
        ctx.set_source_surface(tile, x, pad + (max(sizes)-s_)//2); ctx.paint()
        x += s_ + gap
    surf.write_to_png(path)
