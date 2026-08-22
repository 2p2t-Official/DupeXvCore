package dev.dupexv.core;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CoreCommands implements CommandExecutor, TabCompleter {

    private final DupeXvCore plugin;

    public CoreCommands(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            plugin.lang().send(sender, "core.usage");
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(DupeXvCore.ADMIN_PERMISSION)) {
                plugin.lang().send(sender, "no-permission");
                return true;
            }
            plugin.reloadAll();
            plugin.lang().send(sender, "core.reloaded");
            return true;
        }
        plugin.lang().send(sender, "core.usage");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("reload".startsWith(prefix)) {
                out.add("reload");
            }
            return out;
        }
        return List.of();
    }
}
