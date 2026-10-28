# Manual do OmniDude (fontes)

O manual publicado fica em [`docs/`](../docs) (HTML estático, pronto para o GitHub Pages). Esta pasta guarda as **fontes** e o gerador; não edite os arquivos de `docs/*.html` à mão.

```
manual/
├── build.py        gerador (só a biblioteca padrão do Python 3)
├── check.py        confere links, âncoras e imagens do docs/
├── src/            um fragmento HTML por capítulo: NN-slug.html
├── svg/            diagramas (inline nas páginas)
└── assets/         template.html, manual.css, manual.js, favicon.svg, 404.html
```

## Editar e gerar

```bash
# 1. edite um capítulo em manual/src/ (ou o CSS/JS em manual/assets/)
# 2. gere as páginas
python3 manual/build.py
# 3. confira os links, as âncoras e as imagens
python3 manual/check.py
# 4. (opcional) confirme que docs/ está em dia com as fontes
python3 manual/build.py --check
# 5. visualize
python3 -m http.server 8100 --directory docs      # http://127.0.0.1:8100
```

## Formato de um capítulo

```html
---
title: Título que aparece no menu
group: Usando            # Começando | Usando | Administração | Referência
desc: Uma frase que resume o capítulo (aparece no topo e na busca)
---
<h2>Seção</h2>
<p>…</p>
```

O número no nome do arquivo (`04-mapa.html`) define a ordem e o número do capítulo; o resto do nome vira o endereço (`mapa.html`).

| Recurso | Como escrever |
|---|---|
| Captura de tela ampliável | `{{img:arquivo.jpg\|Legenda}}` (arquivo em `docs/img/manual/`); `…\|Legenda\|wide` ocupa a largura toda |
| Diagrama | `{{svg:nome\|Legenda}}` (arquivo em `manual/svg/nome.svg`) |
| Avisos | `<div class="note">`, `tip`, `warn`, `danger` |
| Passos numerados | `<ol class="steps">` |
| Botão da interface | `<span class="ui">Iniciar varredura</span>` |
| Perfil | `<span class="tag admin">ADMIN</span>`, `<span class="tag viewer">VIEWER</span>` |
| Captura com números | `<div class="annotated">` com `<span class="dot" style="left:…;top:…">1</span>` e `<ol class="legend">` |

Os `id` dos títulos `h2` e `h3` são criados do texto (`Criar um canal` vira `#criar-um-canal`); use `<h2 id="meu-id">` para fixar um.

## Capturas de tela

As imagens de `docs/img/manual/` vieram de uma rede de laboratório simulada (agentes SNMP falsos, dados fictícios). Para refazer uma captura, suba o laboratório, abra a tela no tamanho 1440×900 e salve em JPEG (qualidade ~82) no mesmo nome: nenhuma página precisa mudar.

## Publicar no GitHub Pages

1. Envie o repositório com a pasta `docs/` (já contém o arquivo `.nojekyll`).
2. No GitHub: **Settings → Pages → Build and deployment → Source: Deploy from a branch**.
3. Escolha a branch (por exemplo `main`) e a pasta **`/docs`**, e salve.
4. O manual fica em `https://<usuario>.github.io/<repositorio>/`.

Não há dependências de CDN, fontes externas nem scripts de terceiros: o manual também abre direto do disco (`docs/index.html`), inclusive a busca.
