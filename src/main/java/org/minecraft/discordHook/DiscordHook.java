package org.minecraft.discordHook;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AccountLinkedEvent;
import github.scarsz.discordsrv.objects.managers.AccountLinkManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DiscordHook extends JavaPlugin {

    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        getLogger().info("(DiscordHook Enable) Ver: " + getDescription().getVersion());

        // บันทึก config เริ่มต้น
        saveDefaultConfig();

        // เริ่มต้นการเชื่อมต่อฐานข้อมูล MySQL
        this.databaseManager = new DatabaseManager(this);
        this.databaseManager.initialize();

        // ลงทะเบียน listener กับ DiscordSRV
        DiscordSRV.api.subscribe(this);

        // เพิ่ม TabCompleter
        if (getCommand("discordhook") != null) {
            getCommand("discordhook").setTabCompleter((sender, command, alias, args) -> {
                if (args.length == 1) {
                    List<String> subcommands = List.of("reload", "list", "check", "reset");
                    List<String> matches = new ArrayList<>();
                    for (String sub : subcommands) {
                        if (sub.toLowerCase().startsWith(args[0].toLowerCase())) {
                            matches.add(sub);
                        }
                    }
                    return matches;
                } else if (args.length == 2 && (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("reset"))) {
                    List<String> playerNames = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                            playerNames.add(p.getName());
                        }
                    }
                    return playerNames;
                }
                return List.of();
            });
        }
    }

    @Subscribe
    public void onAccountLinked(AccountLinkedEvent event) {
        OfflinePlayer offlinePlayer = event.getPlayer();
        if (offlinePlayer == null) return;

        UUID uuid = offlinePlayer.getUniqueId();
        String playerName = offlinePlayer.getName() != null ? offlinePlayer.getName() : "Unknown";
        String discordId = event.getUser() != null ? event.getUser().getId() : null;

        // รันการตรวจสอบและบันทึกข้อมูลแบบ Asynchronous เพื่อไม่ให้ Main Server Thread กระตุก
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (!databaseManager.isConnected()) {
                getLogger().warning("Database is not connected! Cannot process link reward for player " + playerName);
                return;
            }

            // 1. ตรวจสอบว่าบัญชีเกม (UUID) นี้เคยรับรางวัลไปแล้วหรือไม่
            if (databaseManager.hasPlayerClaimed(uuid)) {
                getLogger().info("Player " + playerName + " (" + uuid + ") tried to claim reward again, but already claimed!");
                Player onlinePlayer = offlinePlayer.getPlayer();
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    String msg = getConfig().getString("messages.already-claimed", "&c[!] คุณเคยรับรางวัลจากการเชื่อมโยงบัญชีไปแล้ว ไม่สามารถรับซ้ำได้!");
                    onlinePlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }
                return;
            }

            // 2. ตรวจสอบว่าบัญชี Discord นี้เคยนำมารับรางวัลไปแล้วหรือไม่ (หากเปิดใช้การตั้งค่า)
            boolean preventDuplicateDiscord = getConfig().getBoolean("settings.prevent-duplicate-discord", true);
            if (preventDuplicateDiscord && discordId != null && databaseManager.hasDiscordClaimed(discordId)) {
                getLogger().info("Discord ID " + discordId + " was already used to claim a reward!");
                Player onlinePlayer = offlinePlayer.getPlayer();
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    String msg = getConfig().getString("messages.discord-already-claimed", "&c[!] บัญชี Discord นี้เคยถูกนำไปรับรางวัลแล้ว ไม่สามารถรับซ้ำได้!");
                    onlinePlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
                }
                return;
            }

            // 3. บันทึกสิทธิ์ว่าได้รับรางวัลแล้วลงฐานข้อมูล MySQL
            boolean recorded = databaseManager.recordClaim(uuid, playerName, discordId);
            if (!recorded) {
                getLogger().severe("Failed to save claim record for " + playerName + " in MySQL database!");
                return;
            }

            // 4. สลับกลับมารันคำสั่งมอบรางวัลบน Main Server Thread (คำสั่ง Bukkit ต้องรันบน Main Thread เสมอ)
            Bukkit.getScheduler().runTask(this, () -> {
                Player onlinePlayer = offlinePlayer.getPlayer();
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    List<String> commands = getConfig().getStringList("rewards.onLink");
                    for (String cmd : commands) {
                        cmd = cmd.replace("%player%", onlinePlayer.getName());

                        if (cmd.toLowerCase().startsWith("say ")) {
                            String message = cmd.substring(4);
                            message = ChatColor.translateAlternateColorCodes('&', message);
                            Bukkit.broadcastMessage(message);
                        } else {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                        }
                    }

                    String successMsg = getConfig().getString("messages.reward-success", "&a[!] เชื่อมต่อสำเร็จ! คุณได้รับรางวัลเรียบร้อยแล้ว");
                    if (successMsg != null && !successMsg.isEmpty()) {
                        onlinePlayer.sendMessage(ChatColor.translateAlternateColorCodes('&', successMsg));
                    }
                }
            });
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("discordhook")) return false;

        if (args.length >= 1) {
            if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                databaseManager.initialize();
                sender.sendMessage(ChatColor.GREEN + "[DiscordHook] Configuration and Database reloaded!");
                return true;
            } else if (args[0].equalsIgnoreCase("list")) {
                sender.sendMessage(ChatColor.GOLD + "=== Linked Players (DiscordSRV) ===");
                AccountLinkManager manager = DiscordSRV.getPlugin().getAccountLinkManager();
                Map<String, UUID> linked = manager.getLinkedAccounts();

                for (Map.Entry<String, UUID> entry : linked.entrySet()) {
                    String discordId = entry.getKey();
                    UUID uuid = entry.getValue();
                    OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
                    String name = p.getName() != null ? p.getName() : uuid.toString();
                    sender.sendMessage((p.isOnline() ? ChatColor.GREEN : ChatColor.RED) + name
                            + ChatColor.GRAY + " - DiscordID: " + discordId);
                }
                return true;
            } else if (args[0].equalsIgnoreCase("check") && args.length == 2) {
                String targetName = args[1];
                sender.sendMessage(ChatColor.YELLOW + "[DiscordHook] Checking status for " + targetName + "...");

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                    boolean claimed = databaseManager.hasPlayerClaimed(target.getUniqueId());

                    sender.sendMessage(ChatColor.GOLD + "[DiscordHook] " + targetName + " (" + target.getUniqueId() + "): "
                            + (claimed ? ChatColor.RED + "Already Claimed Reward" : ChatColor.GREEN + "Not Claimed Yet"));
                });
                return true;
            } else if (args[0].equalsIgnoreCase("reset") && args.length == 2) {
                String targetName = args[1];
                sender.sendMessage(ChatColor.YELLOW + "[DiscordHook] Resetting claim record for " + targetName + "...");

                Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
                    boolean reset = databaseManager.resetClaim(target.getUniqueId());

                    if (reset) {
                        sender.sendMessage(ChatColor.GREEN + "[DiscordHook] Successfully reset claim record for " + targetName + "! They can claim again.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "[DiscordHook] No record found or failed to reset for " + targetName + ".");
                    }
                });
                return true;
            }
        }

        sender.sendMessage(ChatColor.RED + "Usage: /discordhook <reload|list|check <player>|reset <player>>");
        return true;
    }

    @Override
    public void onDisable() {
        if (databaseManager != null) {
            databaseManager.close();
        }
        getLogger().info("DiscordHook Disabled");
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}
