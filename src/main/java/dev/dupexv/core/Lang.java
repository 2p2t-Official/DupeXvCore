package dev.dupexv.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Lang {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final DupeXvCore plugin;
    private YamlConfiguration lang;
    private YamlConfiguration bundled;

    public Lang(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        plugin.saveResource("lang/en.yml", false);
        String name = plugin.getConfig().getString("language", "en");
        if (name == null || name.isBlank()) {
            name = "en";
        }
        name = name.trim();
        File dir = new File(plugin.getDataFolder(), "lang");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File file = new File(dir, name + ".yml");
        if (!file.exists() && !"en".equalsIgnoreCase(name)) {
            file = new File(dir, "en.yml");
        }
        bundled = new YamlConfiguration();
        InputStream in = plugin.getResource("lang/en.yml");
        if (in != null) {
            bundled = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        lang = loadYaml(file);
        if (fillMissing(lang, bundled)) {
            try {
                lang.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning(String.valueOf(e.getMessage()));
            }
        }
    }

    private YamlConfiguration loadYaml(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.options().width(10000);
        if (!file.exists()) {
            return yaml;
        }
        try {
            yaml.load(file);
        } catch (Exception e) {
            plugin.getLogger().warning(String.valueOf(e.getMessage()));
            return YamlConfiguration.loadConfiguration(file);
        }
        return yaml;
    }

    private boolean fillMissing(FileConfiguration dest, FileConfiguration src) {
        if (dest == null || src == null) {
            return false;
        }
        boolean changed = false;
        for (String key : src.getKeys(true)) {
            if (src.isConfigurationSection(key) || dest.isSet(key)) {
                continue;
            }
            dest.set(key, src.get(key));
            changed = true;
        }
        return changed;
    }

    public String raw(String path) {
        String value = lang != null ? lang.getString(path) : null;
        if (value == null && bundled != null) {
            value = bundled.getString(path);
        }
        return value == null ? path : value;
    }

    public String format(String path, Object... pairs) {
        String value = raw(path);
        if (pairs != null) {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                Object key = pairs[i];
                Object val = pairs[i + 1];
                value = value.replace("{" + key + "}", val == null ? "" : String.valueOf(val));
            }
        }
        if (!"prefix".equals(path)) {
            String prefix = raw("prefix");
            if (prefix != null && !prefix.isEmpty()) {
                value = prefix + value;
            }
        }
        return value;
    }

    public Component component(String path, Object... pairs) {
        return LEGACY.deserialize(format(path, pairs));
    }

    public void send(CommandSender sender, String path, Object... pairs) {
        if (sender != null) {
            sender.sendMessage(component(path, pairs));
        }
    }

    public void tell(Player player, String path, Object... pairs) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            send(player, path, pairs);
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                send(player, path, pairs);
            }
        }, null);
    }

    public void actionBar(Player player, String path, Object... pairs) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            player.sendActionBar(component(path, pairs));
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                player.sendActionBar(component(path, pairs));
            }
        }, null);
    }
}
