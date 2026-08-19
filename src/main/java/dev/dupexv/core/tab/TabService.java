package dev.dupexv.core.tab;

import dev.dupexv.core.DupeXvCore;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TabService implements Listener {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final DupeXvCore plugin;
    // Target uuid -> team id currently in use for that target on every viewer's board.
    private final ConcurrentHashMap<UUID, String> teamIds = new ConcurrentHashMap<>();

    private volatile boolean enabled = true;
    private volatile boolean nametags = true;
    private volatile boolean collision = true;
    private volatile String tpsText = "20.00";
    private volatile String onlineText = "0";
    private volatile String maxText = "0";
    private volatile String msptText = "0.00";
    private ScheduledTask task;
    private int pulseCount;

    public TabService(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        shutdown();
        enabled = plugin.getConfig().getBoolean("tab.enabled", true);
        nametags = plugin.getConfig().getBoolean("tab.nametags", true);
        collision = plugin.getConfig().getBoolean("tab.collision", true);
        if (!enabled) {
            return;
        }
        if (Bukkit.getPluginManager().isPluginEnabled("TAB")) {
            plugin.getLogger().warning("TAB is still loaded. Remove it so DupeXvCore can control the tab list.");
        }
        long period = Math.max(1L, plugin.getConfig().getLong("tab.refresh", 20));
        task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, scheduled -> pulse(), 1L, period);
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            player.getScheduler().run(plugin, scheduled -> {
                if (player.isOnline()) {
                    apply(player);
                }
            }, null);
        }
        syncTeamsForAll();
    }

    public void shutdown() {
        enabled = false;
        if (task != null) {
            task.cancel();
            task = null;
        }
        teamIds.clear();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            try {
                player.getScheduler().run(plugin, scheduled -> clear(player), null);
            } catch (Exception ignored) {
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        player.getScheduler().runDelayed(plugin, scheduled -> {
            if (player.isOnline()) {
                apply(player);
            }
        }, null, 1L);
        // New player needs every existing team; everyone else needs this player's team.
        syncTeamsForAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        removeTargetFromAll(event.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent event) {
        if (!enabled) {
            return;
        }
        Player player = event.getPlayer();
        player.getScheduler().run(plugin, scheduled -> {
            if (player.isOnline()) {
                apply(player);
            }
        }, null);
    }

    private void pulse() {
        if (!enabled) {
            return;
        }
        double[] tps = Bukkit.getTPS();
        tpsText = String.format(Locale.US, "%.2f", tps.length == 0 ? 20.0 : Math.min(20.0, tps[0]));
        msptText = String.format(Locale.US, "%.2f", Bukkit.getAverageTickTime());
        onlineText = Integer.toString(Bukkit.getOnlinePlayers().size());
        maxText = Integer.toString(Bukkit.getMaxPlayers());
        List<Player> online = List.copyOf(Bukkit.getOnlinePlayers());
        for (Player player : online) {
            player.getScheduler().run(plugin, scheduled -> {
                if (player.isOnline()) {
                    apply(player);
                }
            }, null);
        }
        // Refresh name tag teams every so often so prefix changes from LuckPerms show up.
        if (++pulseCount % 5 == 0) {
            syncTeamsForAll();
        }
    }

    private void syncTeamsForAll() {
        if (!enabled) {
            return;
        }
        for (Player viewer : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (!viewer.isOnline()) {
                continue;
            }
            viewer.getScheduler().run(plugin, scheduled -> {
                if (!viewer.isOnline()) {
                    return;
                }
                for (Player target : List.copyOf(Bukkit.getOnlinePlayers())) {
                    if (target.isOnline()) {
                        syncTeam(viewer, target);
                    }
                }
            }, null);
        }
    }

    private void removeTargetFromAll(Player target) {
        String id = teamIds.remove(target.getUniqueId());
        if (id == null) {
            return;
        }
        for (Player viewer : List.copyOf(Bukkit.getOnlinePlayers())) {
            if (viewer.getUniqueId().equals(target.getUniqueId()) || !viewer.isOnline()) {
                continue;
            }
            viewer.getScheduler().run(plugin, scheduled -> {
                if (!viewer.isOnline()) {
                    return;
                }
                Scoreboard board = viewer.getScoreboard();
                Team team = board.getTeam(id);
                if (team != null) {
                    team.unregister();
                }
            }, null);
        }
    }

    private void apply(Player player) {
        if (!enabled || !player.isOnline()) {
            return;
        }
        player.sendPlayerListHeaderAndFooter(join("tab.header", player), join("tab.footer", player));
        String nameFormat = plugin.lang().raw("tab.name");
        if (nameFormat == null || nameFormat.isBlank() || nameFormat.equals("tab.name")) {
            nameFormat = "%prefix%%player%%suffix%";
        }
        player.playerListName(parse(replace(nameFormat, player)));
        player.setPlayerListOrder(1000 - rank(player));
    }

    private void clear(Player player) {
        if (!player.isOnline()) {
            return;
        }
        player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
        player.playerListName(null);
        player.setPlayerListOrder(0);
    }

    private void syncTeam(Player viewer, Player target) {
        if (!viewer.isOnline() || !target.isOnline()) {
            return;
        }
        Scoreboard board = viewer.getScoreboard();
        int rank = rank(target);
        String id = teamName(target.getUniqueId(), rank);
        String previous = teamIds.get(target.getUniqueId());
        if (previous != null && !previous.equals(id)) {
            Team old = board.getTeam(previous);
            if (old != null) {
                old.unregister();
            }
        }
        Team team = board.getTeam(id);
        if (team == null) {
            team = board.registerNewTeam(id);
        }
        team.setCanSeeFriendlyInvisibles(false);
        team.setOption(Team.Option.COLLISION_RULE, collision ? Team.OptionStatus.ALWAYS : Team.OptionStatus.NEVER);
        team.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.ALWAYS);
        if (nametags) {
            String prefix = plugin.lang().raw("tab.nametag-prefix");
            String suffix = plugin.lang().raw("tab.nametag-suffix");
            if (prefix == null || prefix.equals("tab.nametag-prefix")) {
                prefix = "%prefix%";
            }
            if (suffix == null || suffix.equals("tab.nametag-suffix")) {
                suffix = "%suffix%";
            }
            team.prefix(parse(replace(prefix, target)));
            team.suffix(parse(replace(suffix, target)));
        } else {
            team.prefix(Component.empty());
            team.suffix(Component.empty());
        }
        if (!team.hasEntry(target.getName())) {
            for (String other : List.copyOf(team.getEntries())) {
                team.removeEntry(other);
            }
            team.addEntry(target.getName());
        }
        teamIds.put(target.getUniqueId(), id);
    }

    private Component join(String path, Player player) {
        List<String> lines = plugin.lang().list(path);
        Component out = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                out = out.append(Component.newline());
            }
            out = out.append(parse(replace(lines.get(i), player)));
        }
        return out;
    }

    private String replace(String text, Player player) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String prefix = meta(player, true);
        String suffix = meta(player, false);
        String world = player.getWorld() != null ? player.getWorld().getName() : "";
        String group = group(player);
        return text
                .replace("%tps%", tpsText)
                .replace("%online%", onlineText)
                .replace("%max%", maxText)
                .replace("%mspt%", msptText)
                .replace("%ping%", Integer.toString(player.getPing()))
                .replace("%player%", player.getName())
                .replace("%displayname%", player.getName())
                .replace("%world%", world)
                .replace("%uuid%", player.getUniqueId().toString())
                .replace("%health%", Integer.toString((int) Math.ceil(player.getHealth())))
                .replace("%x%", Integer.toString(player.getLocation().getBlockX()))
                .replace("%y%", Integer.toString(player.getLocation().getBlockY()))
                .replace("%z%", Integer.toString(player.getLocation().getBlockZ()))
                .replace("%group%", group)
                .replace("%luckperms-prefix%", prefix)
                .replace("%luckperms-suffix%", suffix)
                .replace("%prefix%", prefix)
                .replace("%suffix%", suffix);
    }

    private int rank(Player player) {
        List<String> sort = plugin.lang().list("tab.sort");
        String group = group(player);
        for (int i = 0; i < sort.size(); i++) {
            if (group.equalsIgnoreCase(sort.get(i))) {
                return i;
            }
        }
        return sort.isEmpty() ? 0 : sort.size();
    }

    private String group(Player player) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return "default";
        }
        try {
            return LuckBridge.group(player);
        } catch (Exception ignored) {
            return "default";
        }
    }

    private String meta(Player player, boolean prefix) {
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            return "";
        }
        try {
            return LuckBridge.meta(player, prefix);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String teamName(UUID uuid, int rank) {
        String hex = uuid.toString().replace("-", "");
        return String.format(Locale.US, "%02d%s", Math.min(99, Math.max(0, rank)), hex.substring(0, 14));
    }

    private static Component parse(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(toMini(text));
        } catch (Exception ignored) {
            return Component.text(text);
        }
    }

    private static String toMini(String input) {
        String s = input.replace('\u00A7', '&');
        StringBuilder out = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if ((next == 'x' || next == 'X') && i + 13 < s.length()) {
                    boolean ok = true;
                    char[] hex = new char[6];
                    for (int h = 0; h < 6; h++) {
                        if (s.charAt(i + 2 + h * 2) != '&') {
                            ok = false;
                            break;
                        }
                        hex[h] = s.charAt(i + 3 + h * 2);
                    }
                    if (ok) {
                        out.append("<#").append(hex).append('>');
                        i += 14;
                        continue;
                    }
                }
                if (next == '#' && i + 7 < s.length()) {
                    String hex = s.substring(i + 2, i + 8);
                    boolean hexOk = true;
                    for (int h = 0; h < 6; h++) {
                        if (Character.digit(hex.charAt(h), 16) < 0) {
                            hexOk = false;
                            break;
                        }
                    }
                    if (hexOk) {
                        out.append("<#").append(hex).append('>');
                        i += 8;
                        continue;
                    }
                }
                String tag = switch (Character.toLowerCase(next)) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obf>";
                    case 'l' -> "<b>";
                    case 'm' -> "<st>";
                    case 'n' -> "<u>";
                    case 'o' -> "<i>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (tag != null) {
                    out.append(tag);
                    i += 2;
                    continue;
                }
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }
}
