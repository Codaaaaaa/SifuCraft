package dev.sifucraft.blocks;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

public final class SignalEntities {
    private SignalEntities(){}
    public static final EntityType<PlayerSignalBody> PLAYER_BODY=Registry.register(BuiltInRegistries.ENTITY_TYPE,
        Identifier.fromNamespaceAndPath("sifucraft","player_signal_body"),
        EntityType.Builder.<PlayerSignalBody>of(PlayerSignalBody::new,MobCategory.MISC).sized(.6f,1.8f)
            .noSave().noSummon().noLootTable().fireImmune().clientTrackingRange(8).updateInterval(1)
            .build(ResourceKey.create(Registries.ENTITY_TYPE,Identifier.fromNamespaceAndPath("sifucraft","player_signal_body"))));
    public static void register(){FabricDefaultAttributeRegistry.register(PLAYER_BODY,Mob.createMobAttributes());}
}
