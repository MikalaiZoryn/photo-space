package com.photospace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.photospace.support.IntegrationTest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The {@code aws} profile behind CloudFront and the internal load balancer (D18): the Google redirect URI must
 * use the public HTTPS origin, whatever the load balancer or the viewer put in the X-Forwarded-* headers.
 * Requests are written by hand because {@code java.net.http} does not let a test set the Host header.
 */
@IntegrationTest
@ActiveProfiles("aws")
class ForwardedHeadersIT {

    private static final String START_GOOGLE_SIGN_IN = "/api/auth/oauth2/authorization/google";

    @LocalServerPort
    private int port;

    @Test
    void googleRedirectUriUsesThePublicHttpsOriginFromCloudFront() throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "d111111abcdef8.cloudfront.net");
        headers.put("CloudFront-Forwarded-Proto", "https");
        // What the load balancer adds for its plain-HTTP listener.
        headers.put("X-Forwarded-Proto", "http");
        headers.put("X-Forwarded-Port", "80");
        headers.put("X-Forwarded-For", "203.0.113.9");
        // A viewer cannot move the redirect to another host.
        headers.put("X-Forwarded-Host", "attacker.example");

        String location = location(get(START_GOOGLE_SIGN_IN, headers));

        assertThat(redirectUri(location)).isEqualTo("https://d111111abcdef8.cloudfront.net/api/auth/oauth2/callback/google");
    }

    @Test
    void withoutCloudFrontTheRequestSchemeIsKept() throws IOException {
        String location = location(get(START_GOOGLE_SIGN_IN, Map.of("Host", "localhost:" + port, "X-Forwarded-Proto", "https")));

        assertThat(redirectUri(location)).isEqualTo("http://localhost:" + port + "/api/auth/oauth2/callback/google");
    }

    private String get(String path, Map<String, String> headers) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            StringBuilder request = new StringBuilder("GET " + path + " HTTP/1.1\r\n");
            headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
            request.append("Connection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            return in.lines().takeWhile(line -> !line.isEmpty()).collect(Collectors.joining("\n"));
        }
    }

    private static String location(String responseHead) {
        assertThat(responseHead).startsWith("HTTP/1.1 302");
        return responseHead.lines()
                .filter(line -> line.regionMatches(true, 0, "Location:", 0, 9))
                .map(line -> line.substring(9).trim())
                .findFirst()
                .orElseThrow();
    }

    private static String redirectUri(String location) {
        URI uri = URI.create(location);
        assertThat(uri.getHost()).isEqualTo("accounts.google.com");
        return Arrays.stream(uri.getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)))
                .get("redirect_uri");
    }
}
