package dev.dupexv.core.chat;

import dev.dupexv.core.DupeXvCore;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class ChatCommands implements CommandExecutor, TabCompleter {

    private final DupeXvCore plugin;
    private final ChatService chat;

    public ChatCommands(DupeXvCore plugin, ChatService chat) {
        this.plugin = plugin;
        this.chat = chat;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.lang().send(sender, "player-only");
            return true;
        }
        String name = command.getName();
        if (name.equals("msg") || name.equals("r")) {
            if (!player.hasPermission(DupeXvCore.MSG_PERMISSION)) {
                plugin.lang().send(player, "no-permission");
                return true;
            }
        } else if (!player.hasPermission(DupeXvCore.IGNORE_PERMISSION)) {
            plugin.lang().send(player, "no-permission");
            return true;
        }
        switch (name) {
            case "msg" -> {
                if (args.length < 2) {
                    plugin.lang().send(player, "msg.usage");
                    return true;
                }
                chat.msg(player, args[0], String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "r" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "msg.reply-usage");
                    return true;
                }
                chat.reply(player, String.join(" ", args));
            }
            case "ignore" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "ignore.usage");
                    return true;
                }
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "add" -> {
                        if (args.length < 2) {
                            plugin.lang().send(player, "ignore.add-usage");
                            return true;
                        }
                        chat.ignoreAdd(player, args[1]);
                    }
                    case "remove" -> {
                        if (args.length < 2) {
                            plugin.lang().send(player, "ignore.remove-usage");
                            return true;
                        }
                        chat.ignoreRemove(player, args[1]);
                    }
                    case "list" -> chat.ignoreList(player);
                    default -> plugin.lang().send(player, "ignore.usage");
                }
            }
            case "unignore" -> {
                if (args.length < 1) {
                    plugin.lang().send(player, "ignore.remove-usage");
                    return true;
                }
                chat.ignoreRemove(player, args[0]);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        String name = command.getName();
        if (name.equals("msg")) {
            if (args.length == 1) {
                return chat.matchOnline(player, args[0]);
            }
            return List.of();
        }
        if (name.equals("ignore")) {
            if (args.length == 1) {
                String prefix = args[0].toLowerCase(Locale.ROOT);
                List<String> subs = new ArrayList<>();
                for (String sub : List.of("add", "remove", "list")) {
                    if (sub.startsWith(prefix)) {
                        subs.add(sub);
                    }
                }
                return subs;
            }
            if (args.length == 2) {
                if (args[0].equalsIgnoreCase("add")) {
                    return chat.matchOnline(player, args[1]);
                }
                if (args[0].equalsIgnoreCase("remove")) {
                    return chat.ignoredNames(player);
                }
            }
            return List.of();
        }
        if (name.equals("unignore")) {
            if (args.length == 1) {
                return chat.ignoredNames(player);
            }
        }
        return List.of();
    }
}
