package dev.sifucraft.combat;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

public final class EnemyEntities {
    private EnemyEntities() {}

    public static final EntityType<EnemyProxy> ENEMY_PROXY = Registry.register(BuiltInRegistries.ENTITY_TYPE,
            Identifier.fromNamespaceAndPath("sifucraft", "enemy_proxy"),
            EntityType.Builder.<EnemyProxy>of(EnemyProxy::new, MobCategory.MISC).sized(0.7f, 1.84f)
                    .noSave().noSummon().noLootTable().fireImmune().clientTrackingRange(8).updateInterval(1)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath("sifucraft", "enemy_proxy"))));

    public static void register() {
        FabricDefaultAttributeRegistry.register(ENEMY_PROXY, Mob.createMobAttributes());
    }
}
