package com.example.AviaryService.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

// The CIMD fetch takes a URL from whoever starts a sign-in, so these pin down
// the SSRF guard: what it refuses before connecting, and what it refuses in
// the response.
class CimdFetcherTest {

    private static final String URL = "https://assistant.example.com/oauth/client.json";
    private static final InetAddress PUBLIC = addr("93.184.216.34");

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private static InetAddress addr(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CimdFetcher fetcher(InetAddress resolved, String body) {
        return new CimdFetcher(host -> new InetAddress[] { resolved },
            uri -> body.getBytes(StandardCharsets.UTF_8));
    }

    private static String doc(String clientId) {
        return "{\"client_id\":\"" + clientId + "\",\"client_name\":\"Test Assistant\","
            + "\"redirect_uris\":[\"http://localhost/callback\"]}";
    }

    @Test
    void acceptsValidDocument() {
        CimdFetcher.ClientMetadata m = fetcher(PUBLIC, doc(URL)).fetch(URL);
        assertEquals("Test Assistant", m.clientName());
        assertEquals(List.of("http://localhost/callback"), m.redirectUris());
    }

    @Test
    void rejectsHttpUrl() {
        String url = "http://assistant.example.com/client.json";
        assertThrows(IllegalArgumentException.class, () -> fetcher(PUBLIC, doc(url)).fetch(url));
    }

    @Test
    void rejectsUrlWithoutPath() {
        String url = "https://assistant.example.com/";
        assertThrows(IllegalArgumentException.class, () -> fetcher(PUBLIC, doc(url)).fetch(url));
    }

    @Test
    void rejectsIpLiteralHosts() {
        for (String url : List.of("https://93.184.216.34/client.json", "https://[2001:db8::1]/client.json")) {
            assertThrows(IllegalArgumentException.class, () -> fetcher(PUBLIC, doc(url)).fetch(url), url);
        }
    }

    @Test
    void rejectsHostsResolvingToPrivateAddresses() {
        for (String ip : List.of("127.0.0.1", "10.0.0.5", "172.16.0.1", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fe80::1", "fd00::1")) {
            CimdFetcher f = fetcher(addr(ip), doc(URL));
            assertThrows(IllegalArgumentException.class, () -> f.fetch(URL), ip);
        }
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsPrivate() {
        CimdFetcher f = new CimdFetcher(host -> new InetAddress[] { PUBLIC, addr("10.0.0.1") },
            uri -> doc(URL).getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> f.fetch(URL));
    }

    @Test
    void rejectsMismatchedClientId() {
        assertThrows(IllegalArgumentException.class,
            () -> fetcher(PUBLIC, doc("https://other.example.com/client.json")).fetch(URL));
    }

    @Test
    void rejectsDocumentWithoutRedirectUris() {
        String body = "{\"client_id\":\"" + URL + "\",\"redirect_uris\":[]}";
        assertThrows(IllegalArgumentException.class, () -> fetcher(PUBLIC, body).fetch(URL));
    }

    @Test
    void rejectsNonJson() {
        assertThrows(IllegalArgumentException.class, () -> fetcher(PUBLIC, "<html>").fetch(URL));
    }

    // The transport itself, against a real local server.

    private URI serve(int status, byte[] body, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/client.json", exchange -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (status == 302) exchange.getResponseHeaders().add("Location", "http://169.254.169.254/");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/client.json");
    }

    @Test
    void transportReturnsSmallBody() throws IOException {
        URI uri = serve(200, "{}".getBytes(StandardCharsets.UTF_8), 0);
        byte[] body = new CimdFetcher.HttpTransport(Duration.ofSeconds(2)).get(uri);
        assertEquals("{}", new String(body, StandardCharsets.UTF_8));
    }

    @Test
    void transportRejectsOversizeBody() throws IOException {
        URI uri = serve(200, new byte[CimdFetcher.MAX_BYTES + 1], 0);
        IOException e = assertThrows(IOException.class,
            () -> new CimdFetcher.HttpTransport(Duration.ofSeconds(2)).get(uri));
        assertTrue(e.getMessage().contains("larger"));
    }

    @Test
    void transportDoesNotFollowRedirects() throws IOException {
        URI uri = serve(302, new byte[0], 0);
        IOException e = assertThrows(IOException.class,
            () -> new CimdFetcher.HttpTransport(Duration.ofSeconds(2)).get(uri));
        assertTrue(e.getMessage().contains("302"));
    }

    @Test
    void transportTimesOut() throws IOException {
        URI uri = serve(200, "{}".getBytes(StandardCharsets.UTF_8), 1500);
        assertThrows(IOException.class, () -> new CimdFetcher.HttpTransport(Duration.ofMillis(200)).get(uri));
    }
}
