package dev.dupexv.core.tpa;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class TpaCommands implements CommandExecutor, TabCompleter {

    private final DupeXvCore plugin;
    private final TpaService tpa;

    public TpaCommands(DupeXvCore plugin, TpaService tpa) {
        this.plugin = plugin;
        this.tpa = tpa;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission(DupeXvCore.TPA_PERMISSION)) {
            plugin.lang().send(player, "no-permission");
            return true;
        }
        switch (command.getName()) {
            case "tpa" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "tpa.usage");
                    return true;
                }
                tpa.requestTo(player, args[0]);
            }
            case "tpa-here" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "tpa.here-usage");
                    return true;
                }
                tpa.requestHere(player, args[0]);
            }
            case "tpa-cancel" -> tpa.cancel(player, args.length > 0 ? args[0] : null);
            case "tpa-accept" -> tpa.accept(player, args.length > 0 ? args[0] : null);
            case "tpa-deny" -> tpa.deny(player, args.length > 0 ? args[0] : null);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player) || args.length > 1) {
            return List.of();
        }
        if (!player.hasPermission(DupeXvCore.TPA_PERMISSION)) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0];
        return switch (command.getName()) {
            case "tpa", "tpa-here" -> tpa.matchOnline(player, prefix, true);
            case "tpa-accept", "tpa-deny" -> tpa.matchIncoming(player, prefix);
            case "tpa-cancel" -> tpa.matchOutgoing(player, prefix);
            default -> List.of();
        };
    }
}
