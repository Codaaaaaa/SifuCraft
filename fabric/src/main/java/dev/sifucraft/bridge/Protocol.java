package dev.sifucraft.bridge;

/**
 * Java mirror of {@code protocol/sifucraft_protocol.h}. Every constant and offset here must match
 * the C++ header; both sides hash {@link #LAYOUT_TABLE} and refuse a peer whose hash differs.
 * Positions, rotations and velocities are in Unreal space (cm, Z up, degrees).
 */
public final class Protocol {
    private Protocol() {}

    public static final int MAGIC = 0x52434653; // "SFCR"
    public static final int VERSION = 14;
    public static final String MAPPING_NAME = "Local\\SifuCraft.Bridge";
    public static final int MAPPING_SIZE = 0x10000;

    public static final int HEARTBEAT_TIMEOUT_MS = 2000;
    public static final int RING_CAPACITY = 256;
    public static final int MAX_BLOCKS = 1536;
    public static final int MAX_ENEMIES = 32;
    public static final float PLAYER_SIFU_HP_PER_MC_HP = 9.0f;
    public static final float ENEMY_SIFU_HP_PER_MC_HP = 3.0f;

    public static final int STATE_ABSENT = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_CLOSING = 2;

    public static final int EVENT_PING = 1;
    public static final int EVENT_PONG = 2;
    public static final int EVENT_KEY = 3;
    public static final int EVENT_MOUSE_BUTTON = 4;
    public static final int EVENT_OWNER_CHANGED = 5;
    public static final int EVENT_ENEMY_DAMAGE = 6;
    public static final int EVENT_DAMAGE_RESULT = 7;
    public static final int EVENT_PLAYER_HIT = 8;
    public static final int EVENT_GUI_MOUSE_MOTION = 9;
    public static final int GUI_MOUSE_SIZE = 24;
    public static final int HIT_CAN_DEFEND = 1;
    public static final int HIT_PARRIABLE = 1 << 1;
    public static final int HIT_CAN_BE_LETHAL = 1 << 2;
    public static final int DAMAGE_CRITICAL = 1;
    public static final int DAMAGE_SWEEP = 1 << 1;
    public static final int DAMAGE_PROJECTILE = 1 << 2;
    public static final int DAMAGE_SMASH = 1 << 3;
    public static final int DAMAGE_APPLIED = 0;
    public static final int DAMAGE_DEAD = 1;
    public static final int DAMAGE_INVINCIBLE = 2;
    public static final int DAMAGE_STALE = 3;
    public static final int DAMAGE_NOT_OWNED = 4;
    public static final int DAMAGE_INVALID = 5;
    public static final int DAMAGE_BUSY = 6;
    public static final int DAMAGE_UNAVAILABLE = 7;

    public static final int OWNER_SIFU = 0;
    public static final int OWNER_MINECRAFT = 1;

    public static final int SNAPSHOT_HAS_PAWN = 1;
    public static final int SNAPSHOT_HAS_CAMERA = 1 << 1;
    public static final int SNAPSHOT_GUI_AVAILABLE = 1 << 2;

    public static final int MC_READY = 1;
    public static final int MC_HAS_POSE = 1 << 1;
    public static final int MC_ON_GROUND = 1 << 2;
    public static final int MC_SNEAKING = 1 << 3;
    public static final int MC_SPRINTING = 1 << 4;
    public static final int MC_FLYING = 1 << 5;
    public static final int MC_REQUEST_ANCHOR = 1 << 6;
    public static final int MC_DEAD = 1 << 7;
    public static final int MC_GUI_AVAILABLE = 1 << 8;
    public static final int MC_GUI_OPEN = 1 << 9;

    public static final int HEADER_OFFSET = 0x0000;
    public static final int SIFU_STATUS_OFFSET = 0x0040;
    public static final int MC_STATUS_OFFSET = 0x0080;
    public static final int SIFU_SNAPSHOT_OFFSET = 0x0100;
    public static final int MC_SNAPSHOT_OFFSET = 0x0200;
    public static final int SIFU_TO_MC_RING_OFFSET = 0x0400;
    public static final int MC_TO_SIFU_RING_OFFSET = 0x4800;
    public static final int COLLISION_MANIFEST_OFFSET = 0x9000;
    public static final int BLOCK_TABLE_OFFSET = 0x9200;
    public static final int ENEMY_TABLE_OFFSET = 0xF300;

