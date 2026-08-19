package dev.dupexv.core.website;

import dev.dupexv.core.DupeXvCore;
import dev.dupexv.core.tab.LuckBridge;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class LinkCommand implements CommandExecutor {

    private final DupeXvCore plugin;
    private final WebsiteLinkService link;

    public LinkCommand(DupeXvCore plugin, WebsiteLinkService link) {
        this.plugin = plugin;
        this.link = link;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "player-only");
            return true;
        }
        if (!player.hasPermission(DupeXvCore.LINK_PERMISSION)) {
            plugin.lang().send(player, "no-permission");
            return true;
        }
        if (args.length != 0) {
            plugin.lang().send(player, "link.usage");
            return true;
        }
        plugin.lang().send(player, "link.wait");
        String uuid = player.getUniqueId().toString();
        String username = player.getName();
        String group = "";
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") != null) {
            try {
                group = LuckBridge.group(player);
            } catch (Exception ignored) {
            }
        }
        String lpGroup = group;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            WebsiteLinkService.LinkResult result = link.verifyLink(uuid, username, lpGroup);
            player.getScheduler().run(plugin, scheduled -> {
                if (player.isOnline()) {
                    player.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize(result.playerMessage()));
                }
            }, null);
        });
        return true;
    }
}
