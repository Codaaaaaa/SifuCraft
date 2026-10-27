package dev.sifucraft.combat;

/** Deferred contact bookkeeping shared by the original mob and player integrators. */
public interface MotionPrediction {
    void beginPrediction();
    boolean clipped(int axis);
    void reconcileFall(double actualY,boolean grounded);
    VanillaFluidBody.Landing consumeLanding();
}
