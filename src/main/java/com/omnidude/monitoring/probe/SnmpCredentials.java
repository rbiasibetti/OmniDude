package com.omnidude.monitoring.probe;

/**
 * Credenciais já descriptografadas, só em memória. {@code v3} só é preenchido quando a versão é "3"; nos demais casos
 * a community vale.
 */
public record SnmpCredentials(String version, String community, int retries, V3 v3) {

    /** Usuário USM. Os protocolos usam os nomes do perfil (SHA, SHA256, SHA512 / AES128, AES192, AES256). */
    public record V3(String username, String securityLevel, String authProtocol, String authPassword,
                     String privProtocol, String privPassword) {

        public boolean authenticates() {
            return !"NO_AUTH_NO_PRIV".equals(securityLevel);
        }

        public boolean encrypts() {
            return "AUTH_PRIV".equals(securityLevel);
        }

        @Override
        public String toString() {
            return "V3[username=" + username + ", level=" + securityLevel + ", auth=" + authProtocol + ", priv="
                    + privProtocol + ", passwords=***]";
        }
    }

    public SnmpCredentials(String version, String community, int retries) {
        this(version, community, retries, null);
    }

    public boolean isV3() {
        return "3".equals(version);
    }

    @Override
    public String toString() {
        return "SnmpCredentials[version=" + version + ", community=***, retries=" + retries + ", v3=" + v3 + "]";
    }
}
