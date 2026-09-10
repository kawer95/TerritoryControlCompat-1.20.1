package com.arxyt.territorycontrolcompat.compat;

import com.arxyt.ratnations.api.RatNationsFactionApi;
import com.arxyt.ratnations.api.RatNationsCivilianApi;
import com.arxyt.territorycontrol.core.data.Faction;
import com.arxyt.territorycontrolcompat.config.RatNationsCivilianConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Entity;

import java.util.LinkedHashSet;
import java.util.Set;

/** Bridges only an unambiguous TC external binding to an individual mutable civilian. */
public final class RatNationsCivilianControlCompat {
    private RatNationsCivilianControlCompat(){}
    public static void onOwnershipChanged(ServerLevel level,ChunkPos chunk,Faction previous,Faction current){
        if(!RatNationsCivilianConfig.civilianControlAffiliationEnabled()||current==null||!level.hasChunk(chunk.x,chunk.z))return;
        Set<ResourceLocation> nations=new LinkedHashSet<>();
        for(String key:current.externalBindingKeys(RatNationsFactionProvider.PROVIDER_ID))try{
            ResourceLocation id=new ResourceLocation(key);if(RatNationsFactionApi.isKnownRatFaction(id))nations.add(id);
        }catch(RuntimeException ignored){}
        if(nations.size()!=1)return;
        ResourceLocation nation=nations.iterator().next();
        AABB box=new AABB(chunk.getMinBlockX(),level.getMinBuildHeight(),chunk.getMinBlockZ(),chunk.getMaxBlockX()+1,level.getMaxBuildHeight(),chunk.getMaxBlockZ()+1);
        level.getEntitiesOfClass(Entity.class,box,civilian->civilian.isAlive()&&RatNationsCivilianApi.canChangeAffiliationFromControl(civilian))
                .forEach(civilian->RatNationsCivilianApi.setAffiliation(civilian,nation));
    }
}
