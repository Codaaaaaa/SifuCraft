package dev.sifucraft.combat;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** Installed boat/raft registry, including chest variants; no single-wood gameplay allowlist. */
public final class BoatCatalog {
    private BoatCatalog() {}
    public record Entry(Identifier id, EntityType<?> type) {}
    public static List<Entry> installed() {
        return BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                .filter(id -> id.getNamespace().equals("minecraft") &&
                        (id.getPath().endsWith("_boat") || id.getPath().endsWith("_raft")))
                .sorted(java.util.Comparator.comparing(Identifier::toString))
                .map(id -> new Entry(id,BuiltInRegistries.ENTITY_TYPE.getValue(id))).toList();
    }
}
