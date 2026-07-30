#!/usr/bin/env python3
"""Generate a stub R class from the resources, so activities that name a
layout, id, string, style, colour or drawable can be type-checked offline.

Only names that actually exist in res/ are emitted. An R that answered to
anything would resolve a typo as readily as a real name, which is the whole
failure this is meant to catch.
"""
import glob, os, re, sys

def collect(res_dir):
    out = {k: set() for k in ("layout","id","string","style","color","drawable","menu","mipmap","array")}
    for path in glob.glob(os.path.join(res_dir, "**", "*.xml"), recursive=True):
        kind = os.path.basename(os.path.dirname(path)).split("-")[0]
        name = os.path.basename(path)[:-4]
        if kind in ("layout","menu","drawable"):
            out[kind].add(name)
        text = open(path, encoding="utf-8").read()
        out["id"].update(re.findall(r'@\+id/(\w+)', text))
        for tag in ("string","color","style","array","integer","dimen"):
            key = tag if tag in out else None
            for m in re.finditer(r'<%s\s+name="([\w.]+)"' % tag, text):
                if key: out[key].add(m.group(1).replace(".", "_"))
        out["style"].update(s.replace(".", "_") for s in re.findall(r'<style\s+name="([\w.]+)"', text))
    for d in glob.glob(os.path.join(res_dir, "mipmap*", "*")) + glob.glob(os.path.join(res_dir, "drawable*", "*")):
        base = os.path.basename(d)
        stem = base.rsplit(".", 1)[0]
        out["mipmap" if "mipmap" in d else "drawable"].add(stem)
    return out

def generate(res_dir, out_path):
    names = collect(res_dir)
    lines = ["package com.rfsat.sts", "", "object R {"]
    for kind in sorted(names):
        lines.append(f"    object {kind} {{")
        for n in sorted(names[kind]):
            if n and (n[0].isalpha() or n[0] == "_"):
                lines.append(f"        const val {n}: Int = 0")
        lines.append("    }")
    lines.append("}")
    open(out_path, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return sum(len(v) for v in names.values())

if __name__ == "__main__":
    print(f"generated R with {generate(sys.argv[1], sys.argv[2])} names")
