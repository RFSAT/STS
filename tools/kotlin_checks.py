#!/usr/bin/env python3
"""
Static checks for the semantic Kotlin mistakes that a resource-and-reference
gate cannot see.

WHY THIS EXISTS. Every one of these was found by the compiler in CI after
passing a full pass of the project's other checks, which verify resources,
binding ids, layout attributes, imports and brace balance. Those catch a lot
and were blind to all of this:

  * two companion objects in one class — legal-looking, and it makes every
    constant in the first one unresolvable, so the real error surfaces
    somewhere else entirely;
  * return@label naming a lambda that is no longer there, which is what a
    rename leaves behind;
  * a `when` over an enum that another module has since extended — the
    ported VTB catalogue covered VTB's three click units while this app has
    six.

Run from the project root. Exits non-zero on any finding, so it can gate a
build before the compiler is started.
"""
import glob, os, re, sys

def strip(src):
    out=[];i=0;n=len(src)
    while i<n:
        if src[i:i+2]=='//':
            j=src.find('\n',i); out.append('\n'*src.count('\n',i,j if j>=0 else n)); i=(j if j>=0 else n); continue
        if src[i:i+2]=='/*':
            j=src.find('*/',i+2); k=(j+2) if j>=0 else n; out.append('\n'*src.count('\n',i,k)); i=k; continue
        if src[i:i+3]=='"""':
            j=src.find('"""',i+3); k=(j+3) if j>=0 else n; out.append('\n'*src.count('\n',i,k)); i=k; continue
        c=src[i]
        if c=='`':
            j=src.find('`',i+1); i=(j+1) if j>=0 else n; out.append('X'); continue
        if c=='"':
            i+=1
            while i<n and src[i]!='"':
                if src[i]=='\\': i+=1
                i+=1
            i+=1; out.append('""'); continue
        out.append(c); i+=1
    return "".join(out)

problems=[]
files=glob.glob("app/src/main/java/**/*.kt",recursive=True)+glob.glob("app/src/test/**/*.kt",recursive=True)

# ---- 1. at most one companion object per class body ----
for f in files:
    code=strip(open(f).read())
    stack=[]        # one entry per open brace: the class name it opens, or None
    pending=None
    counts={}
    for m in re.finditer(r'\b(class|object|interface)\s+(\w+)|companion\s+object|\{|\}', code):
        t=m.group(0)
        if t.startswith(('class ','object ','interface ')):
            pending=m.group(2)
        elif t=='companion object':
            owner=next((x for x in reversed(stack) if x), None)
            key=(f,owner)
            counts[key]=counts.get(key,0)+1
            if counts[key]>1:
                line=code.count('\n',0,m.start())+1
                problems.append(f"{os.path.basename(f)}:{line}  {owner} has a second companion object")
            pending="<companion>"
        elif t=='{':
            stack.append(pending); pending=None
        elif t=='}':
            if stack: stack.pop()

# ---- 2. return@label must name an enclosing lambda ----
for f in files:
    code=strip(open(f).read())
    stack=[]; i=0
    labels_at=[]
    while i < len(code):
        c=code[i]
        if c=='{':
            before=code[max(0,i-80):i]
            m=re.search(r'([A-Za-z_]\w*)\s*(?:\([^()]*\))?\s*$', before)
            stack.append(m.group(1) if m else None)
        elif c=='}':
            if stack: stack.pop()
        elif code.startswith('return@', i):
            m=re.match(r'return@(\w+)', code[i:])
            if m:
                lbl=m.group(1)
                if lbl not in [s for s in stack if s]:
                    line=code.count('\n',0,i)+1
                    problems.append(f"{os.path.basename(f)}:{line}  return@{lbl} but the enclosing lambdas are {[s for s in stack if s][-3:]}")
        i+=1

# ---- 3. when over an enum must cover it, or have an else ----
enums={}
for f in files:
    src=open(f).read()
    for m in re.finditer(r'enum class (\w+)[^{]*\{(.*?)\n\}', src, re.S):
        name=m.group(1); body=m.group(2)
        members=re.findall(r'^\s*([A-Z][A-Z0-9_]*)\s*[(,;]', body, re.M)
        if members: enums[name]=set(members)
for f in files:
    code=strip(open(f).read())
    for m in re.finditer(r'when\s*\(([^()]*)\)\s*\{', code):
        start=m.end()-1; depth=0; j=start
        while j < len(code):
            if code[j]=='{': depth+=1
            elif code[j]=='}':
                depth-=1
                if depth==0: break
            j+=1
        body=code[start:j]
        if re.search(r'\belse\s*->', body): continue
        # a branch may list several members: "A.X, A.Y, A.Z ->"
        used=[]
        for branch in re.finditer(r'([^\n{}]+?)->', body):
            for t,v in re.findall(r'(\w+)\.([A-Z][A-Z0-9_]*)', branch.group(1)):
                used.append((t,v))
        if not used: continue
        types={t for t,_ in used}
        if len(types)!=1: continue
        t=types.pop()
        if t not in enums: continue
        covered={v for _,v in used}
        missing=enums[t]-covered
        if missing:
            line=code.count('\n',0,m.start())+1
            problems.append(f"{os.path.basename(f)}:{line}  when over {t} is missing {sorted(missing)} and has no else")

print(f"{len(files)} Kotlin files checked by the new semantic gates")
print(("PROBLEMS:\n  "+"\n  ".join(problems)) if problems else "No problems found.")
sys.exit(1 if problems else 0)
