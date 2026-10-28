package com.omnidude.web;

import com.omnidude.topology.MapPublicationService;
import com.omnidude.topology.MapPublicationService.PublicMap;
import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acesso público (sem login) a um mapa publicado: a página e os dados. Só GET, sem cookies, sem sessão, e com
 * cabeçalhos que impedem indexação, vazamento do token por Referer e execução de qualquer script que não seja nosso.
 */
@RestController
public class PublicMapController {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{20,64}");
    /**
     * O Cytoscape aplica um estilo inline fixo ao seu contêiner; em vez de liberar 'unsafe-inline' para todo estilo,
     * a política admite só esse texto, pelo hash (a versão da biblioteca é fixa em tools/package.json).
     */
    private static final String CYTOSCAPE_STYLE_HASH = "'sha256-pgvDUBa4IjFA2yuSJ2cqcyxmNYJMborsd0ORcRv9vw8='";
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-hashes' "
            + CYTOSCAPE_STYLE_HASH + "; img-src 'self' data:; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'";

    private final MapPublicationService service;
    private final PublicRateLimiter limiter;

    public PublicMapController(MapPublicationService service, PublicRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    /** A página é a mesma para qualquer token; quem valida o token é a API. */
    @GetMapping(value = "/public/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> page(@PathVariable String token, HttpServletRequest request) {
        if (!limiter.allow(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "60").build();
        }
        if (!TOKEN.matcher(token).matches()) {
            return ResponseEntity.notFound().build();
        }
        return headers(ResponseEntity.ok()).header("Content-Security-Policy", CSP)
                .contentType(MediaType.TEXT_HTML).body(new ClassPathResource("static/public.html"));
    }

    @GetMapping("/api/public/maps/{token}")
    public ResponseEntity<PublicMap> data(@PathVariable String token, HttpServletRequest request) {
        if (!limiter.allow(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header(HttpHeaders.RETRY_AFTER, "60").build();
        }
        if (!TOKEN.matcher(token).matches()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Mapa não encontrado");   // mesma resposta de um token inexistente
        }
        return headers(ResponseEntity.ok()).body(service.publicView(token));
    }

    private static ResponseEntity.BodyBuilder headers(ResponseEntity.BodyBuilder b) {
        return b.cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex, nofollow, noarchive")
                .header("Referrer-Policy", "no-referrer");
    }
}
