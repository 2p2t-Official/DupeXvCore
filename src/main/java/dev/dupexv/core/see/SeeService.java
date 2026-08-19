package dev.dupexv.core.see;

import dev.dupexv.core.DupeXvCore;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SeeService implements Listener {

    private static final int[] CRAFT_GUI = {6, 7, 15, 16};

    private final DupeXvCore plugin;
    private final NamespacedKey fillKey;
    private final ConcurrentHashMap<UUID, SeeHolder> byViewer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, SeeHolder>> byTarget = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public SeeService(DupeXvCore plugin) {
        this.plugin = plugin;
        this.fillKey = new NamespacedKey(plugin, "see-fill");
    }

    public void shutdown() {
        for (SeeHolder see : byViewer.values()) {
            see.open = false;
            Player viewer = Bukkit.getPlayer(see.viewer);
            if (viewer != null && viewer.isOnline()) {
                run(viewer, viewer::closeInventory);
            }
        }
        byViewer.clear();
        byTarget.clear();
        inFlight.clear();
    }

    public void openInv(Player viewer, Player target) {
        run(target, () -> {
            ItemStack[] snap = captureInv(target);
            Component title = plugin.lang().component("invsee.title", "player", target.getName());
            run(viewer, () -> open(viewer, target, SeeHolder.Kind.INV, 54, title, snap));
        });
    }

    public void openEnder(Player viewer, Player target) {
        run(target, () -> {
            ItemStack[] snap = captureEnder(target);
            Component title = plugin.lang().component("endersee.title", "player", target.getName());
            run(viewer, () -> open(viewer, target, SeeHolder.Kind.ENDER, 27, title, snap));
        });
    }

    public void clearInv(CommandSender sender, Player target) {
        String name = target.getName();
        run(target, () -> {
            PlayerInventory inv = target.getInventory();
            inv.clear();
            inv.setItem(EquipmentSlot.HEAD, null);
            inv.setItem(EquipmentSlot.CHEST, null);
            inv.setItem(EquipmentSlot.LEGS, null);
            inv.setItem(EquipmentSlot.FEET, null);
            inv.setItem(EquipmentSlot.OFF_HAND, null);
            target.setItemOnCursor(null);
            CraftingInventory craft = crafting(target);
            if (craft != null) {
                ItemStack[] matrix = craft.getMatrix();
                Arrays.fill(matrix, null);
                craft.setMatrix(matrix);
                craft.setResult(null);
            }
            refreshTarget(target, SeeHolder.Kind.INV);
            done(sender, "invclear.done", name);
        });
    }

    public void clearEnder(CommandSender sender, Player target) {
        String name = target.getName();
        run(target, () -> {
            target.getEnderChest().clear();
            refreshTarget(target, SeeHolder.Kind.ENDER);
            done(sender, "enderclear.done", name);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof SeeHolder see) || !see.open) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        if (clicked == top) {
            int slot = event.getSlot();
            if (blocked(see, slot)) {
                event.setCancelled(true);
                return;
            }
            InventoryAction action = event.getAction();
            if (isFullTake(action) && empty(event.getCursor()) && !empty(event.getCurrentItem())) {
                markDirty(see, slot);
                pushSlot(see, slot, null);
            }
            return;
        }
        if (clicked != null && event.isShiftClick() && !empty(event.getCurrentItem())) {
            event.setCancelled(true);
            int dest = firstEmptyStorage(see);
            if (dest < 0) {
                return;
            }
            ItemStack moving = copy(event.getCurrentItem());
            clicked.setItem(event.getSlot(), null);
            top.setItem(dest, moving);
            markDirty(see, dest);
            pushFromGui(see);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClickLate(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof SeeHolder see) || !see.open) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        Inventory clicked = event.getClickedInventory();
        boolean topClick = clicked == top;
        boolean shiftIn = clicked != null && clicked != top && event.isShiftClick();
        boolean hotbar = event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD;
        if (!topClick && !shiftIn && !hotbar) {
            return;
        }
        markMappedDirty(see);
        pushFromGui(see);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof SeeHolder see) || !see.open) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && blocked(see, raw)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDragLate(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof SeeHolder see) || !see.open) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean hit = false;
        for (int raw : event.getRawSlots()) {
            if (raw < topSize && mapped(see, raw)) {
                markDirty(see, raw);
                hit = true;
            }
        }
        if (hit) {
            pushFromGui(see);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof SeeHolder see)) {
            return;
        }
        if (see.viewer.equals(viewer.getUniqueId()) && see.open) {
            pushFromGui(see);
            drop(see);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        SeeHolder self = byViewer.get(player.getUniqueId());
        if (self != null) {
            pushFromGui(self);
            drop(self);
        }
        ConcurrentHashMap<UUID, SeeHolder> viewers = byTarget.remove(player.getUniqueId());
        if (viewers == null) {
            return;
        }
        for (SeeHolder see : viewers.values()) {
            see.open = false;
            Player viewer = Bukkit.getPlayer(see.viewer);
            if (viewer != null && viewer.isOnline()) {
                run(viewer, () -> {
                    plugin.lang().send(viewer, "invsee.left", "player", player.getName());
                    if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof SeeHolder) {
                        viewer.closeInventory();
                    }
                });
            }
            drop(see);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlot(PlayerInventorySlotChangeEvent event) {
        refreshTarget(event.getPlayer(), SeeHolder.Kind.INV);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTargetClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof SeeHolder) {
            return;
        }
        if (event.getView().getTopInventory().equals(player.getEnderChest())
                || event.getView().getType() == InventoryType.ENDER_CHEST) {
            refreshTarget(player, SeeHolder.Kind.ENDER);
        }
        if (event.getView().getType() == InventoryType.CRAFTING) {
            refreshTarget(player, SeeHolder.Kind.INV);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTargetDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof SeeHolder) {
            return;
        }
        if (event.getView().getTopInventory().equals(player.getEnderChest())
                || event.getView().getType() == InventoryType.ENDER_CHEST) {
            refreshTarget(player, SeeHolder.Kind.ENDER);
        }
        if (event.getView().getType() == InventoryType.CRAFTING) {
            refreshTarget(player, SeeHolder.Kind.INV);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            refreshTarget(player, SeeHolder.Kind.INV);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        refreshTarget(event.getPlayer(), SeeHolder.Kind.INV);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        refreshTarget(event.getPlayer(), SeeHolder.Kind.INV);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(PlayerItemBreakEvent event) {
        refreshTarget(event.getPlayer(), SeeHolder.Kind.INV);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        refreshTarget(event.getPlayer(), SeeHolder.Kind.INV);
    }

    private void open(Player viewer, Player target, SeeHolder.Kind kind, int size, Component title, ItemStack[] snap) {
        SeeHolder previous = byViewer.get(viewer.getUniqueId());
        if (previous != null) {
            drop(previous);
        }
        SeeHolder see = new SeeHolder(viewer.getUniqueId(), target.getUniqueId(), kind, size);
        Inventory inv = Bukkit.createInventory(see, size, title);
        see.bind(inv);
        if (kind == SeeHolder.Kind.INV) {
            paintFillers(inv);
        }
        applySnap(see, snap, true);
        byViewer.put(viewer.getUniqueId(), see);
        byTarget.computeIfAbsent(target.getUniqueId(), id -> new ConcurrentHashMap<>()).put(viewer.getUniqueId(), see);
        viewer.openInventory(inv);
    }

    private void pushFromGui(SeeHolder see) {
        if (!see.open) {
            return;
        }
        ItemStack[] snap;
        int gen;
        synchronized (see) {
            snap = snapFromGui(see);
            see.gen++;
            gen = see.gen;
        }
        Player target = Bukkit.getPlayer(see.target);
        if (target == null || !target.isOnline()) {
            return;
        }
        begin(see.target);
        run(target, () -> {
            try {
                writeSnap(target, see.kind, snap);
            } finally {
                end(see.target);
            }
            Player viewer = Bukkit.getPlayer(see.viewer);
            if (viewer == null) {
                return;
            }
            run(viewer, () -> {
                synchronized (see) {
                    if (see.gen == gen) {
                        Arrays.fill(see.dirty, false);
                    }
                }
            });
        });
    }

    private void pushSlot(SeeHolder see, int guiSlot, ItemStack item) {
        Player target = Bukkit.getPlayer(see.target);
        if (target == null || !target.isOnline()) {
            return;
        }
        ItemStack copy = copy(item);
        begin(see.target);
        run(target, () -> {
            try {
                writeSlot(target, see.kind, guiSlot, copy);
            } finally {
                end(see.target);
            }
        });
    }

    private void refreshTarget(Player target, SeeHolder.Kind kind) {
        if (flying(target.getUniqueId())) {
            return;
        }
        ConcurrentHashMap<UUID, SeeHolder> viewers = byTarget.get(target.getUniqueId());
        if (viewers == null || viewers.isEmpty()) {
            return;
        }
        run(target, () -> {
            if (flying(target.getUniqueId())) {
                return;
            }
            ItemStack[] invSnap = kind == SeeHolder.Kind.INV ? captureInv(target) : null;
            ItemStack[] enderSnap = kind == SeeHolder.Kind.ENDER ? captureEnder(target) : null;
            for (SeeHolder see : viewers.values()) {
                if (!see.open || see.kind != kind) {
                    continue;
                }
                ItemStack[] snap = see.kind == SeeHolder.Kind.INV ? invSnap : enderSnap;
                if (snap == null) {
                    continue;
                }
                Player viewer = Bukkit.getPlayer(see.viewer);
                if (viewer == null) {
                    continue;
                }
                run(viewer, () -> applySnap(see, snap, false));
            }
        });
    }

    private void applySnap(SeeHolder see, ItemStack[] snap, boolean force) {
        Inventory inv = see.getInventory();
        if (inv == null || !see.open) {
            return;
        }
        synchronized (see) {
            for (int slot = 0; slot < inv.getSize() && slot < snap.length; slot++) {
                if (!force && see.dirty[slot]) {
                    continue;
                }
                if (see.kind == SeeHolder.Kind.INV && fillerSlot(slot)) {
                    continue;
                }
                if (see.kind == SeeHolder.Kind.INV && slot == 8) {
                    inv.setItem(8, empty(snap[8]) ? filler() : snap[8]);
                    continue;
                }
                if (see.kind == SeeHolder.Kind.INV && !mapped(see, slot) && slot != 8) {
                    continue;
                }
                inv.setItem(slot, snap[slot]);
            }
        }
    }

    private ItemStack[] snapFromGui(SeeHolder see) {
        Inventory inv = see.getInventory();
        ItemStack[] snap = new ItemStack[inv.getSize()];
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (see.kind == SeeHolder.Kind.INV && (fillerSlot(slot) || slot == 8)) {
                continue;
            }
            ItemStack item = inv.getItem(slot);
            if (filled(item)) {
                continue;
            }
            snap[slot] = copy(item);
        }
        return snap;
    }

    private ItemStack[] captureInv(Player player) {
        ItemStack[] snap = new ItemStack[54];
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            snap[storageToGui(i)] = copy(inv.getItem(i));
        }
        snap[0] = copy(inv.getItem(EquipmentSlot.HEAD));
        snap[1] = copy(inv.getItem(EquipmentSlot.CHEST));
        snap[2] = copy(inv.getItem(EquipmentSlot.LEGS));
        snap[3] = copy(inv.getItem(EquipmentSlot.FEET));
        snap[4] = copy(inv.getItem(EquipmentSlot.OFF_HAND));
        CraftingInventory craft = crafting(player);
        if (craft != null) {
            ItemStack[] matrix = craft.getMatrix();
            int n = Math.min(CRAFT_GUI.length, matrix.length);
            for (int i = 0; i < n; i++) {
                snap[CRAFT_GUI[i]] = copy(matrix[i]);
            }
            snap[8] = copy(craft.getResult());
        }
        return snap;
    }

    private ItemStack[] captureEnder(Player player) {
        Inventory ender = player.getEnderChest();
        ItemStack[] snap = new ItemStack[27];
        for (int i = 0; i < 27; i++) {
            snap[i] = copy(ender.getItem(i));
        }
        return snap;
    }

    private void writeSnap(Player player, SeeHolder.Kind kind, ItemStack[] snap) {
        if (kind == SeeHolder.Kind.ENDER) {
            Inventory ender = player.getEnderChest();
            for (int i = 0; i < 27 && i < snap.length; i++) {
                ender.setItem(i, copy(snap[i]));
            }
            return;
        }
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, copy(snap[storageToGui(i)]));
        }
        inv.setItem(EquipmentSlot.HEAD, copy(snap[0]));
        inv.setItem(EquipmentSlot.CHEST, copy(snap[1]));
        inv.setItem(EquipmentSlot.LEGS, copy(snap[2]));
        inv.setItem(EquipmentSlot.FEET, copy(snap[3]));
        inv.setItem(EquipmentSlot.OFF_HAND, copy(snap[4]));
        CraftingInventory craft = crafting(player);
        if (craft != null) {
            ItemStack[] matrix = craft.getMatrix();
            int n = Math.min(CRAFT_GUI.length, matrix.length);
            for (int i = 0; i < n; i++) {
                matrix[i] = copy(snap[CRAFT_GUI[i]]);
            }
            craft.setMatrix(matrix);
        }
    }

    private void writeSlot(Player player, SeeHolder.Kind kind, int gui, ItemStack item) {
        ItemStack copy = copy(item);
        if (kind == SeeHolder.Kind.ENDER) {
            if (gui >= 0 && gui < 27) {
                player.getEnderChest().setItem(gui, copy);
            }
            return;
        }
        PlayerInventory inv = player.getInventory();
        int storage = guiToStorage(gui);
        if (storage >= 0) {
            inv.setItem(storage, copy);
            return;
        }
        switch (gui) {
            case 0 -> inv.setItem(EquipmentSlot.HEAD, copy);
            case 1 -> inv.setItem(EquipmentSlot.CHEST, copy);
            case 2 -> inv.setItem(EquipmentSlot.LEGS, copy);
            case 3 -> inv.setItem(EquipmentSlot.FEET, copy);
            case 4 -> inv.setItem(EquipmentSlot.OFF_HAND, copy);
            case 6, 7, 15, 16 -> {
                CraftingInventory craft = crafting(player);
                if (craft == null) {
                    return;
                }
                ItemStack[] matrix = craft.getMatrix();
                int index = switch (gui) {
                    case 6 -> 0;
                    case 7 -> 1;
                    case 15 -> 2;
                    default -> 3;
                };
                if (index < matrix.length) {
                    matrix[index] = copy;
                    craft.setMatrix(matrix);
                }
            }
            default -> {
            }
        }
    }

    private void paintFillers(Inventory inv) {
        ItemStack pane = filler();
        for (int slot = 0; slot < inv.getSize(); slot++) {
            if (fillerSlot(slot) || slot == 8) {
                inv.setItem(slot, pane);
            }
        }
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(Component.empty());
            meta.setHideTooltip(true);
            meta.getPersistentDataContainer().set(fillKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean filled(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        Byte mark = meta.getPersistentDataContainer().get(fillKey, PersistentDataType.BYTE);
        return mark != null && mark == (byte) 1;
    }

    private static boolean fillerSlot(int slot) {
        return slot == 5 || slot == 17 || (slot >= 9 && slot <= 14);
    }

    private static boolean blocked(SeeHolder see, int slot) {
        if (see.kind == SeeHolder.Kind.ENDER) {
            return slot < 0 || slot >= 27;
        }
        return fillerSlot(slot) || slot == 8;
    }

    private static boolean mapped(SeeHolder see, int slot) {
        if (see.kind == SeeHolder.Kind.ENDER) {
            return slot >= 0 && slot < 27;
        }
        if (slot >= 0 && slot <= 4) {
            return true;
        }
        if (slot == 6 || slot == 7 || slot == 15 || slot == 16) {
            return true;
        }
        return slot >= 18 && slot <= 53;
    }

    private int firstEmptyStorage(SeeHolder see) {
        Inventory inv = see.getInventory();
        if (inv == null) {
            return -1;
        }
        if (see.kind == SeeHolder.Kind.ENDER) {
            for (int slot = 0; slot < 27; slot++) {
                if (empty(inv.getItem(slot))) {
                    return slot;
                }
            }
            return -1;
        }
        for (int slot = 18; slot <= 53; slot++) {
            if (empty(inv.getItem(slot))) {
                return slot;
            }
        }
        return -1;
    }

    private void markDirty(SeeHolder see, int slot) {
        synchronized (see) {
            if (slot >= 0 && slot < see.dirty.length) {
                see.dirty[slot] = true;
            }
        }
    }

    private void markMappedDirty(SeeHolder see) {
        synchronized (see) {
            for (int slot = 0; slot < see.dirty.length; slot++) {
                if (mapped(see, slot)) {
                    see.dirty[slot] = true;
                }
            }
        }
    }

    private void drop(SeeHolder see) {
        see.open = false;
        byViewer.remove(see.viewer, see);
        ConcurrentHashMap<UUID, SeeHolder> viewers = byTarget.get(see.target);
        if (viewers != null) {
            viewers.remove(see.viewer, see);
            if (viewers.isEmpty()) {
                byTarget.remove(see.target, viewers);
            }
        }
    }

    private void begin(UUID target) {
        inFlight.computeIfAbsent(target, id -> new AtomicInteger()).incrementAndGet();
    }

    private void end(UUID target) {
        AtomicInteger n = inFlight.get(target);
        if (n != null) {
            n.decrementAndGet();
        }
    }

    private boolean flying(UUID target) {
        AtomicInteger n = inFlight.get(target);
        return n != null && n.get() > 0;
    }

    private void done(CommandSender sender, String path, String name) {
        if (sender instanceof Player player) {
            plugin.lang().tell(player, path, "player", name);
            return;
        }
        plugin.lang().send(sender, path, "player", name);
    }

    private void run(Player player, Runnable action) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            action.run();
            return;
        }
        player.getScheduler().run(plugin, task -> {
            if (player.isOnline()) {
                action.run();
            }
        }, null);
    }

    private static CraftingInventory crafting(Player player) {
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) {
            return null;
        }
        Inventory top = player.getOpenInventory().getTopInventory();
        return top instanceof CraftingInventory craft ? craft : null;
    }

    private static int storageToGui(int storage) {
        if (storage >= 0 && storage <= 8) {
            return 45 + storage;
        }
        if (storage >= 9 && storage <= 35) {
            return storage + 9;
        }
        return -1;
    }

    private static int guiToStorage(int gui) {
        if (gui >= 45 && gui <= 53) {
            return gui - 45;
        }
        if (gui >= 18 && gui <= 44) {
            return gui - 9;
        }
        return -1;
    }

    private static boolean isFullTake(InventoryAction action) {
        return action == InventoryAction.PICKUP_ALL
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.DROP_ALL_SLOT
                || action == InventoryAction.HOTBAR_MOVE_AND_READD
                || action == InventoryAction.COLLECT_TO_CURSOR;
    }

    private static ItemStack copy(ItemStack item) {
        if (empty(item)) {
            return null;
        }
        return item.clone();
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }
}
