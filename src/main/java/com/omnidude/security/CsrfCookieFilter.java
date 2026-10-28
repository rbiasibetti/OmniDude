package com.omnidude.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/** O token CSRF é carregado de forma preguiçosa; forçar a leitura garante o cookie XSRF-TOKEN para a SPA. */
class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
        String path = req.getRequestURI();
        boolean publicView = path.startsWith("/public/") || path.startsWith("/api/public/");
        if (token != null && !publicView) {                     // a visão pública não recebe cookie nenhum
            token.getToken();
        }
        chain.doFilter(req, res);
    }
}
