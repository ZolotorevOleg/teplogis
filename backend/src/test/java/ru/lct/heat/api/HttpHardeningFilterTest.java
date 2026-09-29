package ru.lct.heat.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class HttpHardeningFilterTest {
    @Test void addsOperationalHeadersAndPreservesSafeRequestId() throws Exception {
        HttpHardeningFilter filter = new HttpHardeningFilter(2);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/imports/one");
        request.addHeader("X-Request-ID", "load-test-42");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals("load-test-42", response.getHeader("X-Request-ID"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test void staticUiFilesAreServedNoCacheSoARedeployIsSeenOnTheNextOrdinaryReload() throws Exception {
        HttpHardeningFilter filter = new HttpHardeningFilter(2);
        for (String path : new String[]{"/", "/index.html", "/app.js", "/app.css"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", path), response, (req, res) -> { });
            assertEquals("no-cache", response.getHeader("Cache-Control"), path);
        }
    }

    @Test void rejectsThirdUploadBeforeItReachesController() throws Exception {
        HttpHardeningFilter filter = new HttpHardeningFilter(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        FilterChain blocking = (req, res) -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        Future<?> first = executor.submit(() -> run(filter, blocking));
        Future<?> second = executor.submit(() -> run(filter, blocking));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(uploadRequest(), rejected, (req, res) -> fail("controller must not run"));

        assertEquals(429, rejected.getStatus());
        assertEquals("10", rejected.getHeader("Retry-After"));
        assertTrue(rejected.getContentAsString().contains("IMPORT_BUSY"));
        release.countDown();
        first.get(2, TimeUnit.SECONDS);
        second.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();
    }

    // A trailing slash is a different string but the very same @PostMapping, so it must count against the same limiter
    // instead of walking straight past it.
    @Test void aTrailingSlashOnTheUploadPathStillCountsAgainstTheLimiter() throws Exception {
        HttpHardeningFilter filter = new HttpHardeningFilter(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FilterChain blocking = (req, res) -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> first = executor.submit(() -> run(filter, blocking));
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/imports/"), rejected, (req, res) -> fail("controller must not run"));
        assertEquals(429, rejected.getStatus());

        release.countDown();
        first.get(2, TimeUnit.SECONDS);
        executor.shutdownNow();
    }

    private static void run(HttpHardeningFilter filter, FilterChain chain) {
        try { filter.doFilter(uploadRequest(), new MockHttpServletResponse(), chain); }
        catch (Exception e) { throw new CompletionException(e); }
    }

    private static MockHttpServletRequest uploadRequest() {
        return new MockHttpServletRequest("POST", "/api/v1/imports");
    }
}
