package dev.dupexv.core.regiondebug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

public final class RegionDebugCollector {

    private final Plugin plugin;
    private volatile JsonObject lastSnapshot = emptySnapshot("not_yet_collected");
    private final ConcurrentHashMap<String, TileSample> tileCache = new ConcurrentHashMap<>();
    private final Set<String> tileInFlight = ConcurrentHashMap.newKeySet();
    private final RegionEntityUndoStore undoStore = new RegionEntityUndoStore();
    private final RegionTileUndoStore tileUndoStore = new RegionTileUndoStore();

    private record TileSample(long ts, Map<String, Integer> types, int tickingTiles,
                              int pendingBlockTicks, int pendingFluidTicks, int primedTnt,
                              int entityTickingChunks, int navigatingMobs, String status,
                              Map<String, Integer> scheduledRedstone, Map<String, Integer> redstoneTiles,
                              int neighborUpdates, int torchToggles,
                              Map<String, Integer> containerDiag) {
    }

    private static TileSample tileError(String status) {
        return new TileSample(System.currentTimeMillis(), Map.of(), 0, 0, 0, 0, 0, 0, status,
                Map.of(), Map.of(), 0, 0, Map.of());
    }

    private final List<JsonObject> clearResults = new CopyOnWriteArrayList<>();

    public RegionDebugCollector(Plugin plugin) {
        this.plugin = plugin;
    }

    private boolean isRegionDebugStressed() {
        double threshold = plugin.getConfig().getDouble("regions.skip-container-diag-mspt", 40.0);
        try {
            double mspt = Bukkit.getServer().getAverageTickTime();
            return mspt >= threshold;
        } catch (Throwable t) {
            return false;
        }
    }

    public JsonObject lastSnapshot() {
        return lastSnapshot;
    }

