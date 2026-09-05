package com.ming.modernwar_kd;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * Soft-dependency integration with the LuckPerms plugin.
 *
 * Checks whether a player may see match-info broadcasts. The check walks this
 * chain until one provider answers:
 *
 *   1. LuckPerms run as a Forge MOD (API visible to the mod classloader).
 *   2. LuckPerms run as a BUKKIT PLUGIN on a hybrid server (Forge mods +
 *      Spigot plugins, e.g. Mohist/Arclight/Cardboard). Plugins have their
 *      own classloaders, so we reach org.bukkit.Bukkit via reflection and
 *      load the LuckPerms API through the plugin's own classloader.
 *   3. Forge's own PermissionAPI (our node is registered via
 *      PermissionGatherEvent.Nodes; covers Forge permission handlers).
 *   4. Vanilla server operator (permission level 2).
 *
 * The permission node checked is Config.viewPermission (default
 * "modernwar_kd.match.view"); grant it to the LuckPerms group that should
 * see match info.
 */
@Mod.EventBusSubscriber(modid = Modernwar_kd.MODID)
public final class LuckPermsHelper {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Forge PermissionAPI node backing the fallback path. */
    public static final PermissionNode<Boolean> MATCH_VIEW_NODE = new PermissionNode<>(
            Modernwar_kd.MODID, "match.view", PermissionTypes.BOOLEAN,
            (player, uuid, context) -> false);

    private LuckPermsHelper() {}

    /** Register our permission node with Forge's permission system. */
    @SubscribeEvent
    public static void onGatherPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(MATCH_VIEW_NODE);
        LOGGER.info("ModernWar_KD: 已注册 Forge 权限节点: {}", MATCH_VIEW_NODE.getNodeName());
    }

    /** Whether the player may see match-info broadcasts. */
    public static boolean canViewMatchInfo(ServerPlayer player) {
        String node = Config.viewPermission;
        String name = player.getScoreboardName();

        // 1) LuckPerms as a Forge mod
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Boolean result = checkViaLuckPermsProvider(providerClass, player.getUUID(), node);
            if (result != null) {
                LOGGER.debug("ModernWar_KD: LuckPerms(模组版) 权限检查 {} -> {}", name, result);
                return result;
            }
        } catch (ClassNotFoundException ignored) {
            // not on the mod classpath
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("ModernWar_KD: LuckPerms(模组版) 反射调用失败: {}", e.getMessage());
        }

        // 2) LuckPerms as a Bukkit plugin (hybrid server)
        try {
            Boolean result = checkViaBukkitPlugin(player.getUUID(), node);
            if (result != null) {
                LOGGER.debug("ModernWar_KD: LuckPerms(插件版) 权限检查 {} -> {}", name, result);
                return result;
            }
        } catch (ClassNotFoundException ignored) {
            // no CraftBukkit API on the classpath (pure Forge) -> keep walking
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("ModernWar_KD: LuckPerms(插件版) 反射调用失败: {}", e.getMessage());
        }

        // 3) Forge PermissionAPI
        try {
            boolean allowed = Boolean.TRUE.equals(PermissionAPI.getPermission(player, MATCH_VIEW_NODE));
            LOGGER.debug("ModernWar_KD: Forge 权限检查 {} -> {}", name, allowed);
            return allowed;
        } catch (Exception e) {
            LOGGER.warn("ModernWar_KD: Forge PermissionAPI 检查失败: {}", e.getMessage());
        }

        // 4) ultimate fallback: server operator
        return player.hasPermissions(2);
    }

    /**
     * Reach the LuckPerms Bukkit plugin through org.bukkit.Bukkit and load the
     * LuckPerms API using the plugin's own ClassLoader (the mod classloader
     * cannot see plugin classes on hybrid servers).
     *
     * @return TRUE/FALSE if the player was found, or null if no LuckPerms
     *         plugin was loaded / user not cached, so the chain can continue.
     */
    @SuppressWarnings("unused")
    private static Boolean checkViaBukkitPlugin(UUID uuid, String node) throws ReflectiveOperationException {
        Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
        Object server = bukkitClass.getMethod("getServer").invoke(null);
        if (server == null) return null;
        Object pluginManager = server.getClass().getMethod("getPluginManager").invoke(server);
        Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class)
                .invoke(pluginManager, "LuckPerms");
        if (plugin == null) {
            LOGGER.debug("ModernWar_KD: 未找到插件版 LuckPerms，跳过。");
            return null;
        }
        ClassLoader pluginCl = plugin.getClass().getClassLoader();
        Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider", true, pluginCl);
        return checkViaLuckPermsProvider(providerClass, uuid, node);
    }

    /**
     * Shared LuckPerms API lookup (used for both the mod and the plugin version).
     *
     * @return TRUE/FALSE if the user could be resolved, null if not.
     */
    private static Boolean checkViaLuckPermsProvider(Class<?> providerClass, UUID uuid, String node)
            throws ReflectiveOperationException {
        Object api = providerClass.getMethod("get").invoke(null);
        Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
        Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, uuid);
        if (user == null) {
            LOGGER.debug("ModernWar_KD: LuckPerms 未缓存玩家 {}", uuid);
            return null;
        }
        Object cached = user.getClass().getMethod("getCachedData").invoke(user);
        Object permData = cached.getClass().getMethod("getPermissionData").invoke(cached);
        Object triState = permData.getClass().getMethod("checkPermission", String.class)
                .invoke(permData, node);
        return triState != null && triState.toString().equalsIgnoreCase("TRUE");
    }
}