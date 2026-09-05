package com.ming.modernwar_kd;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Modernwar_kd.MODID)
public class MatchCommand {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("match")
                // /match start
                .then(Commands.literal("start")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> startMatch(ctx.getSource(), ctx.getSource().getServer())))

                // /match end <team>
                .then(Commands.literal("end")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("a")
                                .executes(ctx -> endMatch(ctx.getSource(), "a")))
                        .then(Commands.literal("b")
                                .executes(ctx -> endMatch(ctx.getSource(), "b")))
                        .executes(ctx -> endMatch(ctx.getSource(), "a")))

                // /match join <team> — join as self
                .then(Commands.literal("join")
                        .then(Commands.literal("a")
                                .executes(ctx -> joinMatch(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), "a")))
                        .then(Commands.literal("b")
                                .executes(ctx -> joinMatch(ctx.getSource(),
                                        ctx.getSource().getPlayerOrException(), "b"))))

                // /match join <team> <player> — assign another player (op)
                .then(Commands.literal("join")
                        .then(Commands.literal("a")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> joinMatch(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), "a"))))
                        .then(Commands.literal("b")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .requires(src -> src.hasPermission(2))
                                        .executes(ctx -> joinMatch(ctx.getSource(),
                                                EntityArgument.getPlayer(ctx, "player"), "b")))))

                // /match status
                .then(Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))

                // /match info
                .then(Commands.literal("info")
                        .executes(ctx -> showStatus(ctx.getSource())))
        );
    }

    private static int startMatch(CommandSourceStack source, net.minecraft.server.MinecraftServer server) {
        String operator = source.getEntity() instanceof ServerPlayer op ? op.getScoreboardName() : "Server";
        MatchManager.startMatch(server, operator);
        int count = MatchManager.getMatchPlayers().size();
        long teamB = MatchManager.getMatchPlayers().stream()
                .filter(uuid -> "b".equals(MatchManager.getTeam(uuid))).count();
        long teamA = count - teamB;
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7a对局已开始! \u00a7e%d 名玩家 \u00a77已记录。"
                        + "\u00a7bA 队 %d 人 \u00a77/ \u00a7cB 队 %d 人",
                        count, teamA, teamB)), false);
        return 1;
    }

    private static int endMatch(CommandSourceStack source, String team) {
        int uploaded = MatchManager.endMatch(team, source.getServer());
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7a对局已结束。 \u00a7e%s 队 \u00a77获胜。 \u00a7e%d 名玩家 \u00a77已上传到 API。",
                        team.toUpperCase(), uploaded)), false);
        return 1;
    }

    private static int joinMatch(CommandSourceStack source, ServerPlayer player, String team) {
        int result = MatchManager.addPlayerToMatch(player, team);
        if (result < 0) {
            source.sendSuccess(() -> Component.literal(
                    String.format("\u00a76[现代战争] \u00a7c当前没有进行中的对局或队伍无效。")), false);
            return 0;
        }
        if (result == 0) {
            source.sendSuccess(() -> Component.literal(
                    String.format("\u00a76[现代战争] \u00a7e%s \u00a77已在对局中。", player.getScoreboardName())), false);
            return 1;
        }
        String teamLabel = team.toUpperCase();
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[现代战争] \u00a7e%s \u00a77已加入 \u00a7b%s 队\u00a77。", player.getScoreboardName(), teamLabel)), false);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        if (!MatchManager.isMatchActive()) {
            source.sendSuccess(() -> Component.literal("\u00a76[现代战争] \u00a77当前没有进行中的对局。"), false);
            return 0;
        }

        long seconds = MatchManager.getMatchDuration() / 1000;
        source.sendSuccess(() -> Component.literal(String.format(
                "\u00a76[现代战争] \u00a7a对局进行中 (已进行 %d 秒) \u00a77- 共 %d 名玩家",
                seconds, MatchManager.getMatchPlayers().size())), false);

        for (var uuid : MatchManager.getMatchPlayers()) {
            var snap = MatchManager.getSnapshot(uuid);
            if (snap == null) continue;
            String team = MatchManager.getTeam(uuid).toUpperCase();
            source.sendSuccess(() -> Component.literal(String.format(
                    "\u00a7e  %s \u00a77(%s 队) \u00a77- 击杀:%d 死亡:%d",
                    snap.playerName(), team, snap.kills(), snap.deaths())), false);
        }
        return 1;
    }
}
