# Instalação como serviço (systemd)

Para um servidor Linux com systemd. O serviço roda como o usuário sem privilégios `omnidude`, com o sistema de arquivos
somente leitura (exceto `/var/lib/omnidude`) e apenas a capacidade `CAP_NET_RAW`, usada pelo `ping` das checagens ICMP.
A análise `systemd-analyze security` dá exposição **1,7 (OK)**. O porquê de cada diretiva está em
[`packaging/systemd/omnidude.service`](packaging/systemd/omnidude.service).

## Requisitos

- Linux com systemd e **JRE 21** em `/usr/bin/java` (ex.: `sudo apt install openjdk-21-jre-headless`).
- O utilitário `ping` (`iputils-ping`).
- O jar compilado: `mvn clean package` gera `target/omnidude-<versão>.jar`.

## Instalar

```bash
sudo ./packaging/install.sh --start
```

O script é idempotente. Ele cria o usuário e o grupo `omnidude`, copia o jar para `/opt/omnidude/omnidude.jar`, instala a
unidade em `/etc/systemd/system/` e cria `/etc/omnidude/omnidude.env` (modo `0600`) **se ainda não existir**. Sem
`--start`, só habilita o serviço. Para usar outro jar: `sudo ./packaging/install.sh /caminho/omnidude.jar`.

| Caminho | Conteúdo | Dono / modo |
|---|---|---|
| `/opt/omnidude/omnidude.jar` | aplicação | root, 644 |
| `/etc/omnidude/omnidude.env` | configuração e segredos (`OMNIDUDE_*`, `JAVA_OPTS`) | root, 600 |
| `/var/lib/omnidude/` | banco, `secret.key`, backups | omnidude, 700 |
| logs | journal do systemd | `journalctl -u omnidude` |

## Primeiro acesso

Sem `OMNIDUDE_ADMIN_PASSWORD`, a senha inicial do `admin` aparece **uma vez** no log:

```bash
journalctl -u omnidude | grep -A1 "Primeiro acesso"
```

O sistema exige trocá-la no primeiro login. Por padrão o serviço escuta apenas em `127.0.0.1:8080`.

## Acessar de outras máquinas

Por padrão o OmniDude escuta só em `127.0.0.1`. Para abri-lo pelo IP da máquina há três caminhos:

| Opção | Como | Indicada quando |
|---|---|---|
| **A. Escutar na rede (HTTP)** | só `OMNIDUDE_BIND` | rede interna confiável, laboratório |
| **B. TLS no próprio OmniDude** | `OMNIDUDE_BIND` + `SERVER_SSL_*` | HTTPS sem instalar outro programa |
| **C. Proxy reverso com HTTPS** | nginx, Caddy ou Apache | vários usuários, certificado real; **recomendada** |

### A. Escutar na rede (HTTP)

`OMNIDUDE_BIND=0.0.0.0` atende por todas as interfaces IPv4; um IP específico (`192.168.1.10`) atende só aquela interface.

```bash
OMNIDUDE_BIND=0.0.0.0 java -jar target/omnidude-0.1.0-SNAPSHOT.jar      # jar direto
# systemd: edite /etc/omnidude/omnidude.env (OMNIDUDE_BIND=0.0.0.0) e reinicie
sudo systemctl restart omnidude
```

Abra `http://IP-DA-MAQUINA:8080` (`hostname -I` mostra o IP) e libere a porta no firewall, de preferência só para a sua rede
(`sudo ufw allow from 192.168.1.0/24 to any port 8080 proto tcp`). O que observar:

- o log registra o aviso “escuta em 0.0.0.0 sem TLS”: é esperado, e a senha e a sessão trafegam em **texto puro**;
- **não** ligue `OMNIDUDE_COOKIE_SECURE=true` com HTTP: o navegador descarta o cookie e o login não “pega”;
- a unidade systemd não permite portas abaixo de 1024 (sem `CAP_NET_BIND_SERVICE`): use uma porta alta;
- o link do mapa público sai com o endereço pelo qual você abriu o OmniDude;
- descoberta, SNMP e checagens são conexões de saída e não dependem disso.

### B. TLS no próprio OmniDude

Com um certificado PKCS12 (`.p12`):

```bash
keytool -genkeypair -alias omnidude -keyalg RSA -keysize 2048 -storetype PKCS12 \
        -keystore omnidude.p12 -storepass TROQUE-ESTA-SENHA -dname "CN=omnidude" -validity 365   # autoassinado, para teste
sudo install -m 0640 -o root -g omnidude omnidude.p12 /etc/omnidude/omnidude.p12
```

