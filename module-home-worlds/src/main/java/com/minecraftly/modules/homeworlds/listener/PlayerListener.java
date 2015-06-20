package com.minecraftly.modules.homeworlds.listener;

import com.google.common.base.Preconditions;
import com.ikeirnez.pluginmessageframework.packet.PacketHandler;
import com.minecraftly.core.bukkit.language.LanguageManager;
import com.minecraftly.core.bukkit.language.LanguageValue;
import com.minecraftly.core.bukkit.user.User;
import com.minecraftly.core.bukkit.user.UserManager;
import com.minecraftly.core.bukkit.utilities.BukkitUtilities;
import com.minecraftly.core.packets.homes.PacketPlayerGotoHome;
import com.minecraftly.modules.homeworlds.HomeWorldsModule;
import com.minecraftly.modules.homeworlds.WorldDimension;
import com.minecraftly.modules.homeworlds.data.world.WorldUserData;
import com.minecraftly.modules.homeworlds.data.world.WorldUserDataContainer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Created by Keir on 24/04/2015.
 */
public class PlayerListener implements Listener, Consumer<Player> {

    public static final String LANGUAGE_KEY_PREFIX = HomeWorldsModule.getInstance().getLanguageSection();

    private final LanguageValue langLoadingOwner = new LanguageValue("&bOne moment whilst we load your home.");
    private final LanguageValue langLoadingGuest = new LanguageValue("&bOne moment whilst we load that home.");
    private final LanguageValue langWelcomeOwner = new LanguageValue("&aWelcome back to your home, &6%s&a.");
    private final LanguageValue langWelcomeGuest = new LanguageValue("&aWelcome to &6%s&a's home, they will have to grant you permission before you can modify blocks.");
    private final LanguageValue langWelcomeBoth = new LanguageValue("&aYou can go back to chat mode by typing &6/chat&a.");
    private final LanguageValue langPlayerJoinedHome = new LanguageValue("&6%s &bhas joined.");
    private final LanguageValue langPlayerLeftHome = new LanguageValue("&6%s &bhas left.");

    private final LanguageValue langLoadFailed = new LanguageValue("&cWe were unable to load your home, please contact a member of staff.");
    private final LanguageValue langOwnerLeft = new LanguageValue("&cThe owner of that world left.");

    private HomeWorldsModule module;
    private LanguageManager languageManager;
    private UserManager userManager;

    public PlayerListener(final HomeWorldsModule module) {
        this.module = module;
        this.languageManager = module.getPlugin().getLanguageManager();
        this.userManager = module.getPlugin().getUserManager();

        languageManager.registerAll(new HashMap<String, LanguageValue>() {{
            String prefix = PlayerListener.this.module.getLanguageSection();

            put(prefix + ".loading.owner", langLoadingOwner);
            put(prefix + ".loading.guest", langLoadingGuest);
            put(prefix + ".welcome.owner", langWelcomeOwner);
            put(prefix + ".welcome.guest", langWelcomeGuest);
            put(prefix + ".welcome.both", langWelcomeBoth);
            put(prefix + ".joinedHome", langPlayerJoinedHome);
            put(prefix + ".leftHome", langPlayerLeftHome);
            put(prefix + ".error.loadFailed", langLoadFailed);
            put(prefix + ".ownerLeft", langOwnerLeft);
        }});
    }

    @PacketHandler
    public void onPacketJoinWorld(PacketPlayerGotoHome packet) {
        UUID playerUUID = packet.getPlayer();
        UUID worldUUID = packet.getWorld();
        Player player = Bukkit.getPlayer(playerUUID);

        if (player != null) {
            joinWorld(player, worldUUID);
        }
    }

    public void joinWorld(Player player, UUID worldUUID) {
        if (!module.isWorldLoaded(worldUUID)) {
            if (player.getUniqueId().equals(worldUUID)) {
                langLoadingOwner.send(player);
            } else {
                langLoadingGuest.send(player);
            }
        }

        World world = module.getWorld(worldUUID);
        Bukkit.getScheduler().runTaskLater(module.getPlugin(), new Runnable() {
            @Override
            public void run() {
                joinWorld(player, world);
            }
        }, 20L * 2);
    }

