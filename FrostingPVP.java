package com.frostingpvp;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public final class FrostingPVP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<UUID, UUID> duelRequests = new HashMap<>();
    private final Map<UUID, String> selectedArena = new HashMap<>();
    private final Map<UUID, String> playerMode = new HashMap<>();
    private final Set<UUID> inMatch = new HashSet<>();
    private final Map<UUID, String> editingKit = new HashMap<>();

    private static final String MENU_MAIN = ChatColor.DARK_GREEN + "❄ FrostingPVP » Play";
    private static final String MENU_ARENAS = ChatColor.DARK_GREEN + "❄ Choose an Arena";
    private static final String MENU_RANKS = ChatColor.GOLD + "✦ FrostingPVP Ranks";
    private static final String MENU_KIT = ChatColor.RED + "Kit Editor";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        for (String command : List.of("pvp", "duel", "accept", "admin")) {
            Objects.requireNonNull(getCommand(command)).setExecutor(this);
            Objects.requireNonNull(getCommand(command)).setTabCompleter(this);
        }
        getLogger().info("FrostingPVP enabled. Let it snow!");
    }

    @Override
    public void onDisable() {
        getLogger().info("FrostingPVP disabled.");
    }

    private String prefix() {
        return ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages.prefix", "&b&lFrostingPVP &8» "));
    }

    private void msg(CommandSender sender, String message) {
        sender.sendMessage(prefix() + ChatColor.translateAlternateColorCodes('&', message));
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        List<String> lines = new ArrayList<>();
        for (String line : lore) lines.add(ChatColor.translateAlternateColorCodes('&', line));
        meta.setLore(lines);
        stack.setItemMeta(meta);
        return stack;
    }

    private void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_MAIN);
        inv.setItem(10, item(Material.RED_BED, "&c&lBedWars", "&7Team up and defend your bed.", "&eClick to choose an arena"));
        inv.setItem(11, item(Material.IRON_SWORD, "&a&lFFA", "&7Fight everyone. Last player standing!", "&eClick to choose an arena"));
        inv.setItem(12, item(Material.ARMOR_STAND, "&b&lPractice", "&7Warm up against a practice bot.", "&eClick to choose an arena"));
        inv.setItem(13, item(Material.END_CRYSTAL, "&d&lCrystal Classic", "&7Crystal PvP action.", "&eClick to choose an arena"));
        inv.setItem(14, item(Material.DIAMOND_SWORD, "&6&lClassic", "&7Classic kit-based duels.", "&eClick to choose an arena"));
        inv.setItem(15, item(Material.NETHER_STAR, "&e&lRanks", "&7View the server rank ladder.", "&eClick to view ranks"));
        inv.setItem(22, item(Material.PLAYER_HEAD, "&f&lRequest a Fight", "&7Challenge another player.", "&eUse &f/duel <player>"));
        player.openInventory(inv);
    }

    private void openArenaMenu(Player player, String mode) {
        Inventory inv = Bukkit.createInventory(null, 54, MENU_ARENAS);
        inv.setItem(49, item(Material.NETHER_STAR, "&e&lRandom Arena", "&7Let FrostingPVP pick for you.", "&aClick to queue"));
        inv.setItem(45, item(Material.ARROW, "&cBack", "&7Return to game selection"));
        ConfigurationSection arenas = getConfig().getConfigurationSection("arenas");
        int slot = 0;
        if (arenas != null) {
            for (String name : arenas.getKeys(false)) {
                String arenaMode = arenas.getString(name + ".mode", "FFA");
                if (!arenaMode.equalsIgnoreCase(mode) || slot >= 45) continue;
                inv.setItem(slot++, item(Material.SNOW_BLOCK, "&b&l" + name,
                        "&7Mode: &f" + mode, "&7Christmas arena", "&eClick to play here"));
            }
        }
        if (slot == 0) inv.setItem(22, item(Material.BARRIER, "&cNo arenas configured",
                "&7An admin must create an arena first.", "&7Ask an operator to use /admin arena create"));
        player.openInventory(inv);
    }

    private void openRanks(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_RANKS);
        inv.setItem(10, item(Material.IRON_INGOT, "&7&lSnow", "&7Starter rank"));
        inv.setItem(12, item(Material.GOLD_INGOT, "&6&lGingerbread", "&7Earned through play"));
        inv.setItem(14, item(Material.DIAMOND, "&b&lFrost", "&7Premium rank"));
        inv.setItem(16, item(Material.NETHER_STAR, "&d&lMythic", "&7Top supporter rank"));
        inv.setItem(22, item(Material.ARROW, "&cBack"));
        player.openInventory(inv);
    }

    private void startMode(Player player, String mode, String arena) {
        if (getConfig().getBoolean("maintenance", false) && !player.hasPermission("frostingpvp.admin")) {
            msg(player, "&cThe server is in maintenance mode. Please try again later.");
            return;
        }
        ConfigurationSection arenas = getConfig().getConfigurationSection("arenas");
        if (arenas == null || !arenas.contains(arena)) {
            msg(player, "&cThat arena is not configured yet.");
            return;
        }
        String configuredMode = arenas.getString(arena + ".mode", "FFA");
        if (!configuredMode.equalsIgnoreCase(mode)) {
            msg(player, "&cThat arena is not for this game mode.");
            return;
        }
        Location location = readLocation(arenas.getConfigurationSection(arena + ".spawn"));
        if (location == null || location.getWorld() == null) {
            msg(player, "&cArena spawn is invalid. Ask an admin to recreate it.");
            return;
        }
        player.closeInventory();
        playerMode.put(player.getUniqueId(), mode);
        selectedArena.put(player.getUniqueId(), arena);
        inMatch.add(player.getUniqueId());
        player.teleport(location);
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        applyKit(player, mode);
        msg(player, "&aJoining &f" + mode + " &ain arena &b" + arena + "&a!");
        if (mode.equals("PRACTICE")) spawnPracticeBot(player);
    }

    private void spawnPracticeBot(Player player) {
        // Safe starter bot: a named, stationary target dummy. Replace with a combat-AI
        // implementation if you later want pathfinding and sparring behavior.
        player.getWorld().spawn(player.getLocation().add(2, 0, 0), org.bukkit.entity.ArmorStand.class, stand -> {
            stand.setCustomName(ChatColor.AQUA + "Practice Bot");
            stand.setCustomNameVisible(true);
            stand.setArms(true);
            stand.setBasePlate(false);
            stand.setGravity(false);
        });
        msg(player, "&bA practice target has spawned nearby.");
    }

    private void applyKit(Player player, String mode) {
        ConfigurationSection kit = getConfig().getConfigurationSection("kits." + mode.toUpperCase(Locale.ROOT));
        if (kit != null && kit.getItemStack("contents") != null) {
            player.getInventory().setContents(((ItemStack[]) kit.get("contents")));
            return;
        }
        switch (mode.toUpperCase(Locale.ROOT)) {
            case "FFA" -> {
                player.getInventory().addItem(new ItemStack(Material.IRON_SWORD));
                player.getInventory().addItem(new ItemStack(Material.BOW));
                player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
                player.getInventory().setArmorContents(new ItemStack[]{
                        new ItemStack(Material.IRON_BOOTS), new ItemStack(Material.IRON_LEGGINGS),
                        new ItemStack(Material.IRON_CHESTPLATE), new ItemStack(Material.IRON_HELMET)});
            }
            case "PRACTICE", "CLASSIC" -> {
                player.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
                player.getInventory().addItem(new ItemStack(Material.COOKED_BEEF, 16));
                player.getInventory().setArmorContents(new ItemStack[]{
                        new ItemStack(Material.IRON_BOOTS), new ItemStack(Material.IRON_LEGGINGS),
                        new ItemStack(Material.IRON_CHESTPLATE), new ItemStack(Material.IRON_HELMET)});
            }
            case "CRYSTAL" -> {
                player.getInventory().addItem(new ItemStack(Material.END_CRYSTAL, 16));
                player.getInventory().addItem(new ItemStack(Material.OBSIDIAN, 64));
                player.getInventory().addItem(new ItemStack(Material.DIAMOND_SWORD));
                player.getInventory().addItem(new ItemStack(Material.TOTEM_OF_UNDYING, 2));
            }
            case "BEDWARS" -> {
                player.getInventory().addItem(new ItemStack(Material.WOODEN_SWORD));
                player.getInventory().addItem(new ItemStack(Material.WHITE_WOOL, 32));
                player.getInventory().addItem(new ItemStack(Material.BREAD, 8));
            }
        }
    }

    private Location readLocation(ConfigurationSection section) {
        if (section == null) return null;
        World world = Bukkit.getWorld(section.getString("world", ""));
        if (world == null) return null;
        return new Location(world, section.getDouble("x"), section.getDouble("y"),
                section.getDouble("z"), (float)section.getDouble("yaw"), (float)section.getDouble("pitch"));
    }

    private void saveLocation(ConfigurationSection section, Location loc) {
        section.set("world", loc.getWorld().getName());
        section.set("x", loc.getX()); section.set("y", loc.getY()); section.set("z", loc.getZ());
        section.set("yaw", loc.getYaw()); section.set("pitch", loc.getPitch());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (getConfig().getBoolean("maintenance", false) && !player.hasPermission("frostingpvp.admin")) {
            player.kickPlayer(ChatColor.RED + "FrostingPVP is in maintenance. Please try again later.");
            return;
        }
        Location lobby = readLocation(getConfig().getConfigurationSection("lobby"));
        if (lobby != null) player.teleport(lobby);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline()) openMain(player);
        }, 10L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playerMode.remove(id); selectedArena.remove(id); inMatch.remove(id);
        duelRequests.remove(id);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        if (!title.equals(MENU_MAIN) && !title.equals(MENU_ARENAS) && !title.equals(MENU_RANKS)
                && !title.equals(MENU_KIT)) return;
        event.setCancelled(true);
        if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;
        String name = ChatColor.stripColor(event.getCurrentItem().getItemMeta().getDisplayName());
        if (title.equals(MENU_MAIN)) {
            switch (name) {
                case "BedWars" -> openArenaMenu(player, "BEDWARS");
                case "FFA" -> openArenaMenu(player, "FFA");
                case "Practice" -> openArenaMenu(player, "PRACTICE");
                case "Crystal Classic" -> openArenaMenu(player, "CRYSTAL");
                case "Classic" -> openArenaMenu(player, "CLASSIC");
                case "Ranks" -> openRanks(player);
                case "Request a Fight" -> { player.closeInventory(); msg(player, "&eUse /duel <player> to challenge someone."); }
            }
        } else if (title.equals(MENU_ARENAS)) {
            if (name.equals("Back")) { openMain(player); return; }
            String mode = null;
            // Determine mode from menu item lore, and then resolve arena selection.
            ItemStack clicked = event.getCurrentItem();
            if (clicked.getType() == Material.NETHER_STAR) {
                String lastMode = playerMode.getOrDefault(player.getUniqueId(), "FFA");
                mode = lastMode;
                ConfigurationSection sec = getConfig().getConfigurationSection("arenas");
                List<String> choices = new ArrayList<>();
                if (sec != null) for (String a : sec.getKeys(false))
                    if (sec.getString(a + ".mode", "").equalsIgnoreCase(mode)) choices.add(a);
                if (!choices.isEmpty()) startMode(player, mode, choices.get(new Random().nextInt(choices.size())));
                else msg(player, "&cNo arenas for that mode yet.");
                return;
            }
            // Arena item lore stores the mode in line 0.
            List<String> lore = clicked.getItemMeta().getLore();
            if (lore != null && !lore.isEmpty()) {
                String first = ChatColor.stripColor(lore.get(0));
                if (first.startsWith("Mode: ")) mode = first.substring(6).trim();
            }
            if (mode == null) {
                // The menu's title is shared; infer the selected mode from the remembered menu state.
                mode = playerMode.getOrDefault(player.getUniqueId(), "FFA");
            }
            startMode(player, mode, name);
        } else if (title.equals(MENU_RANKS) && name.equals("Back")) {
            openMain(player);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        String kitMode = editingKit.get(player.getUniqueId());
        if (kitMode != null && event.getView().getTitle().equals(MENU_KIT)) {
            getConfig().set("kits." + kitMode + ".contents", player.getInventory().getContents());
            saveConfig();
            editingKit.remove(player.getUniqueId());
            msg(player, "&aKit saved for &f" + kitMode + "&a.");
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!inMatch.contains(event.getPlayer().getUniqueId())) {
            Location lobby = readLocation(getConfig().getConfigurationSection("lobby"));
            if (lobby != null) event.setRespawnLocation(lobby);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("pvp")) {
            if (!(sender instanceof Player p)) { msg(sender, "&cPlayers only."); return true; }
            openMain(p); return true;
        }
        if (cmd.equals("duel")) {
            if (!(sender instanceof Player p) || args.length != 1) { msg(sender, "&cUsage: /duel <player>"); return true; }
            Player target = Bukkit.getPlayerExact(args[0]);
            if (target == null || target.equals(p)) { msg(p, "&cThat player is not online or is yourself."); return true; }
            duelRequests.put(target.getUniqueId(), p.getUniqueId());
            msg(p, "&aDuel request sent to &f" + target.getName() + "&a.");
            msg(target, "&e" + p.getName() + " challenged you! Use &f/accept " + p.getName());
            return true;
        }
        if (cmd.equals("accept")) {
            if (!(sender instanceof Player p) || args.length != 1) { msg(sender, "&cUsage: /accept <player>"); return true; }
            Player challenger = Bukkit.getPlayerExact(args[0]);
            if (challenger == null || !Objects.equals(duelRequests.get(p.getUniqueId()), challenger.getUniqueId())) {
                msg(p, "&cNo matching duel request from that player."); return true;
            }
            duelRequests.remove(p.getUniqueId());
            msg(p, "&aDuel accepted! Arena-based duel setup is ready for the next expansion.");
            msg(challenger, "&a" + p.getName() + " accepted your duel request.");
            return true;
        }
        if (cmd.equals("admin")) return admin(sender, args);
        return false;
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("frostingpvp.admin")) { msg(sender, "&cYou don't have permission."); return true; }
        if (args.length == 0) {
            msg(sender, "&e/admin maintenance <on|off> &7- toggle maintenance");
            msg(sender, "&e/admin shutdown [reason] &7- stop server cleanly");
            msg(sender, "&e/admin setlobby &7- set lobby at your location");
            msg(sender, "&e/admin arena create <name> <mode> &7- create arena spawn");
            msg(sender, "&e/admin kit save <mode> &7- save your inventory as a kit");
            msg(sender, "&e/admin reload &7- reload config");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "maintenance" -> {
                if (args.length < 2) { msg(sender, "&cUse /admin maintenance <on|off>"); return true; }
                boolean on = args[1].equalsIgnoreCase("on");
                getConfig().set("maintenance", on); saveConfig();
                msg(sender, on ? "&cMaintenance enabled." : "&aMaintenance disabled.");
                if (on) for (Player p : Bukkit.getOnlinePlayers())
                    if (!p.hasPermission("frostingpvp.admin")) p.kickPlayer(ChatColor.RED + "FrostingPVP maintenance.");
            }
            case "shutdown" -> {
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Server maintenance";
                Bukkit.broadcastMessage(prefix() + ChatColor.RED + "Server shutting down: " + reason);
                Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 40L);
            }
            case "setlobby" -> {
                if (!(sender instanceof Player p)) { msg(sender, "&cPlayers only."); return true; }
                saveLocation(getConfig().createSection("lobby"), p.getLocation()); saveConfig();
                msg(p, "&aLobby spawn saved.");
            }
            case "arena" -> {
                if (args.length < 4 || !args[1].equalsIgnoreCase("create") || !(sender instanceof Player p)) {
                    msg(sender, "&cUse /admin arena create <name> <mode> while in-game."); return true;
                }
                String name = args[2].replaceAll("[^A-Za-z0-9_-]", "");
                String mode = args[3].toUpperCase(Locale.ROOT);
                if (!Set.of("FFA", "BEDWARS", "PRACTICE", "CRYSTAL", "CLASSIC").contains(mode)) {
                    msg(p, "&cModes: FFA, BEDWARS, PRACTICE, CRYSTAL, CLASSIC"); return true;
                }
                ConfigurationSection sec = getConfig().createSection("arenas." + name);
                sec.set("mode", mode); saveLocation(sec.createSection("spawn"), p.getLocation()); saveConfig();
                msg(p, "&aArena &f" + name + " &acreated for &f" + mode + "&a.");
            }
            case "kit" -> {
                if (args.length != 3 || !args[1].equalsIgnoreCase("save") || !(sender instanceof Player p)) {
                    msg(sender, "&cUse /admin kit save <mode> while holding the kit in your inventory."); return true;
                }
                String mode = args[2].toUpperCase(Locale.ROOT);
                if (!Set.of("FFA", "BEDWARS", "PRACTICE", "CRYSTAL", "CLASSIC").contains(mode)) {
                    msg(p, "&cUnknown mode."); return true;
                }
                getConfig().set("kits." + mode + ".contents", p.getInventory().getContents());
                saveConfig(); msg(p, "&aSaved inventory kit for &f" + mode + "&a.");
            }
            case "reload" -> { reloadConfig(); msg(sender, "&aConfiguration reloaded."); }
            default -> msg(sender, "&cUnknown admin subcommand. Use /admin.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("admin") && args.length == 1)
            return List.of("maintenance", "shutdown", "setlobby", "arena", "kit", "reload");
        if (command.getName().equalsIgnoreCase("admin") && args.length == 2 && args[0].equalsIgnoreCase("maintenance"))
            return List.of("on", "off");
        if (command.getName().equalsIgnoreCase("admin") && args.length == 2 && args[0].equalsIgnoreCase("arena"))
            return List.of("create");
        if (command.getName().equalsIgnoreCase("admin") && args.length == 3 &&
                (args[0].equalsIgnoreCase("arena") || args[0].equalsIgnoreCase("kit")))
            return List.of("FFA", "BEDWARS", "PRACTICE", "CRYSTAL", "CLASSIC");
        if (command.getName().equalsIgnoreCase("duel") || command.getName().equalsIgnoreCase("accept")) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return names;
        }
        return Collections.emptyList();
    }
}
