# Revisão de segurança — OmniDude NMS

Relatório da revisão feita na Fase 6 (hardening). Cobre o código, as dependências, a configuração padrão e o empacotamento
systemd. A política para reportar vulnerabilidades está em [SECURITY.md](SECURITY.md); a instalação endurecida, em
[INSTALL.md](INSTALL.md).

## Escopo e método

- **Código:** autenticação, sessão, CSRF, autorização de todas as rotas, validação de entrada, SQL, saída HTML, segredos,
  arquivos, rede (SNMP, HTTP, SMTP, webhook, Telegram) e o mapa público.
- **Dependências:** versões do Spring Boot, SQLite JDBC e SNMP4J.
- **Configuração:** valores padrão (porta, bind, cookies, cabeçalhos) e o que muda atrás de proxy reverso.
- **Empacotamento:** unidade systemd (`systemd-analyze security`: exposição 1,7 — OK).
- **Método:** leitura do código, testes automatizados que passam a rodar em todo build (ver "Proteções que o build
  garante") e execução do jar para conferir cabeçalhos, cookies e permissões de arquivo.

**Não coberto:** teste de intrusão externo, fuzzing de protocolos (SNMP/CDP/MNDP) e validação contra equipamentos reais.
A interface (JavaScript) não tem testes automatizados.

## Resultado

Nenhuma falha de injeção de SQL, XSS armazenado/refletido ou falta de autorização foi encontrada. Os achados abaixo eram
endurecimento e robustez, e foram corrigidos.

| # | Achado | Severidade | Situação |
|---|--------|-----------|----------|
| 1 | Spring Boot 3.3.x fora de suporte (sem correções de segurança) | Alta | Corrigido: 3.5.16, SQLite JDBC 3.53.4.0, SNMP4J 3.13.1 |
| 2 | Bloqueio de login por usuário permitia negar acesso ao admin de qualquer lugar (DoS) e crescia sem limite na memória | Média | Corrigido: bloqueio por par usuário+IP, limite total por usuário, janela com expiração, teto de entradas, ignora usuários inexistentes |
| 3 | `secret.key` criada com permissão padrão (644) por um instante antes do `chmod` | Média | Corrigido: criada já com `rw-------` |
| 4 | Banco, WAL, chave e backups legíveis por outros usuários da máquina | Média | Corrigido: diretório 700, arquivos 600, reaplicado a cada início (`SecurityStartupCheck`) |
| 5 | Sem CSP, `Referrer-Policy`, `Permissions-Policy` nem HSTS | Média | Corrigido (ver abaixo) |
| 6 | Sem opção de cookie `Secure` nem de proxy reverso confiável; rate limit e bloqueio usariam o IP do proxy | Média | Corrigido: `OMNIDUDE_COOKIE_SECURE`, `OMNIDUDE_FORWARD_HEADERS=native`, `OMNIDUDE_TRUSTED_PROXIES` |
| 7 | Conexões SSE ilimitadas (esgotamento de memória/threads por usuário autenticado) | Baixa | Corrigido: teto de 500, 503 acima disso |
| 8 | JSON malformado ou enum desconhecido respondia 200 vazio | Baixa | Corrigido: 400 |
| 9 | Escuta em todas as interfaces por padrão | Média | Corrigido: padrão `127.0.0.1`; aviso no log ao expor sem TLS/proxy |

## O que já estava correto

- Senhas com BCrypt; troca obrigatória no primeiro acesso quando a senha inicial é gerada.
- Sessão `HttpOnly` + `SameSite=Strict`; CSRF por cookie duplo (`XSRF-TOKEN` / `X-XSRF-TOKEN`) em toda escrita.
- Papéis ADMIN/VIEWER aplicados no servidor. Rotas só-ADMIN: usuários, configurações, canais e regras de notificação,
  publicação de mapa, backups, manutenção.
- Segredos (comunidades/credenciais SNMP, SMTP, tokens) cifrados com AES-256-GCM (`SecretCipher`); a chave não entra nos
  backups e as APIs nunca devolvem o segredo.
- SQL somente parametrizado (JPA/JdbcTemplate); nenhuma concatenação de entrada em consultas.
- Mapa público: a redução dos dados é feita **no servidor** (só nome, tipo, posição, estado e enlaces); a página pública
  não consegue obter mais do que a API entrega.
- Interface sem `innerHTML` com dados do usuário (Alpine `x-text`), então nomes de dispositivos não viram HTML.

## Cabeçalhos de segurança

```
Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-eval'; style-src 'self' 'unsafe-inline';
  img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Strict-Transport-Security: max-age=31536000   (somente em respostas HTTPS)
```

## Proteções que o build garante

Testes que falham o build se a postura regredir:

- `SecurityPostureTest` — varre **todas** as rotas mapeadas (por reflexão) e confere autorização: anônimo, VIEWER e
  ADMIN. Uma rota nova sem regra é detectada. Confere também os cabeçalhos acima.
- `LoginAttemptServiceTest` — bloqueio, decaimento, teto por usuário e limite de memória.
- `FilePermissionsTest` — criação de arquivos/diretórios só do dono.
- `SseHubTest` — teto de conexões em tempo real.
- `SystemdUnitTest` — as diretivas de isolamento da unidade systemd.

## Riscos aceitos (e como mitigar)

| Risco | Por que fica | Mitigação |
|-------|-------------|-----------|
| CSP precisa de `'unsafe-eval'` | A build padrão do Alpine.js avalia expressões com `new Function` | Sem conteúdo de terceiros e sem `innerHTML` com dados; trocar para a build CSP do Alpine exigiria reescrever os templates |
| SSRF por quem é ADMIN: webhook, Telegram, checagem HTTP e SMTP aceitam qualquer destino | São recursos do produto, definidos só por administradores | Dar o papel ADMIN só a pessoas de confiança; isolar a máquina por firewall de saída se necessário |
| Expressões regulares do `fingerprints.yml` (ReDoS) | O arquivo é escrito pelo administrador do servidor | Arquivo em `/var/lib/omnidude`, modo 600 |
| Bloqueio de login e limite de taxa ficam na memória e zeram ao reiniciar | Evita estado extra no banco | Proxy com limite de requisições na frente, se exposto à internet |
| Atrás de proxy, rate limit e bloqueio dependem do IP repassado | Só é confiável se o proxy for confiável | `OMNIDUDE_FORWARD_HEADERS=native` com `OMNIDUDE_TRUSTED_PROXIES` restrito; nunca `framework` |
| O link do mapa público é uma credencial ao portador (sem senha nem expiração) | Foi o desenho pedido | Quem tem o link vê o mapa reduzido; despublique ou gere um novo link ao suspeitar de vazamento |
| A senha inicial do admin gerada aparece uma vez no log (journal) | Necessária para o primeiro acesso | O sistema exige a troca no primeiro login; ou defina `OMNIDUDE_ADMIN_PASSWORD` no arquivo de ambiente (modo 600) e troque-a depois |
| Backups não incluem a chave de criptografia | Evita que um backup roubado exponha os segredos | Guarde `secret.key` / `OMNIDUDE_SECRET_KEY` em local separado |
| SNMP v1/v2c em texto claro | Limitação do protocolo | Use SNMPv3 (authPriv) sempre que o equipamento suportar; SHA-1 está registrado apenas por compatibilidade |
| A plataforma é de monitoramento: HTTP puro sem proxy | O padrão é local (127.0.0.1) | Exponha só atrás de proxy com HTTPS (veja abaixo) |

## Recomendações de implantação

1. **Instale pelo systemd** (`packaging/install.sh`): usuário sem privilégios, dados privados, sistema de arquivos somente
   leitura, apenas `CAP_NET_RAW`.
2. **Mantenha o bind em `127.0.0.1`** e publique por um proxy reverso com HTTPS (nginx, Caddy, Apache) na mesma máquina.
3. No arquivo `/etc/omnidude/omnidude.env`:
   ```
   OMNIDUDE_FORWARD_HEADERS=native
   OMNIDUDE_COOKIE_SECURE=true
   ```
   e, se o proxy não for local, `OMNIDUDE_TRUSTED_PROXIES` com o IP dele (expressão regular).
4. O proxy deve **substituir** (não anexar) `X-Forwarded-For` e `X-Forwarded-Proto`, e repassar `Upgrade`/buffering
   desligado para `/api/stream` (SSE).
5. Defina `OMNIDUDE_ADMIN_PASSWORD`, guarde a chave de criptografia à parte e agende cópia dos backups.
6. Use SNMPv3 e contas de leitura (`read-only`) nos equipamentos.
7. Atualize as dependências a cada release (`mvn versions:display-dependency-updates`) e acompanhe os avisos do Spring Boot.
