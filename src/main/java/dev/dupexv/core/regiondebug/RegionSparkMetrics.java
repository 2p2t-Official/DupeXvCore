package dev.dupexv.core.regiondebug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RegionSparkMetrics {

    private RegionSparkMetrics() {
    }

    public static JsonObject jvmHealth() {
        JsonObject o = new JsonObject();
        try {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            long heapUsed = mem.getHeapMemoryUsage().getUsed();
            long heapCommitted = mem.getHeapMemoryUsage().getCommitted();
            long heapMax = mem.getHeapMemoryUsage().getMax();
            o.addProperty("heapUsedMb", round1(heapUsed / 1024.0 / 1024.0));
            o.addProperty("heapCommittedMb", round1(heapCommitted / 1024.0 / 1024.0));
            o.addProperty("heapMaxMb", heapMax > 0 ? round1(heapMax / 1024.0 / 1024.0) : -1);
            o.addProperty("heapUsedPct", heapMax > 0 ? round1(100.0 * heapUsed / heapMax) : -1);
        } catch (Throwable ignored) {
        }
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            o.addProperty("osLoad", round2(os.getSystemLoadAverage()));
            o.addProperty("cpus", os.getAvailableProcessors());
            try {
                Method cpu = os.getClass().getMethod("getProcessCpuLoad");
                Object v = cpu.invoke(os);
                if (v instanceof Number n && n.doubleValue() >= 0) {
                    o.addProperty("processCpuPct", round1(n.doubleValue() * 100.0));
                }
            } catch (Throwable ignored) {
            }
            try {
                Method cpu = os.getClass().getMethod("getSystemCpuLoad");
                Object v = cpu.invoke(os);
                if (v instanceof Number n && n.doubleValue() >= 0) {
                    o.addProperty("systemCpuPct", round1(n.doubleValue() * 100.0));
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
        long gcTime = 0;
        long gcCount = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionTime() > 0) {
                gcTime += gc.getCollectionTime();
            }
            if (gc.getCollectionCount() > 0) {
                gcCount += gc.getCollectionCount();
            }
        }
        o.addProperty("gcTimeMs", gcTime);
        o.addProperty("gcCount", gcCount);
        o.addProperty("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        o.addProperty("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        o.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
        o.addProperty("maxPlayers", Bukkit.getMaxPlayers());
        return o;
    }

    public static JsonObject worldInfo(World world) {
        JsonObject o = new JsonObject();
        o.addProperty("name", world.getName());
        o.addProperty("environment", world.getEnvironment().name().toLowerCase(Locale.ROOT));
        o.addProperty("difficulty", world.getDifficulty().name().toLowerCase(Locale.ROOT));
        o.addProperty("viewDistance", world.getViewDistance());
        try {
            o.addProperty("simulationDistance", world.getSimulationDistance());
        } catch (Throwable ignored) {
        }
        o.addProperty("pvp", world.getPVP());
        Integer rts = world.getGameRuleValue(GameRule.RANDOM_TICK_SPEED);
        o.addProperty("randomTickSpeed", rts != null ? rts : 3);
        return o;
    }

    public static JsonObject playerDetail(Player player) {
        JsonObject o = new JsonObject();
        o.addProperty("name", player.getName());
        o.addProperty("uuid", player.getUniqueId().toString());
        o.addProperty("x", player.getLocation().getBlockX());
        o.addProperty("y", player.getLocation().getBlockY());
        o.addProperty("z", player.getLocation().getBlockZ());
        o.addProperty("ping", player.getPing());
        GameMode gm = player.getGameMode();
        o.addProperty("gamemode", gm != null ? gm.name().toLowerCase(Locale.ROOT) : "?");
        try {
            o.addProperty("viewDistance", player.getViewDistance());
        } catch (Throwable ignored) {
        }
        try {
            o.addProperty("simulationDistance", player.getSimulationDistance());
        } catch (Throwable ignored) {
        }
        o.addProperty("flying", player.isFlying());
        o.addProperty("gliding", player.isGliding());
        o.addProperty("sneaking", player.isSneaking());
        o.addProperty("sprinting", player.isSprinting());
        o.addProperty("health", round1(player.getHealth()));
        try {
            String brand = player.getClientBrandName();
            if (brand != null && !brand.isBlank()) {
                o.addProperty("brand", brand);
            }
        } catch (Throwable ignored) {
        }
        o.addProperty("protocol", player.getProtocolVersion());
        return o;
    }

    public static String playerLabel(JsonObject d) {
        String name = d.has("name") ? d.get("name").getAsString() : "?";
        String loc = "@" + d.get("x").getAsInt() + "," + d.get("y").getAsInt() + "," + d.get("z").getAsInt();
        String extra = " ping=" + (d.has("ping") ? d.get("ping").getAsInt() : "?");
        if (d.has("viewDistance") || d.has("simulationDistance")) {
            extra += " view=" + (d.has("viewDistance") ? d.get("viewDistance").getAsInt() : "?")
                    + " sim=" + (d.has("simulationDistance") ? d.get("simulationDistance").getAsInt() : "?");
        }
        if (d.has("brand")) {
            extra += " " + d.get("brand").getAsString();
        }
        return name + " " + loc + extra;
    }

    public static void applyLoadBreakdown(JsonObject row, Map<String, Integer> entityTypes,
                                          Map<String, Integer> tileTypes, int chunks,
                                          int randomTickSpeed, int playerCount) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("chunk_random_ticks", chunks * Math.max(0, randomTickSpeed) * 0.09);
        scores.put("players", playerCount * 18.0);

        int hoppers = tileCount(tileTypes, "hopper");
        int pistons = tileCount(tileTypes, "piston") + tileCount(tileTypes, "sticky_piston")
                + tileCount(tileTypes, "observer");
        int furnaces = tileCount(tileTypes, "furnace") + tileCount(tileTypes, "blast_furnace")
                + tileCount(tileTypes, "smoker") + tileCount(tileTypes, "campfire");
        int chests = tileCount(tileTypes, "chest") + tileCount(tileTypes, "trapped_chest")
                + tileCount(tileTypes, "barrel") + tileCount(tileTypes, "hopper_minecart");
        int tickingTe = row.has("tickingTileEntities") ? row.get("tickingTileEntities").getAsInt() : 0;
        int pendingBlocks = row.has("pendingBlockTicks") ? row.get("pendingBlockTicks").getAsInt() : 0;
        int pendingFluids = row.has("pendingFluidTicks") ? row.get("pendingFluidTicks").getAsInt() : 0;
        int primedTnt = row.has("primedTnt") ? row.get("primedTnt").getAsInt() : 0;
        scores.put("hoppers_te", hoppers * 14.0);
        scores.put("redstone_te", pistons * 7.0);
        scores.put("furnaces_te", furnaces * 3.5);
        scores.put("containers_te", chests * 0.6);
        scores.put("ticking_block_entities", Math.max(0, tickingTe - hoppers - furnaces) * 4.0);
        int redstoneSched = row.has("redstoneScheduledTotal") ? row.get("redstoneScheduledTotal").getAsInt() : 0;
        int redstoneTe = row.has("redstoneTileTotal") ? row.get("redstoneTileTotal").getAsInt() : 0;
        int neighborUpdates = row.has("neighborUpdates") ? row.get("neighborUpdates").getAsInt() : 0;
        scores.put("scheduled_block_ticks", Math.max(0, pendingBlocks - redstoneSched) * 0.12);
        scores.put("redstone_clocks", redstoneSched * 0.55);
        scores.put("redstone_tes", Math.max(0, redstoneTe - hoppers) * 6.0);
        scores.put("neighbor_updates", neighborUpdates * 0.08);
        scores.put("scheduled_fluid_ticks", pendingFluids * 0.18);
        scores.put("primed_tnt", primedTnt * 40.0);

        int passive = 0, hostile = 0, items = 0, vehicles = 0, frames = 0, villagers = 0, displays = 0, other = 0;
        if (entityTypes != null) {
            for (Map.Entry<String, Integer> e : entityTypes.entrySet()) {
                String t = e.getKey().toLowerCase(Locale.ROOT).replace("minecraft:", "");
                int n = e.getValue();
                if (t.equals("player")) {
                    continue;
                }
                if (t.equals("item")) {
                    items += n;
                } else if (t.contains("minecart") || t.contains("boat") || t.contains("raft")) {
                    vehicles += n;
                } else if (t.contains("item_frame") || t.contains("painting")) {
                    frames += n;
                } else if (t.contains("villager") || t.equals("wandering_trader")) {
                    villagers += n;
                } else if (t.contains("armor_stand") || t.contains("display") || t.contains("interaction")) {
                    displays += n;
                } else if (isHostile(t)) {
                    hostile += n;
                } else if (isPassive(t)) {
                    passive += n;
                } else {
                    other += n;
                }
            }
        }
        scores.put("passive_mobs", passive * 2.6);
        scores.put("hostile_mobs", hostile * 3.8);
        scores.put("villagers", villagers * 11.0);
        scores.put("vehicles", vehicles * 8.5);
        scores.put("item_frames", frames * 1.4);
        scores.put("ground_items", items * 0.45);
        scores.put("displays", displays * 0.9);
        scores.put("other_entities", other * 1.5);

        double total = scores.values().stream().mapToDouble(Double::doubleValue).sum();
        JsonArray breakdown = new JsonArray();
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        for (Map.Entry<String, Double> e : sorted) {
            if (e.getValue() < 0.01) {
                continue;
            }
            JsonObject part = new JsonObject();
            part.addProperty("name", e.getKey());
            part.addProperty("score", round1(e.getValue()));
            part.addProperty("pct", total > 0 ? round1(100.0 * e.getValue() / total) : 0);
            breakdown.add(part);
        }
        row.add("sparkBreakdown", breakdown);
        row.addProperty("sparkTotalScore", round1(total));
        if (!sorted.isEmpty() && total > 0) {
            row.addProperty("sparkTopShare", sorted.get(0).getKey() + " " + round1(100.0 * sorted.get(0).getValue() / total) + "%");
        }

        int expectedSim = 0;
        if (row.has("playerDetails") && row.get("playerDetails").isJsonArray()) {
            JsonArray dets = row.getAsJsonArray("playerDetails");
            int maxSim = 0;
            int maxView = 0;
            for (var el : dets) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                if (p.has("simulationDistance")) {
                    maxSim = Math.max(maxSim, p.get("simulationDistance").getAsInt());
                }
                if (p.has("viewDistance")) {
                    maxView = Math.max(maxView, p.get("viewDistance").getAsInt());
                }
            }
            if (maxSim > 0) {
                int d = 2 * maxSim + 1;
                expectedSim = d * d;
            }
            int expectedView = maxView > 0 ? (2 * maxView + 1) * (2 * maxView + 1) : 0;
            row.addProperty("expectedSimChunks", expectedSim);
            row.addProperty("expectedViewChunks", expectedView);
            if (expectedView > 0) {
                row.addProperty("chunkLoadFactor", round2(chunks / (double) expectedView));
            } else if (expectedSim > 0) {
                row.addProperty("chunkLoadFactor", round2(chunks / (double) expectedSim));
            }
            if (expectedSim > 0) {
                row.addProperty("chunkLoadFactorSim", round2(chunks / (double) expectedSim));
            }
        }
    }

    public static void applyPlayerSpread(JsonObject row, List<JsonObject> details) {
        if (details == null || details.size() < 2) {
            row.addProperty("playerSpreadBlocks", 0);
            return;
        }
        double max = 0;
        for (int i = 0; i < details.size(); i++) {
            JsonObject a = details.get(i);
            for (int j = i + 1; j < details.size(); j++) {
                JsonObject b = details.get(j);
                double dx = a.get("x").getAsDouble() - b.get("x").getAsDouble();
                double dz = a.get("z").getAsDouble() - b.get("z").getAsDouble();
                max = Math.max(max, Math.sqrt(dx * dx + dz * dz));
            }
        }
        row.addProperty("playerSpreadBlocks", round1(max));
    }

    public static void putSegmentStats(JsonObject target, String prefix, String window, Object segmentedAvg, boolean nanosToMs) {
        if (segmentedAvg == null) {
            return;
        }
        try {
            Object all = segmentedAvg.getClass().getMethod("segmentAll").invoke(segmentedAvg);
            putOneSegment(target, prefix, window, "", all, nanosToMs);
            try {
                Object worst5 = segmentedAvg.getClass().getMethod("segment5PercentWorst").invoke(segmentedAvg);
                putOneSegment(target, prefix, window, "P95", worst5, nanosToMs);
            } catch (Throwable ignored) {
            }
            try {
                Object worst1 = segmentedAvg.getClass().getMethod("segment1PercentWorst").invoke(segmentedAvg);
                putOneSegment(target, prefix, window, "P99", worst1, nanosToMs);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static void putOneSegment(JsonObject target, String prefix, String window, String suffix,
                                      Object segment, boolean nanosToMs) throws Exception {
        if (segment == null) {
            return;
        }
        String p = prefix == null || prefix.isBlank() ? "" : prefix;
        double scale = nanosToMs ? 1_000_000.0 : 1.0;
        double avg = ((Number) segment.getClass().getMethod("average").invoke(segment)).doubleValue() / scale;
        double med = ((Number) segment.getClass().getMethod("median").invoke(segment)).doubleValue() / scale;
        double least = ((Number) segment.getClass().getMethod("least").invoke(segment)).doubleValue() / scale;
        double greatest = ((Number) segment.getClass().getMethod("greatest").invoke(segment)).doubleValue() / scale;
        int count = ((Number) segment.getClass().getMethod("count").invoke(segment)).intValue();
        if (suffix.isEmpty()) {
            target.addProperty(p + "mspt" + window + "Min", round2(least));
            target.addProperty(p + "mspt" + window + "Median", round2(med));
            target.addProperty(p + "mspt" + window + "Max", round2(greatest));
            target.addProperty(p + "ticks" + window, count);
        } else {
            target.addProperty(p + "mspt" + window + suffix, round2(avg));
        }
    }

    private static int tileCount(Map<String, Integer> tiles, String name) {
        if (tiles == null || tiles.isEmpty()) {
            return 0;
        }
        int n = 0;
        String want = name.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        for (Map.Entry<String, Integer> e : tiles.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT).replace("minecraft:", "");
            if (k.equals(want)) {
                n += e.getValue();
            }
        }
        return n;
    }

    private static boolean isHostile(String t) {
        return t.contains("zombie") || t.contains("skeleton") || t.contains("creeper") || t.contains("spider")
                || t.contains("enderman") || t.contains("witch") || t.contains("slime") || t.contains("phantom")
                || t.contains("drowned") || t.contains("pillager") || t.contains("vindicator") || t.contains("ravager")
                || t.contains("hoglin") || t.contains("piglin") || t.contains("blaze") || t.contains("ghast")
                || t.contains("guardian") || t.contains("shulker") || t.equals("warden") || t.contains("breeze")
                || t.contains("bogged") || t.contains("husk") || t.contains("stray") || t.contains("vex")
                || t.contains("evoker") || t.contains("silverfish") || t.contains("endermite") || t.contains("zoglin")
                || t.contains("wither");
    }

    private static boolean isPassive(String t) {
        return t.equals("pig") || t.equals("cow") || t.equals("sheep") || t.equals("chicken") || t.equals("rabbit")
                || t.equals("horse") || t.equals("donkey") || t.equals("mule") || t.equals("llama") || t.equals("cat")
                || t.equals("wolf") || t.equals("parrot") || t.equals("fox") || t.equals("bee") || t.equals("goat")
                || t.equals("frog") || t.equals("axolotl") || t.equals("sniffer") || t.equals("camel") || t.equals("panda")
                || t.equals("mooshroom") || t.equals("turtle") || t.equals("squid") || t.equals("glow_squid")
                || t.equals("dolphin") || t.equals("cod") || t.equals("salmon") || t.equals("tropical_fish")
                || t.equals("pufferfish") || t.equals("bat") || t.equals("allay") || t.equals("armadillo")
                || t.equals("iron_golem") || t.equals("snow_golem");
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
