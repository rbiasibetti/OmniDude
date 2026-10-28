#!/usr/bin/env python3
"""Confere o manual gerado em docs/: links internos, âncoras, imagens e ids duplicados."""
import re, sys
from html.parser import HTMLParser
from pathlib import Path

DOCS = Path(__file__).resolve().parent.parent / "docs"

class P(HTMLParser):
    def __init__(self):
        super().__init__(); self.ids, self.links, self.imgs, self.dups = set(), [], [], []
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if "id" in a:
            if a["id"] in self.ids: self.dups.append(a["id"])
            self.ids.add(a["id"])
        if tag == "a" and a.get("href"): self.links.append(a["href"])
        if tag == "img" and a.get("src"): self.imgs.append(a["src"])
        if tag == "link" and a.get("href"): self.links.append(a["href"])
        if tag == "script" and a.get("src"): self.imgs.append(a["src"])

pages = {f.name: P() for f in sorted(DOCS.glob("*.html"))}
for name, p in pages.items():
    p.feed((DOCS / name).read_text(encoding="utf-8"))
bad = 0
for name, p in pages.items():
    for d in p.dups:
        print(f"{name}: id duplicado #{d}"); bad += 1
    for src in p.imgs:
        if not (DOCS / src).exists():
            print(f"{name}: arquivo ausente {src}"); bad += 1
    for href in p.links:
        if re.match(r"(https?:|mailto:|#$)", href): continue
        target, _, frag = href.partition("#")
        if target and not (DOCS / target).exists():
            print(f"{name}: link quebrado {href}"); bad += 1; continue
        if frag:
            ids = pages[target or name].ids if (target or name) in pages else set()
            if frag not in ids:
                print(f"{name}: âncora inexistente {href}"); bad += 1
unused = {f.name for f in (DOCS / "img" / "manual").glob("*.jpg")} - {s.split("/")[-1] for p in pages.values() for s in p.imgs}
for u in sorted(unused): print(f"aviso: imagem não usada img/manual/{u}")
print(f"{len(pages)} páginas conferidas, {bad} problema(s)")
sys.exit(1 if bad else 0)
