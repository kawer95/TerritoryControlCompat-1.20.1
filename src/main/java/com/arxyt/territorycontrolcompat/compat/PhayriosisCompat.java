package com.arxyt.territorycontrolcompat.compat;
import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrol.core.data.Faction;
import com.arxyt.territorycontrol.core.data.TerritorySavedData;
import com.arxyt.territorycontrol.core.protection.BlockDamageProtection;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Set;

public final class PhayriosisCompat {
    public static final String MOD_ID = "phayriosis";
    private static final Set<String> REPLACEMENT = Set.of("primitive_phayrilesh", "dormant_primitive_phayrilesh", "fertile_primitive_pharilesh", "sappy_phayrilesh", "putridphayrilesh", "primitive_pharium", "dormant_primitive_pharium", "dontfloatpharium", "sappyphayrium", "putridphayrium", "primitive_phayrossen", "dormant_primitive_phayrossen", "packed_primitive_phayrossen", "dormant_packed_phayrossen", "unstablephayrossen", "primitive_infected_coal_ore", "primitive_infected_copper_ore", "primitive_infected_diamond_ore", "primitive_infected_emerald_ore", "primitive_infected_gold_ore", "primitive_infected_iron_ore", "primitive_infected_lapis_lazuli_ore", "primitive_infected_redstone_ore", "assimilated_log", "assimilated_planks", "assimlated_ice", "assimlatedwood_planks", "contaminated_soil", "witherack", "tough_witherack", "molten_witherack", "seared_withering_nylium", "distorted_altered_nylium", "alterrack_tiles", "activealterracktiles", "soul_alterrack", "basalum", "infested_basalum", "gloomium", "pitchium");
    private static final Set<String> SMALL_INSECTS = Set.of(
            "phayrectix", "phayrilesh_mite", "assimilated_mite", "alterack_mite", "siege_mite");
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private PhayriosisCompat() {}
    public static boolean allowBlockPlacement(LevelAccessor l, BlockPos p, BlockState s) { return !(l instanceof ServerLevel server) || !isPhayriosisBlock(s) || !CompatSavedData.get(server).config().restrictPhayriosis() || TerritoryControlApi.isOwnedByModFaction(server, p, MOD_ID); }
    public static boolean sourceCanSpread(LevelAccessor l, double x, double y, double z) { return !(l instanceof ServerLevel server) || !CompatSavedData.get(server).config().restrictPhayriosis() || TerritoryControlApi.isOwnedByModFaction(server, BlockPos.containing(x, y, z), MOD_ID); }
    public static boolean allowExpansionPlacement(LevelAccessor l, BlockPos p) { return DEPTH.get() <= 0 || sourceCanSpread(l, p.getX(), p.getY(), p.getZ()); }
    public static void beginExpansion() { DEPTH.set(DEPTH.get() + 1); }
    public static void endExpansion() { int n = DEPTH.get() - 1; if (n <= 0) DEPTH.remove(); else DEPTH.set(n); }
    public static boolean isPhayriosisBlock(BlockState s) { ResourceLocation id = ForgeRegistries.BLOCKS.getKey(s.getBlock()); return id != null && MOD_ID.equals(id.getNamespace()); }
    public static boolean isSmallInsect(EntityType<?> type) { return isSmallInsectId(ForgeRegistries.ENTITY_TYPES.getKey(type)); }
    static boolean isSmallInsectId(ResourceLocation id) { return id != null && MOD_ID.equals(id.getNamespace()) && SMALL_INSECTS.contains(id.getPath()); }
    public static void onOwnershipChanged(ServerLevel level, ChunkPos chunk, Faction previous, Faction current) {
        if (previous == null || current == null || !previous.ownsMod(MOD_ID) || !CompatSavedData.get(level).config().phayriosisCure() || !level.hasChunk(chunk.x, chunk.z)) return;
        TerritorySavedData data = TerritorySavedData.get(level);
        BlockDamageProtection.runUntracked(() -> { for (int x = chunk.getMinBlockX(); x < chunk.getMinBlockX() + 16; x++) for (int z = chunk.getMinBlockZ(); z < chunk.getMinBlockZ() + 16; z++) for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) { BlockPos pos = new BlockPos(x, y, z); BlockState state = level.getBlockState(pos); ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock()); if (id != null && MOD_ID.equals(id.getNamespace())) { level.setBlock(pos, REPLACEMENT.contains(id.getPath()) ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); data.removeProtectedBlock(level, pos); } }});
    }
}
