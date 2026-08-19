package dev.dupexv.core.tpa;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class TpaListener implements Listener {

    private final TpaService tpa;

    public TpaListener(TpaService tpa) {
        this.tpa = tpa;
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
        tpa.cancelWarmup(event.getPlayer(), "tpa.moved");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        tpa.cancelWarmup(event.getPlayer(), "tpa.moved");
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        tpa.cancelWarmup(event.getEntity(), "tpa.moved");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tpa.onLeave(event.getPlayer());
    }
}
