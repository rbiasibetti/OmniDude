#!/usr/bin/env python3
"""Gera o manual navegável (HTML estático para o GitHub Pages) em docs/.

    python3 manual/build.py            gera
    python3 manual/build.py --check    só confere se docs/ está em dia com manual/src (sai com 1 se não estiver)

Cada arquivo de manual/src/NN-slug.html é um fragmento com um cabeçalho simples:

    ---
    title: Título da página
    group: Grupo do menu
    desc: Resumo de uma frase
    ---
    <h2>...</h2> ...

Atalhos aceitos no corpo:
    {{svg:nome|Legenda}}                   diagrama de manual/svg/nome.svg
    {{img:arquivo.jpg|Legenda}}            figura ampliável (docs/img/manual/arquivo.jpg)
    {{img:arquivo.jpg|Legenda|wide}}       figura que ocupa toda a largura
Os ids dos títulos h2/h3 são criados sozinhos. Só a biblioteca padrão do Python é usada.
"""
import filecmp, html, json, re, shutil, sys, tempfile, unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
ASSETS = ROOT / "assets"
OUT = ROOT.parent / "docs"
VERSION = "0.1.0"
SITE = "Manual do OmniDude"
GROUP_ORDER = ["Começando", "Usando", "Administração", "Referência"]


def slugify(text):
    t = unicodedata.normalize("NFKD", re.sub(r"<[^>]+>", "", text)).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", t.lower()).strip("-") or "secao"


def parse(path):
    raw = path.read_text(encoding="utf-8")
    m = re.match(r"---\n(.*?)\n---\n", raw, re.S)
    if not m:
        sys.exit(f"{path.name}: cabeçalho ausente")
    meta = dict(line.split(":", 1) for line in m.group(1).splitlines() if ":" in line)
    meta = {k.strip(): v.strip() for k, v in meta.items()}
    return meta, raw[m.end():]


def figure(m):
    parts = m.group(1).split("|")
    name, caption = parts[0].strip(), (parts[1].strip() if len(parts) > 1 else "")
    wide = len(parts) > 2 and parts[2].strip() == "wide"
    if not (OUT / "img" / "manual" / name).exists() and not (OUT / "img" / name).exists():
        sys.exit(f"imagem inexistente: {name}")
    rel = f"img/manual/{name}" if (OUT / "img" / "manual" / name).exists() else f"img/{name}"
    alt = html.escape(re.sub(r"<[^>]+>", "", caption), quote=True)
    cls = "figure wide" if wide else "figure"
    return (f'<figure class="{cls}"><a class="zoom" href="{rel}" title="Clique para ampliar">'
            f'<img src="{rel}" alt="{alt}" loading="lazy"></a><figcaption>{caption}</figcaption></figure>')


def diagram(m):
    parts = m.group(1).split("|")
    name, caption = parts[0].strip(), (parts[1].strip() if len(parts) > 1 else "")
    svg = (ROOT / "svg" / f"{name}.svg").read_text(encoding="utf-8").strip()
    label = html.escape(re.sub(r"<[^>]+>", "", caption), quote=True)
    svg = svg.replace("<svg ", f'<svg role="img" aria-label="{label}" ', 1)
    return f'<figure class="diagram">{svg}<figcaption>{caption}</figcaption></figure>'


def add_ids(body):
    """Dá id aos h2/h3 e um link de âncora; devolve o corpo e a lista de títulos."""
    used, heads = set(), []

    def repl(m):
        level, attrs, inner = m.group(1), m.group(2), m.group(3)
        fixed = re.search(r'id="([^"]+)"', attrs)
        sid = fixed.group(1) if fixed else slugify(inner)
        attrs = re.sub(r'\s*id="[^"]+"', "", attrs)
        base, n = sid, 2
        while sid in used and not fixed:
            sid, n = f"{base}-{n}", n + 1
        used.add(sid)
        heads.append((int(level), sid, re.sub(r"<[^>]+>", "", inner).strip()))
        return f'<h{level} id="{sid}"{attrs}>{inner}<a class="anchor" href="#{sid}" aria-label="Link para esta seção">#</a></h{level}>'

    return re.sub(r"<h([23])([^>]*)>(.*?)</h\1>", repl, body, flags=re.S), heads


def sections(body, page_title, page_url):
    """Quebra o corpo por título para o índice de busca."""
    out, pos, cur = [], 0, (page_title, page_url)
    for m in re.finditer(r'<h([23]) id="([^"]+)"[^>]*>(.*?)<a class="anchor".*?</h\1>', body, re.S):
        out.append((cur, body[pos:m.start()]))
        cur = (re.sub(r"<[^>]+>", "", m.group(3)).strip(), f"{page_url}#{m.group(2)}")
        pos = m.end()
    out.append((cur, body[pos:]))
    entries = []
    for (title, url), chunk in out:
        text = html.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", chunk))).strip()
        if text:
            entries.append({"p": page_title, "t": title, "u": url, "x": text[:900]})
    return entries


