package com.omnidude.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Derruba a sessão de quem foi desativado, apagado ou teve o perfil ou a senha alterados por um administrador. */
class SessionRevocationFilter extends OncePerRequestFilter {

    private final SessionRevocation revocation;

    SessionRevocationFilter(SessionRevocation revocation) {
        this.revocation = revocation;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = req.getSession(false);
        if (session != null && auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && revocation.isRevoked(auth.getName(), session.getCreationTime())) {
            session.invalidate();
            SecurityContextHolder.clearContext();
            res.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        chain.doFilter(req, res);
    }
}
