package com.photospace.support;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A minimal browser stand-in for integration tests. It keeps cookies (including {@code Secure} ones, which
 * {@link java.net.CookieManager} would drop over plain HTTP) and sends the CSRF header the way the SPA does.
 */
public final class ApiClient {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private final HttpClient http = HttpClient.newHttpClient(); // Does not follow redirects.
    private final String baseUrl;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /** Signs in through the dev-only endpoint; the returned client carries the session cookie. */
    public ApiClient signIn(String email) {
        Response response = send("POST", "/api/dev/login", "{\"email\":\"" + email + "\"}", false);
        if (response.status() != 204) {
            throw new IllegalStateException("Dev sign-in failed: " + response.status());
        }
        return this;
    }

    /** Creates a workspace owned by this user and returns its ID. */
    public String createWorkspace(String name, String timezone) {
        Response response = post("/api/workspaces", "{\"name\":\"%s\",\"timezone\":\"%s\"}".formatted(name, timezone));
        if (response.status() != 201) {
            throw new IllegalStateException("Workspace creation failed: " + response.status());
        }
        return response.json("$.id");
    }

    public Response get(String path) {
        return send("GET", path, null, false);
    }

    /** A GET with extra request headers, such as a gallery visitor's share token. */
    public Response getWithHeaders(String path, Map<String, String> headers) {
        return send("GET", path, null, false, headers);
    }

    /** A POST without a body, with extra request headers. */
    public Response postWithHeaders(String path, Map<String, String> headers) {
        return send("POST", path, null, true, headers);
    }

    public Response post(String path, String json) {
        return send("POST", path, json, true);
    }

    public Response put(String path, String json) {
        return send("PUT", path, json, true);
    }

    public Response delete(String path) {
        return send("DELETE", path, null, true);
    }

    /** A POST without a body, such as an archive action. */
    public Response post(String path) {
        return send("POST", path, null, true);
    }

    /** A state-changing request that omits the CSRF header, as a cross-site form or script would. */
    public Response postWithoutCsrf(String path, String json) {
        return send("POST", path, json, false);
    }

    /**
     * Sends {@code body} to an absolute URL, such as a presigned upload URL, with only the given headers: no
     * cookies and no CSRF token, as a browser's cross-origin upload would.
     */
    public static Response putBytes(URI url, byte[] body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url).PUT(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        return sendRaw(request.build());
    }

    /** A plain GET of an absolute URL, such as a presigned thumbnail URL. */
    public static HttpResponse<byte[]> getBytes(URI url) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Response sendRaw(HttpRequest request) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String cookie(String name) {
        return cookies.get(name);
    }

    public void setCookie(String name, String value) {
        cookies.put(name, value);
    }

    private Response send(String method, String path, String json, boolean withCsrf) {
        return send(method, path, json, withCsrf, Map.of());
    }

    private Response send(String method, String path, String json, boolean withCsrf, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        if (withCsrf) {
            if (!cookies.containsKey(CSRF_COOKIE)) {
                get("/api/auth/csrf");
            }
            request.header("X-XSRF-TOKEN", cookies.get(CSRF_COOKIE));
        }
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.entrySet().stream()
                    .map(c -> c.getKey() + "=" + c.getValue())
                    .collect(Collectors.joining("; ")));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            storeCookies(response.headers());
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void storeCookies(HttpHeaders headers) {
        for (String setCookie : headers.allValues("Set-Cookie")) {
            String pair = setCookie.split(";", 2)[0];
            String name = pair.substring(0, pair.indexOf('=')).strip();
            String value = pair.substring(pair.indexOf('=') + 1).strip();
            if (value.isEmpty() || setCookie.contains("Max-Age=0")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    public record Response(int status, String body, HttpHeaders headers) {

        public <T> T json(String path) {
            return JsonPath.read(body, path);
        }

        public List<String> setCookies() {
            return headers.allValues("Set-Cookie");
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }
}
