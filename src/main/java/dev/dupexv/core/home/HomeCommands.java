package dev.dupexv.core.home;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

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
        switch (command.getName()) {
            case "home" -> {
                if (args.length < 1) {
                    homes.openMain(player);
                    return true;
                }
                homes.beginTeleport(player, args[0]);
            }
            case "sethome" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "home.set-usage");
                    return true;
                }
                homes.setHome(player, args[0]);
            }
            case "delhome" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "home.del-usage");
                    return true;
                }
                homes.delHome(player, args[0]);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        if (!player.hasPermission(DupeXvCore.HOME_PERMISSION)) {
            return List.of();
        }
        if (command.getName().equals("home") || command.getName().equals("delhome")) {
            return homes.matchHomes(player, args[0]);
        }
        return List.of();
    }
}
