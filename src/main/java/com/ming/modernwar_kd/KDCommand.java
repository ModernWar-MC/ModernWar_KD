package com.ming.modernwar_kd;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Modernwar_kd.MODID)
public class KDCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        // /kd
        dispatcher.register(Commands.literal("kd")
                .executes(ctx -> showSelfStats(ctx.getSource()))
                // /kd <player>
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> showPlayerStats(ctx.getSource(),
                                EntityArgument.getPlayer(ctx, "player"))))
        );

        // /kd top [count]
        dispatcher.register(Commands.literal("kd").then(Commands.literal("top")
                .executes(ctx -> showTopKD(ctx.getSource(), 10))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                        .executes(ctx -> showTopKD(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"))))
        ));

        // /kd win [count]
        dispatcher.register(Commands.literal("kd").then(Commands.literal("win")
                .executes(ctx -> recordWin(ctx.getSource(), 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> recordWin(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"))))
        ));

        // /kd loss [count]
        dispatcher.register(Commands.literal("kd").then(Commands.literal("loss")
                .executes(ctx -> recordLoss(ctx.getSource(), 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> recordLoss(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"))))
        ));

        // /kd head [count]
        dispatcher.register(Commands.literal("kd").then(Commands.literal("head")
                .executes(ctx -> recordHead(ctx.getSource(), 1))
                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                        .executes(ctx -> recordHead(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "count"))))
        ));

        // /kd upload
        dispatcher.register(Commands.literal("kd").then(Commands.literal("upload")
                .executes(ctx -> uploadAll(ctx.getSource()))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> uploadPlayer(ctx.getSource(),
                                EntityArgument.getPlayer(ctx, "player"))))
        ));

        // /kd set <player> kills wins losses deaths heads assists
        dispatcher.register(Commands.literal("kd").then(Commands.literal("set")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("kills", IntegerArgumentType.integer(0))
                                .then(Commands.argument("wins", IntegerArgumentType.integer(0))
                                        .then(Commands.argument("losses", IntegerArgumentType.integer(0))
                                                .then(Commands.argument("deaths", IntegerArgumentType.integer(0))
                                                        .then(Commands.argument("heads", IntegerArgumentType.integer(0))
                                                                .then(Commands.argument("assists", IntegerArgumentType.integer(0))
                                                                        .executes(ctx -> setStats(ctx.getSource(),
                                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                                IntegerArgumentType.getInteger(ctx, "kills"),
                                                                                IntegerArgumentType.getInteger(ctx, "wins"),
                                                                                IntegerArgumentType.getInteger(ctx, "losses"),
                                                                                IntegerArgumentType.getInteger(ctx, "deaths"),
                                                                                IntegerArgumentType.getInteger(ctx, "heads"),
                                                                                IntegerArgumentType.getInteger(ctx, "assists")))
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                )
        ));
    }

    // ---- display commands ----

    private static int showSelfStats(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerStats stats = PlayerStatsManager.getStats(player.getUUID());
        sendStatsMessage(source, player.getScoreboardName(), stats);
        return 1;
    }

    private static int showPlayerStats(CommandSourceStack source, ServerPlayer target) {
        PlayerStats stats = PlayerStatsManager.getStats(target.getUUID());
        sendStatsMessage(source, target.getScoreboardName(), stats);
        return 1;
    }

    private static void sendStatsMessage(CommandSourceStack source, String name, PlayerStats stats) {
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7e%s \u00a77- 击杀: \u00a7c%d \u00a77助攻: \u00a7a%d \u00a77死亡: \u00a74%d \u00a77胜场: \u00a72%d \u00a77负场: \u00a7c%d \u00a77爆头: \u00a7e%d",
                        name, stats.getKills(), stats.getAssists(), stats.getDeaths(),
                        stats.getWins(), stats.getLosses(), stats.getHeads())), false);
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76         \u00a77场次: \u00a7b%d \u00a77| 击杀/死亡比: \u00a7b%.2f",
                        stats.getMatches(), stats.getApiKD())), false);
    }

    private static int showTopKD(CommandSourceStack source, int count) {
        List<Map.Entry<UUID, PlayerStats>> top = PlayerStatsManager.getTopKD(count);

        source.sendSuccess(() -> Component.literal("\u00a76\u00a7l===== 现代战争 KD 排行榜前 " + count + " ====="), false);

        int rank = 1;
        for (Map.Entry<UUID, PlayerStats> entry : top) {
            PlayerStats stats = entry.getValue();
            String name = source.getServer().getProfileCache()
                    .get(entry.getKey()).map(p -> p.getName()).orElse("?");
            String line = String.format("\u00a77#%d \u00a7e%s \u00a77- 击杀:\u00a7c%d 胜:\u00a72%d 负:\u00a7c%d 爆头:\u00a7e%d KD:\u00a7b%.2f",
                    rank++, name,
                    stats.getKills(), stats.getWins(), stats.getLosses(),
                    stats.getHeads(), stats.getApiKD());
            source.sendSuccess(() -> Component.literal(line), false);
        }

        if (top.isEmpty()) {
            source.sendSuccess(() -> Component.literal("\u00a77尚未有任何战绩记录。"), false);
        }
        return 1;
    }

    // ---- record commands ----

    private static int recordWin(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerStats stats = PlayerStatsManager.getStats(player.getUUID());
        stats.addWins(count);
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7a+%d 胜场 \u00a77已记录给 \u00a7e%s \u00a77\u2192 场次: %d, KD: %.2f",
                        count, player.getScoreboardName(), stats.getMatches(), stats.getApiKD())), false);
        return 1;
    }

    private static int recordLoss(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerStats stats = PlayerStatsManager.getStats(player.getUUID());
        stats.addLosses(count);
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7c+%d 负场 \u00a77已记录给 \u00a7e%s \u00a77\u2192 场次: %d, KD: %.2f",
                        count, player.getScoreboardName(), stats.getMatches(), stats.getApiKD())), false);
        return 1;
    }

    private static int recordHead(CommandSourceStack source, int count) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        PlayerStats stats = PlayerStatsManager.getStats(player.getUUID());
        stats.addHeads(count);
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7e+%d 爆头 \u00a77已记录给 \u00a7e%s",
                        count, player.getScoreboardName())), false);
        return 1;
    }

    // ---- upload commands ----

    private static int uploadAll(CommandSourceStack source) {
        var allStats = PlayerStatsManager.getAllStats();
        if (allStats.isEmpty()) {
            source.sendSuccess(() -> Component.literal("\u00a76[现代战争] \u00a77没有可上传的数据。"), false);
            return 0;
        }

        int count = 0;
        for (Map.Entry<UUID, PlayerStats> entry : allStats.entrySet()) {
            UUID uuid = entry.getKey();
            PlayerStats stats = entry.getValue();
            String name = source.getServer().getProfileCache()
                    .get(uuid).map(p -> p.getName()).orElse("?");
            KDApiClient.uploadPlayer(name, stats);
            count++;
        }

        int finalCount = count;
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7a正在向 API 上传 %d 名玩家的战绩...", finalCount)), false);
        return 1;
    }

    private static int uploadPlayer(CommandSourceStack source, ServerPlayer target) {
        PlayerStats stats = PlayerStatsManager.getStats(target.getUUID());
        String name = target.getScoreboardName();

        KDApiClient.uploadPlayer(name, stats).thenAccept(success -> {
            String msg = success
                    ? String.format("\u00a76[现代战争] \u00a7a已上传 \u00a7e%s \u00a7a\u2192 KD: %.2f", name, stats.getApiKD())
                    : String.format("\u00a76[现代战争] \u00a7c上传失败: \u00a7e%s", name);
            target.getServer().execute(() ->
                    source.sendSuccess(() -> Component.literal(msg), false));
        });

        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a77正在上传 \u00a7e%s \u00a77的战绩...", name)), false);
        return 1;
    }

    // ---- set command ----

    private static int setStats(CommandSourceStack source, ServerPlayer target,
                                 int kills, int wins, int losses,
                                 int deaths, int heads, int assists) {
        PlayerStats stats = PlayerStatsManager.getStats(target.getUUID());
        stats.setKills(kills);
        stats.setWins(wins);
        stats.setLosses(losses);
        stats.setDeaths(deaths);
        stats.setHeads(heads);
        stats.setAssists(assists);

        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7e%s \u00a77的战绩已设置 \u2192 击杀:%d 助攻:%d 死亡:%d 胜:%d 负:%d 爆头:%d KD:%.2f",
                        target.getScoreboardName(), kills, assists, deaths, wins, losses,
                        heads, stats.getApiKD())), false);
        return 1;
    }
}
