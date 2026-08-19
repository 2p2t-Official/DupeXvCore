package dev.dupexv.core.home;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HomeCommands implements CommandExecutor, TabCompleter {

    private final DupeXvCore plugin;
    private final HomeService homes;

    public HomeCommands(DupeXvCore plugin, HomeService homes) {
        this.plugin = plugin;
        this.homes = homes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission(DupeXvCore.HOME_PERMISSION)) {
            plugin.lang().send(player, "no-permission");
            return true;
        }
        String name = String.join(" ", args).trim();
        switch (command.getName()) {
            case "home" -> {
                if (name.isEmpty()) {
                    homes.openMain(player);
                    return true;
                }
                homes.beginTeleport(player, name);
            }
            case "sethome" -> {
                if (name.isEmpty()) {
                    plugin.lang().send(player, "home.set-usage");
                    return true;
                }
                homes.setHome(player, name);
            }
            case "delhome" -> {
                if (name.isEmpty()) {
                    plugin.lang().send(player, "home.del-usage");
                    return true;
                }
                homes.delHome(player, name);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length < 1) {
            return List.of();
        }
        if (!player.hasPermission(DupeXvCore.HOME_PERMISSION)) {
            return List.of();
        }
        if (!command.getName().equals("home") && !command.getName().equals("delhome") && !command.getName().equals("sethome")) {
            return List.of();
        }
        String prefix = String.join(" ", args);
        String before = args.length == 1 ? "" : String.join(" ", java.util.Arrays.copyOf(args, args.length - 1)) + " ";
        List<String> out = new ArrayList<>();
        for (String home : homes.matchHomes(player, prefix)) {
            if (before.isEmpty()) {
                out.add(home);
                continue;
            }
            if (home.toLowerCase(Locale.ROOT).startsWith(before.toLowerCase(Locale.ROOT))) {
                out.add(home.substring(before.length()));
            }
        }
        return out;
    }
}
