package dev.sifucraft.blocks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.sifucraft.storage.AtomicFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

/** Exports every installed block and its states, including reasons for redstone review. */
public final class RedstoneCatalog {
    private RedstoneCatalog() {}
    private static final List<String> CLASSES = List.of("diode", "redstone", "button", "pressureplate", "tripwire",
            "lever", "piston", "observer", "dispenser", "dropper", "hopper", "crafter", "rail", "daylight",
            "sculk", "target", "noteblock", "bell", "tnt", "door", "fencegate", "lectern", "jukebox",
            "commandblock", "structureblock", "lightningrod");
    private static final List<String> PROPERTIES = List.of("powered", "power", "triggered", "signal_fire");

    /** Review boundaries, never a claim that every state/interaction was tested. */
    public static String nativeBoundary(Block block){
        return switch(block.getClass().getSimpleName()){
            case "PistonBaseBlock","PistonHeadBlock","MovingPistonBlock" -> "qualified_native_motion_only";
            case "PressurePlateBlock","WeightedPressurePlateBlock","TripWireBlock","TripWireHookBlock" -> "native_proxy_contacts";
            case "RailBlock","PoweredRailBlock","DetectorRailBlock" -> "mc_minecarts_no_native_riders";
            case "SculkSensorBlock","CalibratedSculkSensorBlock","SculkShriekerBlock","SculkCatalystBlock","SculkBlock","SculkVeinBlock","CreakingHeartBlock" -> "mc_events_and_mobs_no_native_combat_events";
            case "CommandBlock","StructureBlock","RespawnAnchorBlock","EndPortalFrameBlock" -> "mc_permissions_and_dimensions_only";
            case "DispenserBlock","DropperBlock","TntBlock" -> "allowlisted_machine_effects_only";
            case "TargetBlock" -> "mc_projectiles_only";
            case "BeehiveBlock","ComposterBlock","CampfireBlock","LightningRodBlock","WeatheringLightningRodBlock" -> "mc_ecology_and_weather_only";
            case "ChestBlock","TrappedChestBlock","CopperChestBlock","WeatheringCopperChestBlock","BarrelBlock","ShulkerBoxBlock","ShelfBlock","ChiseledBookShelfBlock","HopperBlock","CrafterBlock","FurnaceBlock","BlastFurnaceBlock","SmokerBlock","BrewingStandBlock","DecoratedPotBlock","LecternBlock","JukeboxBlock" -> "original_mc_inventory_and_audio";
            case "ButtonBlock","LeverBlock","DoorBlock","TrapDoorBlock","WeatheringCopperDoorBlock","WeatheringCopperTrapDoorBlock","FenceGateBlock","CopperBulbBlock","WeatheringCopperBulbBlock","RedstoneTorchBlock","RedstoneWallTorchBlock","RepeaterBlock","ComparatorBlock","RedstoneWireBlock","RedstoneLampBlock","PoweredBlock","ObserverBlock","DaylightDetectorBlock","NoteBlock","BellBlock","RedStoneOreBlock","CandleCakeBlock","CakeBlock","CauldronBlock","LayeredCauldronBlock","LavaCauldronBlock","CopperGolemStatueBlock","WeatheringCopperGolemStatueBlock","SkullBlock","WallSkullBlock","PlayerHeadBlock","PlayerWallHeadBlock","WitherSkullBlock","WitherWallSkullBlock","PiglinWallSkullBlock" -> "original_block_signals_no_native_world_mutation";
            default -> reasons(block).isEmpty()?"outside_redstone_review":"unreviewed_candidate";
        };
    }

    public static List<String> reasons(Block block) {
        var reasons = new ArrayList<String>();
        for (Class<?> type = block.getClass(); type != null && type != Block.class; type = type.getSuperclass()) {
            String name = type.getSimpleName().toLowerCase(Locale.ROOT);
            for (String hint : CLASSES) if (name.contains(hint)) { reasons.add("class:" + type.getSimpleName()); break; }
        }
        var state = block.defaultBlockState();
        if (state.isSignalSource()) reasons.add("signal_source");
        if (state.hasAnalogOutputSignal()) reasons.add("analog_output");
        for (var property : state.getProperties())
            if (PROPERTIES.contains(property.getName())) reasons.add("property:" + property.getName());
        return List.copyOf(reasons);
    }

    private static <T extends Comparable<T>> JsonArray values(Property<T> property) {
        var values = new JsonArray();
        property.getPossibleValues().forEach(value -> values.add(property.getName(value)));
        return values;
    }

    public static JsonObject create() {
        var result = new JsonObject();
        result.addProperty("schema", 2);
        result.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().name());
        result.addProperty("status", "inventory_only_not_acceptance");
        result.addProperty("boundary_status","installed_class_review_not_behavior_acceptance");
        var boundaries=new java.util.TreeMap<String,Integer>();
        var blocks = new JsonArray(); int candidates = 0, stateCount = 0;
        var sorted = new ArrayList<Block>(); BuiltInRegistries.BLOCK.forEach(sorted::add);
        sorted.sort(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()));
        for (var block : sorted) {
            var row = new JsonObject(); var reasons = reasons(block);
            row.addProperty("id", BuiltInRegistries.BLOCK.getKey(block).toString());
            row.addProperty("class", block.getClass().getName());
            row.addProperty("states", block.getStateDefinition().getPossibleStates().size());
            row.addProperty("default_state", BlockStateKey.encode(block.defaultBlockState()));
            row.addProperty("block_entity", block.defaultBlockState().hasBlockEntity());
            row.addProperty("redstone_candidate", !reasons.isEmpty());
            String boundary=nativeBoundary(block);row.addProperty("native_boundary",boundary);
            if(!reasons.isEmpty())boundaries.merge(boundary,1,Integer::sum);
            var reasonArray = new JsonArray(); reasons.forEach(reasonArray::add); row.add("reasons", reasonArray);
            var properties = new JsonObject();
            for (var property : block.getStateDefinition().getProperties()) {
                properties.add(property.getName(), values(property));
            }
            row.add("properties", properties); blocks.add(row);
            if (!reasons.isEmpty()) candidates++;
            stateCount += block.getStateDefinition().getPossibleStates().size();
        }
        result.addProperty("block_count", sorted.size()); result.addProperty("state_count", stateCount);
        result.addProperty("candidate_count", candidates); result.add("blocks", blocks);
        var boundaryCounts=new JsonObject();boundaries.forEach(boundaryCounts::addProperty);result.add("candidate_native_boundaries",boundaryCounts);
        var entities = new JsonArray();
        BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Object::toString).sorted()
                .filter(id -> id.contains("minecart") || id.contains("boat") || id.contains("raft"))
                .forEach(entities::add);
        result.add("vehicle_review", entities);
        return result;
    }

    public static void write(Path target) throws IOException {
        var json = create();
        AtomicFiles.write(target, out -> new GsonBuilder().setPrettyPrinting().create().toJson(json, out));
    }
}
