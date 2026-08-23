package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.territorycontrol.api.TerritoryControlApi;
import com.arxyt.territorycontrolcompat.data.CompatSavedData;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * One precompiled block classifier shared by all placement and protection hooks. Registry names
 * are resolved once after registration; the setBlock hot path performs identity lookup and bit tests.
 */
public final class CompatBlockPolicy {
    private static final int CAERULA_CONTROLLED = 1;
    private static final int CAERULA_POLLUTION = 1 << 1;
    private static final int EYES_REPLACEMENT = 1 << 2;
    private static final int PHAYRIOSIS = 1 << 3;
    private static final int SPORE_FUNGAL = 1 << 4;
    private static final int SPORE_SPAWNER = 1 << 5;
    private static final int EXEMPT_MASK = CAERULA_POLLUTION | EYES_REPLACEMENT | PHAYRIOSIS | SPORE_FUNGAL;
    private static volatile Reference2IntOpenHashMap<Block> policies;

    private CompatBlockPolicy() {
    }

    public static void compile() {
        Reference2IntOpenHashMap<Block> compiled = new Reference2IntOpenHashMap<>();
        compiled.defaultReturnValue(0);
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            BlockState state = block.defaultBlockState();
            int mask = 0;
            if (CaerulaArborCompat.isControlledBlock(state)) mask |= CAERULA_CONTROLLED;
            if (CaerulaArborCompat.isTidePollutionBlock(state)) mask |= CAERULA_POLLUTION;
            if (EyesCompat.isReplacementBlock(state)) mask |= EYES_REPLACEMENT;
            if (PhayriosisCompat.isPhayriosisBlock(state)) mask |= PHAYRIOSIS;
            if (SporeCompat.isFungalInfectionBlock(state)) mask |= SPORE_FUNGAL;
            if (SporeCompat.isOvergrownSpawner(state)) mask |= SPORE_SPAWNER;
            if (mask != 0) compiled.put(block, mask);
        }
        policies = compiled;
    }

    public static boolean allowPlacement(LevelAccessor level, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server)) return true;
        int mask = policy(state.getBlock());
        if (mask == 0) return true;
        CompatSavedData.Config config = CompatSavedData.get(server).config();
        if (!config.hasPlacementRestrictions()) return true;

        boolean caerula = config.restrictCaerula() && (mask & CAERULA_CONTROLLED) != 0;
        boolean eyes = config.restrictEyes() && (mask & EYES_REPLACEMENT) != 0;
        boolean phayriosis = config.restrictPhayriosis() && (mask & PHAYRIOSIS) != 0;
        boolean spore = (config.restrictSporeInfectionSpread() && (mask & SPORE_FUNGAL) != 0)
                || (config.restrictSporeSpawnerStructures() && (mask & SPORE_SPAWNER) != 0);
        if (!caerula && !eyes && !phayriosis && !spore) return true;
        String modId = caerula ? CaerulaArborCompat.MOD_ID
                : eyes ? EyesCompat.MOD_ID
                : phayriosis ? PhayriosisCompat.MOD_ID
                : SporeCompat.MOD_ID;
        return TerritoryControlApi.isOwnedByModFaction(server, pos, modId);
    }

    public static boolean protectionExempt(LevelAccessor level, BlockPos pos, BlockState oldState, BlockState newState) {
        return (policy(oldState.getBlock()) & EXEMPT_MASK) != 0;
    }

    static int policy(Block block) {
        Reference2IntOpenHashMap<Block> snapshot = policies;
        if (snapshot == null) {
            synchronized (CompatBlockPolicy.class) {
                if (policies == null) compile();
                snapshot = policies;
            }
        }
        return snapshot.getInt(block);
    }
}
