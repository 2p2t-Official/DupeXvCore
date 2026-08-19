package dev.dupexv.core.spawn;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SpawnCommand implements CommandExecutor {

    private final DupeXvCore plugin;
    private final SpawnService spawn;

    public SpawnCommand(DupeXvCore plugin, SpawnService spawn) {
        this.plugin = plugin;
        this.spawn = spawn;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission(DupeXvCore.SPAWN_PERMISSION)) {
            plugin.lang().send(player, "no-permission");
            return true;
        }
        spawn.spawn(player);
        return true;
    }
}
