package dev.sifucraft.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class NativeInteractionTest {
    static void run() {
        var b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        b.putLong(16, 7).putInt(24, 3).putInt(28, 4).putInt(32, 1).putInt(36, 1001).putInt(40, 9);
        b.putFloat(56, 100).putFloat(60, -50).putFloat(64, 0).putFloat(68, 120).putFloat(72, 50).putFloat(76, 200).putFloat(92, 300);
        var candidate = NativeInteraction.decode(42, b.array(), 100);
        var request = ByteBuffer.wrap(candidate.request()).order(ByteOrder.LITTLE_ENDIAN);
        if (request.getLong() != 7 || request.getInt() != 1001 || request.getInt() != 9 ||
                request.getInt() != 3 || request.getInt() != 4 || request.getLong() != 0) throw new AssertionError("native request golden bytes");
        b.putFloat(56, Float.NaN);
        try { NativeInteraction.decode(42, b.array(), 100); throw new AssertionError("NaN candidate accepted"); }
        catch (IllegalArgumentException expected) { }
        var gesture = new UseGesture();
        if (gesture.observed()) throw new AssertionError("unobserved gesture");
        gesture.press(); gesture.decide(true); gesture.decide(false);
        if (!gesture.nativeOwned()) throw new AssertionError("native rejection/repeat fell back to item use");
        gesture.invalidateScope();
        if (!gesture.nativeOwned()) throw new AssertionError("scope invalidation reissued native gesture as vanilla use");
        gesture.press(); gesture.decide(false); gesture.decide(true);
        if (gesture.nativeOwned()) throw new AssertionError("held vanilla use was stolen");
        gesture.reset(); if (gesture.observed()) throw new AssertionError("old scope gesture survived");
        System.out.println("Native interaction wire/gesture tests PASS");
    }
}
