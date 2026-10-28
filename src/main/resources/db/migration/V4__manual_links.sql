-- Fase 3.6 (enlaces manuais e nós lógicos)

-- Natureza do enlace, escolhida pelo usuário: CABLE, WIFI, VPN, INTERNET ou OTHER (validada pela aplicação).
-- Serve para desenhar o enlace de forma diferente e para ligar dispositivos externos (DNS, VPN, nuvem).
ALTER TABLE links ADD COLUMN kind TEXT;
