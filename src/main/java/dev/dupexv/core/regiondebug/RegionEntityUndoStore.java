package dev.dupexv.core.regiondebug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntitySnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class RegionEntityUndoStore {

    private static final int MAX_SNAPSHOTS = 4000;

    private final ConcurrentHashMap<String, Batch> batches = new ConcurrentHashMap<>();

    public record Entry(EntitySnapshot snapshot, Location location) {
    }

    public record Batch(String world, long regionId, String entityType, String clearId,
                        List<Entry> entries, long ts) {
    }

    public static String key(String world, long regionId, String entityType) {
        return (world == null ? "?" : world) + "::" + regionId + "::"
                + (entityType == null ? "?" : entityType.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
    }

    public void put(String world, long regionId, String entityType, String clearId, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            batches.remove(key(world, regionId, entityType));
            return;
        }
        List<Entry> copy = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e != null && e.snapshot() != null && e.location() != null) {
                copy.add(e);
            }
            if (copy.size() >= MAX_SNAPSHOTS) {
                break;
            }
        }
        if (copy.isEmpty()) {
            batches.remove(key(world, regionId, entityType));
            return;
        }
        batches.put(key(world, regionId, entityType),
                new Batch(world, regionId, entityType, clearId, List.copyOf(copy), System.currentTimeMillis()));
    }

    public Batch peek(String world, long regionId, String entityType) {
        return batches.get(key(world, regionId, entityType));
    }

    public Batch take(String world, long regionId, String entityType) {
        return batches.remove(key(world, regionId, entityType));
    }

    public Batch peekBest(String world, long regionId, String entityType) {
        Batch exact = peek(world, regionId, entityType);
        if (exact != null) {
            return exact;
        }
        String want = entityType == null ? "?" : entityType.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        String prefix = (world == null ? "?" : world) + "::";
        String suffix = "::" + want;
        Batch best = null;
        for (Batch b : batches.values()) {
            if (b == null) {
                continue;
            }
            String k = key(b.world(), b.regionId(), b.entityType());
            if (k.startsWith(prefix) && k.endsWith(suffix) && (best == null || b.ts() > best.ts())) {
                best = b;
            }
        }
        return best;
    }

    public Batch takeBest(String world, long regionId, String entityType) {
        Batch exact = take(world, regionId, entityType);
        if (exact != null) {
            return exact;
        }
        Batch best = peekBest(world, regionId, entityType);
        if (best == null) {
            return null;
        }
        batches.remove(key(best.world(), best.regionId(), best.entityType()));
        return best;
    }

    public JsonArray toJson() {
        JsonArray arr = new JsonArray();
        for (Batch b : batches.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", "entity");
            o.addProperty("world", b.world());
            o.addProperty("id", b.regionId());
            o.addProperty("entityType", b.entityType());
            o.addProperty("clearId", b.clearId());
            o.addProperty("count", b.entries().size());
            o.addProperty("ts", b.ts());
            arr.add(o);
        }
        return arr;
    }

    public static Entry snapshotOf(Entity entity) {
        if (entity == null || !entity.isValid()) {
            return null;
        }
        try {
            EntitySnapshot snap = entity.createSnapshot();
            if (snap == null) {
                return null;
            }
            Location loc = entity.getLocation().clone();
            return new Entry(snap, loc);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
