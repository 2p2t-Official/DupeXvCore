package dev.dupexv.core.home;

import dev.dupexv.core.DupeXvCore;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class HomeListener implements Listener {

    private final DupeXvCore plugin;
    private final HomeService homes;

    public HomeListener(DupeXvCore plugin, HomeService homes) {
        this.plugin = plugin;
        this.homes = homes;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!homes.isRenaming(player)) {
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        player.getScheduler().run(plugin, task -> homes.handleRename(player, message), null);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof HomeGuis.Main
                || event.getView().getTopInventory().getHolder() instanceof HomeGuis.Manage
                || event.getView().getTopInventory().getHolder() instanceof HomeGuis.Confirm) {
            event.setCancelled(true);
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (event.getView().getTopInventory().getHolder() instanceof HomeGuis.Main main) {
            homes.clickMain(player, main, event.getSlot(), event.isRightClick());
        } else if (event.getView().getTopInventory().getHolder() instanceof HomeGuis.Manage manage) {
            if (event.getSlot() == 22) {
                homes.openMain(player);
            } else if (event.getSlot() == 15) {
                homes.openConfirm(player, manage.homeName);
            } else if (event.getSlot() == 11) {
                homes.startRename(player, manage.homeName);
            }
        } else if (event.getView().getTopInventory().getHolder() instanceof HomeGuis.Confirm confirm) {
            if (event.getSlot() == 15) {
                homes.openManage(player, confirm.homeName);
            } else if (event.getSlot() == 11) {
                homes.confirmDelete(player, confirm.homeName);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof HomeGuis.Main
                || event.getView().getTopInventory().getHolder() instanceof HomeGuis.Manage
                || event.getView().getTopInventory().getHolder() instanceof HomeGuis.Confirm) {
            event.setCancelled(true);
        }
    }
}
