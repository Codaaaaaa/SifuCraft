package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class ShieldWireTest {
    static void run() {
        byte[] bytes = new PlayerBlocked(100, 42, 3, 7, 9, 6, Protocol.BLOCKED_PERFECT).encode();
        var b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length != 32 || b.getLong(0) != 100 || b.getInt(8) != 42 || b.getInt(12) != 3 ||
                b.getInt(16) != 7 || b.getInt(20) != 9 || b.getFloat(24) != 6 || b.getInt(28) != 1)
            throw new AssertionError("native shield reply layout");
        try { new PlayerBlocked(100, 42, 3, 7, 9, Float.NaN, 1).encode(); throw new AssertionError("NaN receipt"); }
        catch (IllegalArgumentException expected) { }
        var hit = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        hit.putInt(42).putInt(3).putFloat(54).putInt(0x20B).putInt(7).putInt(9).putFloat(1).putFloat(0);
        if (!PlayerHit.decode(hit.array()).perfectWindow() || PlayerHit.decode(hit.array()).attackPower() != 2)
            throw new AssertionError("perfect + strong native hit flags");
        if (PlayerHit.decode(hit.array()).guardBreaker()) throw new AssertionError("Strong is not GuardBreaker");
        hit.putInt(12, 0x21B);
        if (!PlayerHit.decode(hit.array()).guardBreaker() || !PlayerHit.decode(hit.array()).perfectWindow())
            throw new AssertionError("perfect GuardBreaker is independent of Strong");
        hit.putInt(12, Protocol.HIT_GUARD_BREAKER);
        if (!PlayerHit.decode(hit.array()).guardBreaker()) throw new AssertionError("unblockable GuardBreaker flag");
        hit.putInt(12, 0x20);
        try { PlayerHit.decode(hit.array()); throw new AssertionError("unknown hit flag"); }
        catch (IllegalArgumentException expected) { }
        hit.putInt(12, 8);
        try { PlayerHit.decode(hit.array()); throw new AssertionError("perfect without defend"); }
        catch (IllegalArgumentException expected) { }
        hit.putInt(12, 0x209);
        if (!PlayerHit.decode(hit.array()).perfectWindow() || (PlayerHit.decode(hit.array()).flags() & Protocol.HIT_PARRIABLE) != 0)
            throw new AssertionError("weapon perfect timing does not require Sifu's separate Parriable metadata");
        System.out.println("Shield wire/flags tests PASS");
    }
}
