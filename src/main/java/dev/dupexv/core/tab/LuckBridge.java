package dev.dupexv.core.tab;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
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

    public static int weight(Player player) {
        User user = LuckPermsProvider.get().getPlayerAdapter(Player.class).getUser(player);
        String groupName = user.getCachedData().getMetaData().getPrimaryGroup();
        if (groupName == null || groupName.isBlank()) {
            return Integer.MIN_VALUE;
        }
        Group group = LuckPermsProvider.get().getGroupManager().getGroup(groupName);
        if (group == null) {
            return Integer.MIN_VALUE;
        }
        return group.getWeight().orElse(Integer.MIN_VALUE);
    }

    public static String meta(Player player, boolean prefix) {
        CachedMetaData meta = LuckPermsProvider.get().getPlayerAdapter(Player.class).getUser(player).getCachedData().getMetaData();
        String value = prefix ? meta.getPrefix() : meta.getSuffix();
        return value == null ? "" : value;
    }
}
