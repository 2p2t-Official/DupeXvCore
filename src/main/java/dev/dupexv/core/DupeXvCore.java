package dev.dupexv.core;

import dev.dupexv.core.spawn.SpawnCommand;
import dev.dupexv.core.spawn.SpawnService;
import dev.dupexv.core.tpa.TpaCommands;
import dev.dupexv.core.tpa.TpaListener;
import dev.dupexv.core.tpa.TpaService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DupeXvCore extends JavaPlugin {

    public static final String TPA_PERMISSION = "dupexvcore.tpa";
    public static final String SPAWN_PERMISSION = "dupexvcore.spawn";

    private Lang lang;
    private TpaService tpa;
    private SpawnService spawn;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lang = new Lang(this);
        lang.reload();
        tpa = new TpaService(this);
        tpa.reload();
        spawn = new SpawnService(this);
        spawn.reload();
        getServer().getPluginManager().registerEvents(new TpaListener(tpa), this);
        TpaCommands tpaCommands = new TpaCommands(this, tpa);
        String[] tpaNames = {"tpa", "tpa-here", "tpa-cancel", "tpa-accept", "tpa-deny"};
        for (String name : tpaNames) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(tpaCommands);
                command.setTabCompleter(tpaCommands);
            }
        }
        PluginCommand spawnCommand = getCommand("spawn");
        if (spawnCommand != null) {
            spawnCommand.setExecutor(new SpawnCommand(this, spawn));
        }
    }

    @Override
    public void onDisable() {
        if (tpa != null) {
            tpa.shutdown();
        }
        if (spawn != null) {
            spawn.shutdown();
        }
    }

    public Lang lang() {
        return lang;
    }
}
