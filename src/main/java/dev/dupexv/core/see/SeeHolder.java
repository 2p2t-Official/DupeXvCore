package dev.dupexv.core.see;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class SeeHolder implements InventoryHolder {

    public enum Kind {
        INV,
        ENDER
    }

    public final UUID viewer;
    public final UUID target;
    public final Kind kind;
    public final boolean[] dirty;
    public volatile boolean open = true;
    public volatile int gen;
    private Inventory inventory;

    public SeeHolder(UUID viewer, UUID target, Kind kind, int size) {
        this.viewer = viewer;
        this.target = target;
        this.kind = kind;
        this.dirty = new boolean[size];
    }

    public void bind(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
