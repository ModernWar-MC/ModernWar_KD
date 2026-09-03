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
        MatchManager.startMatch(server);
        int count = MatchManager.getMatchPlayers().size();
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[ModernWar] \u00a7aMatch started! \u00a7e%d player(s) \u00a77recorded.",
                        count)), false);
        return 1;
    }

    private static int endMatch(CommandSourceStack source, String team) {
        int uploaded = MatchManager.endMatch(team, source.getServer());
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[ModernWar] \u00a7aMatch ended. \u00a7eTeam %s \u00a77wins. \u00a7e%d player(s) \u00a77uploaded to API.",
                        team.toUpperCase(), uploaded)), false);
        return 1;
    }

    private static int joinMatch(CommandSourceStack source, ServerPlayer player, String team) {
        int result = MatchManager.addPlayerToMatch(player, team);
        if (result < 0) {
            source.sendSuccess(() -> Component.literal(
                    String.format("\u00a76[ModernWar] \u00a7cNo active match or invalid team.")), false);
            return 0;
        }
        if (result == 0) {
            source.sendSuccess(() -> Component.literal(
                    String.format("\u00a76[ModernWar] \u00a7e%s \u00a77already in match.", player.getScoreboardName())), false);
            return 1;
        }
        String teamLabel = team.toUpperCase();
        source.sendSuccess(() -> Component.literal(
                String.format("\u00a76[ModernWar] \u00a7e%s \u00a77joined \u00a7bTeam %s\u00a77.", player.getScoreboardName(), teamLabel)), false);
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        if (!MatchManager.isMatchActive()) {
            source.sendSuccess(() -> Component.literal("\u00a76[ModernWar] \u00a77No active match."), false);
            return 0;
        }

        long seconds = MatchManager.getMatchDuration() / 1000;
        source.sendSuccess(() -> Component.literal(String.format(
                "\u00a76[ModernWar] \u00a7aMatch active (elapsed %d s) \u00a77- %d player(s)",
                seconds, MatchManager.getMatchPlayers().size())), false);

        for (var uuid : MatchManager.getMatchPlayers()) {
            var snap = MatchManager.getSnapshot(uuid);
            if (snap == null) continue;
            String team = MatchManager.getTeam(uuid).toUpperCase();
            source.sendSuccess(() -> Component.literal(String.format(
                    "\u00a7e  %s \u00a77(Team %s) \u00a77- K:%d D:%d",
                    snap.playerName(), team, snap.kills(), snap.deaths())), false);
        }
        return 1;
    }
}
