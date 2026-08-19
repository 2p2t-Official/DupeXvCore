package dev.dupexv.core;

import dev.dupexv.core.delay.Delays;
import dev.dupexv.core.home.HomeCommands;
import dev.dupexv.core.home.HomeListener;
import dev.dupexv.core.home.HomeService;
import dev.dupexv.core.see.SeeCommands;
import dev.dupexv.core.see.SeeService;
import dev.dupexv.core.spawn.SpawnCommand;
import dev.dupexv.core.spawn.SpawnService;
import dev.dupexv.core.store.Database;
import dev.dupexv.core.tab.TabService;
import dev.dupexv.core.tpa.TpaCommands;
import dev.dupexv.core.tpa.TpaService;
import dev.dupexv.core.chat.ChatReportsService;
import dev.dupexv.core.regiondebug.RegionDebugPublisher;
import dev.dupexv.core.website.LinkCommand;
import dev.dupexv.core.website.WebsiteLinkService;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class DupeXvCore extends JavaPlugin {

    public static final String TPA_PERMISSION = "dupexvcore.tpa";
    public static final String SPAWN_PERMISSION = "dupexvcore.spawn";
    public static final String HOME_PERMISSION = "dupexvcore.home";
    public static final String INVSEE_PERMISSION = "dupexvcore.invsee";
    public static final String ENDERSEE_PERMISSION = "dupexvcore.endersee";
    public static final String INVCLEAR_PERMISSION = "dupexvcore.invclear";
    public static final String ENDERCLEAR_PERMISSION = "dupexvcore.enderclear";
    public static final String LINK_PERMISSION = "dupexvcore.link";

    private Lang lang;
    private Delays delays;
    private Database database;
    private TpaService tpa;
    private SpawnService spawn;
    private HomeService homes;
    private TabService tab;
    private SeeService see;
    private WebsiteLinkService websiteLink;
    private RegionDebugPublisher regions;
    private ChatReportsService chatReports;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        fillConfig();
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
        tab = new TabService(this);
        getServer().getPluginManager().registerEvents(new WarmupListener(this), this);
        getServer().getPluginManager().registerEvents(new HomeListener(this, homes), this);
        getServer().getPluginManager().registerEvents(tab, this);
        tab.reload();
        see = new SeeService(this);
        getServer().getPluginManager().registerEvents(see, this);
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
        SeeCommands seeCommands = new SeeCommands(this, see);
        String[] seeNames = {"invsee", "endersee", "invclear", "enderclear"};
        for (String name : seeNames) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(seeCommands);
                command.setTabCompleter(seeCommands);
            }
        }
        websiteLink = new WebsiteLinkService(this);
        PluginCommand linkCommand = getCommand("link");
        if (linkCommand != null) {
            linkCommand.setExecutor(new LinkCommand(this, websiteLink));
        }
        regions = new RegionDebugPublisher(this);
        regions.start();
        chatReports = new ChatReportsService(this);
        getServer().getPluginManager().registerEvents(chatReports, this);
        chatReports.start();
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
        if (tab != null) {
            tab.shutdown();
        }
        if (see != null) {
            see.shutdown();
        }
        if (chatReports != null) {
            chatReports.shutdown();
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

    public TabService tab() {
        return tab;
    }

    private void fillConfig() {
        InputStream in = getResource("config.yml");
        if (in == null) {
            return;
        }
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration dest = new YamlConfiguration();
        dest.options().parseComments(true);
        dest.options().width(10000);
        try {
            if (file.exists()) {
                dest.load(file);
            }
        } catch (Exception e) {
            getLogger().warning(String.valueOf(e.getMessage()));
            return;
        }
        FileConfiguration src = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        boolean changed = false;
        for (String key : src.getKeys(true)) {
            if (src.isConfigurationSection(key) || dest.isSet(key)) {
                continue;
            }
            dest.set(key, src.get(key));
            changed = true;
        }
        if (!changed) {
            return;
        }
        try {
            dest.save(file);
        } catch (Exception e) {
            getLogger().warning(String.valueOf(e.getMessage()));
        }
        reloadConfig();
    }
}