    public static final int ENEMY_ALIVE = 1;
    public static final int ENEMY_DOWN = 1 << 1;
    public static final int ENEMY_INVINCIBLE = 1 << 2;
    public static final int ENEMY_CAN_DIE = 1 << 3;

    /** ProtocolHeader. */
    public static final class Header {
        public static final int SIZE = 0x40;
        public static final int MAGIC = 0;
        public static final int VERSION = 4;
        public static final int MAPPING_SIZE = 8;
        public static final int LAYOUT_HASH = 16;
    }

    /** EndpointStatus; written only by its owner. */
    public static final class Status {
        public static final int SIZE = 0x40;
        public static final int MAGIC = 0;
        public static final int VERSION = 4;
        public static final int LAYOUT_HASH = 8;
        public static final int PID = 16;
        public static final int STATE = 20;
        public static final int SESSION = 24;
        public static final int HEARTBEAT = 32;
        public static final int UNIX_US = 40;
    }

    /** SifuSnapshot (seqlock, written by Sifu). */
    public static final class SifuSnap {
        public static final int SIZE = 0x100;
        public static final int SEQ = 0;
        public static final int FRAME = 8;
        public static final int UNIX_US = 16;
        public static final int WORLD_GENERATION = 24;
        public static final int FLAGS = 28;
        public static final int PLAYER_LOCATION = 32;
        public static final int PLAYER_ROTATION = 44;
        public static final int PLAYER_VELOCITY = 56;
        public static final int CAMERA_LOCATION = 68;
        public static final int CAMERA_ROTATION = 80;
        public static final int CAMERA_FOV = 92;
        public static final int HEALTH = 96;
        public static final int MAX_HEALTH = 100;
        public static final int WORLD_PATH = 104;
        public static final int WORLD_PATH_SIZE = 96;
        public static final int MOUSE_DX = 200;
        public static final int MOUSE_DY = 208;
        public static final int SCROLL = 216;
        public static final int INPUT_OWNER = 224;
        public static final int CAPSULE_HALF_HEIGHT = 228;
        public static final int ANCHOR_EPOCH = 232;
        public static final int LANGUAGE = 236, LANGUAGE_SIZE = 20;
    }

    /** McSnapshot (seqlock, written by Minecraft). */
    public static final class McSnap {
        public static final int SIZE = 0x100;
        public static final int SEQ = 0;
        public static final int FRAME = 8;
        public static final int UNIX_US = 16;
        public static final int LAST_SIFU_FRAME = 24;
        public static final int FLAGS = 32;
        public static final int PERSPECTIVE = 36;
        public static final int PLAYER_LOCATION = 40;
        public static final int PLAYER_ROTATION = 52;
        public static final int PLAYER_VELOCITY = 64;
        public static final int CAMERA_LOCATION = 76;
        public static final int CAMERA_ROTATION = 88;
        public static final int CAMERA_FOV = 100;
        public static final int ANCHOR_EPOCH = 104;
        public static final int COLLISION_GENERATION = 108;
        public static final int SIFU_SESSION = 112;
        public static final int WORLD_GENERATION = 120;
        public static final int GUI_INPUT_ACK = 124;
    }

    /** CollisionManifest (seqlock, written by Sifu): the current voxel grid file. */
    public static final class Manifest {
        public static final int SIZE = 0x200;
        public static final int SEQ = 0;
        public static final int GENERATION = 8;
        public static final int WORLD_GENERATION = 12;
        public static final int BLOCK_COUNT = 16;
        public static final int CELL_COUNT = 24;
        public static final int MAP = 32;
        public static final int MAP_SIZE = 64;
        public static final int PATH = 96;
        public static final int PATH_SIZE = 384;
    }

    /** BlockTable (seqlock, written by Minecraft): the blocks placed in the map Sifu shows. */
    public static final class Blocks {
        public static final int SEQ = 0;
        public static final int GENERATION = 8;
        public static final int COUNT = 12;
        public static final int MAP = 16;
        public static final int MAP_SIZE = 64;
        public static final int PALETTE_GENERATION = 80;
        public static final int ENTRIES = 128;
        public static final int ENTRY_SIZE = 16; // int x, y, z (Unreal blocks), int kind (palette entry, 0 = none)
        public static final int SIZE = ENTRIES + MAX_BLOCKS * ENTRY_SIZE;
    }