Em `/etc/omnidude/omnidude.env`:

```
OMNIDUDE_BIND=0.0.0.0
OMNIDUDE_PORT=8443
OMNIDUDE_COOKIE_SECURE=true
SERVER_SSL_ENABLED=true
SERVER_SSL_KEY_STORE=/etc/omnidude/omnidude.p12
SERVER_SSL_KEY_STORE_PASSWORD=TROQUE-ESTA-SENHA
SERVER_SSL_KEY_STORE_TYPE=PKCS12
```

Reinicie e acesse `https://IP-DA-MAQUINA:8443`. O cookie sai com `Secure` e o servidor envia HSTS. Um certificado renovado só
vale após reiniciar o serviço. Não use `OMNIDUDE_FORWARD_HEADERS` aqui (é só para proxy).

### C. Proxy reverso com HTTPS (recomendada)

Mantenha o OmniDude em `127.0.0.1` e ponha um proxy com TLS na mesma máquina. Em `/etc/omnidude/omnidude.env`:

```
OMNIDUDE_FORWARD_HEADERS=native
OMNIDUDE_COOKIE_SECURE=true
```

Depois `sudo systemctl restart omnidude`. Exemplo para o nginx (o proxy deve **substituir** os cabeçalhos
`X-Forwarded-*` e desligar o buffering do canal de eventos em tempo real):

```nginx
server {
    listen 443 ssl;
    server_name omnidude.exemplo.com;
    # ssl_certificate / ssl_certificate_key ...

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    location /api/stream {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
        proxy_read_timeout 1h;
    }
}
```

Se o proxy estiver em **outra** máquina, escute numa interface que ele alcance (`OMNIDUDE_BIND=IP`, firewall só para o proxy) e
defina `OMNIDUDE_TRUSTED_PROXIES` com o IP dele (expressão regular, ex.: `10\.0\.0\.5`). Não use
`OMNIDUDE_FORWARD_HEADERS=framework`: ele confia em qualquer origem. Veja [SEGURANCA.md](SEGURANCA.md).

## Checagens ICMP (ping) sob o isolamento

O OmniDude chama o `ping` do sistema. A unidade concede `CAP_NET_RAW` como capacidade ambiente, o que cobre as
distribuições cujo `ping` usa soquetes brutos. Se o ping falhar mesmo assim (`journalctl -u omnidude`), a alternativa é
liberar o ICMP sem raw socket para o grupo do serviço:

```bash
echo 'net.ipv4.ping_group_range = 0 2147483647' | sudo tee /etc/sysctl.d/90-ping.conf
sudo sysctl --system
```

Para testar na mão como o serviço enxerga o ping: `sudo -u omnidude ping -c1 192.168.0.1`.

## Atualizar

Compile o jar novo e rode o mesmo comando: ele troca o jar e reinicia o serviço se estiver ativo. Configuração e dados
não são tocados; as migrações do banco rodam sozinhas na partida.

```bash
mvn clean package && sudo ./packaging/install.sh
```

## Backup

Copie `/var/lib/omnidude` com o serviço parado, ou use os backups da própria interface (**Ajustes → Fazer backup agora**, gravados em
`/var/lib/omnidude/backups`). **A chave de criptografia não entra no backup**: guarde `secret.key` (ou o valor de
`OMNIDUDE_SECRET_KEY`) em local separado, senão as credenciais SNMP e dos canais ficam ilegíveis ao restaurar.

## Ajustes comuns

| Quero | Como |
|---|---|
| Mais memória | `JAVA_OPTS=-Xms256m -Xmx1g -XX:+ExitOnOutOfMemoryError` no `omnidude.env` |
| Outra porta | `OMNIDUDE_PORT=9090` |
| Ver o estado | `systemctl status omnidude` |
| Reiniciar | `sudo systemctl restart omnidude` |

## Remover

```bash
sudo ./packaging/uninstall.sh          # mantém dados e configuração
sudo ./packaging/uninstall.sh --purge  # apaga TUDO, inclusive o banco e a chave (espera 5 s)
```

## Validação feita

`systemd-analyze verify` sem avisos, `bash -n` nos scripts e `SystemdUnitTest` a cada build. **Não foi testado** instalar
e iniciar o serviço como root em um servidor real nem o `ping` sob `CAP_NET_RAW` efetivo; faça um teste de fumaça
(`systemctl status`, abrir a interface, criar um dispositivo e conferir o ping) antes de depender dele.
