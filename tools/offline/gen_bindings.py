#!/usr/bin/env python3
"""Generate stub view-binding classes from the layouts.

The Android plugin generates one class per layout with a field per android:id,
typed by the widget. Those classes are what an activity talks to, so without
them nothing in an activity can be type-checked offline. Regenerating them
from the same layouts the plugin reads keeps the stub honest: a field that is
not in the layout is not in the stub either.
"""
import glob, os, re, sys

# XML tag -> the type the plugin would give the field
TYPES = {
    "TextView": "TextView", "Button": "Button", "EditText": "EditText",
    "ImageView": "ImageView", "ImageButton": "ImageButton", "Spinner": "Spinner",
    "CheckBox": "CheckBox", "RadioButton": "RadioButton", "SeekBar": "SeekBar",
    "ProgressBar": "ProgressBar", "ListView": "ListView", "ScrollView": "ScrollView",
    "LinearLayout": "LinearLayout", "FrameLayout": "FrameLayout",
    "RelativeLayout": "RelativeLayout", "TableLayout": "TableLayout",
    "TableRow": "TableRow", "GridLayout": "GridLayout", "Switch": "Switch",
    "View": "View", "Toolbar": "View", "include": "View", "merge": "View",
}

def type_for(tag):
    short = tag.split(".")[-1]
    return TYPES.get(short, short if short[0].isupper() else "View")

def binding_name(layout):
    return "".join(p.capitalize() for p in layout.split("_")) + "Binding"

def generate(layout_dir, out_path):
    out = ["package com.rfsat.sts.databinding", "",
           "import android.view.LayoutInflater", "import android.view.View",
           "import android.view.ViewGroup", "import android.widget.*",
           "import com.rfsat.sts.detect.RegistrationOverlayView",
           "import com.rfsat.sts.ui.CrosshairView",
           "import com.rfsat.sts.ui.ScoreHistogramView",
           "import com.rfsat.sts.ui.TargetPlotView",
           "import com.google.android.material.bottomnavigation.BottomNavigationView",
           "import androidx.camera.view.PreviewView",
           "import android.view.TextureView", ""]
    for path in sorted(glob.glob(os.path.join(layout_dir, "*.xml"))):
        layout = os.path.basename(path)[:-4]
        text = open(path, encoding="utf-8").read()
        # every element that declares an id, with its tag
        fields = {}
        for m in re.finditer(r'<([\w.]+)\b[^>]*?android:id="@\+id/(\w+)"', text, re.S):
            fields[m.group(2)] = type_for(m.group(1))
        # <include> pulls the included layout's ids into the same binding
        for inc in re.findall(r'<include[^>]*layout="@layout/(\w+)"', text):
            p2 = os.path.join(layout_dir, inc + ".xml")
            if os.path.exists(p2):
                t2 = open(p2, encoding="utf-8").read()
                for m in re.finditer(r'<([\w.]+)\b[^>]*?android:id="@\+id/(\w+)"', t2, re.S):
                    fields.setdefault(m.group(2), type_for(m.group(1)))
        cls = binding_name(layout)
        out.append(f"class {cls} private constructor(val root: View) {{")
        for name, t in sorted(fields.items()):
            out.append(f"    @JvmField val {name}: {t} = {t}(android.content.Context())")
        out.append("    companion object {")
        out.append(f"        @JvmStatic fun inflate(i: LayoutInflater): {cls} = {cls}(View())")
        out.append(f"        @JvmStatic fun inflate(i: LayoutInflater, p: ViewGroup?, a: Boolean): {cls} = {cls}(View())")
        out.append("    }")
        out.append("}")
        out.append("")
    open(out_path, "w", encoding="utf-8").write("\n".join(out))
    return len([l for l in out if l.startswith("class ")])

if __name__ == "__main__":
    n = generate(sys.argv[1], sys.argv[2])
    print(f"generated {n} binding stubs")