    /** EnemyTable (seqlock, written by Sifu). */
    public static final class Enemies {
        public static final int SIZE = 0x840;
        public static final int SEQ = 0;
        public static final int FRAME = 8;
        public static final int WORLD_GENERATION = 16;
        public static final int COUNT = 20;
        public static final int ENTRIES = 64;
        public static final int ENTRY_SIZE = 64;
        public static final int ID = 0;
        public static final int SPAWN = 4;
        public static final int LOCATION = 8;
        public static final int YAW = 20;
        public static final int RADIUS = 24;
        public static final int HALF_HEIGHT = 28;
        public static final int HEALTH = 32;
        public static final int MAX_HEALTH = 36;
        public static final int FLAGS = 40;
        public static final int FACTION = 44;
        public static final int VELOCITY = 48;
    }

    /** One event slot. */
    public static final class Event {
        public static final int SIZE = 0x40;
        public static final int ID = 0;
        public static final int SOURCE_SESSION = 8;
        public static final int TARGET_SESSION = 16;
        public static final int TYPE = 24;
        public static final int PAYLOAD_SIZE = 28;
        public static final int PAYLOAD = 32;
        public static final int PAYLOAD_CAPACITY = 32;
    }

    /** InputPayload of Key / MouseButton events. */
    public static final class InputPayload {
        public static final int SIZE = 16;
        public static final int CODE = 0;
        public static final int ACTION = 4;
        public static final int MODIFIERS = 8;
        public static final int GUI_REVISION = 12;
    }

    public static final class EnemyDamage {
        public static final int SIZE = 32;
        public static final int ENEMY_ID = 0;
        public static final int SPAWN = 4;
        public static final int MC_DAMAGE = 8;
        public static final int STUN_HINT = 12;
        public static final int FLAGS = 16;
        public static final int WORLD_GENERATION = 20;
        public static final int ANCHOR_EPOCH = 24;
    }
    public static final class DamageResult {
        public static final int SIZE = 32;
        public static final int EVENT_ID = 0;
        public static final int ENEMY_ID = 8;
        public static final int SPAWN = 12;
        public static final int RESULT = 16;
        public static final int SIFU_DAMAGE = 20;
        public static final int HEALTH_AFTER = 24;
        public static final int WORLD_GENERATION = 28;
    }

    public static final class PlayerHit {
        public static final int SIZE = 32;
        public static final int ATTACKER_ID = 0, SPAWN = 4, SIFU_DAMAGE = 8, FLAGS = 12;
        public static final int WORLD_GENERATION = 16, ANCHOR_EPOCH = 20, DIR_X = 24, DIR_Y = 28;
    }

    /** Single-producer single-consumer event ring. */
    public static final class Ring {
        public static final int WRITE_INDEX = 0;
        public static final int READ_INDEX = 64;
        public static final int ACKED_ID = 72;
        public static final int SLOTS = 128;
        public static final int SIZE = SLOTS + RING_CAPACITY * Event.SIZE;
    }

