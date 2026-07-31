"""
Punch-layout sheets for the STS test corpus.

Everything is drawn in true millimetres on A4. The scale bar is the guard: a
printer that scales, or a "fit to page" left switched on, shows up as a scale
bar that is not 100.0 mm, and one measurement rescales every coordinate on the
sheet rather than silently corrupting them.
"""
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas
from reportlab.lib import colors

W, H = A4
# --- ISSF 10 m Air Pistol: ten ring 11.5 mm, +16 mm diameter per ring ---
TEN, STEP, BLACK_FROM = 11.5, 16.0, 7
RAD = {10 - k: (TEN + k * STEP) / 2.0 for k in range(10)}
BLACK_R = RAD[BLACK_FROM]          # 29.75 mm
OUTER_R = RAD[1]                   # 77.75 mm

def face(c, cx, cy):
    """The printed face, at true scale."""
    c.saveState()
    c.setFillColor(colors.black)
    c.circle(cx, cy, BLACK_R * mm, stroke=0, fill=1)
    for v in range(1, 11):
        r = RAD[v] * mm
        c.setStrokeColor(colors.white if v >= BLACK_FROM else colors.black)
        c.setLineWidth(0.25 * mm if v < BLACK_FROM else 0.3 * mm)
        c.circle(cx, cy, r, stroke=1, fill=0)
    # ring numerals on the two axes, as on a real card
    c.setFont("Helvetica", 7)
    for v in range(1, 9):
        r = (RAD[v] + RAD[v + 1]) / 2.0 * mm
        c.setFillColor(colors.white if v >= BLACK_FROM else colors.black)
        for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            c.drawCentredString(cx + dx * r, cy + dy * r - 2.5, str(v))
    c.restoreState()

def cross(c, cx, cy, u, v, label):
    """A punch mark: 0.15 mm lines, 20% grey, 3 mm arms.

    Thin ON PURPOSE. The detector opens the image with a window half a gauge
    across, which deletes any structure narrower than that whatever its
    contrast — the same property that removes the printed ring lines. A
    0.15 mm cross is an order of magnitude under the threshold, so it cannot
    be mistaken for a shot. This is checked, not assumed: see the protocol.
    """
    x, y = cx + u * mm, cy + v * mm
    c.saveState()
    c.setStrokeColor(colors.Color(0.55, 0.55, 0.55))
    c.setLineWidth(0.15 * mm)
    c.line(x - 3 * mm, y, x + 3 * mm, y)
    c.line(x, y - 3 * mm, x, y + 3 * mm)
    c.setFont("Helvetica", 5)
    c.setFillColor(colors.Color(0.55, 0.55, 0.55))
    c.drawString(x + 3.4 * mm, y + 1.2 * mm, label)
    c.restoreState()

def scalebar(c, x, y):
    c.saveState()
    c.setStrokeColor(colors.black); c.setLineWidth(0.4)
    c.line(x, y, x + 100 * mm, y)
    for i in range(11):
        t = x + i * 10 * mm
        c.line(t, y, t, y + (3.5 * mm if i % 5 == 0 else 2 * mm))
    c.setFont("Helvetica", 7); c.setFillColor(colors.black)
    c.drawString(x, y - 4 * mm, "SCALE CHECK — this bar must measure exactly 100.0 mm. "
                                "If it does not, write down what it does measure.")
    c.restoreState()

def header(c, cid, title, note):
    c.setFont("Helvetica-Bold", 13); c.setFillColor(colors.black)
    c.drawString(18 * mm, H - 18 * mm, "STS test corpus  —  card %s" % cid)
    c.setFont("Helvetica", 9)
    c.drawString(18 * mm, H - 24 * mm, title)
    c.setFont("Helvetica-Oblique", 8)
    c.drawString(18 * mm, H - 29.5 * mm, note)
    c.setFont("Helvetica", 7)
    c.drawRightString(W - 18 * mm, H - 18 * mm, "ISSF 10 m Air Pistol · true scale · A4")

def corner_marks(c):
    """Four registration ticks, so a later scan can be checked for scaling."""
    c.setStrokeColor(colors.black); c.setLineWidth(0.3)
    for x in (12 * mm, W - 12 * mm):
        for y in (12 * mm, H - 12 * mm):
            c.line(x - 3 * mm, y, x + 3 * mm, y)
            c.line(x, y - 3 * mm, x, y + 3 * mm)

# ---------------------------------------------------------------- the cards
#  (u, v) in millimetres from the centre; label; why it is there
CARDS = {
 "A": ("Geometry and edge cases",
       "Punch in the order listed, photographing after each one.",
   [(  0.0,   0.0, "A1", "dead centre — a 10, and the easiest possible case"),
    ( -1.8,  12.7, "A2", "inside the black — the case that was broken until 1.27"),
    (-38.6,   0.0, "A3", "ON the 6-ring line — scores 6 by gauge, 5 by centre alone"),
    ( 44.1,  62.5, "A4", "just inside the 1-ring"),
    (-68.3,  64.2, "A5", "outside the rings — a miss, must appear on the plot"),
    (  8.0, -25.0, "A6", "in the black, low contrast side"),
    (-95.0,   0.0, "A7", "far out, near the sheet edge — tests clipping")]),
 "B": ("Overlapping and near shots",
       "Pairs at decreasing separation. Punch both of a pair, then photograph.",
   [( -30.0,  30.0, "B1", "pair 1a"), ( -25.5,  30.0, "B2", "pair 1b — 4.5 mm apart, one gauge"),
    (  30.0,  30.0, "B3", "pair 2a"), (  33.1,  30.0, "B4", "pair 2b — 3.1 mm, 0.7 gauge"),
    ( -30.0, -30.0, "B5", "pair 3a"), ( -27.7, -30.0, "B6", "pair 3b — 2.3 mm, 0.5 gauge"),
    (  30.0, -30.0, "B7", "pair 4a"), (  32.0, -30.0, "B8", "pair 4b — 2.0 mm, touching")]),
 "C": ("Progressive string",
       "Ten shots in a plausible group. Photograph the blank card first, then after every punch.",
   [(  2.1,   3.4, "C1", ""), ( -1.5,   5.2, "C2", ""), (  4.0,  -2.2, "C3", ""),
    ( -3.8,  -1.1, "C4", ""), (  0.7,   8.9, "C5", ""), (  6.2,   4.5, "C6", ""),
    ( -5.1,   3.0, "C7", ""), (  1.9,  -6.4, "C8", ""), ( -2.4,  -7.8, "C9", ""),
    (  9.5,   1.2, "C10", "")]),
}


