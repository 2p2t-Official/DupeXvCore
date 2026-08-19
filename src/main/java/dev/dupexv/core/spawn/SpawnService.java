package dev.dupexv.core.spawn;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class SpawnService {

    private static final int MAX_ATTEMPTS = 24;

    private final DupeXvCore plugin;
    private final ConcurrentHashMap<UUID, Boolean> pending = new ConcurrentHashMap<>();

    private volatile int radius = 250;
    private volatile String worldName = "world";

    public SpawnService(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        radius = Math.max(0, plugin.getConfig().getInt("spawn.radius", 250));
        String name = plugin.getConfig().getString("spawn.world", "world");
        worldName = name == null || name.isBlank() ? "world" : name.trim();
    }

    public void shutdown() {
        pending.clear();
    }

    public void spawn(Player player) {
        if (pending.putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) {
            return;
        }
        tryFind(player, 0);
    }

    private void tryFind(Player player, int attempt) {
        if (!player.isOnline()) {
            pending.remove(player.getUniqueId());
            return;
        }
        World world = resolveWorld();
        if (world == null) {
            pending.remove(player.getUniqueId());
            plugin.lang().tell(player, "spawn.world");
            return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            pending.remove(player.getUniqueId());
            plugin.lang().tell(player, "spawn.failed");
            return;
        }
        int[] xz = randomXZ();
        Location probe = new Location(world, xz[0], 64, xz[1]);
        Bukkit.getRegionScheduler().run(plugin, probe, task -> {
            if (!player.isOnline()) {
                pending.remove(player.getUniqueId());
                return;
            }
            Location found = scan(world, xz[0], xz[1]);
            if (found == null) {
                tryFind(player, attempt + 1);
                return;
            }
            pending.remove(player.getUniqueId());
            player.teleportAsync(found, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    plugin.lang().tell(player, "spawn.done");
                } else {
                    plugin.lang().tell(player, "spawn.failed");
                }
            });
        });
    }

    private int[] randomXZ() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int range = radius;
        if (range <= 0) {
            return new int[]{0, 0};
        }
        double angle = random.nextDouble() * Math.PI * 2.0;
        double dist = Math.sqrt(random.nextDouble()) * range;
        int x = (int) Math.round(Math.cos(angle) * dist);
        int z = (int) Math.round(Math.sin(angle) * dist);
        return new int[]{x, z};
    }

    private Location scan(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight()) {
            return null;
        }
        Block ground = world.getBlockAt(x, y, z);
        Block feet = ground.getRelative(0, 1, 0);
        Block head = feet.getRelative(0, 1, 0);
        if (!ground.getType().isSolid() || unsafe(ground.getType())) {
            return null;
        }
        if (!feet.isEmpty() && !feet.isPassable()) {
            return null;
        }
        if (!head.isEmpty() && !head.isPassable()) {
            return null;
        }
        if (unsafe(feet.getType()) || unsafe(head.getType())) {
            return null;
        }
        return new Location(world, x + 0.5, feet.getY(), z + 0.5);
    }

    private static boolean unsafe(Material material) {
        return material == Material.LAVA
                || material == Material.WATER
                || material == Material.MAGMA_BLOCK
                || material == Material.FIRE
                || material == Material.SOUL_FIRE
                || material == Material.CACTUS
                || material == Material.SWEET_BERRY_BUSH
                || material == Material.POWDER_SNOW
                || material == Material.KELP
                || material == Material.KELP_PLANT
                || material == Material.SEAGRASS
                || material == Material.TALL_SEAGRASS;
    }

    private World resolveWorld() {
        World named = Bukkit.getWorld(worldName);
        if (named != null) {
            return named;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
    }
}