    public void joinWorld(Player player, World world) {
        Preconditions.checkNotNull(player);
        UUID playerUUID = player.getUniqueId();

        if (world == null) {
            langLoadFailed.send(player);
            return;
        }

        WorldUserDataContainer worldUserDataContainer = userManager.getUser(player).getSingletonUserData(WorldUserDataContainer.class);
        WorldUserData worldUserData = worldUserDataContainer.getOrLoad(module.getHomeOwner(world));

        Location lastLocation = worldUserData.getLastLocation();
        Location bedLocation = worldUserData.getBedLocation();
        Location spawnLocation;

        // todo util method for player data
        if (lastLocation != null) {
            spawnLocation = lastLocation;
        } else if (bedLocation != null) {
            spawnLocation = bedLocation;
        } else {
            spawnLocation = BukkitUtilities.getSafeLocation(world.getSpawnLocation());
        }

        player.teleport(spawnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent e) {
        Player player = e.getPlayer();
        World world = WorldDimension.getBaseWorld(player.getWorld());
        leftWorld(player, world);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent e) {
        final Player player = e.getPlayer();
        UUID uuid = player.getUniqueId();
        World from = WorldDimension.getBaseWorld(e.getFrom().getWorld());
        World to = WorldDimension.getBaseWorld(e.getTo().getWorld());

        if (!from.equals(to)) {
            leftWorld(player, from);

            if (module.isHomeWorld(to)) {
                final UUID owner = module.getHomeOwner(to);
                WorldUserDataContainer worldUserDataContainer = userManager.getUser(player).getSingletonUserData(WorldUserDataContainer.class);
                WorldUserData worldUserData = worldUserDataContainer.get(owner);
                worldUserData.apply(player);

                refreshPlayerVisibilities(player, to);

                if (uuid.equals(owner)) {
                    player.setGameMode(GameMode.SURVIVAL);
                    langWelcomeOwner.send(player);
                    langWelcomeBoth.send(player);
                } else {
                    player.setGameMode(GameMode.ADVENTURE);

                    Bukkit.getScheduler().runTaskAsynchronously(module.getPlugin(), new Runnable() { // async for getOfflinePlayer
                        @Override
                        public void run() {
                            langWelcomeGuest.send(player);
                            langWelcomeBoth.send(player);
                        }
                    });
                }

                BukkitUtilities.broadcast(WorldDimension.getPlayersAllDimensions(to), player, langPlayerJoinedHome.getValue(player.getName()));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();
        World world = WorldDimension.getBaseWorld(player.getWorld());

        if (module.isHomeWorld(world)) {
            WorldUserDataContainer worldUserDataContainer = userManager.getUser(player).getSingletonUserData(WorldUserDataContainer.class);
            WorldUserData worldUserData = worldUserDataContainer.get(module.getHomeOwner(world));

            if (worldUserData != null) {
                // todo can't help but think this could all be shortened
                Location bedLocation = worldUserData.getBedLocation();
                if (bedLocation == null) {
                    bedLocation = player.getBedSpawnLocation();

                    if (bedLocation != null && !world.equals(WorldDimension.getBaseWorld(bedLocation.getWorld()))) { // if bed location is in another "server"
                        bedLocation = null;
                    }
                }

                if (bedLocation != null) {
                    e.setRespawnLocation(bedLocation);
                } else {
                    e.setRespawnLocation(BukkitUtilities.getSafeLocation(world.getSpawnLocation()));
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent e) {
        World world = WorldDimension.getBaseWorld(e.getPlayer().getWorld());

        if (module.isHomeWorld(world)) {
            Set<Player> recipients = e.getRecipients();
            recipients.clear();
            recipients.addAll(WorldDimension.getPlayersAllDimensions(world));
        }
    }

    public void checkWorldForUnloadDelayed(final World world) {
        Bukkit.getScheduler().runTaskLater(module.getPlugin(), new Runnable() {
            @Override
            public void run() {
                checkWorldForUnload(world);
            }
        }, 5L);
    }

    public void checkWorldForUnload(World world) {
        if (module.isHomeWorld(world) && WorldDimension.getPlayersAllDimensions(world).size() == 0) {
            Bukkit.unloadWorld(world, true); // unloads other dimensions too
        }
    }

    public void leftWorld(Player player, World baseWorld) {
        if (module.isHomeWorld(baseWorld)) {
            BukkitUtilities.broadcast(WorldDimension.getPlayersAllDimensions(baseWorld), player, langPlayerLeftHome.getValue(player.getName()));

            if (module.getHomeOwner(baseWorld).equals(player.getUniqueId())) {
                ownerLeftWorld(player, baseWorld);
            }
        }

        checkWorldForUnloadDelayed(baseWorld);
    }

    public void ownerLeftWorld(Player owner, World world) {
        Location spawnLocation = Bukkit.getWorlds().get(0).getSpawnLocation();

        for (Player p : world.getPlayers()) {
            if (p != owner) {
                p.teleport(spawnLocation, PlayerTeleportEvent.TeleportCause.PLUGIN); // put back in chat mode
            }
        }
    }

    public void refreshPlayerVisibilities(Player player, World baseWorld) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p != player) {
                if (WorldDimension.getBaseWorld(p.getWorld()) != baseWorld) {
                    player.hidePlayer(p);
                    p.hidePlayer(player);
                } else {
                    player.showPlayer(p);
                    p.showPlayer(player);
                }
            }
        }
    }

    // fired when player is about to switch server
    @Override
    public void accept(Player player) {
        User user = userManager.getUser(player, false);
        if (user != null) {
            userManager.save(user);
        }
    }
}