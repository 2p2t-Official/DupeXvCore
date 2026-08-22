package dev.dupexv.core.home;

import dev.dupexv.core.DupeXvCore;
import dev.dupexv.core.NcpBridge;
import dev.dupexv.core.store.HomeRecord;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HomeService {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final DupeXvCore plugin;
    private final ConcurrentHashMap<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> renaming = new ConcurrentHashMap<>();

    private volatile int warmupSeconds = 10;
    private volatile int cooldownSeconds = 60;

    public HomeService(DupeXvCore plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        warmupSeconds = Math.max(0, plugin.getConfig().getInt("home.warmup", 10));
        cooldownSeconds = Math.max(0, plugin.getConfig().getInt("home.cooldown", 60));
    }

    public void shutdown() {
        for (Warmup warmup : warmups.values()) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
        }
        warmups.clear();
        renaming.clear();
    }

    public void openMain(Player player) {
        int granted = plugin.delays().maxHomes(player);
        int max = Math.min(10, Math.max(2, granted));
        int[] slots = HomeGuis.homeSlots(max);
        int close = HomeGuis.closeSlot(max);
        HomeGuis.Main holder = new HomeGuis.Main(max, slots, close);
        Inventory inv = Bukkit.createInventory(holder, 54, Component.text(" "));
        holder.bind(inv);
        fillBlack(inv, 54);
        for (int slot : HomeGuis.LIME) {
            inv.setItem(slot, pane(Material.LIME_STAINED_GLASS_PANE));
        }
        List<HomeRecord> homes = plugin.db().homes(player.getUniqueId());
        for (int i = 0; i < slots.length; i++) {
            if (i < homes.size()) {
                inv.setItem(slots[i], filledBed(homes.get(i).name));
            } else {
                inv.setItem(slots[i], emptyBed(i + 1));
            }
        }
        inv.setItem(close, named(Material.BARRIER, plugin.lang().component("home.close"), List.of(plugin.lang().component("home.close-lore"))));
        player.openInventory(inv);
        sound(player, "ui.button.click", 0.05f, 1f);
    }

    public void openManage(Player player, String homeName) {
        HomeGuis.Manage holder = new HomeGuis.Manage(homeName);
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.lang().component("home.manage-title", "name", homeName));
        holder.bind(inv);
        fillBlack(inv, 27);
        inv.setItem(11, named(Material.NAME_TAG, plugin.lang().component("home.rename"), List.of(plugin.lang().component("home.rename-lore"))));
        inv.setItem(15, named(Material.LAVA_BUCKET, plugin.lang().component("home.delete"), List.of(plugin.lang().component("home.delete-lore"))));
        inv.setItem(22, named(Material.BARRIER, plugin.lang().component("home.back"), List.of(plugin.lang().component("home.back-lore"))));
        player.openInventory(inv);
        sound(player, "ui.button.click", 0.05f, 1f);
    }

    public void openConfirm(Player player, String homeName) {
        HomeGuis.Confirm holder = new HomeGuis.Confirm(homeName);
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.lang().component("home.confirm-title"));
        holder.bind(inv);
        fillBlack(inv, 27);
        inv.setItem(11, named(Material.LIME_WOOL, plugin.lang().component("home.confirm"), List.of(plugin.lang().component("home.confirm-lore"))));
        inv.setItem(15, named(Material.RED_WOOL, plugin.lang().component("home.cancel"), List.of(plugin.lang().component("home.cancel-lore"))));
        player.openInventory(inv);
        sound(player, "ui.button.click", 0.05f, 1f);
    }

    public void clickMain(Player player, HomeGuis.Main gui, int slot, boolean right) {
        if (slot == gui.closeSlot) {
            player.closeInventory();
            sound(player, "entity.item_frame.remove_item", 0.5f, 1f);
            return;
        }
        int index = -1;
        for (int i = 0; i < gui.homeSlots.length; i++) {
            if (gui.homeSlots[i] == slot) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        List<HomeRecord> homes = plugin.db().homes(player.getUniqueId());
        if (index < homes.size()) {
            HomeRecord home = homes.get(index);
            if (right) {
                openManage(player, home.name);
                return;
            }
            player.closeInventory();
            beginTeleport(player, home);
            return;
        }
        if (right) {
            return;
        }
        if (plugin.db().homeCount(player.getUniqueId()) >= gui.max) {
            return;
        }
        String name = "Home " + (index + 1);
        if (plugin.db().home(player.getUniqueId(), name) != null) {
            int n = 1;
            while (plugin.db().home(player.getUniqueId(), "Home " + n) != null) {
                n++;
            }
            name = "Home " + n;
        }
        plugin.db().saveHome(player, name, player.getLocation(), System.currentTimeMillis());
        plugin.lang().actionBar(player, "home.set", "name", name);
        sound(player, "block.anvil.use", 0.3f, 1f);
        openMain(player);
    }

    public void setHome(Player player, String name) {
        int max = Math.min(10, Math.max(2, plugin.delays().maxHomes(player)));
        HomeRecord existing = plugin.db().home(player.getUniqueId(), name);
        if (existing == null && plugin.db().homeCount(player.getUniqueId()) >= max) {
            plugin.lang().actionBar(player, "home.max");
            return;
        }
        plugin.db().saveHome(player, name, player.getLocation(), System.currentTimeMillis());
        plugin.lang().actionBar(player, "home.set", "name", existing != null ? existing.name : name);
        sound(player, "block.anvil.use", 0.3f, 1f);
    }

    public void delHome(Player player, String name) {
        HomeRecord existing = plugin.db().home(player.getUniqueId(), name);
        if (existing == null) {
            plugin.lang().actionBar(player, "home.none");
            return;
        }
        plugin.db().deleteHome(player.getUniqueId(), existing.name);
        plugin.lang().actionBar(player, "home.deleted");
        sound(player, "entity.item.break", 0.5f, 1f);
    }

    public void confirmDelete(Player player, String name) {
        plugin.db().deleteHome(player.getUniqueId(), name);
        plugin.lang().actionBar(player, "home.deleted");
        sound(player, "entity.item.break", 0.5f, 1f);
        player.closeInventory();
    }

    public void startRename(Player player, String homeName) {
        renaming.put(player.getUniqueId(), homeName);
        player.closeInventory();
        plugin.lang().send(player, "home.rename-prompt");
        plugin.lang().send(player, "home.rename-cancel-hint");
        sound(player, "block.note_block.pling", 0.5f, 1.2f);
    }

    public boolean isRenaming(Player player) {
        return renaming.containsKey(player.getUniqueId());
    }

    public void handleRename(Player player, String message) {
        String current = renaming.remove(player.getUniqueId());
        if (current == null) {
            return;
        }
        String text = message.trim();
        if (text.equalsIgnoreCase("cancel")) {
            plugin.lang().send(player, "home.rename-cancelled");
            sound(player, "ui.button.click", 0.5f, 1f);
            openManage(player, current);
            return;
        }
        if (text.isEmpty()) {
            renaming.put(player.getUniqueId(), current);
            plugin.lang().send(player, "home.rename-empty");
            return;
        }
        if (text.length() > 32) {
            renaming.put(player.getUniqueId(), current);
            plugin.lang().send(player, "home.rename-long");
            return;
        }
        HomeRecord clash = plugin.db().home(player.getUniqueId(), text);
        if (clash != null && !clash.name.equalsIgnoreCase(current)) {
            renaming.put(player.getUniqueId(), current);
            plugin.lang().send(player, "home.rename-taken");
            return;
        }
        plugin.db().renameHome(player.getUniqueId(), current, text);
        plugin.lang().send(player, "home.renamed", "name", text);
        sound(player, "block.anvil.use", 0.5f, 1f);
        openManage(player, text);
    }

    public void beginTeleport(Player player, String name) {
        HomeRecord home = plugin.db().home(player.getUniqueId(), name);
        if (home == null) {
            plugin.lang().actionBar(player, "home.none");
            return;
        }
        beginTeleport(player, home);
    }

    public void beginTeleport(Player player, HomeRecord home) {
        UUID id = player.getUniqueId();
        if (warmups.containsKey(id)) {
            plugin.lang().actionBar(player, "home.wait");
            return;
        }
        int cooldown = plugin.delays().cooldown(player, "home", cooldownSeconds);
        long left = cooldownLeft(id);
        if (left > 0) {
            plugin.lang().actionBar(player, "home.cooldown", "seconds", left);
            sound(player, "block.note_block.didgeridoo", 0.5f, 1f);
            return;
        }
        Location dest = home.location();
        if (dest == null || dest.getWorld() == null) {
            plugin.lang().actionBar(player, "home.world");
            return;
        }
        int warmup = plugin.delays().warmup(player, "home", warmupSeconds);
        sound(player, "block.note_block.chime", 0.5f, 1f);
        if (warmup <= 0) {
            finish(player, home, cooldown);
            return;
        }
        Warmup w = new Warmup(home, player.getLocation().clone(), warmup, cooldown);
        warmups.put(id, w);
        w.task = player.getScheduler().runAtFixedRate(plugin, task -> tick(player, w), () -> warmups.remove(id, w), 1L, 20L);
    }

    public List<String> matchHomes(Player player, String prefix) {
        String start = prefix.toLowerCase(Locale.ROOT);
        List<String> names = new java.util.ArrayList<>();
        for (HomeRecord home : plugin.db().homes(player.getUniqueId())) {
            if (home.name.toLowerCase(Locale.ROOT).startsWith(start)) {
                names.add(home.name);
            }
        }
        return names;
    }

    public boolean cancelWarmup(Player player, String path) {
        Warmup w = warmups.remove(player.getUniqueId());
        if (w == null) {
            return false;
        }
        if (w.task != null) {
            w.task.cancel();
        }
        if (path != null) {
            plugin.lang().actionBar(player, path);
        }
        return true;
    }

    public void onLeave(Player player) {
        cancelWarmup(player, null);
        renaming.remove(player.getUniqueId());
        plugin.db().touch(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }

    public void onJoin(Player player) {
        plugin.db().touch(player.getUniqueId(), player.getName(), System.currentTimeMillis());
    }

    private void tick(Player player, Warmup w) {
        if (!player.isOnline()) {
            cancelWarmup(player, null);
            return;
        }
        if (player.getLocation().distanceSquared(w.start) > 0.01) {
            cancelWarmup(player, "home.moved");
            return;
        }
        if (w.left <= 0) {
            warmups.remove(player.getUniqueId(), w);
            if (w.task != null) {
                w.task.cancel();
            }
            finish(player, w.home, w.cooldown);
            return;
        }
        plugin.lang().actionBar(player, "home.countdown", "seconds", w.left);
        w.left--;
    }

    private void finish(Player player, HomeRecord home, int cooldown) {
        Location dest = home.location();
        if (dest == null || dest.getWorld() == null) {
            plugin.lang().actionBar(player, "home.world");
            return;
        }
        NcpBridge.exempt(player);
        player.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                if (cooldown > 0) {
                    cooldowns.put(player.getUniqueId(), System.currentTimeMillis() + cooldown * 1000L);
                }
                player.getScheduler().run(plugin, task -> {
                    plugin.lang().actionBar(player, "home.done");
                    sound(player, "entity.enderman.teleport", 0.5f, 1f);
                }, null);
            } else {
                plugin.lang().actionBar(player, "home.failed");
            }
            player.getScheduler().runDelayed(plugin, task -> NcpBridge.unexempt(player), null, 40L);
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

    private void fillBlack(Inventory inv, int size) {
        ItemStack pane = pane(Material.BLACK_STAINED_GLASS_PANE);
        for (int i = 0; i < size; i++) {
            inv.setItem(i, pane);
        }
    }

    private ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.displayName(Component.empty()));
        return item;
    }

    private ItemStack emptyBed(int n) {
        return named(
                Material.GRAY_BED,
                plugin.lang().component("home.empty", "n", n),
                List.of(plugin.lang().component("home.empty-lore"))
        );
    }

    private ItemStack filledBed(String name) {
        Component title = MINI.deserialize(plugin.lang().raw("home.bed-name"), Placeholder.unparsed("name", name));
        return named(
                Material.LIME_BED,
                title,
                List.of(plugin.lang().component("home.teleport-lore"), plugin.lang().component("home.manage-lore"))
        );
    }

    private ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(plain(name));
            meta.lore(lore.stream().map(this::plain).toList());
        });
        return item;
    }

    private Component plain(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private void sound(Player player, String key, float volume, float pitch) {
        player.playSound(player, key, volume, pitch);
    }

    private static final class Warmup {
        private final HomeRecord home;
        private final Location start;
        private int left;
        private final int cooldown;
        private volatile ScheduledTask task;

        private Warmup(HomeRecord home, Location start, int left, int cooldown) {
            this.home = home;
            this.start = start;
            this.left = left;
            this.cooldown = cooldown;
        }
    }
}
