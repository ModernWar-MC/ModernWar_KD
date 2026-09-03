package com.ming.modernwar_kd;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = Modernwar_kd.MODID)
public class CombatEventHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, List<DamageRecord>> RECENT_DAMAGE = new ConcurrentHashMap<>();

    private record DamageRecord(UUID attackerUUID, long timestamp) {}

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;

        DamageSource source = event.getSource();
        if (source == null) return;

        LivingEntity attacker = source.getEntity() instanceof LivingEntity le ? le : null;
        if (attacker == null) return;
        if (attacker.getUUID().equals(victim.getUUID())) return;

        if (!(attacker instanceof Player)) return;

        UUID victimUUID = victim.getUUID();
        UUID attackerUUID = attacker.getUUID();

        RECENT_DAMAGE.computeIfAbsent(victimUUID, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new DamageRecord(attackerUUID, System.currentTimeMillis()));
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) return;

        UUID victimUUID = victim.getUUID();
        DamageSource source = event.getSource();

        LivingEntity killer = null;
        if (source != null && source.getEntity() instanceof LivingEntity le && le instanceof Player) {
            killer = le;
        }

        Set<UUID> processedAttackers = new HashSet<>();

        // About death counting: if onlyPvP is enabled and the death was NOT caused
        // by another player, we do not count kills/assists. If there is no player attacker
        // at all, we only count deaths when onlyPvP is disabled (so the KD stays meaningful).
        if (Config.onlyPvP && killer == null) {
            return;
        }

        PlayerStatsManager.addDeath(victimUUID);

        if (killer != null) {
            UUID killerUUID = killer.getUUID();
            PlayerStatsManager.addKill(killerUUID);
            processedAttackers.add(killerUUID);
        }

        List<DamageRecord> records = RECENT_DAMAGE.remove(victimUUID);
        if (records != null) {
            long now = System.currentTimeMillis();
            for (DamageRecord record : records) {
                if (now - record.timestamp() <= Config.assistWindowMs
                        && !processedAttackers.contains(record.attackerUUID())) {
                    PlayerStatsManager.addAssist(record.attackerUUID());
                    processedAttackers.add(record.attackerUUID());
                }
            }
        }

        PlayerStats victimStats = PlayerStatsManager.getStats(victimUUID);
        LOGGER.info("ModernWar_KD: Player {} died. [K: {}, A: {}, D: {}, KD: {}]",
                victim.getScoreboardName(),
                victimStats.getKills(), victimStats.getAssists(),
                victimStats.getDeaths(), String.format("%.2f", victimStats.getKD()));
    }
}
