package dev.dupexv.core.website;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Level;

public final class WebsiteLinkService {

    private static final String HEADER_NAME = "X-Minecraft-Link-Key";

    private final Plugin plugin;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private volatile String linkApiUrl = "https://dupexv.org/api/auth/minecraft-verify-link";
    private volatile String linkApiKey = "";
    private volatile int timeoutSeconds = 8;

    public WebsiteLinkService(Plugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("website");
        if (section == null) {
            linkApiKey = "";
            return;
        }
        String baseUrl = firstNonBlank(section.getString("api-base-url"), "https://dupexv.org");
        String base = trimTrailingSlash(baseUrl);
        linkApiUrl = base + "/api/auth/minecraft-verify-link";
        linkApiKey = firstNonBlank(section.getString("minecraft-link-api-key"), "");
        timeoutSeconds = Math.max(5, Math.min(15, section.getInt("timeout-seconds", 8)));
    }

    public LinkResult verifyLink(String uuid, String username, String group) {
        if (linkApiKey.isBlank()) {
            return LinkResult.failure("&cAccount linking is not configured.");
        }
        JsonObject body = new JsonObject();
        body.addProperty("uuid", uuid);
        body.addProperty("username", username);
        if (group != null && !group.isBlank()) {
            body.addProperty("group", group);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(linkApiUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header(HEADER_NAME, linkApiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String apiError = extractError(response.body());
            if (status == 200) {
                return LinkResult.success("&aLinked to your dupexv.org account.");
            }
            plugin.getLogger().log(Level.WARNING, "[Link] HTTP " + status + " for " + username + ": " + apiError);
            return LinkResult.failure(mapError(status, apiError));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[Link] Failed for " + username + ": " + e.getMessage());
            return LinkResult.failure("&cCould not reach the website. Try again in a moment.");
        }
    }

    private static String mapError(int status, String apiError) {
        String normalized = apiError == null ? "" : apiError.trim();
        return switch (status) {
            case 403 -> "&cCould not verify your link.";
            case 404 -> "&cNo pending link for this player. Start one on &fdupexv.org&c first.";
            case 410 -> "&cThat link request expired. Start again on the website.";
            case 409 -> mapConflict(normalized);
            default -> normalized.isBlank()
                    ? "&cAccount linking failed. Start again on the website."
                    : "&c" + normalized;
        };
    }

    private static String mapConflict(String apiError) {
        String lower = apiError.toLowerCase(Locale.ROOT);
        if (lower.contains("already linked")) {
            return "&cThis Minecraft account is already linked to another website user.";
        }
        if (lower.contains("username") || lower.contains("mismatch") || lower.contains("expected")) {
            return "&cWrong Minecraft username. Join with the exact name you entered on the website.";
        }
        return apiError.isBlank()
                ? "&cCould not link this account."
                : "&c" + apiError;
    }

    private static String extractError(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("error") && !json.get("error").isJsonNull()) {
                return json.get("error").getAsString();
            }
        } catch (Exception ignored) {
        }
        return body.length() <= 200 ? body : body.substring(0, 200);
    }

    private static String trimTrailingSlash(String url) {
        String s = url == null ? "" : url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return "";
    }

    public record LinkResult(boolean success, String playerMessage) {
        static LinkResult success(String playerMessage) {
            return new LinkResult(true, playerMessage);
        }

        static LinkResult failure(String playerMessage) {
            return new LinkResult(false, playerMessage);
        }
    }
}
