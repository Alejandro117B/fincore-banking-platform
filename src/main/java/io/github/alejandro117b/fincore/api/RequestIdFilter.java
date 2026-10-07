package io.github.alejandro117b.fincore.api;

import java.io.IOException;
import java.util.UUID;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".id";

    static String id(HttpServletRequest request) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing != null) { return existing.toString(); }
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        return id;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = id(request);
        String previous = MDC.get("requestId");
        MDC.put("requestId", id);
        response.setHeader("X-Request-Id", id);
        if (request.getRequestURI().startsWith("/api/v1")) {
            response.setHeader("Cache-Control", "no-store");
        }
        try {
            chain.doFilter(request, response);
        } finally {
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            LOG.info("HTTP method={} route={} status={} requestId={}", request.getMethod(),
                    route == null ? "/unmatched" : route, response.getStatus(), id);
            if (previous == null) { MDC.remove("requestId"); } else { MDC.put("requestId", previous); }
        }
    }
}