def nav_html(pages, current):
    groups = {g: [] for g in GROUP_ORDER}
    for p in pages:
        if p["layout"] != "home":
            groups.setdefault(p["group"], []).append(p)
    parts = [f'<a class="nav-home{" current" if current["layout"] == "home" else ""}" href="index.html">Início do manual</a>']
    for g in GROUP_ORDER:
        if not groups.get(g):
            continue
        parts.append(f'<div class="nav-group"><h3>{html.escape(g)}</h3><ul>')
        for p in groups[g]:
            cur = ' class="current" aria-current="page"' if p is current else ""
            parts.append(f'<li><a{cur} href="{p["file"]}"><span class="n">{p["num"]}</span>{html.escape(p["title"])}</a></li>')
        parts.append("</ul></div>")
    return "\n".join(parts)


TEMPLATE = (ASSETS / "template.html").read_text(encoding="utf-8")


def render(page, pages, idx):
    body = page["body"]
    chapters = [p for p in pages if p["layout"] != "home"]
    prev_next = ""
    if page["layout"] != "home":
        i = chapters.index(page)
        pv = chapters[i - 1] if i > 0 else None
        nx = chapters[i + 1] if i + 1 < len(chapters) else None
        prev_next = '<nav class="pager" aria-label="Navegação entre capítulos">'
        prev_next += (f'<a class="prev" href="{pv["file"]}"><small>Anterior</small>{html.escape(pv["title"])}</a>' if pv else "<span></span>")
        prev_next += (f'<a class="next" href="{nx["file"]}"><small>Próximo</small>{html.escape(nx["title"])}</a>' if nx else "<span></span>")
        prev_next += "</nav>"
    toc = "".join(f'<li class="l{lv}"><a href="#{sid}">{html.escape(tx)}</a></li>' for lv, sid, tx in page["heads"] if lv == 2 or lv == 3)
    toc_html = f'<aside class="toc" aria-label="Nesta página"><h4>Nesta página</h4><ul>{toc}</ul></aside>' if toc and page["layout"] != "home" else ""
    title = SITE if page["layout"] == "home" else f'{page["title"]} · {SITE}'
    head = "" if page["layout"] == "home" else (
        f'<header class="page-head"><p class="eyebrow">{html.escape(page["group"])} · capítulo {page["num"]}</p>'
        f'<h1>{html.escape(page["title"])}</h1><p class="lead">{html.escape(page["desc"])}</p></header>')
    out = (TEMPLATE.replace("{{TITLE}}", html.escape(title)).replace("{{DESC}}", html.escape(page["desc"], quote=True))
           .replace("{{NAV}}", nav_html(pages, page)).replace("{{HEAD}}", head).replace("{{BODY}}", body)
           .replace("{{PAGER}}", prev_next).replace("{{TOC}}", toc_html).replace("{{VERSION}}", VERSION)
           .replace("{{LAYOUT}}", page["layout"]))
    (OUT / page["file"]).write_text(out, encoding="utf-8")


def main():
    OUT.mkdir(exist_ok=True)
    (OUT / "assets").mkdir(exist_ok=True)
    pages = []
    for path in sorted(SRC.glob("*.html")):
        meta, body = parse(path)
        num, slug = re.match(r"(\d+)-(.*)\.html", path.name).groups()
        home = meta.get("layout") == "home"
        body = re.sub(r"\{\{img:([^}]+)\}\}", figure, body)
        body = re.sub(r"\{\{svg:([^}]+)\}\}", diagram, body)
        body, heads = add_ids(body)
        pages.append({"layout": "home" if home else "page", "num": f"{int(num):02d}", "file": "index.html" if home else f"{slug}.html",
                      "title": meta["title"], "group": meta.get("group", ""), "desc": meta.get("desc", ""), "body": body, "heads": heads})
    index = []
    for p in pages:
        render(p, pages, index)
        index += sections(p["body"], p["title"], p["file"])
    for f in ("manual.css", "manual.js", "favicon.svg"):
        shutil.copy(ASSETS / f, OUT / "assets" / f)
    # Como script (e não .json) para a busca funcionar também abrindo os arquivos direto do disco.
    (OUT / "assets" / "search-index.js").write_text("window.MANUAL_INDEX=" + json.dumps(index, ensure_ascii=False, separators=(",", ":")) + ";", encoding="utf-8")
    (OUT / ".nojekyll").write_text("", encoding="utf-8")
    nf = (ASSETS / "404.html").read_text(encoding="utf-8")
    (OUT / "404.html").write_text(nf, encoding="utf-8")
    print(f"{len(pages)} páginas, {len(index)} seções indexadas -> {OUT}")


def check():
    """Gera numa pasta temporária e compara com docs/: acusa páginas editadas à mão ou fontes não regeneradas."""
    global OUT
    real = OUT
    tmp = Path(tempfile.mkdtemp(prefix="manual-check-"))
    shutil.copytree(real / "img", tmp / "img")
    OUT = tmp
    main()
    diffs = [str(p.relative_to(tmp)) for p in sorted(tmp.rglob("*")) if p.is_file() and not p.is_relative_to(tmp / "img")
             and (not (real / p.relative_to(tmp)).exists() or not filecmp.cmp(p, real / p.relative_to(tmp), shallow=False))]
    shutil.rmtree(tmp, ignore_errors=True)
    if diffs:
        print("docs/ fora de dia com manual/src: rode `python3 manual/build.py`. Diferem:", ", ".join(diffs))
        sys.exit(1)
    print("docs/ está em dia com manual/src")


if __name__ == "__main__":
    check() if "--check" in sys.argv else main()
