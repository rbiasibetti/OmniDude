# Política de segurança

## Versões com correções

O OmniDude ainda está na série 0.x: apenas a versão mais recente da branch principal recebe correções de segurança.

## Como reportar uma vulnerabilidade

**Não abra uma issue pública** para falhas de segurança.

Use o recurso de **relato privado de vulnerabilidades do GitHub**: na página do repositório, aba **Security** →
**Report a vulnerability**. Descreva:

- o que foi encontrado e o impacto;
- passos para reproduzir (versão, configuração, requisição de exemplo);
- se possível, uma sugestão de correção.

Você receberá uma resposta de confirmação em alguns dias. Depois da correção, o relato é divulgado em um aviso de segurança
do repositório, com crédito a quem reportou (se desejar).

## O que está no escopo

Autenticação, sessão, autorização, injeção, exposição de segredos, o mapa público e o empacotamento (systemd). Os riscos já
conhecidos e aceitos estão listados em [SEGURANCA.md](SEGURANCA.md).

## Fora do escopo

Ações que exigem o papel ADMIN já concedido (por exemplo, apontar um webhook para um endereço interno), ataques que
dependem de acesso ao sistema de arquivos do servidor, e equipamentos ou serviços de terceiros monitorados.
