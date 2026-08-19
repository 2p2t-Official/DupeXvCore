package dev.dupexv.core.regiondebug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

public final class RegionTileUndoStore {

    private static final int MAX_SNAPSHOTS = 8000;

    private final ConcurrentHashMap<String, Batch> batches = new ConcurrentHashMap<>();

    public record Entry(Location location, BlockState state, ItemStack[] contents, String blockData) {
    }

    public record Batch(String world, long regionId, String tileType, String clearId,
                        List<Entry> entries, long ts) {
    }

    public static String key(String world, long regionId, String tileType) {
        return (world == null ? "?" : world) + "::" + regionId + "::tile::"
                + (tileType == null ? "?" : tileType.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
    }

    public void put(String world, long regionId, String tileType, String clearId, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            batches.remove(key(world, regionId, tileType));
            return;
        }
        List<Entry> copy = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            if (e != null && e.location() != null
                    && (e.state() != null || e.blockData() != null || e.contents() != null)) {
                copy.add(e);
            }
            if (copy.size() >= MAX_SNAPSHOTS) {
                break;
            }
        }
        if (copy.isEmpty()) {
            batches.remove(key(world, regionId, tileType));
            return;
        }
        batches.put(key(world, regionId, tileType),
                new Batch(world, regionId, tileType, clearId, List.copyOf(copy), System.currentTimeMillis()));
    }

    public Batch peek(String world, long regionId, String tileType) {
        return batches.get(key(world, regionId, tileType));
    }

    public Batch take(String world, long regionId, String tileType) {
        return batches.remove(key(world, regionId, tileType));
    }

    public Batch peekBest(String world, long regionId, String tileType) {
        Batch exact = peek(world, regionId, tileType);
        if (exact != null) {
            return exact;
        }
        String want = tileType == null ? "?" : tileType.toLowerCase(Locale.ROOT).replace("minecraft:", "");
        String prefix = (world == null ? "?" : world) + "::";
        String suffix = "::tile::" + want;
        Batch best = null;
        for (Batch b : batches.values()) {
            if (b == null) {
                continue;
            }
            String k = key(b.world(), b.regionId(), b.tileType());
            if (k.startsWith(prefix) && k.endsWith(suffix) && (best == null || b.ts() > best.ts())) {
                best = b;
            }
        }
        return best;
    }

    public Batch takeBest(String world, long regionId, String tileType) {
        Batch exact = take(world, regionId, tileType);
        if (exact != null) {
            return exact;
        }
        Batch best = peekBest(world, regionId, tileType);
        if (best == null) {
            return null;
        }
        batches.remove(key(best.world(), best.regionId(), best.tileType()));
        return best;
    }

    public void append(String world, long regionId, String tileType, String clearId, Entry extra) {
        if (extra == null || extra.location() == null) {
            return;
        }
        Batch existing = peek(world, regionId, tileType);
        List<Entry> all = new ArrayList<>();
        if (existing != null) {
            all.addAll(existing.entries());
        }
        all.add(extra);
        put(world, regionId, tileType, clearId != null ? clearId : (existing != null ? existing.clearId() : "?"), all);
    }

    public JsonArray toJson() {
        JsonArray arr = new JsonArray();
        for (Batch b : batches.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", "tile");
            o.addProperty("world", b.world());
            o.addProperty("id", b.regionId());
            o.addProperty("tileType", b.tileType());
            o.addProperty("entityType", b.tileType());
            o.addProperty("clearId", b.clearId());
            o.addProperty("count", b.entries().size());
            o.addProperty("ts", b.ts());
            arr.add(o);
        }
        return arr;
    }

    public static Entry snapshotOf(Block block) {
        if (block == null || block.getType() == Material.AIR || block.getType().isAir()) {
            return null;
        }
        try {
            BlockState state = block.getState();
            if (state == null) {
                return null;
            }
            String data;
            try {
                data = block.getBlockData().getAsString();
            } catch (Throwable t) {
                data = block.getType().getKey().toString();
            }
            ItemStack[] contents = null;
            if (state instanceof InventoryHolder holder) {
                ItemStack[] src = holder.getInventory().getContents();
                contents = new ItemStack[src.length];
                for (int i = 0; i < src.length; i++) {
                    contents[i] = src[i] == null ? null : src[i].clone();
                }
            }
            return new Entry(block.getLocation().clone(), state, contents, data);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean restore(Entry entry) {
        if (entry == null || entry.location() == null || entry.location().getWorld() == null) {
            return false;
        }
        try {
            Block block = entry.location().getBlock();
            if (entry.blockData() != null && !entry.blockData().isBlank()) {
                try {
                    BlockData data = Bukkit.createBlockData(entry.blockData());
                    block.setBlockData(data, false);
                } catch (Throwable t) {
                    if (entry.state() != null) {
                        block.setType(entry.state().getType(), false);
                    }
                }
            } else if (entry.state() != null) {
                block.setType(entry.state().getType(), false);
            }
            boolean ok = false;
            if (entry.state() != null) {
                ok = entry.state().update(true, false);
            }
            if (entry.contents() != null) {
                BlockState fresh = block.getState();
                if (fresh instanceof InventoryHolder holder) {
                    holder.getInventory().setContents(entry.contents());
                    ok = fresh.update(true, false) || ok;
                }
            }
            return ok || block.getType() != Material.AIR;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
