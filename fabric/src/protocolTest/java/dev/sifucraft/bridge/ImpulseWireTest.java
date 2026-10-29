package dev.sifucraft.bridge;

import java.util.Arrays;

final class ImpulseWireTest {
    static void run() {
        byte[] expected = {8,7,6,5,4,3,2,1, 42,0,0,0, 3,0,0,0, 7,0,0,0, 9,0,0,0,
                0,0,(byte)0x80,0x3f, 0,0,0,(byte)0xc0};
        if (!Arrays.equals(expected, new EnemyImpulse(0x0102030405060708L, 42, 3, 7, 9, 1, -2).encode()))
            throw new AssertionError("native impulse golden bytes");
        for (var invalid : new EnemyImpulse[]{new EnemyImpulse(0,42,3,7,9,1,0), new EnemyImpulse(1,42,0,7,9,1,0),
                new EnemyImpulse(1,42,3,0,9,1,0), new EnemyImpulse(1,42,3,7,0,1,0),
                new EnemyImpulse(1,42,3,7,9,Float.NaN,0), new EnemyImpulse(1,42,3,7,9,0,Float.POSITIVE_INFINITY),
                new EnemyImpulse(1,42,3,7,9,17,0), new EnemyImpulse(1,42,3,7,9,0,0)}) {
            try { invalid.encode(); throw new AssertionError("accepted invalid impulse " + invalid); }
            catch (IllegalArgumentException expectedException) { }
        }
        System.out.println("Impulse wire/bounds tests PASS");
    }
}
