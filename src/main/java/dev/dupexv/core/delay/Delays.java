package dev.dupexv.core.delay;

import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Locale;

public final class Delays {

    public int warmup(Permissible player, String feature, int fallback) {
        Integer value = numbered(player, feature, "warmup");
        return value != null ? value : Math.max(0, fallback);
    }

    public int cooldown(Permissible player, String feature, int fallback) {
        Integer value = numbered(player, feature, "cooldown");
        return value != null ? value : Math.max(0, fallback);
    }

    public int maxHomes(Permissible player) {
        int max = 0;
        for (int i = 2; i <= 10; i++) {
            if (player.hasPermission("dupexvcore.home.max." + i)) {
                max = i;
            }
        }
        return max;
    }

    private Integer numbered(Permissible player, String feature, String kind) {
        String prefix = ("dupexvcore." + feature + "." + kind + ".").toLowerCase(Locale.ROOT);
        Integer best = null;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            if (!info.getValue()) {
                continue;
            }
            String perm = info.getPermission();
            if (perm == null || !perm.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                continue;
            }
            String tail = perm.substring(prefix.length());
            try {
                int n = Integer.parseInt(tail);
                if (n < 0) {
                    continue;
                }
                if (best == null || n < best) {
                    best = n;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }
}
