package dev.dupexv.core.spawn;

import dev.dupexv.core.DupeXvCore;
import dev.dupexv.core.NcpBridge;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
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
    private final ConcurrentHashMap<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    private volatile int radius = 250;
    private volatile String worldName = "world";
    private volatile int warmupSeconds = 30;
    private volatile int cooldownSeconds = 30;

    public SpawnService(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        radius = Math.max(0, plugin.getConfig().getInt("spawn.radius", 250));
        String name = plugin.getConfig().getString("spawn.world", "world");
        worldName = name == null || name.isBlank() ? "world" : name.trim();
        warmupSeconds = Math.max(0, plugin.getConfig().getInt("spawn.warmup", 30));
        cooldownSeconds = Math.max(0, plugin.getConfig().getInt("spawn.cooldown", 30));
    }

    public void shutdown() {
        for (Warmup warmup : warmups.values()) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
        }
        warmups.clear();
        pending.clear();
    }

    public void spawn(Player player) {
        UUID id = player.getUniqueId();
        if (warmups.containsKey(id) || pending.containsKey(id)) {
            plugin.lang().tell(player, "spawn.wait");
            return;
        }
        int cooldown = plugin.delays().cooldown(player, "spawn", cooldownSeconds);
        long left = cooldownLeft(id);
        if (left > 0) {
            plugin.lang().tell(player, "spawn.cooldown", "seconds", left);
            return;
        }
        int warmup = plugin.delays().warmup(player, "spawn", warmupSeconds);
        if (warmup <= 0) {
            pending.put(id, Boolean.TRUE);
            tryFind(player, 0, cooldown);
            return;
        }
        Warmup w = new Warmup(player.getLocation().clone(), warmup, cooldown);
        warmups.put(id, w);
        w.task = player.getScheduler().runAtFixedRate(plugin, task -> tick(player, w), () -> warmups.remove(id, w), 1L, 20L);
    }

    public boolean cancelWarmup(Player player, String path, boolean applyCooldown) {
        Warmup w = warmups.remove(player.getUniqueId());
        if (w == null) {
            return false;
        }
        if (w.task != null) {
            w.task.cancel();
        }
        if (applyCooldown && w.cooldown > 0) {
            cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + w.cooldown * 1000L);
        }
        if (path != null) {
            plugin.lang().actionBar(player, path);
        }
        return true;
    }

    public void onLeave(Player player) {
        cancelWarmup(player, null, false);
        pending.remove(player.getUniqueId());
    }

    private void tick(Player player, Warmup w) {
        if (!player.isOnline()) {
            cancelWarmup(player, null, false);
            return;
        }
        if (player.getLocation().distanceSquared(w.start) > 0.0001) {
            cancelWarmup(player, "spawn.moved", true);
            return;
        }
        if (w.left <= 0) {
            warmups.remove(player.getUniqueId(), w);
            if (w.task != null) {
                w.task.cancel();
            }
            pending.put(player.getUniqueId(), Boolean.TRUE);
            tryFind(player, 0, w.cooldown);
            return;
        }
        plugin.lang().actionBar(player, "spawn.countdown", "seconds", w.left);
        w.left--;
    }

    private void tryFind(Player player, int attempt, int cooldown) {
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
                tryFind(player, attempt + 1, cooldown);
                return;
            }
            pending.remove(player.getUniqueId());
            NcpBridge.exempt(player);
            player.teleportAsync(found, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
                if (Boolean.TRUE.equals(ok)) {
                    if (cooldown > 0) {
                        cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
                    }
                } else {
                    plugin.lang().tell(player, "spawn.failed");
                }
                player.getScheduler().runDelayed(plugin, scheduled -> NcpBridge.unexempt(player), null, 40L);
            });
        });
    }

    private long cooldownLeft(UUID id) {
        Long until = cooldowns.get(id);
        if (until == null) {
            return 0L;
        }
        long left = until - System.currentTimeMillis();
        if (left <= 0L) {
            cooldowns.remove(id, until);
            return 0L;
        }
        return (left + 999L) / 1000L;
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

    private static final class Warmup {
        private final Location start;
        private int left;
        private final int cooldown;
        private volatile ScheduledTask task;

        private Warmup(Location start, int left, int cooldown) {
            this.start = start;
            this.left = left;
            this.cooldown = cooldown;
        }
    }
}
