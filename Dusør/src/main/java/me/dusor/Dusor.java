package main.java.me.dusor;

import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Dusor extends JavaPlugin implements Listener, CommandExecutor {

    private final Map<UUID, Integer> randomBounties = new HashMap<>();
    private final Map<UUID, Integer> playerBounties = new HashMap<>();
    private final Map<UUID, Long> systemBountyTimestamps = new HashMap<>();
    private final Set<UUID> recentlyKilled = new HashSet<>();
    private Inventory dusorGUI;
    private Economy economy;
    private FileConfiguration config;
    private String prefix;
    private boolean debugMode = false; 

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
        prefix = colorize(config.getString("prefix", "&c[Dusør] "));
        debugMode = config.getBoolean("debug", false);

        if (!setupEconomy()) {
            getLogger().severe("Vault ikke fundet! Deaktiverer plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        
        if (getServer().getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens ikke fundet! Deaktiverer plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        
        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("dusor") != null) getCommand("dusor").setExecutor(this);
        if (getCommand("dusør") != null) getCommand("dusør").setExecutor(this);

        buildGUI();
        loadBounties();
        updateGUI();
        scheduleRandomBountyTask();
        scheduleSystemBountyCleanup();
        
        getLogger().info("Dusør plugin aktiveret!");
        getLogger().info("Citizens version: " + getServer().getPluginManager().getPlugin("Citizens").getDescription().getVersion());
    }

    @Override
    public void onDisable() {
        saveBounties();
        getLogger().info("Dusør plugin deaktiveret!");
    }

    private void saveBounties() {
        File file = new File(getDataFolder(), "dusorer.yml");
        FileConfiguration data = new YamlConfiguration();
        
        
        for (UUID uuid : randomBounties.keySet()) {
            String uuidStr = uuid.toString();
            data.set(uuidStr + ".random", randomBounties.get(uuid));
            
            if (systemBountyTimestamps.containsKey(uuid)) {
                data.set(uuidStr + ".timestamp", systemBountyTimestamps.get(uuid));
            }
        }
        
        
        for (UUID uuid : playerBounties.keySet()) {
            String uuidStr = uuid.toString();
            data.set(uuidStr + ".player", playerBounties.get(uuid));
        }
        
        try {
            data.save(file);
            if (debugMode) {
                getLogger().info("Saved " + randomBounties.size() + " random bounties and " + playerBounties.size() + " player bounties.");
            }
        } catch (IOException e) {
            getLogger().warning("Kunne ikke gemme dusorer.yml: " + e.getMessage());
        }
    }

    private void loadBounties() {
        File file = new File(getDataFolder(), "dusorer.yml");
        if (!file.exists()) {
            getLogger().info("No bounties file found, starting fresh.");
            return;
        }

        FileConfiguration data = YamlConfiguration.loadConfiguration(file);
        int loadedRandom = 0;
        int loadedPlayer = 0;
        
        for (String key : data.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                
                
                if (data.contains(key + ".random")) {
                    int amount = data.getInt(key + ".random", 0);
                    if (amount > 0) {
                        randomBounties.put(uuid, amount);
                        loadedRandom++;
                        
                        if (data.contains(key + ".timestamp")) {
                            long timestamp = data.getLong(key + ".timestamp");
                            systemBountyTimestamps.put(uuid, timestamp);
                        }
                        
                        if (debugMode) {
                            getLogger().info("Loaded random bounty: " + uuid + " = $" + amount);
                        }
                    }
                }
                
                
                if (data.contains(key + ".player")) {
                    int amount = data.getInt(key + ".player", 0);
                    if (amount > 0) {
                        playerBounties.put(uuid, amount);
                        loadedPlayer++;
                        
                        if (debugMode) {
                            getLogger().info("Loaded player bounty: " + uuid + " = $" + amount);
                        }
                    }
                }
            } catch (IllegalArgumentException e) {
                if (debugMode) {
                    getLogger().warning("Invalid UUID in bounties file: " + key);
                }
            }
        }
        
        getLogger().info("Loaded " + loadedRandom + " random bounties and " + loadedPlayer + " player bounties.");
    }

    private void scheduleRandomBountyTask() {
        long delay = 20L * 60 * 60; // 1 time
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (players.isEmpty()) {
                if (debugMode) getLogger().info("No players online for random bounty.");
                return;
            }

            Player target = players.get(new Random().nextInt(players.size()));
            UUID id = target.getUniqueId();
            
            
            if (randomBounties.containsKey(id)) {
                if (debugMode) getLogger().info("Player " + target.getName() + " already has a random bounty, skipping.");
                return;
            }
            
            
            randomBounties.put(id, 100);
            systemBountyTimestamps.put(id, System.currentTimeMillis());
            saveBounties();
            updateGUI();

            String msg = getMessage("random-bounty-added", "%target%", target.getName(), "%amount%", "$100");
            Bukkit.broadcastMessage(prefix + msg);
            
            if (debugMode) {
                getLogger().info("Added random bounty on " + target.getName() + " for $100");
            }
        }, delay, delay);
    }

    private void scheduleSystemBountyCleanup() {
        long delay = 20L * 60; 
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (debugMode) {
                getLogger().info("Running automatic bounty cleanup check...");
            }
            
            long currentTime = System.currentTimeMillis();
            long twentyFourHours = 24L * 60 * 60 * 1000;
            
            List<UUID> toRemove = new ArrayList<>();
            
            for (Map.Entry<UUID, Long> entry : systemBountyTimestamps.entrySet()) {
                UUID playerId = entry.getKey();
                long timestamp = entry.getValue();
                long timeLeft = (24L * 60 * 60 * 1000) - (currentTime - timestamp);
                
                if (debugMode) {
                    OfflinePlayer p = Bukkit.getOfflinePlayer(playerId);
                    String name = p.getName() != null ? p.getName() : "Unknown";
                    long minutesLeft = timeLeft / (60 * 1000);
                    getLogger().info("Random bounty on " + name + ": " + minutesLeft + " minutes left");
                }
                
                if (currentTime - timestamp > twentyFourHours) {
                    toRemove.add(playerId);
                }
            }
            
            for (UUID playerId : toRemove) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
                String playerName = player.getName() != null ? player.getName() : "Ukendt";
                
                
                randomBounties.remove(playerId);
                systemBountyTimestamps.remove(playerId);
                
                getLogger().info("Random dusør på " + playerName + " er udløbet efter 24 timer.");
                
                
                String expiredMsg = getMessage("random-bounty-expired", "%player%", playerName);
                Bukkit.broadcastMessage(prefix + expiredMsg);
            }
            
            if (!toRemove.isEmpty()) {
                saveBounties();
                updateGUI();
                getLogger().info("Cleanup removed " + toRemove.size() + " expired random bounties.");
            } else if (debugMode) {
                getLogger().info("No expired bounties to clean up.");
            }
        }, delay, delay);
    }

    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private String getMessage(String key) {
        return colorize(config.getString("messages." + key, key));
    }

    private String getMessage(String key, String... replacements) {
        String msg = getMessage(key);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                msg = msg.replace(replacements[i], replacements[i + 1]);
            }
        }
        return msg;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    private void buildGUI() {
        dusorGUI = Bukkit.createInventory(null, 54, ChatColor.DARK_RED + "Dusør Menu");

        for (int i = 0; i < 54; i++) dusorGUI.setItem(i, null);

        ItemStack redGlass = new ItemStack(Material.STAINED_GLASS_PANE, 1, (byte) 14);
        ItemMeta redMeta = redGlass.getItemMeta();
        redMeta.setDisplayName(" ");
        redGlass.setItemMeta(redMeta);

        for (int i = 0; i < 9; i++) if (i != 4) dusorGUI.setItem(i, redGlass);

        ItemStack whiteGlass = new ItemStack(Material.STAINED_GLASS_PANE, 1, (byte) 0);
        ItemMeta whiteMeta = whiteGlass.getItemMeta();
        whiteMeta.setDisplayName(" ");
        whiteGlass.setItemMeta(whiteMeta);

        for (int i = 45; i < 54; i++) dusorGUI.setItem(i, whiteGlass);

        ItemStack info = new ItemStack(Material.SIGN);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "DUSØR");
        infoMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "Sæt en dusør på en spiller,",
                ChatColor.GRAY + "og få dem dræbt!",
                "",
                ChatColor.GRAY + "Opret dusør:",
                ChatColor.GRAY + "\"/dusør <spiller> <beløb>\""
        ));
        info.setItemMeta(infoMeta);
        dusorGUI.setItem(4, info);
    }

    private void updateGUI() {
        for (int i = 9; i <= 44; i++) dusorGUI.setItem(i, null);

        
        Set<UUID> allBountyPlayers = new HashSet<>();
        allBountyPlayers.addAll(randomBounties.keySet());
        allBountyPlayers.addAll(playerBounties.keySet());
        
        
        List<UUID> sorted = new ArrayList<>(allBountyPlayers);
        sorted.sort((a, b) -> {
            int totalA = randomBounties.getOrDefault(a, 0) + playerBounties.getOrDefault(a, 0);
            int totalB = randomBounties.getOrDefault(b, 0) + playerBounties.getOrDefault(b, 0);
            return Integer.compare(totalB, totalA);
        });

        int[] slots = {
                9, 10, 11, 12, 13, 14, 15, 16, 17,
                18, 19, 20, 21, 22, 23, 24, 25, 26,
                27, 28, 29, 30, 31, 32, 33, 34, 35,
                36, 37, 38, 39, 40, 41, 42, 43, 44
        };

        int index = 0;
        long currentTime = System.currentTimeMillis();
        
        for (UUID uuid : sorted) {
            if (index >= slots.length) break;

            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            if (target == null || target.getName() == null) continue;

            ItemStack skull = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            if (meta == null) continue;

            meta.setOwner(target.getName());
            meta.setDisplayName(ChatColor.RED + target.getName());
            
            List<String> lore = new ArrayList<>();
            
            int randomAmount = randomBounties.getOrDefault(uuid, 0);
            int playerAmount = playerBounties.getOrDefault(uuid, 0);
            int total = randomAmount + playerAmount;
            
            lore.add(ChatColor.GRAY + "Total Dusør: " + ChatColor.GOLD + "$" + total);
            lore.add("");
            
            
            if (randomAmount > 0 && systemBountyTimestamps.containsKey(uuid)) {
                long timestamp = systemBountyTimestamps.get(uuid);
                long timeLeft = (24L * 60 * 60 * 1000) - (currentTime - timestamp);
                
                if (timeLeft > 0) {
                    long hoursLeft = timeLeft / (60 * 60 * 1000);
                    long minutesLeft = (timeLeft % (60 * 60 * 1000)) / (60 * 1000);
                    
                    lore.add(ChatColor.YELLOW + "⚡ Random: " + ChatColor.GOLD + "$" + randomAmount);
                    lore.add(ChatColor.GRAY + "   Udløber om: " + ChatColor.YELLOW + hoursLeft + "t " + minutesLeft + "m");
                }
            }
            
            
            if (playerAmount > 0) {
                lore.add(ChatColor.GREEN + "⭐ Player: " + ChatColor.GOLD + "$" + playerAmount);
                lore.add(ChatColor.GRAY + "   Udløber aldrig");
            }
            
            meta.setLore(lore);
            skull.setItemMeta(meta);

            dusorGUI.setItem(slots[index], skull);
            index++;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNPCClick(NPCRightClickEvent event) {
        Player player = event.getClicker();
        
        
        if (debugMode) {
            getLogger().info("NPC clicked by: " + player.getName());
            getLogger().info("NPC name: " + event.getNPC().getName());
            getLogger().info("NPC full name: " + event.getNPC().getFullName());
        }
        
        if (recentlyKilled.contains(player.getUniqueId())) {
            if (debugMode) getLogger().info("Player recently killed, ignoring.");
            return;
        }

        
        String npcName = ChatColor.stripColor(event.getNPC().getName()).toLowerCase();
        String npcFullName = ChatColor.stripColor(event.getNPC().getFullName()).toLowerCase();
        
        
        if (!npcName.contains("dusør") && !npcName.contains("dusor") && 
            !npcFullName.contains("dusør") && !npcFullName.contains("dusor")) {
            if (debugMode) getLogger().info("NPC name doesn't match 'Dusør' or 'Dusor'");
            return;
        }

        if (debugMode) getLogger().info("Opening Dusør GUI for " + player.getName());
        
        updateGUI();
        player.openInventory(dusorGUI);
    }
    
    
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (getServer().getPluginManager().getPlugin("Citizens") == null) return;
        
        Player player = event.getPlayer();
        
        
        if (!event.getRightClicked().hasMetadata("NPC")) return;
        
        String entityName = ChatColor.stripColor(event.getRightClicked().getName()).toLowerCase();
        
        if (debugMode) {
            getLogger().info("Entity interaction: " + entityName);
        }
        
        
        if (entityName.contains("dusør") || entityName.contains("dusor")) {
            if (debugMode) getLogger().info("Opening GUI via entity interaction");
            
            if (!recentlyKilled.contains(player.getUniqueId())) {
                updateGUI();
                player.openInventory(dusorGUI);
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(dusorGUI)) return;
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (event.getSlot() == 4 && clicked.getType() == Material.SIGN) {
            player.closeInventory();
            player.sendMessage(prefix + getMessage("usage"));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        if (killer == null) return;
        
        UUID victimId = victim.getUniqueId();
        int randomAmount = randomBounties.getOrDefault(victimId, 0);
        int playerAmount = playerBounties.getOrDefault(victimId, 0);
        int totalReward = randomAmount + playerAmount;
        
        if (totalReward == 0) return;

        String allowedWorld = config.getString("allowed-world", "main");
        if (!victim.getWorld().getName().equalsIgnoreCase(allowedWorld)) return;

        
        randomBounties.remove(victimId);
        playerBounties.remove(victimId);
        systemBountyTimestamps.remove(victimId);
        recentlyKilled.add(killer.getUniqueId());
        saveBounties();
        updateGUI();

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "addshutdown " + killer.getName());

        Bukkit.getScheduler().runTaskLater(this, () -> {
            economy.depositPlayer(killer, totalReward);
            Bukkit.broadcastMessage(prefix + getMessage("bounty-reward",
                    "%killer%", killer.getName(),
                    "%victim%", victim.getName(),
                    "%amount%", "$" + totalReward));
            killer.sendMessage(prefix + getMessage("bounty-reward-personal",
                    "%amount%", "$" + totalReward));

            Bukkit.getScheduler().runTaskLater(this, () ->
                    recentlyKilled.remove(killer.getUniqueId()), 20L);
        }, 1L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(prefix + "Kun spillere kan bruge denne kommando.");
                return true;
            }
            Player player = (Player) sender;
            updateGUI();
            player.openInventory(dusorGUI);
            player.sendMessage(prefix + ChatColor.GREEN + "Åbner Dusør GUI...");
            return true;
        }

        // Debug command
        if (args.length == 1 && args[0].equalsIgnoreCase("debug")) {
            if (sender.hasPermission("dusor.reload")) {
                debugMode = !debugMode;
                sender.sendMessage(prefix + "Debug mode: " + (debugMode ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            if (sender.hasPermission("dusor.reload")) {
                int totalBounties = new HashSet<UUID>() {{
                    addAll(randomBounties.keySet());
                    addAll(playerBounties.keySet());
                }}.size();
                
                sender.sendMessage(prefix + ChatColor.YELLOW + "=== Dusør Info ===");
                sender.sendMessage(ChatColor.GRAY + "Total spillere med bounty: " + ChatColor.WHITE + totalBounties);
                sender.sendMessage(ChatColor.GRAY + "Random bounties: " + ChatColor.WHITE + randomBounties.size());
                sender.sendMessage(ChatColor.GRAY + "Player bounties: " + ChatColor.WHITE + playerBounties.size());
                
                Set<UUID> allPlayers = new HashSet<>();
                allPlayers.addAll(randomBounties.keySet());
                allPlayers.addAll(playerBounties.keySet());
                
                if (!allPlayers.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Current bounties:");
                    long currentTime = System.currentTimeMillis();
                    
                    for (UUID uuid : allPlayers) {
                        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
                        String name = p.getName() != null ? p.getName() : "Unknown";
                        
                        int randomAmount = randomBounties.getOrDefault(uuid, 0);
                        int playerAmount = playerBounties.getOrDefault(uuid, 0);
                        int total = randomAmount + playerAmount;
                        
                        StringBuilder info = new StringBuilder();
                        info.append(ChatColor.GRAY).append("- ").append(name).append(": ")
                            .append(ChatColor.GOLD).append("$").append(total).append(" ");
                        
                        if (randomAmount > 0 && systemBountyTimestamps.containsKey(uuid)) {
                            long timestamp = systemBountyTimestamps.get(uuid);
                            long timeLeft = (24L * 60 * 60 * 1000) - (currentTime - timestamp);
                            long hoursLeft = Math.max(0, timeLeft / (60 * 60 * 1000));
                            long minutesLeft = Math.max(0, (timeLeft % (60 * 60 * 1000)) / (60 * 1000));
                            info.append(ChatColor.YELLOW).append("(Random: $").append(randomAmount)
                                .append(" - ").append(hoursLeft).append("t ").append(minutesLeft).append("m) ");
                        }
                        
                        if (playerAmount > 0) {
                            info.append(ChatColor.GREEN).append("(Player: $").append(playerAmount).append(")");
                        }
                        
                        sender.sendMessage(info.toString());
                    }
                }
            }
            return true;
        }

       
        if (args.length == 1 && args[0].equalsIgnoreCase("test")) {
            if (!sender.hasPermission("dusor.reload")) {
                sender.sendMessage(prefix + ChatColor.RED + "Ingen tilladelse.");
                return true;
            }

            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (players.isEmpty()) {
                sender.sendMessage(prefix + ChatColor.RED + "Ingen spillere online.");
                return true;
            }

            Player target = players.get(new Random().nextInt(players.size()));
            UUID id = target.getUniqueId();
            
            if (randomBounties.containsKey(id)) {
                sender.sendMessage(prefix + ChatColor.YELLOW + target.getName() + " har allerede en random dusør.");
                return true;
            }
            
            randomBounties.put(id, 100);
            systemBountyTimestamps.put(id, System.currentTimeMillis());
            saveBounties();
            updateGUI();

            String msg = getMessage("random-bounty-added", "%target%", target.getName(), "%amount%", "$100");
            Bukkit.broadcastMessage(prefix + msg);
            
            String testMsg = getMessage("test-bounty-added", "%target%", target.getName(), "%amount%", "$100");
            sender.sendMessage(prefix + testMsg);
            
            return true;
        }

        
        if (args.length >= 2 && args[0].equalsIgnoreCase("testexpire")) {
            if (!sender.hasPermission("dusor.reload")) {
                sender.sendMessage(prefix + ChatColor.RED + "Ingen tilladelse.");
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (target.getName() == null) {
                sender.sendMessage(prefix + ChatColor.RED + "Spilleren findes ikke.");
                return true;
            }

            UUID id = target.getUniqueId();
            
            
            long almostExpiredTime = System.currentTimeMillis() - (23L * 60 * 60 * 1000) - (59L * 60 * 1000);
            
            randomBounties.put(id, 100);
            systemBountyTimestamps.put(id, almostExpiredTime);
            saveBounties();
            updateGUI();

            sender.sendMessage(prefix + getMessage("test-expire-set", "%target%", target.getName()));
            sender.sendMessage(prefix + getMessage("test-expire-hint"));
            
            return true;
        }

        
        if (args.length == 1 && args[0].equalsIgnoreCase("cleanup")) {
            if (!sender.hasPermission("dusor.reload")) {
                sender.sendMessage(prefix + ChatColor.RED + "Ingen tilladelse.");
                return true;
            }

            long currentTime = System.currentTimeMillis();
            long twentyFourHours = 24L * 60 * 60 * 1000;
            
            List<UUID> toRemove = new ArrayList<>();
            
            for (Map.Entry<UUID, Long> entry : systemBountyTimestamps.entrySet()) {
                UUID playerId = entry.getKey();
                long timestamp = entry.getValue();
                
                if (currentTime - timestamp > twentyFourHours) {
                    toRemove.add(playerId);
                }
            }
            
            if (toRemove.isEmpty()) {
                sender.sendMessage(prefix + getMessage("cleanup-none"));
                return true;
            }
            
            for (UUID playerId : toRemove) {
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
                String playerName = player.getName() != null ? player.getName() : "Ukendt";
                
                
                randomBounties.remove(playerId);
                systemBountyTimestamps.remove(playerId);
                
                sender.sendMessage(prefix + getMessage("cleanup-removed", "%player%", playerName));
                Bukkit.broadcastMessage(prefix + getMessage("random-bounty-expired", "%player%", playerName));
            }
            
            saveBounties();
            updateGUI();
            sender.sendMessage(prefix + getMessage("cleanup-complete", "%count%", String.valueOf(toRemove.size())));
            
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (sender instanceof Player && !sender.hasPermission("dusor.reload")) {
                sender.sendMessage(prefix + ChatColor.RED + "Du har ikke tilladelse.");
                return true;
            }

            reloadConfig();
            config = getConfig();
            prefix = colorize(config.getString("prefix", "&c[Dusør] "));
            updateGUI();
            sender.sendMessage(prefix + ChatColor.GREEN + "Config genindlæst.");
            return true;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            if (sender instanceof Player && !sender.hasPermission("dusor.remove")) {
                sender.sendMessage(prefix + ChatColor.RED + "Ingen tilladelse.");
                return true;
            }

            OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
            if (target == null || target.getName() == null) {
                sender.sendMessage(prefix + "Spilleren findes ikke.");
                return true;
            }

            UUID targetId = target.getUniqueId();
            boolean hadRandom = randomBounties.containsKey(targetId);
            boolean hadPlayer = playerBounties.containsKey(targetId);
            
            if (hadRandom || hadPlayer) {
                randomBounties.remove(targetId);
                playerBounties.remove(targetId);
                systemBountyTimestamps.remove(targetId);
                saveBounties();
                updateGUI();
                
                if (hadRandom && hadPlayer) {
                    sender.sendMessage(prefix + "Fjernede både random og player dusør fra " + target.getName());
                } else if (hadRandom) {
                    sender.sendMessage(prefix + "Fjernede random dusør fra " + target.getName());
                } else {
                    sender.sendMessage(prefix + "Fjernede player dusør fra " + target.getName());
                }
            } else {
                sender.sendMessage(prefix + "Der er ingen dusør på " + target.getName());
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(prefix + getMessage("no-console"));
            return true;
        }

        Player player = (Player) sender;

        if (args.length != 2) {
            player.sendMessage(prefix + getMessage("usage"));
            return true;
        }

        
        Player onlineTarget = Bukkit.getPlayer(args[0]);
        OfflinePlayer target;
        
        if (onlineTarget != null) {
            
            target = onlineTarget;
        } else {
            
            target = Bukkit.getOfflinePlayer(args[0]);
            
            
            if (target.getName() == null) {
                player.sendMessage(prefix + getMessage("player-not-found"));
                return true;
            }
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(prefix + getMessage("cannot-target-yourself"));
            return true;
        }

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage(prefix + getMessage("invalid-amount"));
            return true;
        }

        int min = config.getInt("min-bounty", 50);
        int max = config.getInt("max-bounty", 5000);

        if (amount < min) {
            player.sendMessage(prefix + "Minimum dusør er $" + min + ".");
            return true;
        }

        if (amount > max) {
            player.sendMessage(prefix + "Maksimum dusør er $" + max + ".");
            return true;
        }

        if (!economy.has(player, amount)) {
            player.sendMessage(prefix + getMessage("not-enough-money"));
            return true;
        }

        economy.withdrawPlayer(player, amount);

        UUID targetId = target.getUniqueId();
        
        
        int existingPlayer = playerBounties.getOrDefault(targetId, 0);
        int newTotal = existingPlayer + amount;

        playerBounties.put(targetId, newTotal);
        saveBounties();
        updateGUI();

        String msg = existingPlayer > 0
                ? getMessage("bounty-increased", "%player%", player.getName(), "%target%", target.getName(), "%amount%", "$" + amount, "%total%", "$" + newTotal)
                : getMessage("bounty-added", "%player%", player.getName(), "%target%", target.getName(), "%amount%", "$" + amount);

        Bukkit.broadcastMessage(prefix + msg);
        return true;
    }
}