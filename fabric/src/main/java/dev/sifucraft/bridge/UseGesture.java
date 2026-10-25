package dev.sifucraft.bridge;

/** One physical use gesture has one owner, including repeats after a refused native request. */
public final class UseGesture {
    private int owner = -1;
    private boolean observed;
    public void press() { owner = -1; observed = true; }
    public void reset() { owner = -1; observed = false; }
    public void invalidateScope() { if (!nativeOwned()) reset(); }
    public boolean observed() { return observed; }
    public boolean decided() { return owner != -1; }
    public boolean nativeOwned() { return owner == 1; }
    public void decide(boolean nativeOwner) { if (owner == -1) owner = nativeOwner ? 1 : 0; }
}
