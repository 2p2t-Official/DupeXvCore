package dev.dupexv.core.tab;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import org.bukkit.entity.Player;

public final class LuckBridge {

    private LuckBridge() {
    }

    public static String group(Player player) {
        User user = LuckPermsProvider.get().getPlayerAdapter(Player.class).getUser(player);
        String group = user.getPrimaryGroup();
        return group == null || group.isBlank() ? "default" : group;
    }

    public static String meta(Player player, boolean prefix) {
        CachedMetaData meta = LuckPermsProvider.get().getPlayerAdapter(Player.class).getUser(player).getCachedData().getMetaData();
        String value = prefix ? meta.getPrefix() : meta.getSuffix();
        return value == null ? "" : value;
    }
}
