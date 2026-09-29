package ru.lct.heat.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpHardeningFilter extends OncePerRequestFilter {
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final Semaphore importPermits;

    public HttpHardeningFilter(@Value("${app.max-concurrent-imports:2}") int maxConcurrentImports) {
        importPermits = new Semaphore(Math.max(1, maxConcurrentImports), true);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        addHeaders(request, response);
        if (!isImportUpload(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (!importPermits.tryAcquire()) {
            response.setStatus(429);
            response.setHeader("Retry-After", "10");
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"code\":\"IMPORT_BUSY\",\"message\":\"Retry later\"}");
            return;
        }
        try { chain.doFilter(request, response); }
        finally { importPermits.release(); }
    }

    private boolean isImportUpload(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // Слэш в конце всё равно попадает на тот же @PostMapping (сопоставление путей Spring по умолчанию к этому терпимо),
        // поэтому ограничитель параллелизма тоже должен его распознавать; иначе "/api/v1/imports/" полностью обходит лимит загрузок.
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        return "POST".equals(request.getMethod()) && "/api/v1/imports".equals(path);
    }

    private void addHeaders(HttpServletRequest request, HttpServletResponse response) {
        String supplied = request.getHeader("X-Request-ID");
        String requestId = supplied != null && SAFE_REQUEST_ID.matcher(supplied).matches()
            ? supplied : UUID.randomUUID().toString();
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "geolocation=(), camera=(), microphone=()");
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.setHeader("Cache-Control", "no-store");
        } else {
            // Интерфейс (index.html/app.js/app.css) переразворачивается часто; без этого эвристическое кэширование браузера
            // (без явного заголовка, только Last-Modified) может продолжать отдавать предыдущую сборку сессии, которая ни
            // разу не делала жёсткую перезагрузку. no-cache заставляет делать условный GET (If-Modified-Since) при каждом
            // переходе, поэтому редеплой становится заметен уже на следующей обычной перезагрузке, а не только после Ctrl+F5.
            response.setHeader("Cache-Control", "no-cache");
        }
    }
}
