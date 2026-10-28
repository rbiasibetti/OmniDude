package com.omnidude;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnidude.security.Role;
import com.omnidude.security.User;
import com.omnidude.security.UserRepository;
import java.io.IOException;
import java.net.DatagramSocket;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Atalhos comuns dos testes de API: login como admin, JSON de ida e volta, criação de dispositivos e perfis SNMP. */
public abstract class ApiTestSupport extends IntegrationTestBase {

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected UserRepository users;
    @Autowired protected PasswordEncoder encoder;

    protected MockHttpSession admin;

    @BeforeEach
    void loginAsAdmin() throws Exception {
        admin = login("admin", "Test-Pass-12345");
    }

    protected MockHttpSession login(String user, String pass) throws Exception {
        return (MockHttpSession) mvc.perform(post("/login").with(csrf()).param("username", user).param("password", pass))
                .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }

    protected MockHttpSession viewer() throws Exception {
        if (users.findByUsername("viewer-fase3").isEmpty()) {
            User u = new User();
            u.setUsername("viewer-fase3");
            u.setPasswordHash(encoder.encode("Viewer3-Pass-123"));
            u.setRole(Role.VIEWER);
            u.setCreatedAt(Instant.now().getEpochSecond());
            users.save(u);
        }
        return login("viewer-fase3", "Viewer3-Pass-123");
    }

    protected ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected JsonNode read(ResultActions a) throws Exception {
        // application/json não declara charset e o MockMvc assume ISO-8859-1; JSON é UTF-8 (como o navegador lê).
        return json.readTree(a.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    protected JsonNode getJson(String url) throws Exception {
        return read(mvc.perform(get(url).session(admin)).andExpect(status().isOk()));
    }

    protected JsonNode createDevice(String name, String ip, String type) throws Exception {
        return read(postJson("/api/devices", Map.of("name", name, "primaryIp", ip, "deviceType", type))
                .andExpect(status().isCreated()));
    }

    protected JsonNode createProfile(String name, String community, int port) throws Exception {
        return read(postJson("/api/snmp-profiles", Map.of("name", name, "version", "2c", "community", community,
                "port", port, "retries", 0, "timeoutMs", 300)).andExpect(status().isCreated()));
    }

    protected static int freeUdpPort() throws IOException {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }
}