    public JsonObject collect() {
        JsonObject root = new JsonObject();
        root.addProperty("ts", System.currentTimeMillis());
        root.addProperty("folia", true);
        root.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
        root.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        try {
            root.addProperty("bukkitVersion", Bukkit.getVersion());
            root.addProperty("minecraftVersion", Bukkit.getMinecraftVersion());
        } catch (Throwable ignored) {
        }
        root.add("jvm", RegionSparkMetrics.jvmHealth());
        JsonArray worldsJson = new JsonArray();
        for (World w : Bukkit.getWorlds()) {
            worldsJson.add(RegionSparkMetrics.worldInfo(w));
        }
        root.add("worlds", worldsJson);

        try {
            Class<?> tickRegionsClass = Class.forName("io.papermc.paper.threadedregions.TickRegions");
            Method getScheduler = tickRegionsClass.getMethod("getScheduler");
            Object scheduler = getScheduler.invoke(null);
            Method getTotalThreadCount = scheduler.getClass().getMethod("getTotalThreadCount");
            root.addProperty("regionThreads", ((Number) getTotalThreadCount.invoke(scheduler)).intValue());
        } catch (Throwable t) {
            root.addProperty("folia", false);
            root.addProperty("error", "not_folia_or_unsupported: " + t.getClass().getSimpleName());
            root.add("regions", new JsonArray());
            lastSnapshot = root;
            return root;
        }

        fillGlobalTick(root);

        Map<String, List<JsonObject>> playersByRegion = mapPlayersToRegions();
        List<JsonObject> regions = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            try {
                collectWorldRegions(world, playersByRegion, regions);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "[Regions] world " + world.getName() + ": " + t.getMessage());
            }
        }

        regions.sort(Comparator
                .comparingDouble((JsonObject o) -> o.has("mspt5s") ? o.get("mspt5s").getAsDouble() : 0.0)
                .reversed());

        JsonArray arr = new JsonArray();
        for (JsonObject r : regions) {
            arr.add(r);
        }
        root.add("regions", arr);
        root.addProperty("regionCount", regions.size());

        JsonArray hot = new JsonArray();
        for (int i = 0; i < Math.min(10, regions.size()); i++) {
            hot.add(regions.get(i));
        }
        root.add("hotRegions", hot);

        JsonArray clears = new JsonArray();
        for (JsonObject c : clearResults) {
            clears.add(c);
        }
        while (clearResults.size() > 30) {
            clearResults.remove(0);
        }
        root.add("clearResults", clears);
        JsonArray restore = undoStore.toJson();
        for (var el : tileUndoStore.toJson()) {
            restore.add(el);
        }
        root.add("restoreAvailable", restore);

        double worstMspt = regions.isEmpty() ? 0.0
                : regions.get(0).has("mspt5s") ? regions.get(0).get("mspt5s").getAsDouble() : 0.0;
        root.addProperty("worstRegionMspt5s", round2(worstMspt));
        root.addProperty("hint",
                "Folia is regionized: global TPS can stay ~20 while one region burns its budget. "
                        + "MSPT is work per tick; util is MSPT / tick-interval; low TPS with MSPT well under 50ms usually means the region thread is shared (long tick interval), not that entities alone are over budget. "
                        + "Chunks stay loaded by tickets (players/view/sim, forceload, portals, plugins), not only by someone standing in them.");

        lastSnapshot = root;
        return root;
    }

    private void fillGlobalTick(JsonObject root) {
        try {
            Class<?> regionizedServer = Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            Method getGlobal = regionizedServer.getMethod("getGlobalTickData");
            Object handle = getGlobal.invoke(null);
            if (handle == null) {
                return;
            }
            putTickReports(root, "global", handle);
        } catch (Throwable ignored) {
        }
    }

    private void collectWorldRegions(World world, Map<String, List<JsonObject>> playersByRegion,
                                     List<JsonObject> out) throws Exception {
        Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
        Field regioniserField = findField(serverLevel.getClass(), "regioniser");
        regioniserField.setAccessible(true);
        Object regioniser = regioniserField.get(serverLevel);
        if (regioniser == null) {
            return;
        }

        Object ticketsByChunk = copyWorldTickets(serverLevel);
        Set<Long> forceLoaded = forceLoadedChunkKeys(world);
        Map<Object, String> ticketTypeNames = ticketTypeNameMap();

        Method computeForAll = regioniser.getClass().getMethod("computeForAllRegions", java.util.function.Consumer.class);
        List<Object> threadedRegions = new CopyOnWriteArrayList<>();
        computeForAll.invoke(regioniser, (java.util.function.Consumer<Object>) threadedRegions::add);

        for (Object region : threadedRegions) {
            JsonObject row = regionToJson(world, region, playersByRegion, ticketsByChunk, forceLoaded, ticketTypeNames);
            if (row != null) {
                out.add(row);
            }
        }
    }

    private JsonObject regionToJson(World world, Object threadedRegion,
                                    Map<String, List<JsonObject>> playersByRegion,
                                    Object ticketsByChunk,
                                    Set<Long> forceLoaded,
                                    Map<Object, String> ticketTypeNames) {
        try {
            String worldName = world.getName();
            Field idField = findField(threadedRegion.getClass(), "id");
            idField.setAccessible(true);
            long id = idField.getLong(threadedRegion);
            String key = worldName + ":" + id;

            Method getData = threadedRegion.getClass().getMethod("getData");
            Object tickRegionData = getData.invoke(threadedRegion);
            if (tickRegionData == null) {
                return null;
            }

            Method getRegionStats = tickRegionData.getClass().getMethod("getRegionStats");
            Object stats = getRegionStats.invoke(tickRegionData);

            int entityCount = ((Number) stats.getClass().getMethod("getEntityCount").invoke(stats)).intValue();
            int playerCount = ((Number) stats.getClass().getMethod("getPlayerCount").invoke(stats)).intValue();
            int chunkCount = ((Number) stats.getClass().getMethod("getChunkCount").invoke(stats)).intValue();

            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("world", worldName);
            row.addProperty("chunks", chunkCount);
            row.addProperty("entities", entityCount);
            row.addProperty("playerCount", playerCount);
            row.add("worldInfo", RegionSparkMetrics.worldInfo(world));

            Set<Long> ownedChunks = ownedChunkKeys(threadedRegion);

            try {
                Method getCenterChunk = threadedRegion.getClass().getMethod("getCenterChunk");
                Object chunkPos = getCenterChunk.invoke(threadedRegion);
                if (chunkPos != null) {
                    int cx;
                    int cz;
                    try {
                        cx = ((Number) chunkPos.getClass().getMethod("x").invoke(chunkPos)).intValue();
                        cz = ((Number) chunkPos.getClass().getMethod("z").invoke(chunkPos)).intValue();
                    } catch (Throwable t) {
                        cx = ((Number) chunkPos.getClass().getField("x").get(chunkPos)).intValue();
                        cz = ((Number) chunkPos.getClass().getField("z").get(chunkPos)).intValue();
                    }
                    row.addProperty("centerChunkX", cx);
                    row.addProperty("centerChunkZ", cz);
                    row.addProperty("centerBlockX", cx * 16 + 8);
                    row.addProperty("centerBlockZ", cz * 16 + 8);
                }
            } catch (Throwable ignored) {
            }

            try {
                Method getCurrentTick = tickRegionData.getClass().getMethod("getCurrentTick");
                row.addProperty("tick", ((Number) getCurrentTick.invoke(tickRegionData)).longValue());
            } catch (Throwable ignored) {
            }

            try {
                Method getHandle = tickRegionData.getClass().getMethod("getRegionSchedulingHandle");
                Object scheduleHandle = getHandle.invoke(tickRegionData);
                if (scheduleHandle != null) {
                    putTickReports(row, "", scheduleHandle);
                }
            } catch (Throwable ignored) {
            }

            List<JsonObject> details = playersByRegion.getOrDefault(key, List.of());
            JsonArray players = new JsonArray();
            JsonArray playerDetails = new JsonArray();
            for (JsonObject d : details) {
                playerDetails.add(d);
                players.add(RegionSparkMetrics.playerLabel(d));
            }
            row.add("players", players);
            row.add("playerDetails", playerDetails);
            row.addProperty("listedPlayers", details.size());
            RegionSparkMetrics.applyPlayerSpread(row, details);

            fillKeepAlive(row, tickRegionData, ownedChunks, ticketsByChunk, forceLoaded, ticketTypeNames, details.size(), playerCount);

            int cx = row.has("centerChunkX") ? row.get("centerChunkX").getAsInt() : 0;
            int cz = row.has("centerChunkZ") ? row.get("centerChunkZ").getAsInt() : 0;
            applyTileCache(row, world, id, cx, cz);

            Map<String, Integer> entityTypes = new LinkedHashMap<>();
            if (row.has("entityTypes") && row.get("entityTypes").isJsonObject()) {
                row.getAsJsonObject("entityTypes").entrySet()
                        .forEach(e -> entityTypes.put(e.getKey(), e.getValue().getAsInt()));
            }
            Map<String, Integer> tileTypes = new LinkedHashMap<>();
            if (row.has("tileEntityTypes") && row.get("tileEntityTypes").isJsonObject()) {
                row.getAsJsonObject("tileEntityTypes").entrySet()
                        .forEach(e -> tileTypes.put(e.getKey(), e.getValue().getAsInt()));
            }
            int rts = 3;
            try {
                Integer v = world.getGameRuleValue(org.bukkit.GameRule.RANDOM_TICK_SPEED);
                if (v != null) rts = v;
            } catch (Throwable ignored) {
            }
            RegionSparkMetrics.applyLoadBreakdown(row, entityTypes, tileTypes, chunkCount, rts, details.size());

            return row;
        } catch (Throwable t) {
            return null;
        }
    }

    private void fillKeepAlive(JsonObject row, Object tickRegionData, Set<Long> ownedChunks,
                               Object ticketsByChunk, Set<Long> forceLoaded,
                               Map<Object, String> ticketTypeNames,
                               int listedPlayers, int playerCount) {
        Map<String, Integer> ticketCounts = new LinkedHashMap<>();
        Map<String, Integer> ticketLevels = new LinkedHashMap<>();
        int ticketsOnOwned = 0;
        int chunksWithTickets = 0;
        if (ticketsByChunk != null && !ownedChunks.isEmpty()) {
            try {
                Method get = ticketsByChunk.getClass().getMethod("get", long.class);
                for (long chunkKey : ownedChunks) {
                    Object coll = get.invoke(ticketsByChunk, chunkKey);
                    if (!(coll instanceof Collection<?> tickets) || tickets.isEmpty()) {
                        continue;
                    }
                    chunksWithTickets++;
                    for (Object ticket : tickets) {
                        ticketsOnOwned++;
                        String typeName = ticketTypeLabel(ticket, ticketTypeNames);
                        ticketCounts.merge(typeName, 1, Integer::sum);
                        try {
                            int lvl = ((Number) ticket.getClass().getMethod("getTicketLevel").invoke(ticket)).intValue();
                            ticketLevels.merge("level_" + lvl, 1, Integer::sum);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        int forceInRegion = 0;
        if (!forceLoaded.isEmpty() && !ownedChunks.isEmpty()) {
            for (long chunkKey : ownedChunks) {
                if (forceLoaded.contains(chunkKey)) {
                    forceInRegion++;
                }
            }
        }

            Map<String, Integer> entityTypes = sampleEntityTypes(tickRegionData);
        Map<String, Integer> tileTypes = sampleTileEntityTypes(tickRegionData);
        int enderPearlEntities = entityTypes.getOrDefault("ender_pearl", 0)
                + entityTypes.getOrDefault("minecraft:ender_pearl", 0);

        EntityCategories cats = categorizeEntities(entityTypes);
        JsonObject categories = new JsonObject();
        categories.addProperty("hostile", cats.hostile);
        categories.addProperty("passive", cats.passive);
        categories.addProperty("items", cats.items);
        categories.addProperty("xp", cats.xp);
        categories.addProperty("projectiles", cats.projectiles);
        categories.addProperty("vehicles", cats.vehicles);
        categories.addProperty("display", cats.display);
        categories.addProperty("other", cats.other);
        row.add("entityCategories", categories);

        int chunkCount = row.has("chunks") ? row.get("chunks").getAsInt() : 0;
        int entityCount = row.has("entities") ? row.get("entities").getAsInt() : 0;
        int ownedChunkCount = ownedChunks.size();
        row.addProperty("ownedChunks", ownedChunkCount);
        if (ownedChunkCount > 0 || chunkCount > 0) {
            int denom = chunkCount > 0 ? chunkCount : ownedChunkCount;
            row.addProperty("entitiesPerChunk", round2(entityCount / (double) Math.max(1, denom)));
        }
        fillChunkBounds(row, ownedChunks);

        int tileTotal = tileTypes.values().stream().mapToInt(Integer::intValue).sum();
        row.addProperty("tileEntities", tileTotal);
        JsonObject tilesJson = new JsonObject();
        tileTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(20)
                .forEach(e -> tilesJson.addProperty(e.getKey(), e.getValue()));
        row.add("tileEntityTypes", tilesJson);

        List<String> reasons = new ArrayList<>();
        if (listedPlayers > 0 || playerCount > 0
                || ticketCounts.containsKey("player_loading")
                || ticketCounts.containsKey("player_simulation")
                || ticketCounts.containsKey("player_spawn")
                || hasTicketPrefix(ticketCounts, "player")) {
            reasons.add("players");
        }
        if (forceInRegion > 0 || ticketCounts.containsKey("forced") || hasTicketPrefix(ticketCounts, "forced")) {
            reasons.add("forceload");
        }
        if (ticketCounts.containsKey("portal")
                || ticketCounts.containsKey("nether_portal_double_check")
                || ticketCounts.containsKey("end_gateway_exit_search")
                || hasTicketPrefix(ticketCounts, "portal")) {
            reasons.add("portal");
        }
        if (ticketCounts.containsKey("ender_pearl") || enderPearlEntities > 0
                || hasTicketPrefix(ticketCounts, "ender_pearl")) {
            reasons.add("ender_pearl");
        }
        if (ticketCounts.containsKey("plugin") || ticketCounts.containsKey("plugin_ticket")
                || hasTicketPrefix(ticketCounts, "plugin")) {
            reasons.add("plugin");
        }
        if (ticketCounts.containsKey("dragon") || hasTicketPrefix(ticketCounts, "dragon")) {
            reasons.add("dragon");
        }
        if (ticketCounts.containsKey("region_scheduler_api_hold")
                || hasTicketPrefix(ticketCounts, "region_scheduler")) {
            reasons.add("region_scheduler");
        }
        if (ticketCounts.containsKey("delayed") || ticketCounts.containsKey("post_teleport")
                || ticketCounts.containsKey("teleport_hold_ticket")
                || ticketCounts.containsKey("chunk_load")
                || ticketCounts.containsKey("future_await")
                || hasTicketPrefix(ticketCounts, "teleport")
                || hasTicketPrefix(ticketCounts, "delayed")) {
            reasons.add("transient");
        }
        if (reasons.isEmpty() && ticketsOnOwned > 0) {
            reasons.add("other_tickets");
        }
        if (reasons.isEmpty() && row.has("chunks") && row.get("chunks").getAsInt() > 0) {
            reasons.add("unknown");
        }

        JsonArray reasonArr = new JsonArray();
        for (String r : reasons) {
            reasonArr.add(r);
        }
        row.add("keptAliveBy", reasonArr);

        JsonObject ticketsJson = new JsonObject();
        ticketCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> ticketsJson.addProperty(e.getKey(), e.getValue()));
        row.add("ticketCounts", ticketsJson);
        JsonObject levelsJson = new JsonObject();
        ticketLevels.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> levelsJson.addProperty(e.getKey(), e.getValue()));
        row.add("ticketLevels", levelsJson);
        row.addProperty("ticketChunks", chunksWithTickets);
        row.addProperty("ticketTotal", ticketsOnOwned);
        row.addProperty("forceLoadedChunks", forceInRegion);

        JsonObject entitiesJson = new JsonObject();
        entityTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(40)
                .forEach(e -> entitiesJson.addProperty(e.getKey(), e.getValue()));
        row.add("entityTypes", entitiesJson);
        if (enderPearlEntities > 0) {
            row.addProperty("enderPearlEntities", enderPearlEntities);
        }

        row.addProperty("keepAliveSummary", formatKeepAliveSummary(reasons, ticketCounts, forceInRegion, enderPearlEntities));
    }

    private static boolean hasTicketPrefix(Map<String, Integer> ticketCounts, String needle) {
        String n = needle.toLowerCase(Locale.ROOT);
        for (String k : ticketCounts.keySet()) {
            if (k.toLowerCase(Locale.ROOT).contains(n)) {
                return true;
            }
        }
        return false;
    }

    private void fillChunkBounds(JsonObject row, Set<Long> ownedChunks) {
        if (ownedChunks.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : ownedChunks) {
            int[] xz = unpackChunkPos(key);
            int x = xz[0];
            int z = xz[1];
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        if (minX != Integer.MAX_VALUE) {
            row.addProperty("chunkMinX", minX);
            row.addProperty("chunkMaxX", maxX);
            row.addProperty("chunkMinZ", minZ);
            row.addProperty("chunkMaxZ", maxZ);
            row.addProperty("chunkSpanX", maxX - minX + 1);
            row.addProperty("chunkSpanZ", maxZ - minZ + 1);
        }
    }

    private JsonArray buildLagHints(JsonObject row) {
        JsonArray hints = new JsonArray();
        double mspt = row.has("mspt5s") ? row.get("mspt5s").getAsDouble() : 0;
        double util = row.has("util5s") ? row.get("util5s").getAsDouble() : 0;
        double tps = row.has("tps5s") ? row.get("tps5s").getAsDouble() : 20;
        boolean hot = mspt >= 25 || util >= 0.45 || tps < 19;
        int chunkCount = row.has("chunks") ? row.get("chunks").getAsInt() : 0;
        int entityCount = row.has("entities") ? row.get("entities").getAsInt() : 0;
        int listedPlayers = row.has("listedPlayers") ? row.get("listedPlayers").getAsInt() : 0;
        int playerCount = row.has("playerCount") ? row.get("playerCount").getAsInt() : 0;
        int tileTotal = row.has("tileEntities") ? row.get("tileEntities").getAsInt() : 0;
        JsonObject catsJson = row.has("entityCategories") && row.get("entityCategories").isJsonObject()
                ? row.getAsJsonObject("entityCategories") : new JsonObject();
        int catItems = catsJson.has("items") ? catsJson.get("items").getAsInt() : 0;
        int catHostile = catsJson.has("hostile") ? catsJson.get("hostile").getAsInt() : 0;
        int catPassive = catsJson.has("passive") ? catsJson.get("passive").getAsInt() : 0;
        int catVehicles = catsJson.has("vehicles") ? catsJson.get("vehicles").getAsInt() : 0;
        int catProjectiles = catsJson.has("projectiles") ? catsJson.get("projectiles").getAsInt() : 0;
        Map<String, Integer> tileTypes = new LinkedHashMap<>();
        if (row.has("tileEntityTypes") && row.get("tileEntityTypes").isJsonObject()) {
            row.getAsJsonObject("tileEntityTypes").entrySet()
                    .forEach(e -> tileTypes.put(e.getKey(), e.getValue().getAsInt()));
        }

        int expectedView = row.has("expectedViewChunks") ? row.get("expectedViewChunks").getAsInt() : 0;
        double viewFactor = row.has("chunkLoadFactor") ? row.get("chunkLoadFactor").getAsDouble() : 0;

        if (expectedView > 0 && chunkCount >= 200) {
            
            int viewRadius = Math.max(0, ((int) Math.round(Math.sqrt(expectedView)) - 1) / 2);
            int slackSquare = (2 * viewRadius + 3) * (2 * viewRadius + 3);
            if (chunkCount > slackSquare * 1.35) {
                hints.add("Loaded chunks (" + chunkCount + ") are well above the largest player view square ("
                        + expectedView + ", +1 chunk slack " + slackSquare
                        + ") — extra tickets or far-apart players, not just view/sim distance.");
            }
        }
        if (catItems >= 200) {
            hints.add(catItems + " dropped item entities sitting on the ground (not chest/inventory items). "
                    + "They tick every tick — use Delete on “item” below to remove them.");
        }
        if (catHostile >= 150) {
            hints.add("Dense hostile mobs (" + catHostile + ") — consider /clear-entities mobs hostile.");
        }
        if (catPassive >= 200) {
            hints.add("Dense passive animals (" + catPassive + ") — farms / breed piles often show up as chicken/pig/cow/sheep spikes.");
        }
        if (catVehicles >= 40) {
            hints.add("Many vehicles/minecarts (" + catVehicles + ") — hopper minecart lines and chest carts are expensive.");
        }
        if (catProjectiles >= 80) {
            hints.add("Many projectiles (" + catProjectiles + ") — arrows/pearls/fireballs can spike MSPT.");
        }
        if (tileTotal >= 500) {
            hints.add("High tile-entity count (" + tileTotal + ") — hoppers/furnaces/pistons/etc. often dominate when entity counts look fine.");
        }
        int hoppers = tileTypes.getOrDefault("hopper", 0) + tileTypes.getOrDefault("minecraft:hopper", 0);
        if (hoppers >= 100) {
            hints.add("Many hoppers (" + hoppers + ") — classic redstone/item-transfer lag source.");
        }
        int pistons = tileTypes.getOrDefault("piston", 0) + tileTypes.getOrDefault("sticky_piston", 0)
                + tileTypes.getOrDefault("minecraft:piston", 0) + tileTypes.getOrDefault("minecraft:sticky_piston", 0);
        if (pistons >= 50) {
            hints.add("Many pistons (" + pistons + ") — check for flying machines / clocks.");
        }
        if (hot && entityCount < 80 && tileTotal < 100 && chunkCount > 200) {
            hints.add("Hot region with relatively few entities/tiles — likely chunk/block-tick load (random ticks, fluids, redstone) or something outside the entity sample.");
        }
        if (hot && listedPlayers == 0 && playerCount == 0) {
            hints.add("No players listed in this region — keep-alive tickets (forceload/portal/plugin/pearl) may be holding it loaded. Check ticketCounts.");
        }
        if (row.has("missingCpuMs5s") && row.get("missingCpuMs5s").getAsDouble() >= 5) {
            hints.add("Missing CPU time ~" + row.get("missingCpuMs5s").getAsDouble()
                    + "ms/tick (5s) — the region is waiting on CPU (thread contention / overloaded host), not only world work.");
        }
        if (hot && util < 0.3 && mspt >= 30) {
            hints.add("High MSPT but low util — ticks are slow but not filling the whole interval; look for spikes / wait time rather than steady entity load.");
        }
        if (row.has("tickIntervalMs5s") && row.has("mspt5s")) {
            double interval = row.get("tickIntervalMs5s").getAsDouble();
            double miss = row.has("missingCpuMs5s") ? row.get("missingCpuMs5s").getAsDouble() : 0;
            if (tps < 18 && mspt < 45 && interval > 55 && miss < 2) {
                hints.add("Tick interval ~" + round2(interval)
                        + "ms with MSPT still under budget — Folia region-thread sharing / scheduler cadence, not just entity/tile work. "
                        + "Other hot regions on the same thread will drag this TPS down.");
            }
        }
        if (row.has("redstoneScheduledTotal") && row.get("redstoneScheduledTotal").getAsInt() >= 150) {
            hints.add("Heavy redstone clocks: " + row.get("redstoneScheduledTotal").getAsInt()
                    + " scheduled repeater/observer/comparator/etc. ticks in this region.");
        }
        if (row.has("neighborUpdates") && row.get("neighborUpdates").getAsInt() >= 400) {
            hints.add("Large redstone neighbor-update queue (" + row.get("neighborUpdates").getAsInt()
                    + ") — dust/piston/observer spam.");
        }
        if (row.has("pendingBlockTicks") && row.get("pendingBlockTicks").getAsInt() >= 2000) {
            hints.add("Huge scheduled block-tick queue (" + row.get("pendingBlockTicks").getAsInt()
                    + ") — observer/repeater/farm clocks or random-tick storms.");
        }
        if (row.has("pendingFluidTicks") && row.get("pendingFluidTicks").getAsInt() >= 1500) {
            hints.add("Huge fluid-tick queue (" + row.get("pendingFluidTicks").getAsInt()
                    + ") — water/lava flow or dripstone/farm fluid spam.");
        }
        if (row.has("tickingTileEntities") && row.get("tickingTileEntities").getAsInt() >= 200) {
            hints.add("Many ticking tile entities (" + row.get("tickingTileEntities").getAsInt()
                    + ") — hoppers/furnaces/brewers dominate when entity counts look fine.");
        }
        if (hints.size() == 0 && hot) {
            hints.add("Region is behind 20 TPS but samples look moderate — compare 5s vs 1m MSPT/min/max, check ticking TEs + scheduled block/fluid ticks, and inspect the area in-game for redstone/farms.");
        }
        return hints;
    }

    private record EntityCategories(int hostile, int passive, int items, int xp, int projectiles,
                                    int vehicles, int display, int other) {
    }

    private EntityCategories categorizeEntities(Map<String, Integer> types) {
        int hostile = 0, passive = 0, items = 0, xp = 0, projectiles = 0, vehicles = 0, display = 0, other = 0;
        for (Map.Entry<String, Integer> e : types.entrySet()) {
            String t = e.getKey().toLowerCase(Locale.ROOT).replace("minecraft:", "");
            int n = e.getValue();
            if (t.equals("item")) {
                items += n;
            } else if (t.equals("experience_orb") || t.equals("xp_orb")) {
                xp += n;
            } else if (isHostileType(t)) {
                hostile += n;
            } else if (isProjectileType(t)) {
                projectiles += n;
            } else if (isVehicleType(t)) {
                vehicles += n;
            } else if (isDisplayType(t)) {
                display += n;
            } else if (isPassiveType(t)) {
                passive += n;
            } else {
                other += n;
            }
        }
        return new EntityCategories(hostile, passive, items, xp, projectiles, vehicles, display, other);
    }

    private static boolean isHostileType(String t) {
        return t.contains("zombie") || t.contains("skeleton") || t.contains("creeper") || t.contains("spider")
                || t.contains("enderman") || t.contains("witch") || t.contains("slime") || t.contains("magma")
                || t.contains("phantom") || t.contains("drowned") || t.contains("pillager") || t.contains("vindicator")
                || t.contains("ravager") || t.contains("hoglin") || t.contains("piglin") || t.contains("blaze")
                || t.contains("ghast") || t.contains("wither") || t.contains("guardian") || t.contains("shulker")
                || t.equals("warden") || t.contains("breeze") || t.contains("bogged") || t.contains("husk")
                || t.contains("stray") || t.contains("vex") || t.contains("evoker") || t.contains("silverfish")
                || t.contains("endermite") || t.contains("cave_spider") || t.contains("zoglin");
    }

    private static boolean isPassiveType(String t) {
        return t.equals("pig") || t.equals("cow") || t.equals("sheep") || t.equals("chicken") || t.equals("rabbit")
                || t.equals("horse") || t.equals("donkey") || t.equals("mule") || t.equals("llama") || t.equals("cat")
                || t.equals("wolf") || t.equals("parrot") || t.equals("fox") || t.equals("bee") || t.equals("goat")
                || t.equals("frog") || t.equals("axolotl") || t.equals("sniffer") || t.equals("camel") || t.equals("panda")
                || t.equals("mooshroom") || t.equals("turtle") || t.equals("squid") || t.equals("glow_squid")
                || t.equals("dolphin") || t.equals("cod") || t.equals("salmon") || t.equals("tropical_fish")
                || t.equals("pufferfish") || t.equals("bat") || t.equals("villager") || t.equals("wandering_trader")
                || t.equals("iron_golem") || t.equals("snow_golem") || t.equals("allay") || t.equals("armadillo");
    }

    private static boolean isProjectileType(String t) {
        return t.contains("arrow") || t.contains("fireball") || t.contains("pearl") || t.contains("trident")
                || t.contains("shulker_bullet") || t.contains("snowball") || t.contains("egg") || t.contains("potion")
                || t.contains("firework") || t.contains("llama_spit") || t.contains("wither_skull")
                || t.contains("dragon_fireball") || t.contains("small_fireball");
    }

    private static boolean isVehicleType(String t) {
        return t.contains("minecart") || t.contains("boat") || t.contains("chest_boat") || t.equals("oak_boat");
    }

    private static boolean isDisplayType(String t) {
        return t.contains("item_frame") || t.contains("painting") || t.contains("armor_stand")
                || t.contains("interaction") || t.contains("display") || t.contains("leash");
    }

    private Map<String, Integer> sampleTileEntityTypes(Object tickRegionData) {
        Map<String, Integer> counts = new HashMap<>();
        try {
            Object worldData = findRegionizedWorldData(tickRegionData);
            if (worldData == null) {
                return counts;
            }
            
            Object[] tiles = null;
            for (String methodName : List.of("getLocalBlockEntitiesCopy", "getBlockEntitiesCopy", "getLocalTileEntitiesCopy")) {
                try {
                    Method m = worldData.getClass().getMethod(methodName);
                    Object result = m.invoke(worldData);
                    if (result instanceof Object[] arr) {
                        tiles = arr;
                        break;
                    }
                    if (result instanceof Collection<?> coll) {
                        tiles = coll.toArray();
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (tiles == null) {
                
                for (Field f : worldData.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(worldData);
                    if (val instanceof Map<?, ?> map && !map.isEmpty()) {
                        Object sample = map.values().iterator().next();
                        if (sample != null && sample.getClass().getName().contains("BlockEntity")) {
                            for (Object be : map.values()) {
                                String name = blockEntityTypeName(be);
                                if (name != null) {
                                    counts.merge(name, 1, Integer::sum);
                                }
                            }
                            return counts;
                        }
                    }
                }
                return counts;
            }
            for (Object be : tiles) {
                String name = blockEntityTypeName(be);
                if (name != null) {
                    counts.merge(name, 1, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }
        return counts;
    }

    private Object findRegionizedWorldData(Object tickRegionData) throws Exception {
        Field mapField = findField(tickRegionData.getClass(), "regionizedData");
        mapField.setAccessible(true);
        Object map = mapField.get(tickRegionData);
        if (map == null) {
            return null;
        }
        Collection<?> values = (Collection<?>) map.getClass().getMethod("values").invoke(map);
        for (Object v : values) {
            if (v != null && v.getClass().getName().endsWith("RegionizedWorldData")) {
                return v;
            }
        }
        return null;
    }

    private String blockEntityTypeName(Object blockEntity) {
        if (blockEntity == null) {
            return null;
        }
        try {
            Object type = blockEntity.getClass().getMethod("getType").invoke(blockEntity);
            try {
                Object key = type.getClass().getMethod("builtInRegistryHolder").invoke(type);
                
            } catch (Throwable ignored) {
            }
            String s = String.valueOf(type).toLowerCase(Locale.ROOT);
            int colon = s.lastIndexOf(':');
            if (colon >= 0) {
                s = s.substring(colon + 1);
            }
            
            int lb = s.indexOf('[');
            int rb = s.lastIndexOf(']');
            if (lb >= 0 && rb > lb) {
                s = s.substring(lb + 1, rb);
                colon = s.lastIndexOf(':');
                if (colon >= 0) {
                    s = s.substring(colon + 1);
                }
            }
            return s.replace(' ', '_');
        } catch (Throwable t) {
            String n = blockEntity.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (n.endsWith("blockentity")) {
                n = n.substring(0, n.length() - "blockentity".length());
            }
            return n.isEmpty() ? null : n;
        }
    }

    private Map<String, Integer> sampleEntityTypes(Object tickRegionData) {
        Map<String, Integer> counts = new HashMap<>();
        try {
            Object worldData = findRegionizedWorldData(tickRegionData);
            if (worldData == null) {
                return counts;
            }
            Object[] entities = (Object[]) worldData.getClass().getMethod("getLocalEntitiesCopy").invoke(worldData);
            if (entities == null) {
                return counts;
            }
            for (Object entity : entities) {
                if (entity == null) {
                    continue;
                }
                String type = entityTypeName(entity);
                if (type != null) {
                    counts.merge(type, 1, Integer::sum);
                }
            }
        } catch (Throwable ignored) {
        }
        return counts;
    }

    private static String formatKeepAliveSummary(List<String> reasons, Map<String, Integer> ticketCounts,
                                                 int forceInRegion, int enderPearlEntities) {
        if (reasons.isEmpty()) {
            return "—";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(", ", reasons));
        List<String> detail = new ArrayList<>();
        ticketCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> detail.add(e.getKey() + "=" + e.getValue()));
        if (forceInRegion > 0) {
            detail.add(0, "forceload_chunks=" + forceInRegion);
        }
        if (enderPearlEntities > 0) {
            detail.add("pearl_entities=" + enderPearlEntities);
        }
        if (!detail.isEmpty()) {
            sb.append(" [").append(String.join(" · ", detail)).append("]");
        }
        return sb.toString();
    }

    private Object copyWorldTickets(Object serverLevel) {
        try {
            Object scheduler = serverLevel.getClass().getMethod("moonrise$getChunkTaskScheduler").invoke(serverLevel);
            Field managerField = scheduler.getClass().getField("chunkHolderManager");
            Object manager = managerField.get(scheduler);
            return manager.getClass().getMethod("getTicketsCopy").invoke(manager);
        } catch (Throwable t) {
            return null;
        }
    }

    private Set<Long> forceLoadedChunkKeys(World world) {
        Set<Long> keys = new HashSet<>();
        try {
            Collection<?> chunks = world.getForceLoadedChunks();
            for (Object chunkObj : chunks) {
                try {
                    int x = ((Number) chunkObj.getClass().getMethod("getX").invoke(chunkObj)).intValue();
                    int z = ((Number) chunkObj.getClass().getMethod("getZ").invoke(chunkObj)).intValue();
                    keys.add(packChunkPos(x, z));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return keys;
    }

    private Set<Long> ownedChunkKeys(Object threadedRegion) {
        Set<Long> owned = new HashSet<>();
        try {
            Method getOwnedChunks = threadedRegion.getClass().getMethod("getOwnedChunks");
            Object arr = getOwnedChunks.invoke(threadedRegion);
            if (arr != null) {
                Method size = arr.getClass().getMethod("size");
                Method getLong = arr.getClass().getMethod("getLong", int.class);
                int n = ((Number) size.invoke(arr)).intValue();
                for (int i = 0; i < n; i++) {
                    owned.add(((Number) getLong.invoke(arr, i)).longValue());
                }
            }
        } catch (Throwable ignored) {
        }
        return owned;
    }

    private Map<Object, String> ticketTypeNameMap() {
        Map<Object, String> map = new HashMap<>();
        try {
            Class<?> ticketType = Class.forName("net.minecraft.server.level.TicketType");
            for (Field f : ticketType.getFields()) {
                if (f.getType() != ticketType) {
                    continue;
                }
                Object val = f.get(null);
                if (val != null) {
                    map.put(val, f.getName().toLowerCase(Locale.ROOT));
                }
            }
            
            for (Field f : ticketType.getDeclaredFields()) {
                if (f.getType() != ticketType) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        map.putIfAbsent(val, f.getName().toLowerCase(Locale.ROOT));
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return map;
    }

    private String ticketTypeLabel(Object ticket, Map<Object, String> ticketTypeNames) {
        try {
            Object type = ticket.getClass().getMethod("getType").invoke(ticket);
            String mapped = ticketTypeNames.get(type);
            if (mapped != null) {
                return mapped;
            }
            for (Map.Entry<Object, String> e : ticketTypeNames.entrySet()) {
                if (e.getKey() == type) {
                    return e.getValue();
                }
            }
            try {
                Object nameObj = type.getClass().getMethod("toString").invoke(type);
                String raw = String.valueOf(nameObj).toLowerCase(Locale.ROOT);
                int lb = raw.indexOf('[');
                int rb = raw.lastIndexOf(']');
                if (lb >= 0 && rb > lb) {
                    raw = raw.substring(lb + 1, rb);
                }
                int colon = raw.lastIndexOf(':');
                if (colon >= 0 && colon + 1 < raw.length()) {
                    raw = raw.substring(colon + 1);
                }
                if (!raw.contains("timeout") && !raw.contains("_flags") && !raw.startsWith("tickettype")) {
                    return raw.replace(' ', '_');
                }
            } catch (Throwable ignored) {
            }
            try {
                boolean load = false, sim = false, persist = false, keepDim = false;
                try {
                    load = Boolean.TRUE.equals(type.getClass().getMethod("doesLoad").invoke(type));
                    sim = Boolean.TRUE.equals(type.getClass().getMethod("doesSimulate").invoke(type));
                    persist = Boolean.TRUE.equals(type.getClass().getMethod("persist").invoke(type));
                    keepDim = Boolean.TRUE.equals(type.getClass().getMethod("shouldKeepDimensionActive").invoke(type));
                } catch (Throwable ignored) {
                }
                int flags = -1;
                long timeout = -1;
                try {
                    flags = ((Number) type.getClass().getMethod("flags").invoke(type)).intValue();
                } catch (Throwable ignored) {
                }
                try {
                    timeout = ((Number) type.getClass().getMethod("timeout").invoke(type)).longValue();
                } catch (Throwable ignored) {
                }
                if (persist && !load && !sim) {
                    return "forced";
                }
                if (load && sim && timeout == 0) {
                    return keepDim ? "player" : "player_load_sim";
                }
                if (load && !sim) {
                    return timeout == 0 ? "player_loading" : "loading";
                }
                if (!load && sim) {
                    return "player_simulation";
                }
                StringBuilder sb = new StringBuilder();
                if (persist) sb.append("persist+");
                if (load) sb.append("load+");
                if (sim) sb.append("sim+");
                if (keepDim) sb.append("keepdim+");
                if (!sb.isEmpty()) {
                    sb.setLength(sb.length() - 1);
                    if (timeout >= 0) {
                        sb.append("_t").append(timeout);
                    }
                    return sb.toString();
                }
                if (flags >= 0) {
                    return "ticket_flags_" + flags + (timeout >= 0 ? "_t" + timeout : "");
                }
            } catch (Throwable ignored) {
            }
            return "unknown_ticket";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private String entityTypeName(Object nmsEntity) {
        try {
            Object bukkit = nmsEntity.getClass().getMethod("getBukkitEntity").invoke(nmsEntity);
            Object type = bukkit.getClass().getMethod("getType").invoke(bukkit);
            return String.valueOf(type).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
        }
        try {
            Object type = nmsEntity.getClass().getMethod("getType").invoke(nmsEntity);
            try {
                return String.valueOf(type.getClass().getMethod("toShortString").invoke(type)).toLowerCase(Locale.ROOT);
            } catch (Throwable t) {
                return String.valueOf(type).toLowerCase(Locale.ROOT);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void putTickReports(JsonObject target, String prefix, Object scheduleHandle) {
        long now = System.nanoTime();
        putOneReport(target, prefix, "5s", scheduleHandle, "getTickReport5s", now);
        putOneReport(target, prefix, "15s", scheduleHandle, "getTickReport15s", now);
        putOneReport(target, prefix, "1m", scheduleHandle, "getTickReport1m", now);
        putOneReport(target, prefix, "5m", scheduleHandle, "getTickReport5m", now);
        putOneReport(target, prefix, "15m", scheduleHandle, "getTickReport15m", now);
    }

    private void putOneReport(JsonObject target, String prefix, String window,
                              Object scheduleHandle, String methodName, long nowNs) {
        try {
            Method m = scheduleHandle.getClass().getMethod(methodName, long.class);
            Object report = m.invoke(scheduleHandle, nowNs);
            if (report == null) {
                return;
            }
            String p = prefix == null || prefix.isBlank() ? "" : prefix;
            Method utilisation = report.getClass().getMethod("utilisation");
            double util = ((Number) utilisation.invoke(report)).doubleValue();
            target.addProperty(p + "util" + window, round3(util));

            Method tpsData = report.getClass().getMethod("tpsData");
            Object tpsSeg = tpsData.invoke(report);
            double tps = segmentedAverage(tpsSeg);
            target.addProperty(p + "tps" + window, round2(tps));

            Method timePerTickData = report.getClass().getMethod("timePerTickData");
            Object msptSeg = timePerTickData.invoke(report);
            double msptNs = segmentedAverage(msptSeg);
            double mspt = msptNs / 1_000_000.0;
            target.addProperty(p + "mspt" + window, round2(mspt));
            RegionSparkMetrics.putSegmentStats(target, p, window, msptSeg, true);

            Method missing = report.getClass().getMethod("missingCPUTimeData");
            Object missSeg = missing.invoke(report);
            double missNs = segmentedAverage(missSeg);
            target.addProperty(p + "missingCpuMs" + window, round2(missNs / 1_000_000.0));

            try {
                int collected = ((Number) report.getClass().getMethod("collectedTicks").invoke(report)).intValue();
                target.addProperty(p + "collectedTicks" + window, collected);
            } catch (Throwable ignored) {
            }
            try {
                long totalNs = ((Number) report.getClass().getMethod("totalTimeTicking").invoke(report)).longValue();
                target.addProperty(p + "totalTickMs" + window, round2(totalNs / 1_000_000.0));
            } catch (Throwable ignored) {
            }

            if (util > 0.01 && mspt > 0) {
                double interval = mspt / util;
                target.addProperty(p + "tickIntervalMs" + window, round2(interval));
                target.addProperty(p + "impliedTps" + window, round2(1000.0 / interval));
                target.addProperty(p + "idleMs" + window, round2(Math.max(0, interval - mspt)));
            }
        } catch (Throwable ignored) {
        }
    }

    private static double segmentedAverage(Object segmentedAverage) throws Exception {
        if (segmentedAverage == null) {
            return 0.0;
        }
        Method segmentAll = segmentedAverage.getClass().getMethod("segmentAll");
        Object segment = segmentAll.invoke(segmentedAverage);
        Method average = segment.getClass().getMethod("average");
        return ((Number) average.invoke(segment)).doubleValue();
    }

    private Map<String, List<JsonObject>> mapPlayersToRegions() {
        Map<String, List<JsonObject>> out = new HashMap<>();
        
        Map<String, List<RegionChunkIndex>> indexes = new HashMap<>();
        for (World world : Bukkit.getWorlds()) {
            try {
                Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
                Field regioniserField = findField(serverLevel.getClass(), "regioniser");
                regioniserField.setAccessible(true);
                Object regioniser = regioniserField.get(serverLevel);
                if (regioniser == null) continue;
                Method computeForAll = regioniser.getClass().getMethod("computeForAllRegions", java.util.function.Consumer.class);
                List<Object> regions = new ArrayList<>();
                computeForAll.invoke(regioniser, (java.util.function.Consumer<Object>) regions::add);
                List<RegionChunkIndex> list = new ArrayList<>();
                for (Object region : regions) {
                    Field idField = findField(region.getClass(), "id");
                    idField.setAccessible(true);
                    long id = idField.getLong(region);
                    java.util.HashSet<Long> owned = new java.util.HashSet<>();
                    try {
                        Method getOwnedChunks = region.getClass().getMethod("getOwnedChunks");
                        Object arr = getOwnedChunks.invoke(region);
                        if (arr != null) {
                            Method size = arr.getClass().getMethod("size");
                            Method getLong = arr.getClass().getMethod("getLong", int.class);
                            int n = ((Number) size.invoke(arr)).intValue();
                            for (int i = 0; i < n; i++) {
                                owned.add(((Number) getLong.invoke(arr, i)).longValue());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    list.add(new RegionChunkIndex(id, owned, regioniser));
                }
                indexes.put(world.getName(), list);
            } catch (Throwable ignored) {
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Location loc = player.getLocation();
                World world = loc.getWorld();
                if (world == null) continue;
                int cx = loc.getBlockX() >> 4;
                int cz = loc.getBlockZ() >> 4;
                Long regionId = null;
                List<RegionChunkIndex> list = indexes.get(world.getName());
                if (list != null) {
                    for (RegionChunkIndex idx : list) {
                        if (!idx.ownedChunks.isEmpty()) {
                            long key = packChunkPos(cx, cz);
                            if (idx.ownedChunks.contains(key)) {
                                regionId = idx.id;
                                break;
                            }
                        }
                    }
                    if (regionId == null) {
                        
                        for (RegionChunkIndex idx : list) {
                            try {
                                Object regioniser = idx.regioniser;
                                Method getSectionCoordinate = regioniser.getClass().getMethod("getSectionCoordinate", int.class);
                                int sx = ((Number) getSectionCoordinate.invoke(regioniser, cx)).intValue();
                                int sz = ((Number) getSectionCoordinate.invoke(regioniser, cz)).intValue();
                                Method getRegionAt = regioniser.getClass().getMethod("getRegionAtUnsynchronised", int.class, int.class);
                                Object region = getRegionAt.invoke(regioniser, sx, sz);
                                if (region == null) {
                                    region = getRegionAt.invoke(regioniser, cx, cz);
                                }
                                if (region != null) {
                                    Field idField = findField(region.getClass(), "id");
                                    idField.setAccessible(true);
                                    regionId = idField.getLong(region);
                                    break;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
                if (regionId == null) continue;
                String key = world.getName() + ":" + regionId;
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(RegionSparkMetrics.playerDetail(player));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static long packChunkPos(int x, int z) {
        try {
            Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos");
            Method pack = chunkPos.getMethod("pack", int.class, int.class);
            return ((Number) pack.invoke(null, x, z)).longValue();
        } catch (Throwable t) {
            
            return ((long) x & 0xffffffffL) | ((long) z & 0xffffffffL) << 32;
        }
    }

    private record RegionChunkIndex(long id, java.util.Set<Long> ownedChunks, Object regioniser) {
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private void applyTileCache(JsonObject row, World world, long regionId, int centerCx, int centerCz) {
        String key = world.getName() + ":" + regionId;
        TileSample sample = tileCache.get(key);
        if (sample == null || System.currentTimeMillis() - sample.ts() > 12_000L) {
            requestTileSample(world, regionId, centerCx, centerCz);
        }
        if (sample == null) {
            row.addProperty("tileSampleStatus", "pending");
            return;
        }
        int tileTotal = sample.types().values().stream().mapToInt(Integer::intValue).sum();
        row.addProperty("tileEntities", tileTotal);
        row.addProperty("tickingTileEntities", sample.tickingTiles());
        row.addProperty("pendingBlockTicks", sample.pendingBlockTicks());
        row.addProperty("pendingFluidTicks", sample.pendingFluidTicks());
        row.addProperty("primedTnt", sample.primedTnt());
        row.addProperty("entityTickingChunks", sample.entityTickingChunks());
        row.addProperty("navigatingMobs", sample.navigatingMobs());
        row.addProperty("tileSampleAgeMs", System.currentTimeMillis() - sample.ts());
        row.addProperty("tileSampleStatus", sample.status());
        JsonObject tilesJson = new JsonObject();
        sample.types().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(30)
                .forEach(e -> tilesJson.addProperty(e.getKey(), e.getValue()));
        row.add("tileEntityTypes", tilesJson);

        int redstoneSched = sample.scheduledRedstone().values().stream().mapToInt(Integer::intValue).sum();
        int redstoneTe = sample.redstoneTiles().values().stream().mapToInt(Integer::intValue).sum();
        row.addProperty("redstoneScheduledTotal", redstoneSched);
        row.addProperty("redstoneTileTotal", redstoneTe);
        row.addProperty("neighborUpdates", sample.neighborUpdates());
        row.addProperty("redstoneTorchToggles", sample.torchToggles());
        JsonObject schedJson = new JsonObject();
        sample.scheduledRedstone().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(25)
                .forEach(e -> schedJson.addProperty(e.getKey(), e.getValue()));
        row.add("redstoneScheduledTypes", schedJson);
        JsonObject rsTeJson = new JsonObject();
        sample.redstoneTiles().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(25)
                .forEach(e -> rsTeJson.addProperty(e.getKey(), e.getValue()));
        row.add("redstoneTileTypes", rsTeJson);

        JsonObject containerDiagJson = new JsonObject();
        sample.containerDiag().forEach(containerDiagJson::addProperty);
        row.add("containerDiagnostics", containerDiagJson);
    }

    private void requestTileSample(World world, long regionId, int centerCx, int centerCz) {
        String key = world.getName() + ":" + regionId;
        if (!tileInFlight.add(key)) {
            return;
        }
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, centerCx, centerCz, () -> {
                try {
                    tileCache.put(key, sampleTilesOnRegionThread(world, regionId));
                } catch (Throwable t) {
                    tileCache.put(key, tileError("error:" + t.getClass().getSimpleName()));
                } finally {
                    tileInFlight.remove(key);
                }
            });
        } catch (Throwable t) {
            tileInFlight.remove(key);
        }
    }

    private TileSample sampleTilesOnRegionThread(World world, long regionId) {
        Map<String, Integer> types = new LinkedHashMap<>();
        Map<String, Integer> scheduledRedstone = new LinkedHashMap<>();
        Map<String, Integer> redstoneTiles = new LinkedHashMap<>();
        Map<String, Integer> containerDiag = new LinkedHashMap<>();
        int ticking = 0, blockTicks = 0, fluidTicks = 0, tnt = 0, entChunks = 0;
        int neighborUpdates = 0, torchToggles = 0;
        String status = "ok";
        try {
            Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
            Field regioniserField = findField(serverLevel.getClass(), "regioniser");
            regioniserField.setAccessible(true);
            Object regioniser = regioniserField.get(serverLevel);
            Method computeForAll = regioniser.getClass().getMethod("computeForAllRegions", java.util.function.Consumer.class);
            List<Object> regions = new ArrayList<>();
            computeForAll.invoke(regioniser, (java.util.function.Consumer<Object>) regions::add);
            Object tickRegionData = null;
            Object targetRegion = null;
            for (Object region : regions) {
                Field idField = findField(region.getClass(), "id");
                idField.setAccessible(true);
                if (idField.getLong(region) == regionId) {
                    targetRegion = region;
                    tickRegionData = region.getClass().getMethod("getData").invoke(region);
                    break;
                }
            }
            if (tickRegionData == null) {
                return tileError("region_gone");
            }
            Object worldData = findRegionizedWorldData(tickRegionData);
            if (worldData == null) {
                return tileError("no_world_data");
            }
            try {
                Object tickers = worldData.getClass().getMethod("getBlockEntityTickers").invoke(worldData);
                if (tickers instanceof Iterable<?> it) {
                    for (Object te : it) {
                        ticking++;
                        String name = tickingTileName(te);
                        if (name != null) {
                            types.merge(name, 1, Integer::sum);
                            if (isRedstoneComponent(name)) {
                                redstoneTiles.merge(name, 1, Integer::sum);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                status = "tickers_fail";
            }
            try {
                Object bt = worldData.getClass().getMethod("getBlockLevelTicks").invoke(worldData);
                blockTicks = ((Number) bt.getClass().getMethod("count").invoke(bt)).intValue();
                histogramScheduledTicks(bt, scheduledRedstone);
            } catch (Throwable ignored) {
            }
            try {
                Object ft = worldData.getClass().getMethod("getFluidLevelTicks").invoke(worldData);
                fluidTicks = ((Number) ft.getClass().getMethod("count").invoke(ft)).intValue();
            } catch (Throwable ignored) {
            }
            try {
                Field tntField = findField(worldData.getClass(), "currentPrimedTnt");
                tntField.setAccessible(true);
                tnt = ((Number) tntField.get(worldData)).intValue();
            } catch (Throwable ignored) {
            }
            try {
                entChunks = ((Number) worldData.getClass().getMethod("getEntityTickingChunkCount").invoke(worldData)).intValue();
            } catch (Throwable ignored) {
            }
            try {
                Field nuField = findField(worldData.getClass(), "neighborUpdater");
                nuField.setAccessible(true);
                Object nu = nuField.get(worldData);
                if (nu != null) {
                    try {
                        Field stack = findField(nu.getClass(), "stack");
                        stack.setAccessible(true);
                        Object dq = stack.get(nu);
                        if (dq instanceof Collection<?> c) {
                            neighborUpdates += c.size();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Field added = findField(nu.getClass(), "addedThisLayer");
                        added.setAccessible(true);
                        Object list = added.get(nu);
                        if (list instanceof Collection<?> c) {
                            neighborUpdates += c.size();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Field countF = findField(nu.getClass(), "count");
                        countF.setAccessible(true);
                        neighborUpdates = Math.max(neighborUpdates, ((Number) countF.get(nu)).intValue());
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                Field torchField = findField(worldData.getClass(), "redstoneUpdateInfos");
                torchField.setAccessible(true);
                Object q = torchField.get(worldData);
                if (q instanceof Collection<?> c) {
                    torchToggles = c.size();
                }
            } catch (Throwable ignored) {
            }
            try {
                
                if (targetRegion != null
                        && plugin.getConfig().getBoolean("regions.sample-container-diagnostics", false)
                        && !isRegionDebugStressed()) {
                    sampleContainerDiagnostics(world, ownedChunkKeys(targetRegion), containerDiag);
                } else if (targetRegion != null) {
                    containerDiag.put("skipped", 1);
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            status = "error:" + t.getClass().getSimpleName();
        }
        return new TileSample(System.currentTimeMillis(), types, ticking, blockTicks, fluidTicks, tnt, entChunks, 0,
                status, scheduledRedstone, redstoneTiles, neighborUpdates, torchToggles, containerDiag);
    }

    private void sampleContainerDiagnostics(World world, Set<Long> ownedChunks, Map<String, Integer> out) {
        int containerCount = 0;
        int fullContainers = 0;
        int emptyContainers = 0;
        int partialContainers = 0;
        int hopperCount = 0;
        int hopperFull = 0;
        int hopperEmpty = 0;
        int hopperPartial = 0;
        int hopperPushBlocked = 0;
        int hopperPullStarved = 0;
        int hopperSandwichBlocked = 0;
        int hoppersWithAboveContainer = 0;
        int hoppersWithBelowContainer = 0;
        int hoppersNoAboveContainer = 0;
        int hoppersNoBelowContainer = 0;
        int hoppersReadyPush = 0;
        int hoppersReadyPull = 0;
        int hoppersCrossChunkIo = 0;

        Map<String, Integer> containerTypes = new LinkedHashMap<>();

        for (long key : ownedChunks) {
            int[] xz = unpackChunkPos(key);
            int cx = xz[0];
            int cz = xz[1];
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
            for (org.bukkit.block.BlockState state : chunk.getTileEntities()) {
                if (!(state instanceof org.bukkit.inventory.InventoryHolder holder)) {
                    continue;
                }
                org.bukkit.inventory.Inventory inv = holder.getInventory();
                if (inv == null) {
                    continue;
                }
                containerCount++;
                String typeName = state.getType().name().toLowerCase(Locale.ROOT);
                containerTypes.merge(typeName, 1, Integer::sum);

                boolean invFull = isInventoryFull(inv);
                boolean invEmpty = isInventoryEmpty(inv);
                if (invFull) {
                    fullContainers++;
                } else if (invEmpty) {
                    emptyContainers++;
                } else {
                    partialContainers++;
                }

                if (state instanceof org.bukkit.block.Hopper hopper) {
                    hopperCount++;
                    org.bukkit.inventory.Inventory hInv = hopper.getInventory();
                    boolean hopperInvFull = isInventoryFull(hInv);
                    boolean hopperInvEmpty = isInventoryEmpty(hInv);
                    boolean hopperHasItems = !hopperInvEmpty;

                    if (hopperInvFull) {
                        hopperFull++;
                    } else if (hopperInvEmpty) {
                        hopperEmpty++;
                    } else {
                        hopperPartial++;
                    }

                    org.bukkit.block.Block block = hopper.getBlock();
                    org.bukkit.block.Block below = block.getRelative(org.bukkit.block.BlockFace.DOWN);
                    org.bukkit.block.Block above = block.getRelative(org.bukkit.block.BlockFace.UP);
                    org.bukkit.block.BlockState belowState = below.getState();
                    org.bukkit.block.BlockState aboveState = above.getState();

                    org.bukkit.inventory.Inventory belowInv = (belowState instanceof org.bukkit.inventory.InventoryHolder bHolder)
                            ? bHolder.getInventory() : null;
                    org.bukkit.inventory.Inventory aboveInv = (aboveState instanceof org.bukkit.inventory.InventoryHolder aHolder)
                            ? aHolder.getInventory() : null;

                    boolean hasBelowContainer = belowInv != null;
                    boolean hasAboveContainer = aboveInv != null;
                    if (hasBelowContainer) {
                        hoppersWithBelowContainer++;
                    } else {
                        hoppersNoBelowContainer++;
                    }
                    if (hasAboveContainer) {
                        hoppersWithAboveContainer++;
                    } else {
                        hoppersNoAboveContainer++;
                    }

                    boolean belowFull = hasBelowContainer && isInventoryFull(belowInv);
                    boolean aboveHasItems = hasAboveContainer && !isInventoryEmpty(aboveInv);

                    if (belowFull && hopperHasItems) {
                        hopperPushBlocked++;
                    }
                    if (hasAboveContainer && !aboveHasItems && hopperInvEmpty) {
                        hopperPullStarved++;
                    }
                    if (belowFull && aboveHasItems && hopperHasItems) {
                        hopperSandwichBlocked++;
                    }
                    if (hasBelowContainer && !belowFull && hopperHasItems) {
                        hoppersReadyPush++;
                    }
                    if (hasAboveContainer && aboveHasItems && !hopperInvFull) {
                        hoppersReadyPull++;
                    }

                    if (hasAboveContainer) {
                        int acx = above.getX() >> 4;
                        int acz = above.getZ() >> 4;
                        if (!ownedChunks.contains(packChunkPos(acx, acz))) {
                            hoppersCrossChunkIo++;
                        }
                    }
                    if (hasBelowContainer) {
                        int bcx = below.getX() >> 4;
                        int bcz = below.getZ() >> 4;
                        if (!ownedChunks.contains(packChunkPos(bcx, bcz))) {
                            hoppersCrossChunkIo++;
                        }
                    }
                }
            }
        }

        out.put("containersTotal", containerCount);
        out.put("containersFull", fullContainers);
        out.put("containersEmpty", emptyContainers);
        out.put("containersPartial", partialContainers);
        out.put("hoppersTotal", hopperCount);
        out.put("hoppersFull", hopperFull);
        out.put("hoppersEmpty", hopperEmpty);
        out.put("hoppersPartial", hopperPartial);
        out.put("hoppersPushBlocked", hopperPushBlocked);
        out.put("hoppersPullStarved", hopperPullStarved);
        out.put("hoppersSandwichBlocked", hopperSandwichBlocked);
        out.put("hoppersWithAboveContainer", hoppersWithAboveContainer);
        out.put("hoppersWithBelowContainer", hoppersWithBelowContainer);
        out.put("hoppersNoAboveContainer", hoppersNoAboveContainer);
        out.put("hoppersNoBelowContainer", hoppersNoBelowContainer);
        out.put("hoppersReadyPush", hoppersReadyPush);
        out.put("hoppersReadyPull", hoppersReadyPull);
        out.put("hoppersCrossChunkIo", hoppersCrossChunkIo);

        containerTypes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(12)
                .forEach(e -> out.put("containerType_" + e.getKey(), e.getValue()));
    }

    private static boolean isInventoryEmpty(org.bukkit.inventory.Inventory inv) {
        if (inv == null) {
            return true;
        }
        for (org.bukkit.inventory.ItemStack stack : inv.getContents()) {
            if (stack != null && stack.getType() != Material.AIR && stack.getAmount() > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isInventoryFull(org.bukkit.inventory.Inventory inv) {
        if (inv == null) {
            return false;
        }
        for (org.bukkit.inventory.ItemStack stack : inv.getContents()) {
            if (stack == null || stack.getType() == Material.AIR || stack.getAmount() <= 0 || stack.getAmount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private void histogramScheduledTicks(Object levelTicks, Map<String, Integer> redstoneOut) {
        try {
            Field containersField = findField(levelTicks.getClass(), "allContainers");
            containersField.setAccessible(true);
            Object containers = containersField.get(levelTicks);
            if (!(containers instanceof Map<?, ?> map)) {
                return;
            }
            for (Object chunkTicks : map.values()) {
                if (chunkTicks == null) {
                    continue;
                }
                boolean counted = false;
                try {
                    Object stream = chunkTicks.getClass().getMethod("getAll").invoke(chunkTicks);
                    if (stream instanceof java.util.stream.Stream<?> s) {
                        s.forEach(tick -> countScheduledTick(tick, redstoneOut));
                        counted = true;
                    }
                } catch (Throwable ignored) {
                }
                if (!counted) {
                    try {
                        Field qf = findField(chunkTicks.getClass(), "tickQueue");
                        qf.setAccessible(true);
                        Object q = qf.get(chunkTicks);
                        if (q instanceof Collection<?> coll) {
                            for (Object tick : coll) {
                                countScheduledTick(tick, redstoneOut);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void countScheduledTick(Object tick, Map<String, Integer> redstoneOut) {
        if (tick == null) {
            return;
        }
        try {
            Object type = tick.getClass().getMethod("type").invoke(tick);
            String name = nmsRegistryName(type);
            if (name != null && isRedstoneComponent(name)) {
                redstoneOut.merge(name, 1, Integer::sum);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String nmsRegistryName(Object nms) {
        if (nms == null) {
            return null;
        }
        try {
            String s = String.valueOf(nms).toLowerCase(Locale.ROOT);
            int lb = s.indexOf('{');
            int rb = s.lastIndexOf('}');
            if (lb >= 0 && rb > lb) {
                s = s.substring(lb + 1, rb);
            }
            int colon = s.lastIndexOf(':');
            if (colon >= 0) {
                s = s.substring(colon + 1);
            }
            s = s.replace(' ', '_').trim();
            return s.isEmpty() || s.equals("null") ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean isRedstoneComponent(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String t = name.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        return t.equals("repeater") || t.equals("comparator") || t.equals("observer")
                || t.equals("piston") || t.equals("sticky_piston") || t.equals("moving_piston")
                || t.equals("dispenser") || t.equals("dropper") || t.equals("hopper")
                || t.equals("redstone_wire") || t.equals("redstone_torch") || t.equals("redstone_wall_torch")
                || t.equals("redstone_lamp") || t.equals("redstone_block")
                || t.equals("lever") || t.equals("target") || t.equals("note_block")
                || t.equals("daylight_detector") || t.equals("crafter") || t.equals("copper_bulb")
                || t.equals("calibrated_sculk_sensor") || t.equals("sculk_sensor") || t.equals("sculk_shrieker")
                || t.equals("tripwire") || t.equals("tripwire_hook") || t.contains("button")
                || t.contains("pressure_plate") || t.contains("redstone");
    }

    private String tickingTileName(Object ticker) {
        if (ticker == null) {
            return null;
        }
        try {
            Object be = ticker.getClass().getMethod("getTileEntity").invoke(ticker);
            String fromBe = blockEntityTypeName(be);
            if (fromBe != null && !fromBe.isBlank() && !fromBe.equals("null")) {
                return fromBe;
            }
        } catch (Throwable ignored) {
        }
        for (String methodName : List.of("getType", "toString")) {
            try {
                Object rawObj = ticker.getClass().getMethod(methodName).invoke(ticker);
                String parsed = nmsRegistryName(rawObj);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Throwable ignored) {
            }
        }
        try {
            Class<?> iface = Class.forName("net.minecraft.world.level.block.entity.TickingBlockEntity");
            Object rawObj = iface.getMethod("getType").invoke(ticker);
            String parsed = nmsRegistryName(rawObj);
            if (parsed != null) {
                return parsed;
            }
            Object be = iface.getMethod("getTileEntity").invoke(ticker);
            return blockEntityTypeName(be);
        } catch (Throwable ignored) {
        }
        String simple = ticker.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        return simple.isEmpty() ? "unknown_te" : simple;
    }

    public void handleRemoteActions(com.google.gson.JsonArray actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        for (var el : actions) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject a = el.getAsJsonObject();
            String type = a.has("type") ? a.get("type").getAsString() : "";
            String worldName = a.has("world") ? a.get("world").getAsString() : "";
            long regionId = a.has("id") ? a.get("id").getAsLong() : -1L;
            String entityType = a.has("entityType") ? a.get("entityType").getAsString() : "";
            String tileType = a.has("tileType") ? a.get("tileType").getAsString() : entityType;
            String actionId = a.has("actionId") ? a.get("actionId").getAsString() : UUID.randomUUID().toString();
            int[] hint = chunkHintFromAction(a, worldName, regionId);
            if ("clear_entities".equals(type)) {
                scheduleClearEntities(worldName, regionId, entityType, actionId, hint[0], hint[1]);
            } else if ("restore_entities".equals(type)) {
                scheduleRestoreEntities(worldName, regionId, entityType, actionId, hint[0], hint[1]);
            } else if ("clear_tiles".equals(type)) {
                scheduleClearTiles(worldName, regionId, tileType, actionId, hint[0], hint[1]);
            } else if ("restore_tiles".equals(type)) {
                scheduleRestoreTiles(worldName, regionId, tileType, actionId, hint[0], hint[1]);
            }
        }
    }

    private void scheduleClearEntities(String worldName, long regionId, String entityType, String actionId,
                                       int hintCx, int hintCz) {
        JsonObject fail = new JsonObject();
        fail.addProperty("actionId", actionId);
        fail.addProperty("world", worldName);
        fail.addProperty("id", regionId);
        fail.addProperty("entityType", entityType);
        fail.addProperty("ts", System.currentTimeMillis());
        if (worldName == null || worldName.isBlank() || regionId < 0 || entityType == null || entityType.isBlank()) {
            fail.addProperty("ok", false);
            fail.addProperty("error", "bad_request");
            fail.addProperty("removed", 0);
            clearResults.add(fail);
            return;
        }
        String want = entityType.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        if (want.equals("player") || want.equals("human")) {
            fail.addProperty("ok", false);
            fail.addProperty("error", "refused_player");
            fail.addProperty("removed", 0);
            clearResults.add(fail);
            return;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            fail.addProperty("ok", false);
            fail.addProperty("error", "unknown_world");
            fail.addProperty("removed", 0);
            clearResults.add(fail);
            return;
        }
        final int fcx = hintCx;
        final int fcz = hintCz;
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, fcx, fcz, () -> {
                JsonObject result = new JsonObject();
                result.addProperty("actionId", actionId);
                result.addProperty("world", worldName);
                result.addProperty("id", regionId);
                result.addProperty("kind", "entity");
                result.addProperty("entityType", want);
                result.addProperty("hintChunkX", fcx);
                result.addProperty("hintChunkZ", fcz);
                result.addProperty("ts", System.currentTimeMillis());
                try {
                    long liveId = liveRegionId(world, regionId, fcx, fcz);
                    result.addProperty("resolvedId", liveId);
                    int removed = clearEntitiesOnRegionThread(world, regionId, liveId, want, actionId, fcx, fcz);
                    result.addProperty("ok", removed > 0);
                    result.addProperty("removed", removed);
                    if (removed == 0) {
                        result.addProperty("error", "none_found");
                    }
                    result.addProperty("restoreAvailable",
                            undoStore.peek(worldName, liveId, want) != null
                                    || undoStore.peek(worldName, regionId, want) != null);
                } catch (Throwable t) {
                    result.addProperty("ok", false);
                    result.addProperty("removed", 0);
                    result.addProperty("error", t.getClass().getSimpleName());
                }
                clearResults.add(result);
            });
        } catch (Throwable t) {
            fail.addProperty("ok", false);
            fail.addProperty("error", t.getClass().getSimpleName());
            fail.addProperty("removed", 0);
            clearResults.add(fail);
        }
    }

    private void scheduleRestoreEntities(String worldName, long regionId, String entityType, String actionId,
                                         int hintCx, int hintCz) {
        JsonObject fail = new JsonObject();
        fail.addProperty("actionId", actionId);
        fail.addProperty("world", worldName);
        fail.addProperty("id", regionId);
        fail.addProperty("entityType", entityType);
        fail.addProperty("ts", System.currentTimeMillis());
        fail.addProperty("restore", true);
        String want = entityType == null ? "" : entityType.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || regionId < 0 || want.isBlank()) {
            fail.addProperty("ok", false);
            fail.addProperty("error", "bad_request");
            fail.addProperty("restored", 0);
            clearResults.add(fail);
            return;
        }
        int cx = hintCx;
        int cz = hintCz;
        RegionEntityUndoStore.Batch peek = undoStore.peekBest(worldName, regionId, want);
        if (peek != null && !peek.entries().isEmpty() && peek.entries().get(0).location() != null) {
            Location loc = peek.entries().get(0).location();
            cx = loc.getBlockX() >> 4;
            cz = loc.getBlockZ() >> 4;
        }
        final int fcx = cx;
        final int fcz = cz;
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, fcx, fcz, () -> {
                JsonObject result = new JsonObject();
                result.addProperty("actionId", actionId);
                result.addProperty("world", worldName);
                result.addProperty("id", regionId);
                result.addProperty("kind", "entity");
                result.addProperty("entityType", want);
                result.addProperty("ts", System.currentTimeMillis());
                result.addProperty("restore", true);
                try {
                    int restored = restoreEntitiesOnRegionThread(worldName, regionId, want);
                    result.addProperty("ok", restored > 0);
                    result.addProperty("restored", restored);
                    if (restored == 0) {
                        result.addProperty("error", "none_found");
                    }
                    result.addProperty("restoreAvailable", undoStore.peekBest(worldName, regionId, want) != null);
                } catch (Throwable t) {
                    result.addProperty("ok", false);
                    result.addProperty("restored", 0);
                    result.addProperty("error", t.getClass().getSimpleName());
                }
                clearResults.add(result);
            });
        } catch (Throwable t) {
            fail.addProperty("ok", false);
            fail.addProperty("error", t.getClass().getSimpleName());
            fail.addProperty("restored", 0);
            clearResults.add(fail);
        }
    }

    private int restoreEntitiesOnRegionThread(String worldName, long regionId, String wantType) {
        RegionEntityUndoStore.Batch batch = undoStore.takeBest(worldName, regionId, wantType);
        if (batch == null || batch.entries().isEmpty()) {
            return 0;
        }
        int restored = 0;
        for (RegionEntityUndoStore.Entry entry : batch.entries()) {
            if (restoreEntityEntry(entry)) {
                restored++;
            }
        }
        return restored;
    }

    private boolean restoreEntityEntry(RegionEntityUndoStore.Entry entry) {
        try {
            Location loc = entry.location();
            if (loc == null || loc.getWorld() == null || entry.snapshot() == null) {
                return false;
            }
            if (!Bukkit.isOwnedByCurrentRegion(loc)) {
                Bukkit.getRegionScheduler().execute(plugin, loc, () -> restoreEntityEntry(entry));
                return true;
            }
            Entity spawned = entry.snapshot().createEntity(loc);
            return spawned != null && spawned.isValid();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int clearEntitiesOnRegionThread(World world, long regionId, long liveId, String wantType, String actionId,
                                           int hintCx, int hintCz) throws Exception {
        Object tickRegionData = findTickRegionData(world, regionId, hintCx, hintCz);
        if (tickRegionData == null) {
            plugin.getLogger().warning("[Regions] clear_entities " + wantType + " " + world.getName()
                    + " #" + regionId + " @chunk " + hintCx + "," + hintCz + ": region not found");
            return 0;
        }
        Object worldData = findRegionizedWorldData(tickRegionData);
        if (worldData == null) {
            return 0;
        }
        Object[] entities = (Object[]) worldData.getClass().getMethod("getLocalEntitiesCopy").invoke(worldData);
        if (entities == null) {
            return 0;
        }
        int removed = 0;
        List<RegionEntityUndoStore.Entry> undo = new ArrayList<>();
        for (Object nms : entities) {
            if (nms == null) {
                continue;
            }
            try {
                Object bukkit = nms.getClass().getMethod("getBukkitEntity").invoke(nms);
                if (!(bukkit instanceof Entity entity) || entity instanceof Player) {
                    continue;
                }
                String type = entity.getType().name().toLowerCase(Locale.ROOT).replace("minecraft:", "");
                String key;
                try {
                    key = String.valueOf(entity.getType().getKey().getKey()).toLowerCase(Locale.ROOT);
                } catch (Throwable t) {
                    key = type;
                }
                if (!wantType.equals(type) && !wantType.equals(key)) {
                    continue;
                }
                if (entity.isValid()) {
                    Location loc = entity.getLocation();
                    if (!Bukkit.isOwnedByCurrentRegion(loc)) {
                        Bukkit.getRegionScheduler().execute(plugin, loc, () -> {
                            try {
                                if (!entity.isValid() || !Bukkit.isOwnedByCurrentRegion(entity.getLocation())) {
                                    return;
                                }
                                RegionEntityUndoStore.Entry snap = RegionEntityUndoStore.snapshotOf(entity);
                                entity.remove();
                                if (snap != null) {
                                    List<RegionEntityUndoStore.Entry> extra = new ArrayList<>();
                                    extra.add(snap);
                                    RegionEntityUndoStore.Batch existing = undoStore.peek(world.getName(), liveId, wantType);
                                    if (existing != null) {
                                        extra.addAll(0, existing.entries());
                                    }
                                    undoStore.put(world.getName(), liveId, wantType, actionId, extra);
                                    if (liveId != regionId) {
                                        undoStore.put(world.getName(), regionId, wantType, actionId, extra);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                        removed++;
                        continue;
                    }
                    RegionEntityUndoStore.Entry snap = RegionEntityUndoStore.snapshotOf(entity);
                    if (snap != null) {
                        undo.add(snap);
                    }
                    entity.remove();
                    removed++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (!undo.isEmpty()) {
            undoStore.put(world.getName(), liveId, wantType, actionId, undo);
            if (liveId != regionId) {
                undoStore.put(world.getName(), regionId, wantType, actionId, undo);
            }
        }
        plugin.getLogger().info("[Regions] clear_entities " + wantType + " " + world.getName()
                + " requested#" + regionId + " live#" + liveId + " @" + hintCx + "," + hintCz
                + " removed=" + removed);
        return removed;
    }

    private void scheduleClearTiles(String worldName, long regionId, String tileType, String actionId,
                                    int hintCx, int hintCz) {
        JsonObject fail = new JsonObject();
        fail.addProperty("actionId", actionId);
        fail.addProperty("world", worldName);
        fail.addProperty("id", regionId);
        fail.addProperty("kind", "tile");
        fail.addProperty("tileType", tileType);
        fail.addProperty("entityType", tileType);
        fail.addProperty("ts", System.currentTimeMillis());
        String want = tileType == null ? "" : tileType.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || regionId < 0 || want.isBlank() || want.equals("moving_piston")) {
            fail.addProperty("ok", false);
            fail.addProperty("error", "bad_request");
            fail.addProperty("removed", 0);
            clearResults.add(fail);
            return;
        }
        final int fcx = hintCx;
        final int fcz = hintCz;
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, fcx, fcz, () -> {
                JsonObject result = new JsonObject();
                result.addProperty("actionId", actionId);
                result.addProperty("world", worldName);
                result.addProperty("id", regionId);
                result.addProperty("kind", "tile");
                result.addProperty("tileType", want);
                result.addProperty("entityType", want);
                result.addProperty("hintChunkX", fcx);
                result.addProperty("hintChunkZ", fcz);
                result.addProperty("ts", System.currentTimeMillis());
                try {
                    long liveId = liveRegionId(world, regionId, fcx, fcz);
                    result.addProperty("resolvedId", liveId);
                    int removed = clearTilesOnRegionThread(world, regionId, liveId, want, actionId, fcx, fcz);
                    result.addProperty("ok", removed > 0);
                    result.addProperty("removed", removed);
                    if (removed == 0) {
                        result.addProperty("error", "none_found");
                    }
                    result.addProperty("restoreAvailable",
                            tileUndoStore.peek(worldName, liveId, want) != null
                                    || tileUndoStore.peek(worldName, regionId, want) != null);
                } catch (Throwable t) {
                    result.addProperty("ok", false);
                    result.addProperty("removed", 0);
                    result.addProperty("error", t.getClass().getSimpleName());
                }
                clearResults.add(result);
            });
        } catch (Throwable t) {
            fail.addProperty("ok", false);
            fail.addProperty("error", t.getClass().getSimpleName());
            fail.addProperty("removed", 0);
            clearResults.add(fail);
        }
    }

    private void scheduleRestoreTiles(String worldName, long regionId, String tileType, String actionId,
                                      int hintCx, int hintCz) {
        JsonObject fail = new JsonObject();
        fail.addProperty("actionId", actionId);
        fail.addProperty("world", worldName);
        fail.addProperty("id", regionId);
        fail.addProperty("kind", "tile");
        fail.addProperty("tileType", tileType);
        fail.addProperty("entityType", tileType);
        fail.addProperty("ts", System.currentTimeMillis());
        fail.addProperty("restore", true);
        String want = tileType == null ? "" : tileType.toLowerCase(Locale.ROOT).replace("minecraft:", "").trim();
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || regionId < 0 || want.isBlank()) {
            fail.addProperty("ok", false);
            fail.addProperty("error", "bad_request");
            fail.addProperty("restored", 0);
            clearResults.add(fail);
            return;
        }
        int cx = hintCx;
        int cz = hintCz;
        RegionTileUndoStore.Batch peek = tileUndoStore.peekBest(worldName, regionId, want);
        if (peek != null && !peek.entries().isEmpty() && peek.entries().get(0).location() != null) {
            Location loc = peek.entries().get(0).location();
            cx = loc.getBlockX() >> 4;
            cz = loc.getBlockZ() >> 4;
        }
        final int fcx = cx;
        final int fcz = cz;
        try {
            Bukkit.getRegionScheduler().execute(plugin, world, fcx, fcz, () -> {
                JsonObject result = new JsonObject();
                result.addProperty("actionId", actionId);
                result.addProperty("world", worldName);
                result.addProperty("id", regionId);
                result.addProperty("kind", "tile");
                result.addProperty("tileType", want);
                result.addProperty("entityType", want);
                result.addProperty("ts", System.currentTimeMillis());
                result.addProperty("restore", true);
                try {
                    int restored = restoreTilesOnRegionThread(worldName, regionId, want);
                    result.addProperty("ok", restored > 0);
                    result.addProperty("restored", restored);
                    if (restored == 0) {
                        result.addProperty("error", "none_found");
                    }
                    result.addProperty("restoreAvailable", tileUndoStore.peekBest(worldName, regionId, want) != null);
                } catch (Throwable t) {
                    result.addProperty("ok", false);
                    result.addProperty("restored", 0);
                    result.addProperty("error", t.getClass().getSimpleName());
                }
                clearResults.add(result);
            });
        } catch (Throwable t) {
            fail.addProperty("ok", false);
            fail.addProperty("error", t.getClass().getSimpleName());
            fail.addProperty("restored", 0);
            clearResults.add(fail);
        }
    }

    private int restoreTilesOnRegionThread(String worldName, long regionId, String wantType) {
        RegionTileUndoStore.Batch batch = tileUndoStore.takeBest(worldName, regionId, wantType);
        if (batch == null || batch.entries().isEmpty()) {
            return 0;
        }
        int restored = 0;
        for (RegionTileUndoStore.Entry entry : batch.entries()) {
            if (restoreTileEntry(entry)) {
                restored++;
            }
        }
        return restored;
    }

    private boolean restoreTileEntry(RegionTileUndoStore.Entry entry) {
        try {
            Location loc = entry.location();
            if (loc == null || loc.getWorld() == null) {
                return false;
            }
            if (!Bukkit.isOwnedByCurrentRegion(loc)) {
                Bukkit.getRegionScheduler().execute(plugin, loc, () -> restoreTileEntry(entry));
                return true;
            }
            return RegionTileUndoStore.restore(entry);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int clearTilesOnRegionThread(World world, long regionId, long liveId, String wantType, String actionId,
                                        int hintCx, int hintCz) throws Exception {
        Object region = findThreadedRegion(world, regionId, hintCx, hintCz);
        if (region == null) {
            plugin.getLogger().warning("[Regions] clear_tiles " + wantType + " " + world.getName()
                    + " #" + regionId + " @chunk " + hintCx + "," + hintCz + ": region not found");
            return 0;
        }
        Set<Long> owned = ownedChunkKeys(region);
        int removed = 0;
        int scannedChunks = 0;
        List<RegionTileUndoStore.Entry> undo = new ArrayList<>();
        List<int[]> deferredChunks = new ArrayList<>();

        if (!owned.isEmpty()) {
            for (long key : owned) {
                int[] xz = unpackChunkPos(key);
                int cx = xz[0];
                int cz = xz[1];
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                Location probe = new Location(world, (cx << 4) + 8, 64, (cz << 4) + 8);
                if (!Bukkit.isOwnedByCurrentRegion(probe)) {
                    deferredChunks.add(new int[]{cx, cz});
                    continue;
                }
                scannedChunks++;
                removed += clearTilesInChunk(world, cx, cz, wantType, undo);
            }
        } else {
            
            removed += clearTilesViaTickers(world, region, wantType, undo);
        }

        if (!undo.isEmpty()) {
            tileUndoStore.put(world.getName(), liveId, wantType, actionId, undo);
            if (liveId != regionId) {
                tileUndoStore.put(world.getName(), regionId, wantType, actionId, undo);
            }
        }

        for (int[] chunk : deferredChunks) {
            final int cx = chunk[0];
            final int cz = chunk[1];
            Bukkit.getRegionScheduler().execute(plugin, world, cx, cz, () -> {
                try {
                    if (!world.isChunkLoaded(cx, cz)) {
                        return;
                    }
                    List<RegionTileUndoStore.Entry> extra = new ArrayList<>();
                    int n = clearTilesInChunk(world, cx, cz, wantType, extra);
                    if (n > 0) {
                        for (RegionTileUndoStore.Entry e : extra) {
                            tileUndoStore.append(world.getName(), liveId, wantType, actionId, e);
                            if (liveId != regionId) {
                                tileUndoStore.append(world.getName(), regionId, wantType, actionId, e);
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            });
        }

        tileCache.remove(world.getName() + ":" + liveId);
        tileCache.remove(world.getName() + ":" + regionId);
        plugin.getLogger().info("[Regions] clear_tiles " + wantType + " " + world.getName()
                + " requested#" + regionId + " live#" + liveId + " @" + hintCx + "," + hintCz
                + " ownedChunks=" + owned.size() + " scannedChunks=" + scannedChunks
                + " deferredChunks=" + deferredChunks.size() + " removed=" + removed);
        return removed;
    }

    private int clearTilesInChunk(World world, int cx, int cz, String wantType,
                                  List<RegionTileUndoStore.Entry> undo) {
        int removed = 0;
        try {
            org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
            org.bukkit.block.BlockState[] tiles = chunk.getTileEntities();
            if (tiles == null || tiles.length == 0) {
                return 0;
            }
            for (org.bukkit.block.BlockState state : tiles) {
                if (state == null) {
                    continue;
                }
                String key;
                try {
                    key = state.getType().getKey().getKey().toLowerCase(Locale.ROOT);
                } catch (Throwable t) {
                    key = state.getType().name().toLowerCase(Locale.ROOT);
                }
                if (!wantType.equals(key)) {
                    continue;
                }
                Block block = state.getBlock();
                Location loc = block.getLocation();
                if (!Bukkit.isOwnedByCurrentRegion(loc)) {
                    continue;
                }
                RegionTileUndoStore.Entry snap = RegionTileUndoStore.snapshotOf(block);
                if (snap != null) {
                    undo.add(snap);
                }
                block.setType(Material.AIR, false);
                removed++;
            }
        } catch (Throwable ignored) {
        }
        return removed;
    }

    private int clearTilesViaTickers(World world, Object region, String wantType,
                                     List<RegionTileUndoStore.Entry> undo) throws Exception {
        Object tickRegionData = region.getClass().getMethod("getData").invoke(region);
        Object worldData = findRegionizedWorldData(tickRegionData);
        if (worldData == null) {
            return 0;
        }
        Object tickers = worldData.getClass().getMethod("getBlockEntityTickers").invoke(worldData);
        if (!(tickers instanceof Iterable<?> it)) {
            return 0;
        }
        int removed = 0;
        int matched = 0;
        int noLoc = 0;
        for (Object te : it) {
            if (te == null) {
                continue;
            }
            try {
                String name = tickingTileName(te);
                String key = name == null ? "" : name.toLowerCase(Locale.ROOT).replace("minecraft:", "");
                if (!wantType.equals(key)) {
                    continue;
                }
                matched++;
                Location loc = tickerBukkitLocation(world, te);
                if (loc == null) {
                    noLoc++;
                    continue;
                }
                if (!Bukkit.isOwnedByCurrentRegion(loc)) {
                    continue;
                }
                Block block = loc.getBlock();
                RegionTileUndoStore.Entry snap = RegionTileUndoStore.snapshotOf(block);
                if (snap != null) {
                    undo.add(snap);
                }
                block.setType(Material.AIR, false);
                removed++;
            } catch (Throwable ignored) {
            }
        }
        plugin.getLogger().info("[Regions] clear_tiles ticker-fallback matched=" + matched
                + " noLoc=" + noLoc + " removed=" + removed);
        return removed;
    }

    private Object findThreadedRegion(World world, long regionId, int hintCx, int hintCz) throws Exception {
        Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
        Field regioniserField = findField(serverLevel.getClass(), "regioniser");
        regioniserField.setAccessible(true);
        Object regioniser = regioniserField.get(serverLevel);

        Object byChunk = regionAtUnsync(regioniser, hintCx, hintCz);
        if (byChunk == null && hintCx != hintCz) {
            byChunk = regionAtUnsync(regioniser, hintCz, hintCx);
        }
        if (byChunk != null) {
            return byChunk;
        }

        Method computeForAll = regioniser.getClass().getMethod("computeForAllRegions", java.util.function.Consumer.class);
        List<Object> regions = new ArrayList<>();
        computeForAll.invoke(regioniser, (java.util.function.Consumer<Object>) regions::add);
        Object byId = null;
        Object byOwned = null;
        long packed = packChunkPos(hintCx, hintCz);
        long packedSwap = packChunkPos(hintCz, hintCx);
        for (Object region : regions) {
            Field idField = findField(region.getClass(), "id");
            idField.setAccessible(true);
            long id = idField.getLong(region);
            if (id == regionId) {
                byId = region;
            }
            Set<Long> owned = ownedChunkKeys(region);
            if (owned.contains(packed) || owned.contains(packedSwap)) {
                byOwned = region;
            }
        }
        return byOwned != null ? byOwned : byId;
    }

    private int[] unpackChunkPos(long key) {
        try {
            Class<?> chunkPos = Class.forName("net.minecraft.world.level.ChunkPos");
            Object pos = chunkPos.getConstructor(long.class).newInstance(key);
            try {
                int x = ((Number) pos.getClass().getMethod("getX").invoke(pos)).intValue();
                int z = ((Number) pos.getClass().getMethod("getZ").invoke(pos)).intValue();
                return new int[]{x, z};
            } catch (Throwable t) {
                int x = ((Number) pos.getClass().getField("x").get(pos)).intValue();
                int z = ((Number) pos.getClass().getField("z").get(pos)).intValue();
                return new int[]{x, z};
            }
        } catch (Throwable t) {
            
            int x = (int) key;
            int z = (int) (key >> 32);
            return new int[]{x, z};
        }
    }

    private Location tickerBukkitLocation(World world, Object ticker) {
        Object pos = invokeFirst(ticker, "getPos", "getBlockPos", "pos", "getBlockPosition");
        if (pos == null) {
            Object be = invokeFirst(ticker, "getTileEntity", "getBlockEntity", "blockEntity");
            if (be != null) {
                pos = invokeFirst(be, "getBlockPos", "getPos", "getBlockPosition", "pos");
                if (pos == null) {
                    pos = fieldFirst(be, "worldPosition", "position", "blockPos", "pos");
                }
            }
        }
        if (pos == null) {
            return null;
        }
        try {
            int x;
            int y;
            int z;
            try {
                x = ((Number) pos.getClass().getMethod("getX").invoke(pos)).intValue();
                y = ((Number) pos.getClass().getMethod("getY").invoke(pos)).intValue();
                z = ((Number) pos.getClass().getMethod("getZ").invoke(pos)).intValue();
            } catch (Throwable t) {
                x = ((Number) pos.getClass().getField("x").get(pos)).intValue();
                y = ((Number) pos.getClass().getField("y").get(pos)).intValue();
                z = ((Number) pos.getClass().getField("z").get(pos)).intValue();
            }
            return new Location(world, x, y, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object fieldFirst(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Field f = findField(target.getClass(), name);
                f.setAccessible(true);
                Object v = f.get(target);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object invokeFirst(Object target, String... methods) {
        if (target == null) {
            return null;
        }
        for (String name : methods) {
            try {
                Object v = target.getClass().getMethod(name).invoke(target);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private int[] chunkHintFromAction(JsonObject a, String worldName, long regionId) {
        Integer cx = jsonInt(a, "centerChunkX");
        Integer cz = jsonInt(a, "centerChunkZ");
        if (cx == null) {
            Integer bx = jsonInt(a, "centerBlockX");
            if (bx != null) {
                cx = bx >> 4;
            }
        }
        if (cz == null) {
            Integer bz = jsonInt(a, "centerBlockZ");
            if (bz != null) {
                cz = bz >> 4;
            }
        }
        if (cx == null || cz == null) {
            int[] fb = regionCenterChunks(worldName, regionId);
            if (cx == null) {
                cx = fb[0];
            }
            if (cz == null) {
                cz = fb[1];
            }
        }
        return new int[]{cx, cz};
    }

    private static Integer jsonInt(JsonObject a, String key) {
        try {
            if (a != null && a.has(key) && !a.get(key).isJsonNull()) {
                return a.get(key).getAsInt();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object regionAtUnsync(Object regioniser, int cx, int cz) {
        if (regioniser == null) {
            return null;
        }
        try {
            Method getSectionCoordinate = regioniser.getClass().getMethod("getSectionCoordinate", int.class);
            int sx = ((Number) getSectionCoordinate.invoke(regioniser, cx)).intValue();
            int sz = ((Number) getSectionCoordinate.invoke(regioniser, cz)).intValue();
            Method getRegionAt = regioniser.getClass().getMethod("getRegionAtUnsynchronised", int.class, int.class);
            Object region = getRegionAt.invoke(regioniser, sx, sz);
            if (region == null) {
                region = getRegionAt.invoke(regioniser, cx, cz);
            }
            return region;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object findTickRegionData(World world, long regionId, int hintCx, int hintCz) throws Exception {
        Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
        Field regioniserField = findField(serverLevel.getClass(), "regioniser");
        regioniserField.setAccessible(true);
        Object regioniser = regioniserField.get(serverLevel);

        Object byChunk = regionAtUnsync(regioniser, hintCx, hintCz);
        if (byChunk == null && hintCx != hintCz) {
            byChunk = regionAtUnsync(regioniser, hintCz, hintCx);
        }
        if (byChunk != null) {
            return byChunk.getClass().getMethod("getData").invoke(byChunk);
        }

        Method computeForAll = regioniser.getClass().getMethod("computeForAllRegions", java.util.function.Consumer.class);
        List<Object> regions = new ArrayList<>();
        computeForAll.invoke(regioniser, (java.util.function.Consumer<Object>) regions::add);
        Object byId = null;
        Object byOwned = null;
        long packed = packChunkPos(hintCx, hintCz);
        long packedSwap = packChunkPos(hintCz, hintCx);
        for (Object region : regions) {
            Field idField = findField(region.getClass(), "id");
            idField.setAccessible(true);
            long id = idField.getLong(region);
            if (id == regionId) {
                byId = region.getClass().getMethod("getData").invoke(region);
            }
            Set<Long> owned = ownedChunkKeys(region);
            if (owned.contains(packed) || owned.contains(packedSwap)) {
                byOwned = region.getClass().getMethod("getData").invoke(region);
            }
        }
        if (byOwned != null) {
            return byOwned;
        }
        return byId;
    }

    private long liveRegionId(World world, long fallback, int hintCx, int hintCz) {
        try {
            Object serverLevel = world.getClass().getMethod("getHandle").invoke(world);
            Field regioniserField = findField(serverLevel.getClass(), "regioniser");
            regioniserField.setAccessible(true);
            Object regioniser = regioniserField.get(serverLevel);
            Object region = regionAtUnsync(regioniser, hintCx, hintCz);
            if (region == null) {
                region = regionAtUnsync(regioniser, hintCz, hintCx);
            }
            if (region != null) {
                Field idField = findField(region.getClass(), "id");
                idField.setAccessible(true);
                return idField.getLong(region);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private int[] regionCenterChunks(String worldName, long regionId) {
        int cx = 0;
        int cz = 0;
        try {
            JsonObject snap = lastSnapshot;
            if (snap != null && snap.has("regions") && snap.get("regions").isJsonArray()) {
                for (var el : snap.getAsJsonArray("regions")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject r = el.getAsJsonObject();
                    if (worldName.equals(r.has("world") ? r.get("world").getAsString() : "")
                            && r.has("id") && r.get("id").getAsLong() == regionId) {
                        if (r.has("centerChunkX")) cx = r.get("centerChunkX").getAsInt();
                        if (r.has("centerChunkZ")) cz = r.get("centerChunkZ").getAsInt();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new int[]{cx, cz};
    }

    private static JsonObject emptySnapshot(String reason) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", System.currentTimeMillis());
        o.addProperty("error", reason);
        o.add("regions", new JsonArray());
        return o;
    }
}
