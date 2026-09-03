package com.ming.modernwar_kd;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Forge's config APIs
@Mod.EventBusSubscriber(modid = Modernwar_kd.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    private static final ForgeConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ForgeConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), Config::validateItemName);

    // How long after being damaged by an attacker (in milliseconds) a kill is still counted as an assist for them.
    public static final ForgeConfigSpec.IntValue ASSIST_WINDOW_MS = BUILDER
            .comment("Time window (ms) in which a player who damaged the victim still counts as an assist.")
            .defineInRange("assistWindowMs", 5000, 1000, 30000);

    // Whether to only count player-vs-player combat (kills/assists/deaths by other players).
    public static final ForgeConfigSpec.BooleanValue ONLY_PVP = BUILDER
            .comment("Only record kills/assists/deaths when caused by another player. If false, mob kills also count deaths.")
            .define("onlyPvP", true);

    // API endpoint URL
    public static final ForgeConfigSpec.ConfigValue<String> API_URL = BUILDER
            .comment("API endpoint URL for uploading KD data.")
            .define("apiUrl", "http://127.0.0.1:3000/api/plugin/kd");

    // Season identifier
    public static final ForgeConfigSpec.ConfigValue<String> SEASON = BUILDER
            .comment("Current season identifier for API uploads.")
            .define("season", "2026_S3");

    // Rank label
    public static final ForgeConfigSpec.ConfigValue<String> RANK_LABEL = BUILDER
            .comment("Player rank label for API uploads.")
            .define("rankLabel", "钻石");

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean logDirtBlock;
    public static int magicNumber;
    public static String magicNumberIntroduction;
    public static Set<Item> items;
    public static int assistWindowMs;
    public static boolean onlyPvP;
    public static String apiUrl;
    public static String season;
    public static String rankLabel;

    private static boolean validateItemName(final Object obj)
    {
        return obj instanceof final String itemName && ForgeRegistries.ITEMS.containsKey(new ResourceLocation(itemName));
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        logDirtBlock = LOG_DIRT_BLOCK.get();
        magicNumber = MAGIC_NUMBER.get();
        magicNumberIntroduction = MAGIC_NUMBER_INTRODUCTION.get();

        // convert the list of strings into a set of items
        items = ITEM_STRINGS.get().stream()
                .map(itemName -> ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemName)))
                .collect(Collectors.toSet());

        assistWindowMs = ASSIST_WINDOW_MS.get();
        onlyPvP = ONLY_PVP.get();
        apiUrl = API_URL.get();
        season = SEASON.get();
        rankLabel = RANK_LABEL.get();
    }
}
