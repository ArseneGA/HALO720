"""Petits outils pour lire les symboles KiCad (texte brut + broches)."""
import re

def extract_symbol(path, name):
    txt = open(path, encoding="utf-8").read()
    for key in ('\n  (symbol "%s"' % name, '\n\t(symbol "%s"' % name):
        i = txt.find(key)
        if i >= 0:
            break
    else:
        raise KeyError(name)
    i += 1
    while txt[i] in " \t":
        i += 1
    depth = 0
    j = i
    while True:
        c = txt[j]
        if c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return txt[i:j + 1]
        elif c == '"':
            j += 1
            while txt[j] != '"':
                if txt[j] == '\\':
                    j += 1
                j += 1
        j += 1

PIN_RE = re.compile(r'\(pin (\w+) (\w+)\s*\(at ([-\d.]+) ([-\d.]+)(?: ([-\d.]+))?\)\s*\(length ([-\d.]+)\)(.*?)\(name "([^"]*)".*?\(number "([^"]*)"', re.S)

def pins(symtxt):
    out = []
    for m in PIN_RE.finditer(symtxt):
        typ, style, x, y, a, ln, mid, name, num = m.groups()
        out.append(dict(type=typ, x=float(x), y=float(y), a=float(a or 0), len=float(ln), name=name, num=num, hide='hide' in mid.split('(name')[0]))
    return out
