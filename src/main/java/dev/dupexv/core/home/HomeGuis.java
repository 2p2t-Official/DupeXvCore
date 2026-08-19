package dev.dupexv.core.home;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public final class HomeGuis {

    public static final class Main implements InventoryHolder {
        private Inventory inventory;
        public final int max;
        public final int[] homeSlots;
        public final int closeSlot;

        public Main(int max, int[] homeSlots, int closeSlot) {
            this.max = max;
            this.homeSlots = homeSlots;
            this.closeSlot = closeSlot;
        }

        public void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class Manage implements InventoryHolder {
        private Inventory inventory;
        public final String homeName;

        public Manage(String homeName) {
            this.homeName = homeName;
        }

        public void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static final class Confirm implements InventoryHolder {
        private Inventory inventory;
        public final String homeName;

        public Confirm(String homeName) {
            this.homeName = homeName;
        }

        public void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public static int[] homeSlots(int max) {
        return switch (Math.min(10, Math.max(2, max))) {
            case 2 -> new int[]{20, 24};
            case 3 -> new int[]{20, 22, 24};
            case 4 -> new int[]{20, 21, 23, 24};
            case 5 -> new int[]{20, 21, 22, 23, 24};
            case 6 -> new int[]{20, 21, 22, 23, 24, 31};
            case 7 -> new int[]{20, 21, 22, 23, 24, 29, 33};
            case 8 -> new int[]{20, 21, 22, 23, 24, 29, 30, 32};
            case 9 -> new int[]{20, 21, 22, 23, 24, 29, 30, 32, 33};
            default -> new int[]{20, 21, 22, 23, 24, 29, 30, 31, 32, 33};
        };
    }

    public static int closeSlot(int max) {
        return switch (Math.min(10, Math.max(2, max))) {
            case 2, 4 -> 22;
            case 3, 5, 7, 8 -> 31;
            case 6, 9 -> 40;
            default -> 49;
        };
    }

    public static final int[] LIME = {
            10, 11, 12, 13, 14, 15, 16, 19, 25, 28, 34, 37, 38, 39, 40, 41, 42, 43
    };
}
