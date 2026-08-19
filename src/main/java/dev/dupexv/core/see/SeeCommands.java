package dev.dupexv.core.see;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SeeCommands implements CommandExecutor, TabCompleter {

    private final DupeXvCore plugin;
    private final SeeService see;

    public SeeCommands(DupeXvCore plugin, SeeService see) {
        this.plugin = plugin;
        this.see = see;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String perm = permission(command.getName());
        if (perm != null && !sender.hasPermission(perm)) {
            plugin.lang().send(sender, "no-permission");
            return true;
        }
        if (args.length < 1) {
            plugin.lang().send(sender, command.getName() + ".usage");
            return true;
        }
        Player target = findPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            plugin.lang().send(sender, "offline-player", "player", args[0]);
            return true;
        }
        switch (command.getName()) {
            case "invsee" -> {
                if (!(sender instanceof Player player)) {
                    plugin.lang().send(sender, "player-only");
                    return true;
                }
                see.openInv(player, target);
            }
            case "endersee" -> {
                if (!(sender instanceof Player player)) {
                    plugin.lang().send(sender, "player-only");
                    return true;
                }
                see.openEnder(player, target);
            }
            case "invclear" -> see.clearInv(sender, target);
            case "enderclear" -> see.clearEnder(sender, target);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        String perm = permission(command.getName());
        if (perm != null && !sender.hasPermission(perm)) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }
        String start = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(start)) {
                names.add(player.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private static String permission(String name) {
        return switch (name) {
            case "invsee" -> DupeXvCore.INVSEE_PERMISSION;
            case "endersee" -> DupeXvCore.ENDERSEE_PERMISSION;
            case "invclear" -> DupeXvCore.INVCLEAR_PERMISSION;
            case "enderclear" -> DupeXvCore.ENDERCLEAR_PERMISSION;
            default -> null;
        };
    }

    private static Player findPlayer(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) {
            return exact;
        }
        return Bukkit.getPlayer(name);
    }
}
