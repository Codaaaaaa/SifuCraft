package dev.sifucraft.blocks;

import java.util.concurrent.atomic.AtomicLong;

/** Derived geometry changes which do not change a saved BlockState (notably piston progress). */
public final class ShapeChanges {
    private ShapeChanges(){}
    private static final AtomicLong revision=new AtomicLong();
    public static long revision(){return revision.get();}
    public static void changed(){revision.incrementAndGet();}
}
