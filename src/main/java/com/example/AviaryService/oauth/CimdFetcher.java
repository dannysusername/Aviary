package com.example.AviaryService.oauth;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// Downloads an AI assistant's Client ID Metadata Document (CIMD): the JSON at
// its client_id URL that names it and lists where to send the user back after
// sign-in. The URL comes from whoever starts a sign-in, so it is untrusted --
// this is the server-side fetch an attacker would aim at Aviary's own network
// (SSRF). Hence: https only, no IP-literal hosts, the host must not resolve
// to a private/loopback/link-local address, no redirects, small body, short
// timeout.
@Component
public class CimdFetcher {

    static final int MAX_BYTES = 5 * 1024;
    static final Duration TIMEOUT = Duration.ofSeconds(5);

    public record ClientMetadata(String clientId, String clientName, List<String> redirectUris) {}

    // Seams for tests: DNS lookup and the HTTP GET.
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    interface Transport {
        // Returns the body, or throws if the status isn't 200 or the body is too big.
        byte[] get(URI uri) throws IOException;
    }

    private final Resolver resolver;
    private final Transport transport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CimdFetcher() {
        this(InetAddress::getAllByName, new HttpTransport(TIMEOUT));
    }

    CimdFetcher(Resolver resolver, Transport transport) {
        this.resolver = resolver;
        this.transport = transport;
    }

    // Throws IllegalArgumentException with a short reason when the URL or
    // document isn't acceptable.
    public ClientMetadata fetch(String clientId) {
        URI uri = checkUrl(clientId);
        checkHost(uri.getHost());

        byte[] body;
        try {
            body = transport.get(uri);
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not load client metadata: " + e.getMessage());
        }

        JsonNode doc;
        try {
            doc = objectMapper.readTree(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("Client metadata is not valid JSON");
        }
        if (doc == null || !doc.isObject()) {
            throw new IllegalArgumentException("Client metadata is not a JSON object");
        }
        if (!clientId.equals(doc.path("client_id").asText(null))) {
            throw new IllegalArgumentException("client_id in the document does not match its URL");
        }
        List<String> redirectUris = new ArrayList<>();
        doc.path("redirect_uris").forEach(n -> {
            if (n.isTextual() && !n.asText().isBlank()) redirectUris.add(n.asText());
        });
        if (redirectUris.isEmpty()) {
            throw new IllegalArgumentException("Client metadata lists no redirect_uris");
        }
        String name = doc.path("client_name").asText(null);
        if (name == null || name.isBlank()) name = uri.getHost();
        if (name.length() > 100) name = name.substring(0, 100);
        return new ClientMetadata(clientId, name, List.copyOf(redirectUris));
    }

    // A CIMD client_id: an https URL with a path and no credentials or fragment.
    static boolean looksLikeCimdUrl(String clientId) {
        try {
            checkUrl(clientId);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static URI checkUrl(String clientId) {
        URI uri;
        try {
            uri = URI.create(clientId);
        } catch (Exception e) {
            throw new IllegalArgumentException("client_id is not a URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("client_id must be an https URL");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("client_id URL is not allowed");
        }
        if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) {
            throw new IllegalArgumentException("client_id URL must have a path");
        }
        return uri;
    }

    private void checkHost(String host) {
        String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (isIpLiteral(h)) {
            throw new IllegalArgumentException("client_id host must be a name, not an IP address");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(h);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("client_id host does not resolve");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("client_id host does not resolve");
        }
        for (InetAddress a : addresses) {
            if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress() || isUniqueLocalV6(a) || isCarrierNat(a)) {
                throw new IllegalArgumentException("client_id host points to a private address");
            }
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }

    // fc00::/7
    private static boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xFE) == 0xFC;
    }

    // 100.64.0.0/10
    private static boolean isCarrierNat(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
    }

    static class HttpTransport implements Transport {
        private final Duration timeout;
        private final HttpClient client;

        HttpTransport(Duration timeout) {
            this.timeout = timeout;
            this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        }

        @Override
        public byte[] get(URI uri) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("status " + response.statusCode());
                }
                byte[] body = in.readNBytes(MAX_BYTES + 1);
                if (body.length > MAX_BYTES) {
                    throw new IOException("document larger than " + MAX_BYTES + " bytes");
                }
                return body;
            }
        }
    }
}
