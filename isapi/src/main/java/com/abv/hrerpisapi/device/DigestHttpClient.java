package com.abv.hrerpisapi.device;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DigestHttpClient {
    private final HttpClient client;
    private final String baseUrl;   // e.g. http://192.168.0.200:8080
    private final String username;
    private final String password;

    public DigestHttpClient(String baseUrl, String username, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
        this.password = password;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public HttpResponse<String> get(String path) throws IOException, InterruptedException {
        URI uri = URI.create(baseUrl + path);

        // 1) unauthenticated request to get the digest challenge
        HttpRequest req1 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "*/*")
                .GET()
                .build();

        HttpResponse<String> r1 = client.send(req1, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r1.statusCode() != 401) return r1;

        Optional<String> wwwAuthOpt = r1.headers().firstValue("WWW-Authenticate");
        if (wwwAuthOpt.isEmpty() || !wwwAuthOpt.get().toLowerCase(Locale.ROOT).startsWith("digest")) {
            throw new IOException("Expected WWW-Authenticate: Digest, got: " + wwwAuthOpt.orElse("<missing>"));
        }

        DigestChallenge ch = DigestChallenge.parse(wwwAuthOpt.get());
        String auth = buildDigestAuthorization(ch, "GET", uri.getPath());

        // 2) retry with Authorization header
        HttpRequest req2 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "*/*")
                .header("Authorization", auth)
                .GET()
                .build();

        return client.send(req2, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public HttpResponse<byte[]> getBytes(String path) throws IOException, InterruptedException {
        String normalized = normalizePath(path);
        IOException lastError = null;

        for (String digestUri : digestUriCandidates(normalized)) {
            try {
                HttpResponse<byte[]> response = getBytesWithDigestUri(digestUri);
                if (response.statusCode() == 200) {
                    return response;
                }
                if (response.statusCode() != 401 && response.statusCode() != 404) {
                    return response;
                }
            } catch (IOException ex) {
                lastError = ex;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return getBytesWithDigestUri(normalized);
    }

    /**
     * Download bytes from a full device resource URL (e.g. ACS event pictureURL).
     * Tries several request/digest-uri combinations — Hikvision often requires the
     * {@code @WEB…} token in the HTTP request while the digest hash uses the bare path.
     */
    public HttpResponse<byte[]> getBytesFromResourceUrl(String resourceUrl) throws IOException, InterruptedException {
        ParsedResource resource = parseResourceUrl(resourceUrl, baseUrl);
        requireSameOrigin(resource.baseUrl(), baseUrl);
        DigestHttpClient hostClient = new DigestHttpClient(resource.baseUrl(), username, password);

        IOException lastError = null;
        for (DigestAttempt attempt : pictureDownloadAttempts(resourceUrl, resource.path())) {
            try {
                HttpResponse<byte[]> response = hostClient.getBytesWithDigestAttempt(attempt);
                if (response.statusCode() == 200) {
                    return response;
                }
                if (response.statusCode() != 401 && response.statusCode() != 404) {
                    return response;
                }
            } catch (IOException ex) {
                lastError = ex;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        return hostClient.getBytes(resource.path());
    }

    private HttpResponse<byte[]> getBytesWithDigestAttempt(DigestAttempt attempt) throws IOException, InterruptedException {
        URI uri = resolveRequestUri(attempt.requestTarget());

        HttpRequest req1 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "image/jpeg, image/*, */*")
                .GET()
                .build();

        HttpResponse<byte[]> r1 = client.send(req1, HttpResponse.BodyHandlers.ofByteArray());
        if (r1.statusCode() != 401) {
            return r1;
        }

        Optional<String> wwwAuthOpt = r1.headers().firstValue("WWW-Authenticate");
        if (wwwAuthOpt.isEmpty() || !wwwAuthOpt.get().toLowerCase(Locale.ROOT).startsWith("digest")) {
            throw new IOException("Expected WWW-Authenticate: Digest, got: " + wwwAuthOpt.orElse("<missing>"));
        }

        DigestChallenge ch = DigestChallenge.parse(wwwAuthOpt.get());
        String auth = buildDigestAuthorization(ch, "GET", attempt.digestUri());

        HttpRequest req2 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "image/jpeg, image/*, */*")
                .header("Authorization", auth)
                .GET()
                .build();

        return client.send(req2, HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI resolveRequestUri(String requestTarget) throws IOException {
        if (requestTarget.startsWith("http://") || requestTarget.startsWith("https://")) {
            return URI.create(requestTarget);
        }
        return buildRequestUri(requestTarget);
    }

    private static List<DigestAttempt> pictureDownloadAttempts(String resourceUrl, String path) {
        LinkedHashSet<DigestAttempt> attempts = new LinkedHashSet<>();
        String normalized = normalizePath(path);

        attempts.add(new DigestAttempt(resourceUrl, normalized));
        attempts.add(new DigestAttempt(normalized, normalized));

        if (normalized.contains("@")) {
            attempts.add(new DigestAttempt(resourceUrl, normalized.replace("@", "%40")));
            attempts.add(new DigestAttempt(normalized.replace("@", "%40"), normalized.replace("@", "%40")));
        }

        int webTokenIndex = normalized.indexOf("@WEB");
        if (webTokenIndex > 0) {
            String strippedPath = normalized.substring(0, webTokenIndex);
            String strippedUrl = resourceUrl.contains("@")
                    ? resourceUrl.substring(0, resourceUrl.indexOf('@'))
                    : resourceUrl;
            attempts.add(new DigestAttempt(resourceUrl, strippedPath));
            attempts.add(new DigestAttempt(strippedUrl, strippedPath));
            attempts.add(new DigestAttempt(strippedPath, strippedPath));
        }

        int atIndex = normalized.indexOf('@');
        if (atIndex > 0) {
            String cleanPath = normalized.substring(0, atIndex);
            String cleanUrl = resourceUrl.contains("@")
                    ? resourceUrl.substring(0, resourceUrl.indexOf('@'))
                    : resourceUrl;
            attempts.add(new DigestAttempt(cleanUrl, cleanPath));
        }

        return new ArrayList<>(attempts);
    }

    private record DigestAttempt(String requestTarget, String digestUri) {
    }

    private HttpResponse<byte[]> getBytesWithDigestUri(String digestUri) throws IOException, InterruptedException {
        return getBytesWithDigestAttempt(new DigestAttempt(digestUri, digestUri));
    }

    private URI buildRequestUri(String digestUri) throws IOException {
        try {
            URI base = URI.create(baseUrl);
            String scheme = base.getScheme() == null ? "http" : base.getScheme();
            String host = base.getHost();
            int port = base.getPort();
            if (host == null || host.isBlank()) {
                throw new IOException("Invalid base URL: " + baseUrl);
            }
            return new URI(scheme, null, host, port, digestUri, null, null);
        } catch (URISyntaxException ex) {
            throw new IOException("Invalid request URI for path: " + digestUri, ex);
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private static List<String> digestUriCandidates(String normalizedPath) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizedPath);

        if (normalizedPath.contains("@")) {
            candidates.add(normalizedPath.replace("@", "%40"));
        }

        int webTokenIndex = normalizedPath.indexOf("@WEB");
        if (webTokenIndex > 0) {
            candidates.add(normalizedPath.substring(0, webTokenIndex));
            String stripped = normalizedPath.substring(0, webTokenIndex);
            if (stripped.contains("@")) {
                candidates.add(stripped.replace("@", "%40"));
            }
        }

        return new ArrayList<>(candidates);
    }

    static ParsedResource parseResourceUrl(String resourceUrl, String fallbackBaseUrl) throws IOException {
        if (resourceUrl == null || resourceUrl.isBlank()) {
            throw new IOException("Resource URL is blank");
        }
        if (!resourceUrl.startsWith("http://") && !resourceUrl.startsWith("https://")) {
            String path = resourceUrl.startsWith("/") ? resourceUrl : "/" + resourceUrl;
            return new ParsedResource(fallbackBaseUrl, path);
        }

        int schemeSep = resourceUrl.indexOf("://");
        int hostStart = schemeSep + 3;
        int pathStart = resourceUrl.indexOf('/', hostStart);
        if (pathStart < 0) {
            throw new IOException("Resource URL has no path: " + resourceUrl);
        }

        String scheme = resourceUrl.substring(0, schemeSep);
        String authority = resourceUrl.substring(hostStart, pathStart);
        String path = resourceUrl.substring(pathStart);
        String base = scheme + "://" + authority;
        return new ParsedResource(base, path);
    }

    record ParsedResource(String baseUrl, String path) {
    }

    public static void requireSameOrigin(String resourceUrl, String configuredUrl) throws IOException {
        try {
            URI resource = URI.create(resourceUrl);
            URI configured = URI.create(configuredUrl);
            int resourcePort = resource.getPort() < 0 ? ("https".equalsIgnoreCase(resource.getScheme()) ? 443 : 80) : resource.getPort();
            int configuredPort = configured.getPort() < 0 ? ("https".equalsIgnoreCase(configured.getScheme()) ? 443 : 80) : configured.getPort();
            if (resource.getRawUserInfo() != null || resource.getHost() == null
                    || !("http".equalsIgnoreCase(resource.getScheme()) || "https".equalsIgnoreCase(resource.getScheme()))
                    || !resource.getHost().equalsIgnoreCase(configured.getHost())
                    || !resource.getScheme().equalsIgnoreCase(configured.getScheme()) || resourcePort != configuredPort) {
                throw new IOException("Picture origin does not match the configured device");
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid picture origin", ex);
        }
    }

    private String buildDigestAuthorization(DigestChallenge ch, String method, String uriPath) throws IOException {
        String realm = ch.params.get("realm");
        String nonce = ch.params.get("nonce");
        String qop = ch.params.getOrDefault("qop", "auth"); // device might return: auth or auth,auth-int
        if (qop.contains(",")) qop = qop.split(",")[0].trim();

        String opaque = ch.params.get("opaque");
        String algorithm = ch.params.getOrDefault("algorithm", "MD5");

        if (!"MD5".equalsIgnoreCase(algorithm)) {
            // Many devices are MD5 only. If it's MD5-sess etc, we can extend later.
            throw new IOException("Unsupported digest algorithm: " + algorithm);
        }

        String nc = "00000001";
        String cnonce = randomHex(16);

        String ha1 = md5Hex(username + ":" + realm + ":" + password);
        String ha2 = md5Hex(method + ":" + uriPath);
        String response = md5Hex(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":" + qop + ":" + ha2);

        StringBuilder sb = new StringBuilder();
        sb.append("Digest ");
        sb.append("username=\"").append(username).append("\", ");
        sb.append("realm=\"").append(realm).append("\", ");
        sb.append("nonce=\"").append(nonce).append("\", ");
        sb.append("uri=\"").append(uriPath).append("\", ");
        sb.append("qop=").append(qop).append(", ");
        sb.append("nc=").append(nc).append(", ");
        sb.append("cnonce=\"").append(cnonce).append("\", ");
        sb.append("response=\"").append(response).append("\"");

        if (opaque != null) sb.append(", opaque=\"").append(opaque).append("\"");
        sb.append(", algorithm=").append(algorithm);

        return sb.toString();
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    record DigestChallenge(Map<String, String> params) {

        static DigestChallenge parse(String header) {
                // header like: Digest realm="IP Camera", nonce="....", qop="auth", opaque="..", algorithm=MD5
                String h = header.trim();
                int idx = h.indexOf(' ');
                String rest = (idx >= 0) ? h.substring(idx + 1) : "";
                Map<String, String> m = new HashMap<>();

                // Parse key=value pairs, values can be quoted
                Pattern p = Pattern.compile("(\\w+)=(\"([^\"]*)\"|([^,]*))(?:,\\s*)?");
                Matcher mm = p.matcher(rest);
                while (mm.find()) {
                    String key = mm.group(1);
                    String val = mm.group(3) != null ? mm.group(3) : mm.group(4);
                    if (val != null) val = val.trim();
                    m.put(key, val);
                }
                return new DigestChallenge(m);
            }
        }

    public HttpResponse<String> post(String path, String contentType, String body) throws IOException, InterruptedException {
        return sendWithDigest("POST", path, contentType, body);
    }

    public HttpResponse<String> put(String path, String contentType, String body) throws IOException, InterruptedException {
        return sendWithDigest("PUT", path, contentType, body);
    }

    public HttpResponse<String> putBytes(String path, String contentType, byte[] body) throws IOException, InterruptedException {
        return sendWithDigestBytes("PUT", path, contentType, body);
    }

    public HttpResponse<String> delete(String path, String contentType, String body) throws IOException, InterruptedException {
        return sendWithDigest("DELETE", path, contentType, body);
    }

    private HttpResponse<String> sendWithDigest(String method, String path, String contentType, String body)
            throws IOException, InterruptedException {

        URI uri = URI.create(baseUrl + path);

        HttpRequest.BodyPublisher publisher =
                (body == null) ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);

        HttpRequest req1 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/xml, text/xml, */*")
                .header("Content-Type", contentType)
                .method(method, publisher)
                .build();

        HttpResponse<String> r1 = client.send(req1, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r1.statusCode() != 401) return r1;

        String www = r1.headers().firstValue("WWW-Authenticate")
                .orElseThrow(() -> new IOException("Missing WWW-Authenticate header"));

        DigestChallenge ch = DigestChallenge.parse(www);
        String auth = buildDigestAuthorization(ch, method, uri.getPath());

        HttpRequest req2 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/xml, text/xml, */*")
                .header("Content-Type", contentType)
                .header("Authorization", auth)
                .method(method, publisher)
                .build();

        return client.send(req2, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendWithDigestBytes(String method, String path, String contentType, byte[] body)
            throws IOException, InterruptedException {

        URI uri = URI.create(baseUrl + path);

        HttpRequest.BodyPublisher publisher =
                (body == null) ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body);

        HttpRequest req1 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/xml, text/xml, */*")
                .header("Content-Type", contentType)
                .method(method, publisher)
                .build();

        HttpResponse<String> r1 = client.send(req1, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r1.statusCode() != 401) return r1;

        String www = r1.headers().firstValue("WWW-Authenticate")
                .orElseThrow(() -> new IOException("Missing WWW-Authenticate header"));

        DigestChallenge ch = DigestChallenge.parse(www);
        String auth = buildDigestAuthorization(ch, method, uri.getPath());

        HttpRequest req2 = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json, application/xml, text/xml, */*")
                .header("Content-Type", contentType)
                .header("Authorization", auth)
                .method(method, publisher)
                .build();

        return client.send(req2, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
