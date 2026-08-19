package dev.dupexv.core;

import dev.dupexv.core.delay.Delays;
import dev.dupexv.core.home.HomeCommands;
import dev.dupexv.core.home.HomeListener;
import dev.dupexv.core.home.HomeService;
import dev.dupexv.core.spawn.SpawnCommand;
import dev.dupexv.core.spawn.SpawnService;
import dev.dupexv.core.store.Database;
import dev.dupexv.core.tpa.TpaCommands;
import dev.dupexv.core.tpa.TpaService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DupeXvCore extends JavaPlugin {

    public static final String TPA_PERMISSION = "dupexvcore.tpa";
    public static final String SPAWN_PERMISSION = "dupexvcore.spawn";
    public static final String HOME_PERMISSION = "dupexvcore.home";

    private Lang lang;
    private Delays delays;
    private Database database;
    private TpaService tpa;
    private SpawnService spawn;
    private HomeService homes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        lang = new Lang(this);
        lang.reload();
        delays = new Delays();
        database = new Database(this);
        database.open();
        tpa = new TpaService(this);
        tpa.reload();
        spawn = new SpawnService(this);
        spawn.reload();
        homes = new HomeService(this);
        homes.reload();
        getServer().getPluginManager().registerEvents(new WarmupListener(this), this);
        getServer().getPluginManager().registerEvents(new HomeListener(this, homes), this);
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
        HomeCommands homeCommands = new HomeCommands(this, homes);
        String[] homeNames = {"home", "sethome", "delhome"};
        for (String name : homeNames) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(homeCommands);
                command.setTabCompleter(homeCommands);
            }
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
        if (homes != null) {
            homes.shutdown();
        }
        if (database != null) {
            database.close();
        }
    }

    public Lang lang() {
        return lang;
    }

    public Delays delays() {
        return delays;
    }

    public Database db() {
        return database;
    }

    public TpaService tpa() {
        return tpa;
    }

    public SpawnService spawn() {
        return spawn;
    }

    public HomeService homes() {
        return homes;
    }
}
