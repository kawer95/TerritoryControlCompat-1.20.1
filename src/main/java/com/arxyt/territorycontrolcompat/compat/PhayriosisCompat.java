package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

public final class PhayriosisCompat {
    public static final String MOD_ID = "phayriosis";
    private static final Set<String> REPLACEMENT = Set.of("primitive_phayrilesh", "dormant_primitive_phayrilesh",
            "fertile_primitive_pharilesh", "sappy_phayrilesh", "putridphayrilesh", "primitive_pharium",
            "dormant_primitive_pharium", "dontfloatpharium", "sappyphayrium", "putridphayrium",
            "primitive_phayrossen", "dormant_primitive_phayrossen", "packed_primitive_phayrossen",
            "dormant_packed_phayrossen", "unstablephayrossen", "primitive_infected_coal_ore",
            "primitive_infected_copper_ore", "primitive_infected_diamond_ore", "primitive_infected_emerald_ore",
            "primitive_infected_gold_ore", "primitive_infected_iron_ore", "primitive_infected_lapis_lazuli_ore",
            "primitive_infected_redstone_ore", "assimilated_log", "assimilated_planks", "assimlated_ice",
            "assimlatedwood_planks", "contaminated_soil", "witherack", "tough_witherack", "molten_witherack",
            "seared_withering_nylium", "distorted_altered_nylium", "alterrack_tiles", "activealterracktiles",
            "soul_alterrack", "basalum", "infested_basalum", "gloomium", "pitchium");
    private static final Set<String> SMALL_INSECTS = Set.of("phayrectix", "phayrilesh_mite", "assimilated_mite",
            "alterack_mite", "siege_mite");
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private PhayriosisCompat() {
    }

    public static boolean allowBlockPlacement(LevelAccessor level, BlockPos pos, BlockState state) {
        return !(level instanceof ServerLevel server) || !isPhayriosisBlock(state)
                || !CompatSavedData.get(server).config().restrictPhayriosis()
                || TerritoryControlApi.isOwnedByModFaction(server, pos, MOD_ID);
    }

    public static boolean sourceCanSpread(LevelAccessor level, double x, double y, double z) {
        return !(level instanceof ServerLevel server) || !CompatSavedData.get(server).config().restrictPhayriosis()
                || TerritoryControlApi.isOwnedByModFaction(server, BlockPos.containing(x, y, z), MOD_ID);
    }

    public static boolean allowExpansionPlacement(LevelAccessor level, BlockPos pos) {
        return DEPTH.get() <= 0 || sourceCanSpread(level, pos.getX(), pos.getY(), pos.getZ());
    }

    public static void beginExpansion() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void endExpansion() {
        int remaining = DEPTH.get() - 1;
        if (remaining <= 0) DEPTH.remove(); else DEPTH.set(remaining);
    }

    public static boolean isPhayriosisBlock(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && MOD_ID.equals(id.getNamespace());
    }

    public static boolean isSmallInsect(EntityType<?> type) {
        return isSmallInsectId(ForgeRegistries.ENTITY_TYPES.getKey(type));
    }

    static boolean isSmallInsectId(ResourceLocation id) {
        return id != null && MOD_ID.equals(id.getNamespace()) && SMALL_INSECTS.contains(id.getPath());
    }

    public static TerritoryControlApi.TerrainCleanupProfile terrainCleanupProfile() {
        return new TerritoryControlApi.TerrainCleanupProfile(MOD_ID,
                level -> CompatSavedData.get(level).config().phayriosisCure(),
                (level, pos, state) -> replacementFor(state));
    }

    private static BlockState replacementFor(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !MOD_ID.equals(id.getNamespace())) return null;
        return REPLACEMENT.contains(id.getPath()) ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }
}
