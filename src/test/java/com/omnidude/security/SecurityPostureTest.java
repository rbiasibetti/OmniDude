package com.omnidude.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.omnidude.ApiTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Revisão de segurança que roda a cada build: autorização de TODAS as rotas (descobertas por reflexão, então uma rota nova
 * sem regra não passa despercebida), cabeçalhos de segurança e comportamento sem sessão.
 */
class SecurityPostureTest extends ApiTestSupport {

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    /** Rotas abertas de propósito (visão pública do mapa e a página de erro). */
    private static final List<String> PUBLIC = List.of("/public/{token}", "/api/public/maps/{token}", "/error");
    /** Só o ADMIN, até para ler. */
    private static final List<String> ADMIN_READS = List.of("/api/notification-channels", "/api/notification-rules",
            "/api/notification-log", "/api/settings", "/api/users", "/api/admin/backups", "/api/maps/{id}/publication");
    /** O VIEWER pode alterar isto (a própria senha) e só isto. */
    private static final List<String> VIEWER_MAY_WRITE = List.of("/api/me/password");

    private record Route(String pattern, String method) {
        String path() {
            return pattern.replaceAll("\\{[^}]+}", "1");
        }

        boolean isAdminRead() {
            return method.equals("GET") && ADMIN_READS.stream().anyMatch(p -> pattern.equals(p) || pattern.startsWith(p + "/"));
        }
    }

    private List<Route> routes() {
        java.util.ArrayList<Route> out = new java.util.ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            var patterns = e.getKey().getPathPatternsCondition().getPatternValues();
            var methods = e.getKey().getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (methods.isEmpty()) {
                    out.add(new Route(p, "GET"));
                }
                methods.forEach(m -> out.add(new Route(p, m.name())));
            }
        }
        return out;
    }

    private RequestBuilder request(Route r, org.springframework.mock.web.MockHttpSession session) {
        var b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(r.method()), r.path());
        if (!r.method().equals("GET")) {
            b = b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return session == null ? b : b.session(session);
    }

    @Test
    void everyRouteRejectsAnonymousVisitorsUnlessItIsDeliberatelyPublic() throws Exception {
        int checked = 0;
        for (Route r : routes()) {
            if (PUBLIC.contains(r.pattern())) {
                continue;
            }
            int st = mvc.perform(request(r, null)).andReturn().getResponse().getStatus();
            assertThat(st).as("anônimo em %s %s".formatted(r.method(), r.pattern())).isIn(401, 302);
            checked++;
        }
        assertThat(checked).as("o teste precisa estar mesmo percorrendo as rotas").isGreaterThan(60);
    }

    @Test
    void viewersCanNeverWriteAndCannotReadAdministration() throws Exception {
        var viewer = viewer();
        int writes = 0;
        int adminReads = 0;
        for (Route r : routes()) {
            if (PUBLIC.contains(r.pattern()) || r.pattern().equals("/api/stream")) {
                continue;
            }
            int st = mvc.perform(request(r, viewer)).andReturn().getResponse().getStatus();
            if (!r.method().equals("GET") && !VIEWER_MAY_WRITE.contains(r.pattern())) {
                assertThat(st).as("VIEWER escrevendo em %s %s".formatted(r.method(), r.pattern())).isEqualTo(403);
                writes++;
            } else if (r.isAdminRead()) {
                assertThat(st).as("VIEWER lendo %s".formatted(r.pattern())).isEqualTo(403);
                adminReads++;
            } else {
                assertThat(st).as("VIEWER em %s %s deveria chegar ao controlador".formatted(r.method(), r.pattern()))
                        .isNotIn(401, 403);
            }
        }
        assertThat(writes).isGreaterThan(35);
        assertThat(adminReads).isGreaterThanOrEqualTo(7);
    }

    @Test
    void theStreamRequiresALogin() throws Exception {
        mvc.perform(get("/api/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminPagesCarryTheSecurityHeaders() throws Exception {
        MockHttpServletResponse r = mvc.perform(get("/login.html")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getHeader("Content-Security-Policy")).contains("default-src 'self'").contains("object-src 'none'")
                .contains("frame-ancestors 'none'").contains("base-uri 'none'").doesNotContain("*");
        assertThat(r.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(r.getHeader("X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.getHeader("Permissions-Policy")).contains("camera=()").contains("geolocation=()");
        assertThat(r.getHeader("Strict-Transport-Security")).as("HSTS só em HTTPS").isNull();

        MockHttpServletResponse https = mvc.perform(get("/login.html").secure(true)).andReturn().getResponse();
        assertThat(https.getHeader("Strict-Transport-Security")).contains("max-age=31536000").doesNotContain("includeSubDomains");
    }

    @Test
    void thePublicPageKeepsItsOwnStricterPolicyAndApiErrorsLeakNothing() throws Exception {
        long map = read(postJson("/api/maps", Map.of("name", "Seguranca-" + System.nanoTime())).andExpect(status().isCreated())).get("id").asLong();
        String path = read(putJson("/api/maps/" + map + "/publication", Map.of("published", true)).andExpect(status().isOk())).get("path").asText();
        MockHttpServletResponse page = mvc.perform(get(path).with(r -> {
            r.setRemoteAddr("198.51.100.200");
            return r;
        })).andReturn().getResponse();
        assertThat(page.getHeader("Content-Security-Policy")).contains("default-src 'none'").doesNotContain("unsafe-eval");

        // erro de API: sem stack trace, sem nomes de classe
        String err = mvc.perform(post("/api/devices").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{nome:"))
                .andReturn().getResponse().getContentAsString();
        assertThat(err).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("org.springframework");
        String notFound = mvc.perform(get("/api/devices/99999999").session(admin)).andReturn().getResponse().getContentAsString();
        assertThat(notFound).doesNotContain("Exception").doesNotContain("at com.");
    }

    @Test
    void loginDoesNotRevealWhetherTheUserExistsAndLocksOnlyTheAttackingSource() throws Exception {
        for (String user : new String[] {"admin", "usuario-que-nao-existe"}) {
            var r = mvc.perform(post("/login").with(csrf()).param("username", user).param("password", "senha-errada-123")).andReturn().getResponse();
            assertThat(r.getStatus()).as(user).isEqualTo(401);
            assertThat(r.getContentAsString()).doesNotContain("not found").doesNotContain("encontrado");
        }
        // 5 falhas de um IP trancam admin para ele, mas o administrador legítimo, de outro IP, ainda entra
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/login").with(csrf()).with(from("203.0.113.7")).param("username", "admin").param("password", "errada-" + i + "-xxxx"))
                    .andReturn();
        }
        mvc.perform(post("/login").with(csrf()).with(from("203.0.113.7")).param("username", "admin").param("password", "Test-Pass-12345"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/login").with(csrf()).with(from("203.0.113.8")).param("username", "admin").param("password", "Test-Pass-12345"))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }
}