    /** Same entries, same order as {@code kLayoutTable} in sifucraft_protocol.h. */
    static final int[] LAYOUT_TABLE = {
        VERSION,
        SNAPSHOT_GUI_AVAILABLE, MC_GUI_AVAILABLE, MC_GUI_OPEN,
        EVENT_GUI_MOUSE_MOTION, GUI_MOUSE_SIZE,
        Float.floatToRawIntBits(PLAYER_SIFU_HP_PER_MC_HP),
        Float.floatToRawIntBits(ENEMY_SIFU_HP_PER_MC_HP),
        MAPPING_SIZE,
        RING_CAPACITY,
        HEADER_OFFSET,
        Header.SIZE,
        SIFU_STATUS_OFFSET,
        MC_STATUS_OFFSET,
        Status.SIZE,
        Status.SESSION,
        Status.HEARTBEAT,
        SIFU_SNAPSHOT_OFFSET,
        SifuSnap.SIZE,
        SifuSnap.PLAYER_LOCATION,
        SifuSnap.CAMERA_LOCATION,
        SifuSnap.CAMERA_FOV,
        SifuSnap.HEALTH,
        SifuSnap.WORLD_PATH,
        SifuSnap.MOUSE_DX,
        SifuSnap.SCROLL,
        SifuSnap.INPUT_OWNER,
        SifuSnap.CAPSULE_HALF_HEIGHT,
        SifuSnap.ANCHOR_EPOCH,
        SifuSnap.LANGUAGE, SifuSnap.LANGUAGE_SIZE,
        MC_SNAPSHOT_OFFSET,
        McSnap.SIZE,
        McSnap.LAST_SIFU_FRAME,
        McSnap.FLAGS,
        McSnap.PERSPECTIVE,
        McSnap.PLAYER_LOCATION,
        McSnap.CAMERA_LOCATION,
        McSnap.CAMERA_FOV,
        McSnap.ANCHOR_EPOCH,
        McSnap.COLLISION_GENERATION,
        McSnap.SIFU_SESSION,
        McSnap.WORLD_GENERATION,
        McSnap.GUI_INPUT_ACK,
        SIFU_TO_MC_RING_OFFSET,
        MC_TO_SIFU_RING_OFFSET,
        Ring.SIZE,
        Ring.READ_INDEX,
        Ring.ACKED_ID,
        Ring.SLOTS,
        Event.SIZE,
        Event.TYPE,
        Event.PAYLOAD,
        InputPayload.SIZE,
        InputPayload.GUI_REVISION,
        COLLISION_MANIFEST_OFFSET,
        Manifest.SIZE,
        Manifest.GENERATION,
        Manifest.WORLD_GENERATION,
        Manifest.BLOCK_COUNT,
        Manifest.CELL_COUNT,
        Manifest.MAP,
        Manifest.PATH,
        MAX_BLOCKS,
        BLOCK_TABLE_OFFSET,
        Blocks.SIZE,
        Blocks.GENERATION,
        Blocks.COUNT,
        Blocks.MAP,
        Blocks.ENTRIES,
        Blocks.ENTRY_SIZE,
        Blocks.PALETTE_GENERATION,
        MAX_ENEMIES,
        ENEMY_TABLE_OFFSET,
        Enemies.SIZE,
        Enemies.FRAME,
        Enemies.WORLD_GENERATION,
        Enemies.COUNT,
        Enemies.ENTRIES,
        Enemies.ENTRY_SIZE,
        Enemies.ID,
        Enemies.SPAWN,
        Enemies.LOCATION,
        Enemies.YAW,
        Enemies.RADIUS,
        Enemies.HALF_HEIGHT,
        Enemies.HEALTH,
        Enemies.MAX_HEALTH,
        Enemies.FLAGS,
        Enemies.FACTION,
        Enemies.VELOCITY,
        EnemyDamage.SIZE,
        EnemyDamage.ENEMY_ID,
        EnemyDamage.MC_DAMAGE,
        EnemyDamage.STUN_HINT,
        EnemyDamage.FLAGS,
        EnemyDamage.WORLD_GENERATION,
        EnemyDamage.ANCHOR_EPOCH,
        DamageResult.SIZE,
        DamageResult.EVENT_ID,
        DamageResult.ENEMY_ID,
        DamageResult.RESULT,
        DamageResult.SIFU_DAMAGE,
        DamageResult.HEALTH_AFTER,
        DamageResult.WORLD_GENERATION,
        PlayerHit.SIZE,
        PlayerHit.ATTACKER_ID,
        PlayerHit.SPAWN,
        PlayerHit.SIFU_DAMAGE,
        PlayerHit.FLAGS,
        PlayerHit.WORLD_GENERATION,
        PlayerHit.ANCHOR_EPOCH,
        PlayerHit.DIR_X,
        PlayerHit.DIR_Y,
    };

    public static final long LAYOUT_HASH = layoutHash();

    /** FNV-1a 64 over each table entry as 4 little-endian bytes. */
    static long layoutHash() {
        long hash = 0xCBF29CE484222325L;
        for (int value : LAYOUT_TABLE) {
            for (int shift = 0; shift < 32; shift += 8) {
                hash ^= (value >>> shift) & 0xFF;
                hash *= 0x100000001B3L;
            }
        }
        return hash;
    }
}
