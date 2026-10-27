package dev.sifucraft.blocks;

import dev.sifucraft.bridge.EnemyFrame;
import dev.sifucraft.bridge.WorldMotionProtocol;
import dev.sifucraft.combat.EnemyMirrors;
import dev.sifucraft.combat.EnemyProxy;
import net.minecraft.world.entity.Entity;

/** Server-owned mechanical identities. The player sentinel is never an enemy-table entry on the wire. */
public final class NativeMotionSubjects {
    public static final EnemyFrame.Key PLAYER = new EnemyFrame.Key(0, 0);
    private NativeMotionSubjects() {}
    public static boolean player(EnemyFrame.Key key) { return PLAYER.equals(key); }
    public static EnemyFrame.Key key(Entity entity) {
        if (entity == null) return null;
        if (entity instanceof EnemyProxy proxy) return proxy.sifuKey();
        return PlayerSignalContacts.nativeBody() == entity ? PLAYER : null;
    }
    public static Entity find(EnemyFrame.Key key) {
        return player(key) ? PlayerSignalContacts.nativeBody() : EnemyMirrors.hitAttacker(key.id(), key.spawn());
    }
    public static boolean current(Entity entity) {
        var key = key(entity);
        return key != null && find(key) == entity && !entity.isRemoved();
    }
    public static EnemyFrame.Entry entry(WorldMotionWorld.View view, EnemyFrame.Key key) {
        if (player(key)) return PlayerSignalContacts.nativeEntry(view);
        return view.enemies().entries().stream().filter(e -> e.key().equals(key) && e.alive()).findFirst().orElse(null);
    }
    public static boolean playerReady(WorldMotionWorld.View view) {
        return view.nativeStatus() != null && (view.nativeStatus().capabilities() & WorldMotionProtocol.PLAYER_MACHINE_CAPABILITY) != 0 &&
            view.nativeStatus().player() != null && PlayerSignalContacts.nativeEntry(view) != null;
    }
}
