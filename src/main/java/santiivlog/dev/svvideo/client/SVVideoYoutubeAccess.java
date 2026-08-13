package santiivlog.dev.svvideo.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SVVideoYoutubeAccess {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Pattern PLAYABILITY_STATUS_PATTERN = Pattern.compile(
            "\"playabilityStatus\":\\{\"status\":\"([^\"]+)\"(?:,\"reason\":\"([^\"]*)\")?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Map<String, AccessDecision> CACHE = new ConcurrentHashMap<>();

    private SVVideoYoutubeAccess() {
    }

    static boolean isYoutubeUrl(String source) {
        if (source == null || source.isBlank()) {
            return false;
        }
        String lower = source.toLowerCase();
        return lower.contains("youtube.com/watch") || lower.contains("youtu.be/");
    }

    static AccessDecision checkAnonymousPlayback(String source) {
        if (!isYoutubeUrl(source)) {
            return AccessDecision.ALLOWED;
        }
        return CACHE.computeIfAbsent(source, SVVideoYoutubeAccess::fetchAccessDecision);
    }

    private static AccessDecision fetchAccessDecision(String source) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(source))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return AccessDecision.ALLOWED;
            }

            Matcher matcher = PLAYABILITY_STATUS_PATTERN.matcher(response.body());
            if (!matcher.find()) {
                return AccessDecision.ALLOWED;
            }

            String status = matcher.group(1);
            String reason = matcher.group(2) == null ? "" : matcher.group(2).trim();
            if ("LOGIN_REQUIRED".equalsIgnoreCase(status)) {
                String detail = reason.isBlank() ? "YouTube requires sign-in for this video." : reason;
                return new AccessDecision(
                        false,
                        "YouTube pide iniciar sesion para este video y WaterMedia no puede abrirlo de forma anonima: "
                                + detail
                );
            }
        } catch (Exception ignored) {
        }

        return AccessDecision.ALLOWED;
    }

    record AccessDecision(boolean allowed, String message) {
        private static final AccessDecision ALLOWED = new AccessDecision(true, "");
    }
}

