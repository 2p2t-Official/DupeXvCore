package dev.dupexv.core.chat;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import dev.dupexv.core.DupeXvCore;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

public final class ChatReportsService implements Listener {

    private final DupeXvCore plugin;
    private PacketListenerAbstract packets;
    private volatile boolean enabled = true;
    private volatile boolean hidePopup = true;
    private volatile boolean preventKicks = true;

    public ChatReportsService(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public boolean hidePopup() {
        return hidePopup;
    }

    public void start() {
        reload();
        if (!enabled) {
            return;
        }
        if (!hasPacketEvents()) {
            plugin.getLogger().warning("[Chat] PacketEvents is missing. Chat reports are not stripped.");
            return;
        }
        packets = new ChatReportsPackets(this);
        PacketEvents.getAPI().getEventManager().registerListener(packets);
        plugin.getLogger().info("[Chat] Unsigned chat enabled");
    }

    public void shutdown() {
        if (packets != null && hasPacketEvents()) {
            try {
                PacketEvents.getAPI().getEventManager().unregisterListener(packets);
            } catch (Throwable ignored) {
            }
        }
        packets = null;
    }

    public void reload() {
        enabled = plugin.getConfig().getBoolean("chat-reports.enabled", true);
        hidePopup = plugin.getConfig().getBoolean("chat-reports.hide-popup", true);
        preventKicks = plugin.getConfig().getBoolean("chat-reports.prevent-kicks", true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        if (!enabled || !preventKicks) {
            return;
        }
        switch (event.getCause()) {
            case TOO_MANY_PENDING_CHATS, UNSIGNED_CHAT, CHAT_VALIDATION_FAILED,
                    EXPIRED_PROFILE_PUBLIC_KEY, OUT_OF_ORDER_CHAT, INVALID_PUBLIC_KEY_SIGNATURE -> {
                event.setCancelled(true);
                plugin.lang().tell(event.getPlayer(), "chat-reports.kick");
            }
            default -> {
            }
        }
    }

    private static boolean hasPacketEvents() {
        return Bukkit.getPluginManager().getPlugin("packetevents") != null
                || Bukkit.getPluginManager().getPlugin("PacketEvents") != null;
    }
}