def instructions(c):
    L, T = 18 * mm, H - 20 * mm
    c.setFont("Helvetica-Bold", 16); c.setFillColor(colors.black)
    c.drawString(L, T, "STS test corpus — punched-card protocol")
    c.setFont("Helvetica", 9)
    y = T - 9 * mm
    def para(txt, bold=False, gap=5.2, size=9, indent=0):
        nonlocal y
        c.setFont("Helvetica-Bold" if bold else "Helvetica", size)
        for line in txt.split("\n"):
            c.drawString(L + indent, y, line); y -= gap * mm
        y -= 1.2 * mm
    para("Why this exists", True, size=11)
    para("The ranges are shut, but almost nothing in the planned test needed live fire. Punching a card\n"
         "reproduces the whole protocol indoors, and gives BETTER ground truth than a range would: every\n"
         "hole is at a coordinate you chose, known to a tenth of a millimetre, instead of one inferred\n"
         "afterwards. What it cannot reproduce is the appearance of a pellet strike — a punch tears the\n"
         "paper less and craters it not at all — so this tests geometry, registration, scale, tilt and the\n"
         "two-photograph method, not the fine detail of how a real hole looks.")
    para("What you need", True, size=11)
    para("A 4.5 mm leather or paper punch (a 4.5 mm drill bit turned by hand into a cork backing also\n"
         "works). A ruler with a millimetre scale. A phone. A flat surface with even light.")
    para("Printing — the one step that can quietly ruin everything", True, size=11)
    para("Print at 100 per cent. Switch OFF \u201cfit to page\u201d, \u201cshrink to fit\u201d and \u201cscale to paper size\u201d;\n"
         "these are on by default in most print dialogues and will scale the sheet by a few per cent.\n"
         "Then MEASURE THE SCALE BAR at the foot of each sheet. It must read 100.0 mm. If it reads\n"
         "anything else, write the figure on the sheet and tell me — one number rescales every coordinate,\n"
         "but only if I know it. A sheet printed at 97 per cent with nobody noticing is worse than no sheet.")
    para("Punching", True, size=11)
    para("Punch at the centre of each grey cross, in the order the labels give. The crosses are printed at\n"
         "0.15 mm and are invisible to the detector — this was checked, not assumed: the unpunched sheet\n"
         "was rendered at photographic resolution and the detector found zero holes on it.")
    para("Photographing", True, size=11)
    para("1.  Photograph the BLANK sheet before punching anything. This is the reference for the\n"
         "    two-photograph method, which is the most accurate path in the app and the least tested.\n"
         "2.  Photograph after EVERY punch, without moving the phone or the sheet between shots. A phone\n"
         "    on a small stand makes this easy and is worth the trouble.\n"
         "3.  Fill the frame with the sheet. Square on. Flat, even light, no hard shadow, no flash.\n"
         "4.  When the card is finished, take five or six more of the completed card at angles — roughly\n"
         "    10, 20 and 30 degrees, tilted and rotated — WITHOUT moving any shot. Same holes, different\n"
         "    viewpoints, so any change in the score is the app's error and nothing else.")
    para("Naming", True, size=11)
    para("A_00_blank.jpg, A_01.jpg, A_02.jpg \u2026 then A_angle_1.jpg and so on. Same for B and C.\n"
         "The number is how many holes are in the card, which is the ground truth for that picture.")
    para("What each card is for", True, size=11)
    para("A — geometry and edge cases. Seven holes chosen to be awkward: dead centre, inside the black,\n"
         "     straddling a ring line, just inside the outer ring, outside the rings, and near the sheet edge.\n"
         "B — overlapping shots. Four pairs at one, 0.7, 0.5 and 0.45 of a gauge apart. Where the app\n"
         "     splits one region into two it is making an inference, and this says when it should refuse.\n"
         "C — a progressive string of ten in a plausible group, for the difference method and for the\n"
         "     running-total behaviour.")
    para("If the punch wanders", True, size=11)
    para("It will, by a few tenths of a millimetre. That is fine and it is worth recording: if a hole is\n"
         "visibly off its cross, measure the offset with the ruler and note it. If it is not visibly off,\n"
         "leave it. The tolerance in the tests is a millimetre and a half.")
    c.showPage()

c = canvas.Canvas("/tmp/punch/STS_punch_sheets.pdf", pagesize=A4)
instructions(c)
for cid, (title, note, pts) in CARDS.items():
    cx, cy = W / 2.0, H / 2.0 + 6 * mm
    corner_marks(c)
    header(c, cid, title, note)
    face(c, cx, cy)
    for (u, v, lab, _why) in pts:
        cross(c, cx, cy, u, v, lab)
    scalebar(c, 18 * mm, 20 * mm)
    c.showPage()
c.save()
print("sheets written")
for cid,(t,n,pts) in CARDS.items():
    print(cid, t, len(pts), "marks")
