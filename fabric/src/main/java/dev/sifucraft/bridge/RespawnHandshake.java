package dev.sifucraft.bridge;

/** Death -> delayed respawn -> scoped anchor request -> landing. Contains no game API calls. */
public final class RespawnHandshake {
    public enum Phase { ALIVE, DEAD_WAIT, RESPAWN_SENT, REQUESTING, LANDING }
    public enum Action { NONE, DIED, RESPAWN, REQUEST, ACKNOWLEDGED, LANDED }
    private Phase phase = Phase.ALIVE;
    private long deathMs, lastSendMs, sourceSession;
    private int requestWorld, requestEpoch;
    private boolean deathScreen;

    public Phase phase() { return phase; }
    public boolean ready() { return phase == Phase.ALIVE; }
    public boolean mayPlace() { return phase == Phase.ALIVE || phase == Phase.LANDING; }
    public int requestEpoch() { return requestEpoch; }
    public int flags() {
        return Protocol.MC_READY | switch (phase) {
            case DEAD_WAIT, RESPAWN_SENT -> Protocol.MC_DEAD;
            case REQUESTING -> Protocol.MC_REQUEST_ANCHOR;
            default -> 0;
        };
    }

    private void request(long session, SifuFrame frame) {
        sourceSession = session;
        requestWorld = frame == null ? 0 : frame.worldGeneration();
        requestEpoch = frame == null ? 0 : frame.anchorEpoch();
        phase = Phase.REQUESTING;
    }

    public Action tick(long nowMs, boolean connected, long session, SifuFrame frame,
                       boolean present, boolean dead, boolean anchored) {
        return tick(nowMs, connected, session, frame, present, dead, anchored, false);
    }
    /** {@code clickToRespawn}: vanilla's death screen is shown in Sifu and respawns on the player's click. */
    public Action tick(long nowMs, boolean connected, long session, SifuFrame frame,
                       boolean present, boolean dead, boolean anchored, boolean clickToRespawn) {
        if (!connected) return Action.NONE;
        if (present && dead && clickToRespawn && phase == Phase.ALIVE) { deathScreen = true; return Action.NONE; }
        if (present && !dead && deathScreen) {
            deathScreen = false;
            request(session, frame);
            return Action.REQUEST;
        }
        if (present && dead) {
            deathScreen = false;
            if (phase != Phase.DEAD_WAIT && phase != Phase.RESPAWN_SENT) {
                phase = Phase.DEAD_WAIT; deathMs = nowMs;
                return Action.DIED;
            }
            if (nowMs - deathMs >= 1000 && (phase == Phase.DEAD_WAIT || nowMs - lastSendMs >= 2000)) {
                lastSendMs = nowMs; phase = Phase.RESPAWN_SENT;
                return Action.RESPAWN;
            }
            return Action.NONE;
        }
        if (!present) return Action.NONE; // respawn may replace LocalPlayer asynchronously
        if (phase == Phase.DEAD_WAIT || phase == Phase.RESPAWN_SENT) {
            request(session, frame);
            return Action.REQUEST;
        }
        if (phase == Phase.REQUESTING) {
            if (frame == null || !frame.hasPawn()) return Action.NONE;
            if (sourceSession != session || requestWorld != frame.worldGeneration()) {
                request(session, frame);
                return Action.REQUEST;
            }
            if (frame.anchorEpoch() != 0 && frame.anchorEpoch() != requestEpoch) {
                phase = Phase.LANDING;
                return Action.ACKNOWLEDGED;
            }
        } else if (phase == Phase.LANDING) {
            if (frame == null || !frame.hasPawn()) return Action.NONE;
            if (sourceSession != session || requestWorld != frame.worldGeneration()) {
                request(session, frame);
                return Action.REQUEST;
            }
            if (anchored) { phase = Phase.ALIVE; return Action.LANDED; }
        }
        return Action.NONE;
    }
}
