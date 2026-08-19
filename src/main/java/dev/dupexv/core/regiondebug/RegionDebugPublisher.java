package dev.dupexv.core.regiondebug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class RegionDebugPublisher {

    private static final String USER_AGENT = "DupeXvCore-Regions";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final Plugin plugin;
    private final RegionDebugCollector collector;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final AtomicBoolean posting = new AtomicBoolean(false);

    private volatile boolean enabled;
    private volatile String baseUrl = "";
    private volatile String secret = "";
    private volatile String path = "/api/mc/regions";
    private volatile long intervalSeconds = 5L;

    public RegionDebugPublisher(Plugin plugin) {
        this.plugin = plugin;
        this.collector = new RegionDebugCollector(plugin);
    }

    public RegionDebugCollector collector() {
        return collector;
    }

    public void start() {
        reloadSettings();
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!enabled) {
                return;
            }
            try {
                JsonObject snap = collector.collect();
                Bukkit.getAsyncScheduler().runNow(plugin, t -> post(snap));
            } catch (Throwable e) {
                plugin.getLogger().log(Level.FINE, "[Regions] collect failed: " + e.getMessage());
            }
        }, 40L, Math.max(20L, intervalSeconds * 20L));
        plugin.getLogger().info("[Regions] Publisher started (interval " + intervalSeconds + "s, enabled=" + enabled + ")");
    }

    public void reloadSettings() {
        FileConfiguration cfg = plugin.getConfig();
        enabled = cfg.getBoolean("regions.enabled", true);
        String url = cfg.getString("regions.api-url", "");
        String sec = cfg.getString("regions.shared-secret", "");
        path = cfg.getString("regions.path", "/api/mc/regions");
        intervalSeconds = Math.max(2L, cfg.getLong("regions.interval-seconds", 5L));
        if (url == null) {
            url = "";
        }
        if (sec == null) {
            sec = "";
        }
        url = url.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        baseUrl = url;
        secret = sec.trim();
        if (path == null || path.isBlank()) {
            path = "/api/mc/regions";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (enabled && (baseUrl.isBlank() || secret.isBlank())) {
            plugin.getLogger().warning("[Regions] Enabled but api-url/shared-secret missing");
            enabled = false;
        }
    }

    private void post(JsonObject snap) {
        if (!enabled || baseUrl.isBlank() || secret.isBlank()) {
            return;
        }
        if (!posting.compareAndSet(false, true)) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .POST(HttpRequest.BodyPublishers.ofString(snap.toString()))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                plugin.getLogger().log(Level.FINE, "[Regions] POST " + path + " -> HTTP " + res.statusCode());
            } else {
                try {
                    JsonObject resp = JsonParser.parseString(res.body()).getAsJsonObject();
                    if (resp.has("actions") && resp.get("actions").isJsonArray()
                            && resp.getAsJsonArray("actions").size() > 0) {
                        var actions = resp.getAsJsonArray("actions");
                        Bukkit.getGlobalRegionScheduler().run(plugin, t -> collector.handleRemoteActions(actions));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "[Regions] POST failed: " + e.getMessage());
        } finally {
            posting.set(false);
        }
    }
}
