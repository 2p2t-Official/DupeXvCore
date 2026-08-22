package dev.dupexv.core;

import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class NcpBridge {

    private static final String METADATA_KEY = "nocheat.exempt";
    private static final String[] CHECK_NAMES = {
            "MOVING",
            "MOVING_SURVIVALFLY",
            "MOVING_MOREPACKETS",
            "MOVING_PASSABLE",
            "NET_MOVING"
    };

    private static DupeXvCore plugin;
    private static Object checks;
    private static Method exempt;
    private static Method unexempt;

    private NcpBridge() {
    }

    public static void init(DupeXvCore core) {
        plugin = core;
        try {
            Class<?> api = Class.forName("fr.neatmonster.nocheatplus.checks.NoCheatPlusAPI");
            Class<?> type = Class.forName("fr.neatmonster.nocheatplus.checks.CheckType");
            List<Object> selected = new ArrayList<>();
            for (Object constant : type.getEnumConstants()) {
                String name = ((Enum<?>) constant).name();
                for (String wanted : CHECK_NAMES) {
                    if (name.equals(wanted)) {
                        selected.add(constant);
                        break;
                    }
                }
            }
            if (!selected.isEmpty()) {
                Class<?> arrayClass = Array.newInstance(type, 0).getClass();
                Object array = Array.newInstance(type, selected.size());
                for (int i = 0; i < selected.size(); i++) {
                    Array.set(array, i, selected.get(i));
                }
                checks = array;
                exempt = api.getMethod("exempt", Player.class, arrayClass);
                unexempt = api.getMethod("unexempt", Player.class, arrayClass);
            }
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
        }
    }

    public static void exempt(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (plugin != null) {
            player.setMetadata(METADATA_KEY, new FixedMetadataValue(plugin, true));
        }
        invoke(exempt, player);
    }

    public static void unexempt(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (plugin != null) {
            player.removeMetadata(METADATA_KEY, plugin);
        }
        invoke(unexempt, player);
    }

    private static void invoke(Method method, Player player) {
        if (method == null || checks == null) {
            return;
        }
        try {
            method.invoke(null, player, checks);
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }
    }
}
