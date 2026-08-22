package dev.dupexv.core.chat;

import dev.dupexv.core.DupeXvCore;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class ChatService implements Listener {

    private final DupeXvCore plugin;
    private final ConcurrentHashMap<UUID, Deque<Long>> messages = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Repeat> repeats = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> muted = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> lastChat = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile boolean pmEnabled = true;
    private volatile boolean spamEnabled = true;
    private volatile int maxMessages = 4;
    private volatile int windowMs = 4000;
    private volatile int muteSeconds = 30;
    private volatile int maxRepeats = 3;
    private volatile int capsPercent = 60;
    private volatile int capsMinLength = 6;
    private volatile String spamBypass = "dupexvcore.chat.bypass";
    private volatile boolean filterEnabled = true;
    private volatile List<String> blockedWords = List.of();
    private volatile boolean blockIps = true;
    private volatile boolean blockDiscord = true;
    private volatile boolean blockUrls = false;
    private volatile boolean whitelistEnabled = true;
    private volatile String whitelistBypass = "dupexvcore.commandwhitelist.bypass";
    private volatile List<String> whitelistedCommands = List.of();

    private Pattern ipPattern = Pattern.compile("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)");
    private Pattern discordPattern = Pattern.compile("(?:discord(?:\\.gg|app\\.com/invite|me/)|dsc\\.gg/|discord\\.com/invite/)[\\w-]+", Pattern.CASE_INSENSITIVE);
    private Pattern urlPattern = Pattern.compile("(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

    public ChatService(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("chat.enabled", true);
        pmEnabled = plugin.getConfig().getBoolean("chat.private-messages.enabled", true);
        spamEnabled = plugin.getConfig().getBoolean("chat.spam.enabled", true);
        maxMessages = Math.max(1, plugin.getConfig().getInt("chat.spam.max-messages", 4));
        windowMs = Math.max(1000, plugin.getConfig().getInt("chat.spam.window-seconds", 4) * 1000);
        muteSeconds = Math.max(0, plugin.getConfig().getInt("chat.spam.mute-seconds", 30));
        maxRepeats = Math.max(1, plugin.getConfig().getInt("chat.spam.max-repeats", 3));
        capsPercent = Math.min(100, Math.max(0, plugin.getConfig().getInt("chat.spam.caps-percent", 60)));
        capsMinLength = Math.max(1, plugin.getConfig().getInt("chat.spam.caps-min-length", 6));
        spamBypass = plugin.getConfig().getString("chat.spam.bypass-permission", "dupexvcore.chat.bypass");
        filterEnabled = plugin.getConfig().getBoolean("chat.filter.enabled", true);
        blockedWords = plugin.getConfig().getStringList("chat.filter.blocked-words").stream()
                .map(word -> word.toLowerCase(Locale.ROOT))
                .toList();
        blockIps = plugin.getConfig().getBoolean("chat.filter.block-ips", true);
        blockDiscord = plugin.getConfig().getBoolean("chat.filter.block-discord-invites", true);
        blockUrls = plugin.getConfig().getBoolean("chat.filter.block-urls", false);
        whitelistEnabled = plugin.getConfig().getBoolean("command-whitelist.enabled", true);
        whitelistBypass = plugin.getConfig().getString("command-whitelist.bypass-permission", "dupexvcore.commandwhitelist.bypass");
        whitelistedCommands = normalizeCommands(plugin.getConfig().getStringList("command-whitelist.commands"));
    }

    public void shutdown() {
        messages.clear();
        repeats.clear();
        muted.clear();
        lastChat.clear();
    }

    public void msg(Player from, String targetName, String message) {
        if (!enabled || !pmEnabled) {
            plugin.lang().send(from, "msg.disabled");
            return;
        }
        Player to = findPlayer(targetName);
        if (to == null || !to.isOnline()) {
            plugin.lang().send(from, "msg.offline", "player", targetName);
            return;
        }
        sendMessage(from, to, message);
    }

    public void reply(Player from, String message) {
        if (!enabled || !pmEnabled) {
            plugin.lang().send(from, "msg.disabled");
            return;
        }
        UUID id = lastChat.get(from.getUniqueId());
        if (id == null) {
            plugin.lang().send(from, "msg.no-reply");
            return;
        }
        Player to = Bukkit.getPlayer(id);
        if (to == null || !to.isOnline()) {
            plugin.lang().send(from, "msg.reply-offline", "player", id.toString());
            return;
        }
        sendMessage(from, to, message);
    }

    public void ignoreAdd(Player player, String name) {
        UUID id = findUuid(name);
        if (id == null) {
            plugin.lang().send(player, "ignore.player-offline", "player", name);
            return;
        }
        if (id.equals(player.getUniqueId())) {
            plugin.lang().send(player, "ignore.self");
            return;
        }
        if (plugin.db().isIgnoring(player.getUniqueId(), id)) {
            plugin.lang().send(player, "ignore.already", "player", name);
            return;
        }
        plugin.db().addIgnore(player.getUniqueId(), id);
        plugin.lang().send(player, "ignore.added", "player", name);
    }

    public void ignoreRemove(Player player, String name) {
        UUID id = findUuid(name);
        if (id == null) {
            for (UUID ignored : plugin.db().ignores(player.getUniqueId())) {
                String resolved = Bukkit.getOfflinePlayer(ignored).getName();
                if (resolved != null && resolved.equalsIgnoreCase(name)) {
                    id = ignored;
                    break;
                }
            }
        }
        if (id == null || !plugin.db().removeIgnore(player.getUniqueId(), id)) {
            plugin.lang().send(player, "ignore.not-ignoring", "player", name);
            return;
        }
        plugin.lang().send(player, "ignore.removed", "player", name);
    }

    public void ignoreList(Player player) {
        List<UUID> ids = plugin.db().ignores(player.getUniqueId());
        if (ids.isEmpty()) {
            plugin.lang().send(player, "ignore.list-empty");
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            names.add(name != null ? name : id.toString());
        }
        plugin.lang().send(player, "ignore.list", "players", String.join(", ", names));
    }

    public List<String> ignoredNames(Player player) {
        List<String> out = new ArrayList<>();
        for (UUID id : plugin.db().ignores(player.getUniqueId())) {
            String name = Bukkit.getOfflinePlayer(id).getName();
            if (name != null) {
                out.add(name);
            }
        }
        Collections.sort(out);
        return out;
    }

    public List<String> matchOnline(Player viewer, String prefix) {
        String start = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(viewer)) {
                continue;
            }
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                out.add(player.getName());
            }
        }
        Collections.sort(out);
        return out;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enabled || !whitelistEnabled) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isOp()) {
            return;
        }
        if (whitelistBypass != null && !whitelistBypass.isEmpty() && player.hasPermission(whitelistBypass)) {
            return;
        }
        String raw = event.getMessage().trim();
        if (raw.isEmpty()) {
            return;
        }
        String command = raw.split(" ")[0].toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        if (!whitelistedCommands.contains(command)) {
            event.setCancelled(true);
            plugin.lang().tell(player, "command-whitelist.not-allowed", "command", raw.split(" ")[0]);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.homes().isRenaming(player)) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Block block = check(player, text);
        if (block != null) {
            event.setCancelled(true);
            sendBlock(player, block);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        messages.remove(id);
        repeats.remove(id);
        lastChat.remove(id);
    }

    private void sendMessage(Player from, Player to, String message) {
        if (from.equals(to)) {
            plugin.lang().send(from, "msg.self");
            return;
        }
        if (plugin.db().isIgnoring(from.getUniqueId(), to.getUniqueId())) {
            plugin.lang().send(from, "msg.ignoring", "player", to.getName());
            return;
        }
        if (plugin.db().isIgnoring(to.getUniqueId(), from.getUniqueId())) {
            plugin.lang().send(from, "msg.blocked", "player", to.getName());
            return;
        }
        Long until = muted.get(from.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            long left = (until - System.currentTimeMillis() + 999L) / 1000L;
            plugin.lang().send(from, "msg.muted", "seconds", left);
            return;
        }
        Component outgoing = plugin.lang().component("msg.to", "player", to.getName()).append(Component.text(message));
        Component incoming = plugin.lang().component("msg.from", "player", from.getName()).append(Component.text(message));
        send(to, incoming);
        send(from, outgoing);
        lastChat.put(from.getUniqueId(), to.getUniqueId());
        lastChat.put(to.getUniqueId(), from.getUniqueId());
    }

    private Block check(Player player, String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        boolean bypass = spamBypass != null && !spamBypass.isEmpty() && player.hasPermission(spamBypass);
        if (!bypass) {
            Long until = muted.get(player.getUniqueId());
            if (until != null) {
                if (until > now) {
                    return new Block("chat.muted", (until - now + 999L) / 1000L);
                }
                muted.remove(player.getUniqueId(), until);
            }
            if (spamEnabled) {
                Deque<Long> times = messages.computeIfAbsent(player.getUniqueId(), id -> new ArrayDeque<>());
                synchronized (times) {
                    while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
                        times.pollFirst();
                    }
                    times.addLast(now);
                    if (times.size() > maxMessages) {
                        return mute(player, "chat.spam");
                    }
                }
                String folded = fold(text);
                Repeat rep = repeats.get(player.getUniqueId());
                if (rep != null && rep.text.equals(folded)) {
                    rep.count++;
                    if (rep.count > maxRepeats) {
                        return mute(player, "chat.repeats");
                    }
                } else {
                    repeats.put(player.getUniqueId(), new Repeat(folded, 1));
                }
                int letters = 0;
                int upper = 0;
                for (char c : text.toCharArray()) {
                    if (Character.isLetter(c)) {
                        letters++;
                        if (Character.isUpperCase(c)) {
                            upper++;
                        }
                    }
                }
                if (letters >= capsMinLength && upper * 100 >= letters * capsPercent) {
                    return new Block("chat.caps");
                }
            }
        }
        if (filterEnabled) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            for (String word : blockedWords) {
                if (lower.contains(word)) {
                    return new Block("chat.blocked");
                }
            }
            if (blockIps && ipPattern.matcher(trimmed).find()) {
                return new Block("chat.blocked");
            }
            if (blockDiscord && discordPattern.matcher(lower).find()) {
                return new Block("chat.blocked");
            }
            if (blockUrls && urlPattern.matcher(lower).find()) {
                return new Block("chat.blocked");
            }
        }
        return null;
    }

    private Block mute(Player player, String key) {
        if (muteSeconds > 0) {
            muted.put(player.getUniqueId(), System.currentTimeMillis() + muteSeconds * 1000L);
        }
        return new Block(key);
    }

    private void sendBlock(Player player, Block block) {
        Runnable run = block.seconds > 0
                ? () -> plugin.lang().send(player, block.path, "seconds", block.seconds)
                : () -> plugin.lang().send(player, block.path);
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            run.run();
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                run.run();
            }
        }, null);
    }

    private void send(Player player, Component component) {
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            player.sendMessage(component);
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                player.sendMessage(component);
            }
        }, null);
    }

    private Player findPlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        return Bukkit.getPlayer(name);
    }

    private UUID findUuid(String name) {
        Player online = findPlayer(name);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) {
            return offline.getUniqueId();
        }
        return null;
    }

    private static String fold(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static List<String> normalizeCommands(List<String> list) {
        List<String> out = new ArrayList<>();
        for (String raw : list) {
            String command = raw.trim().toLowerCase(Locale.ROOT);
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (!command.isEmpty()) {
                out.add(command);
            }
        }
        return out;
    }

    private static final class Repeat {
        private final String text;
        private int count;

        private Repeat(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    private static final class Block {
        private final String path;
        private final long seconds;

        private Block(String path) {
            this(path, 0L);
        }

        private Block(String path, long seconds) {
            this.path = path;
            this.seconds = seconds;
        }
    }
}
