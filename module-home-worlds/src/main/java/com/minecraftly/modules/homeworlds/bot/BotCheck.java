package com.minecraftly.modules.homeworlds.bot;

import com.minecraftly.core.packets.PacketBotCheck;
import com.minecraftly.modules.homeworlds.HomeWorldsModule;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.Random;

/**
 * Created by Keir on 24/06/2015.
 */
public class BotCheck implements Listener, Runnable {

    public static final String KEY_HUMAN_CHECK_INVENTORY = "Minecraftly.HumanCheckInventory";

    public static final String INVENTORY_NAME = "Are you a bot?";

    public static final ItemStack ACCEPT_ITEM_STACK = new ItemStack(Material.WOOL, 1, (short) 5) {{
        ItemMeta itemMeta = getItemMeta();
        itemMeta.setDisplayName(ChatColor.GREEN + "I confirm I am not a bot.");
        setItemMeta(itemMeta);
    }};

    private HomeWorldsModule module;
    private Random random = new Random();

    public BotCheck(HomeWorldsModule module) {
        this.module = module;
    }

    public void showHumanCheck(Player player) {
        Inventory inventory;

        if (!player.hasMetadata(KEY_HUMAN_CHECK_INVENTORY)) {
            int inventorySize = 9 * 6;
            inventory = Bukkit.createInventory(player, inventorySize, INVENTORY_NAME);
            inventory.setItem(random.nextInt(inventorySize), ACCEPT_ITEM_STACK);
            player.setMetadata(KEY_HUMAN_CHECK_INVENTORY, new FixedMetadataValue(module.getPlugin(), inventory));
        } else {
            inventory = (Inventory) player.getMetadata(KEY_HUMAN_CHECK_INVENTORY).get(0).value();
        }

        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        HumanEntity whoClicked = e.getWhoClicked();

        if (whoClicked instanceof Player) {
            Player player = (Player) whoClicked;
            Inventory inventory = e.getInventory();
            ItemStack currentItem = e.getCurrentItem();

            if (inventory.getName().equals(INVENTORY_NAME)) {
                if (currentItem != null && currentItem.getType() == ACCEPT_ITEM_STACK.getType()) { // todo make this check better
                    player.closeInventory();
                    module.getPlugin().getUserManager().getUser(player).getSingletonUserData(BotCheckStatusData.class).setStatus(true);
                    module.getPlugin().getGateway().sendPacket(player, new PacketBotCheck(true));
                }

                e.setCancelled(true);
            }
        }
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getWorlds().get(0).getPlayers()) {
            if (!module.getPlugin().getUserManager().getUser(player).getSingletonUserData(BotCheckStatusData.class).getStatus()) {
                showHumanCheck(player);
            }
        }
    }
}
