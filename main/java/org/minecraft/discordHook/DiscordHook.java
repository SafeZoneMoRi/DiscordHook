package org.minecraft.discordHook;

import github.scarsz.discordsrv.DiscordSRV;
import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.AccountLinkedEvent;
import github.scarsz.discordsrv.objects.managers.AccountLinkManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.ChatColor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DiscordHook extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("(DiscordHook Enable) Ver: 1.0");

        // ลงทะเบียน listener กับ DiscordSRV
        DiscordSRV.api.subscribe(this);

        saveDefaultConfig();

        // เพิ่ม TabCompleter
        getCommand("discordhook").setTabCompleter((sender, command, alias, args) -> {
            if (args.length == 1) {
                return List.of("reload", "list"); // subcommands
            }
            return List.of(); // ไม่มี autocomplete สำหรับ args มากกว่า 1
        });
    }

    @Subscribe
    public void onAccountLinked(AccountLinkedEvent event) {
        OfflinePlayer offlinePlayer = event.getPlayer();
        Player player = offlinePlayer.getPlayer();

        if (player != null && player.isOnline()) {
            List<String> commands = getConfig().getStringList("rewards.onLink");

            // รันบน main thread
            Bukkit.getScheduler().runTask(this, () -> {
                for (String cmd : commands) {
                    cmd = cmd.replace("%player%", player.getName()); // แทนชื่อผู้เล่น

                    if (cmd.toLowerCase().startsWith("say ")) {
                        // แปลงสีและ broadcast แทนการ dispatch command
                        String message = cmd.substring(4);
                        message = ChatColor.translateAlternateColorCodes('&', message);
                        Bukkit.broadcastMessage(message);
                    } else {
                        // ถ้าเป็นคำสั่งอื่น แค่ dispatch ปกติ
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                }
            });
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("discordhook")) return false;

        if (args.length == 1) {
            if (args[0].equalsIgnoreCase("reload")) {
                reloadConfig();
                sender.sendMessage(ChatColor.GREEN + "DiscordHook config reloaded!");
                return true;
            } else if (args[0].equalsIgnoreCase("list")) {
                sender.sendMessage(ChatColor.GOLD + "=== Linked Players ===");

                AccountLinkManager manager = DiscordSRV.getPlugin().getAccountLinkManager();
                Map<String, UUID> linked = manager.getLinkedAccounts(); // Key = DiscordID, Value = UUID

                for (Map.Entry<String, UUID> entry : linked.entrySet()) {
                    String discordId = entry.getKey();
                    UUID uuid = entry.getValue();
                    OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
                    sender.sendMessage((p.isOnline() ? ChatColor.GREEN : ChatColor.RED) + p.getName()
                            + ChatColor.GRAY + " - DiscordID: " + discordId);
                }
                return true;
            }
        }

        sender.sendMessage(ChatColor.RED + "Usage: /discordhook <reload|list>");
        return true;
    }

    @Override
    public void onDisable() {
        getLogger().info("DiscordHook Disabled");
    }
}
