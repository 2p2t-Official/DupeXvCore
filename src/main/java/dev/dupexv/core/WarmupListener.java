package dev.dupexv.core;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class WarmupListener implements Listener {

    private final DupeXvCore plugin;

    public WarmupListener(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Location from = event.getFrom();
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        Player player = event.getPlayer();
        plugin.tpa().cancelWarmup(player, "tpa.moved");
        plugin.homes().cancelWarmup(player, "home.moved");
        plugin.spawn().cancelWarmup(player, "spawn.moved", true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        plugin.tpa().cancelWarmup(player, "tpa.moved");
        plugin.homes().cancelWarmup(player, "home.moved");
        plugin.spawn().cancelWarmup(player, "spawn.moved", true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        plugin.tpa().cancelWarmup(player, "tpa.moved");
        plugin.homes().cancelWarmup(player, "home.hurt");
        plugin.spawn().cancelWarmup(player, "spawn.hurt", true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        plugin.tpa().cancelWarmup(player, "tpa.moved");
        plugin.homes().cancelWarmup(player, "home.moved");
        plugin.spawn().cancelWarmup(player, "spawn.moved", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.homes().onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.tpa().onLeave(event.getPlayer());
        plugin.homes().onLeave(event.getPlayer());
        plugin.spawn().onLeave(event.getPlayer());
    }
}
