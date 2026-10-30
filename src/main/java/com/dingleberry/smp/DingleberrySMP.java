package com.dingleberry.smp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class DingleberrySMP extends JavaPlugin implements Listener {

    /** Everything we track per player. New players start at 0. */
    static class Data {
        int kills = 0;
        int deaths = 0;
        double money = 0;
        long playSeconds = 0;
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final int LINES = 4;

    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private File dataFile;

    @Override
    public void onEnable() {
        dataFile = new File(getDataFolder(), "data.yml");
        loadData();

        getServer().getPluginManager().registerEvents(this, this);

        // every second: add 1 second of playtime to each ONLINE player + refresh scoreboard
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        // every 5 minutes: save to disk
        getServer().getScheduler().runTaskTimer(this, this::saveData, 6000L, 6000L);

        for (Player p : Bukkit.getOnlinePlayers()) createBoard(p);
    }

    @Override
    public void onDisable() {
        saveData();
    }

    // ---------------------------------------------------------------- data

    private Data get(UUID id) {
        return data.computeIfAbsent(id, k -> new Data());
    }

    private void loadData() {
        data.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        var section = yml.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            try {
                Data d = new Data();
                d.kills = section.getInt(key + ".kills");
                d.deaths = section.getInt(key + ".deaths");
                d.money = section.getDouble(key + ".money");
                d.playSeconds = section.getLong(key + ".playSeconds");
                data.put(UUID.fromString(key), d);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Data> e : data.entrySet()) {
            String k = "players." + e.getKey();
            yml.set(k + ".kills", e.getValue().kills);
            yml.set(k + ".deaths", e.getValue().deaths);
            yml.set(k + ".money", e.getValue().money);
            yml.set(k + ".playSeconds", e.getValue().playSeconds);
        }
        try {
            getDataFolder().mkdirs();
            yml.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------- events

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        get(e.getPlayer().getUniqueId()); // makes sure they exist (all 0)
        createBoard(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        boards.remove(e.getPlayer().getUniqueId());
        saveData();
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getEntity();
        get(victim.getUniqueId()).deaths++;
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            get(killer.getUniqueId()).kills++;
        }
    }

    // ---------------------------------------------------------- scoreboard

    private void createBoard(Player p) {
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("dsmp", Criteria.DUMMY,
                LEGACY.deserialize("&b&lDingleberrySMP"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (int i = 0; i < LINES; i++) {
            Team team = board.registerNewTeam("line" + i);
            String entry = "\u00a7" + Integer.toHexString(i); // invisible unique entry
            team.addEntry(entry);
            obj.getScore(entry).setScore(LINES - i);
        }

        boards.put(p.getUniqueId(), board);
        p.setScoreboard(board);
        updateBoard(p);
    }

    private void updateBoard(Player p) {
        Scoreboard board = boards.get(p.getUniqueId());
        if (board == null) return;
        Data d = get(p.getUniqueId());

        String[] text = {
                "&c\u2694 Kills &f" + d.kills,
                "&6\u2620 Deaths &f" + d.deaths,
                "&a$ Money &f" + formatMoney(d.money),
                "&e\u231A Playtime &f" + formatTime(d.playSeconds)
        };

        for (int i = 0; i < LINES; i++) {
            Team team = board.getTeam("line" + i);
            if (team != null) team.prefix(LEGACY.deserialize(text[i]));
        }
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            get(p.getUniqueId()).playSeconds++;
            updateBoard(p);
        }
    }

    // ------------------------------------------------------------- format

    static String formatMoney(double m) {
        if (m >= 1_000_000_000) return String.format(Locale.US, "%.2fb", m / 1_000_000_000);
        if (m >= 1_000_000) return String.format(Locale.US, "%.2fm", m / 1_000_000);
        if (m >= 1_000) return String.format(Locale.US, "%.2fk", m / 1_000);
        return String.valueOf((long) m);
    }

    static String formatTime(long s) {
        long days = s / 86400;
        long hours = (s % 86400) / 3600;
        long mins = (s % 3600) / 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }

    // ------------------------------------------------------------ commands

    /** Other code (/sell, /shop later) can use these. */
    public double getMoney(UUID id) { return get(id).money; }
    public void addMoney(UUID id, double amount) { get(id).money += amount; }
    public boolean takeMoney(UUID id, double amount) {
        Data d = get(id);
        if (d.money < amount) return false;
        d.money -= amount;
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("balance")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            p.sendMessage(LEGACY.deserialize("&aYou have &f$" + formatMoney(get(p.getUniqueId()).money)));
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("eco")) {
            if (args.length != 3 || !args[0].equalsIgnoreCase("give")) {
                sender.sendMessage("Usage: /eco give <player> <amount>");
                return true;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            double amount;
            try {
                amount = Double.parseDouble(args[2]);
            } catch (NumberFormatException ex) {
                sender.sendMessage("That's not a number.");
                return true;
            }
            addMoney(target.getUniqueId(), amount);
            sender.sendMessage("Gave $" + formatMoney(amount) + " to " + args[1]);
            return true;
        }
        return false;
    }
}
