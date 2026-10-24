// SifuCraft shared-memory protocol.
//
// The single definition of the byte layout shared by sifu-native (C++) and the Minecraft side.
// The Java mirror lives in fabric/src/main/java/dev/sifucraft/bridge/Protocol.java; any change to
// a struct, offset or constant here must be mirrored there and must bump kVersion. Both sides
// hash kLayoutTable into kLayoutHash and refuse a peer whose hash differs.
//
// Ownership rules (the core invariant): every field has exactly one writer.
//   ProtocolHeader         both sides write the same constants (idempotent)
//   sifu/mc EndpointStatus only the named side
//   SifuSnapshot           Sifu (seqlock)          McSnapshot   Minecraft (seqlock)
//   CollisionManifest      Sifu (seqlock)
//   BlockTable             Minecraft (seqlock)
//   EnemyTable             Sifu (seqlock)
//   EventRing              producer owns write_index and the slots, consumer owns read_index/acked_id
//
// Every position, rotation and velocity is in Unreal space (cm, Z up, degrees); the Minecraft side
// owns the conversion. All values are little-endian with natural alignment; 64-bit fields are
// 8-byte aligned so both sides can access them atomically.
#pragma once

#include <cstddef>
#include <cstdint>

namespace sifucraft::protocol {

inline constexpr std::uint32_t kMagic = 0x52434653;  // "SFCR"
// The name carries no version so that mismatched builds see each other and report INCOMPATIBLE.
inline constexpr std::uint32_t kVersion = 11;
inline constexpr wchar_t kMappingName[] = L"Local\\SifuCraft.Bridge";
inline constexpr std::uint32_t kMappingSize = 0x10000;

// A peer whose heartbeat has not advanced for this long is treated as gone.
inline constexpr std::uint32_t kHeartbeatTimeoutMs = 2000;
inline constexpr std::uint32_t kRingCapacity = 256;
inline constexpr std::uint32_t kMaxBlocks = 1536;
inline constexpr std::uint32_t kMaxEnemies = 32;
inline constexpr float kSifuHpPerMcHp = 9.0f;

enum class EndpointState : std::uint32_t {
    Absent = 0,
    Running = 1,
    Closing = 2,
};

enum class EventType : std::uint32_t {
    Ping = 1,          // Minecraft -> Sifu, PingPayload
    Pong = 2,          // Sifu -> Minecraft, echoes the PingPayload it answers
    Key = 3,           // Sifu -> Minecraft, InputPayload (code = SDL scancode)
    MouseButton = 4,   // Sifu -> Minecraft, InputPayload (code = SDL mouse button)
    OwnerChanged = 5,  // Sifu -> Minecraft, OwnerPayload
    EnemyDamage = 6,   // Minecraft -> Sifu, EnemyDamagePayload
    DamageResult = 7,  // Sifu -> Minecraft, DamageResultPayload
    PlayerHit = 8,     // Sifu -> Minecraft, PlayerHitPayload
};

// Who receives the player's keyboard and mouse.
enum class InputOwner : std::uint32_t {
    Sifu = 0,
    Minecraft = 1,
};

inline constexpr std::uint32_t kSnapshotHasPawn = 1u << 0;
inline constexpr std::uint32_t kSnapshotHasCamera = 1u << 1;

inline constexpr std::uint32_t kMcReady = 1u << 0;      // in a world: may receive input
inline constexpr std::uint32_t kMcHasPose = 1u << 1;    // pose / camera fields are valid
inline constexpr std::uint32_t kMcOnGround = 1u << 2;
inline constexpr std::uint32_t kMcSneaking = 1u << 3;
inline constexpr std::uint32_t kMcSprinting = 1u << 4;
inline constexpr std::uint32_t kMcFlying = 1u << 5;
inline constexpr std::uint32_t kMcRequestAnchor = 1u << 6; // respawned; anchor_epoch is the epoch to invalidate
inline constexpr std::uint32_t kMcDead = 1u << 7;          // keep ownership/protection, publish no pose

struct Vec3 {
    float x, y, z;
};

struct Rot3 {
    float pitch, yaw, roll;
};

struct ProtocolHeader {
    std::uint32_t magic;
    std::uint32_t version;
    std::uint32_t mapping_size;
    std::uint32_t reserved0;
    std::uint64_t layout_hash;
    std::uint8_t reserved[40];
};

struct EndpointStatus {
    std::uint32_t magic;        // kMagic once the endpoint has attached
    std::uint32_t version;
    std::uint64_t layout_hash;
    std::uint32_t pid;
    std::uint32_t state;        // EndpointState
    std::uint64_t session;      // nonzero, new for every attach
    std::uint64_t heartbeat;    // advances at least every few hundred ms while alive
    std::uint64_t unix_us;      // wall clock of the last heartbeat (diagnostics only)
    std::uint8_t reserved[16];
};

struct SifuSnapshot {
    std::uint64_t seq;          // odd while the writer is inside
    std::uint64_t frame;
    std::uint64_t unix_us;
    std::uint32_t world_generation;
    std::uint32_t flags;        // kSnapshotHas*
    Vec3 player_location;       // capsule centre
    Rot3 player_rotation;
    Vec3 player_velocity;
    Vec3 camera_location;
    Rot3 camera_rotation;
    float camera_fov;           // horizontal, degrees
    float health;
    float max_health;
    char world_path[96];        // UTF-8, NUL-terminated
    // Mouse motion and wheel routed to Minecraft, as running totals so nothing is lost or doubled:
    // the reader applies the difference to its previous read. Screen convention: +x right, +y down.
    double mouse_dx;
    double mouse_dy;
    double scroll;
    std::uint32_t input_owner;  // InputOwner
    float capsule_half_height;
    // Bumped each time Sifu hands the player to Minecraft: Minecraft re-anchors to where the pawn
    // is now, and Sifu applies Minecraft's pose / camera only once they carry this epoch back.
    std::uint32_t anchor_epoch;
    std::uint8_t reserved[20];
};

struct McSnapshot {
    std::uint64_t seq;          // odd while the writer is inside
    std::uint64_t frame;
    std::uint64_t unix_us;
    std::uint64_t last_sifu_frame;  // newest SifuSnapshot.frame the Minecraft side has consumed
    std::uint32_t flags;            // kMc*
    std::uint32_t perspective;      // 0 first person, 1 third person back, 2 third person front
    Vec3 player_location;           // feet
    Rot3 player_rotation;           // body: yaw only matters
    Vec3 player_velocity;           // cm/s
    Vec3 camera_location;
    Rot3 camera_rotation;
    float camera_fov;               // vertical, degrees (Minecraft's convention)
    std::uint32_t anchor_epoch;     // SifuSnapshot.anchor_epoch the pose fields are anchored to
    std::uint32_t collision_generation;  // CollisionManifest.generation Minecraft has loaded (0 = none)
    std::uint64_t sifu_session;          // target Sifu lifetime, captured with this pose/request
    std::uint32_t world_generation;
    std::uint8_t reserved[132];
};

// Sifu's collision as a voxel grid, published as a file Minecraft loads whole (format: the
// "SCVX" layout in sifu-native/src/collision/export.h). A new generation replaces the previous one.
struct CollisionManifest {
    std::uint64_t seq;               // odd while the writer is inside
    std::uint32_t generation;        // bumped for each published grid; 0 = none yet
    std::uint32_t world_generation;  // SifuSnapshot.world_generation the grid was built from
    std::uint32_t block_count;       // for checking the loaded file
    std::uint32_t reserved0;
    std::uint64_t cell_count;
    char map[64];                    // UTF-8, NUL-terminated, e.g. "Wuguan_Main"
    char path[384];                  // UTF-8, NUL-terminated absolute path of the grid file
    std::uint8_t reserved[32];
};

// One Minecraft block (full solid blocks only), as a 1 m cube in Unreal space: x * 100 ..
// (x + 1) * 100 cm, same for y, z.
struct BlockEntry {
    std::int32_t x, y, z;
    // How it looks: an entry of the block palette (1-based), see BlockTable::palette_generation;
    // 0 = no palette entry (Sifu shows a plain block).
    std::uint32_t kind;
};

// The blocks placed in the map Sifu shows, as Minecraft has them (Minecraft owns blocks). Sifu
// keeps one actor per entry: it spawns what is new and destroys what is gone, so a missed update,
// a reconnect or a level change needs nothing but the next table.
struct BlockTable {
    std::uint64_t seq;          // odd while the writer is inside
    std::uint32_t generation;   // bumped whenever the table changes; 0 = no table yet
    std::uint32_t count;        // entries in use (at most kMaxBlocks)
    char map[64];               // UTF-8, NUL-terminated: the map the blocks belong to
    // Bumped whenever Minecraft rewrites the block palette: SifuCraft\textures\palette.txt in Sifu's
    // folder, one line per entry "<kind> <+X> <-X> <+Y> <-Y> <+Z> <-Z>" naming each face's texture
    // (a PNG in the same folder; Unreal axes). 0 = none.
    std::uint32_t palette_generation;
    std::uint8_t reserved[44];
    BlockEntry entries[kMaxBlocks];
};

inline constexpr std::uint32_t kEnemyAlive = 1u << 0;
inline constexpr std::uint32_t kEnemyDown = 1u << 1;
inline constexpr std::uint32_t kEnemyInvincible = 1u << 2;
inline constexpr std::uint32_t kEnemyCanDie = 1u << 3;

struct EnemyEntry {
    std::uint32_t id;           // UObject internal index
    std::uint32_t spawn;        // activation generation, changes on pool reuse or index reuse
    Vec3 location;              // hit capsule centre, Unreal cm
    float yaw;
    float capsule_radius;      // world-space cm
    float capsule_half_height;
    float health;
    float max_health;           // includes difficulty multiplier
    std::uint32_t flags;
    std::uint32_t faction;
    Vec3 velocity;              // cm/s
    std::uint32_t reserved;
};

struct EnemyTable {
    std::uint64_t seq;
    std::uint64_t frame;        // SifuSnapshot.frame at publication
    std::uint32_t world_generation;
    std::uint32_t count;
    std::uint8_t reserved[40];
    EnemyEntry entries[kMaxEnemies];
};

struct PingPayload {
    std::uint64_t nonce;
    std::uint64_t sent_unix_us;
};

struct InputPayload {
    std::int32_t code;       // SDL scancode or SDL mouse button
    std::int32_t action;     // 1 press, 0 release, -1 repeat (Minecraft's InputConstants)
    std::int32_t modifiers;  // SDL keymod bits
    std::int32_t reserved;
};

struct OwnerPayload {
    std::uint32_t owner;  // InputOwner
    std::uint32_t reserved;
};

inline constexpr std::uint32_t kDamageCritical = 1u << 0;
inline constexpr std::uint32_t kDamageSweep = 1u << 1;
inline constexpr std::uint32_t kDamageProjectile = 1u << 2;
inline constexpr std::uint32_t kDamageSmash = 1u << 3;

struct EnemyDamagePayload {
    std::uint32_t enemy_id, spawn;
    float mc_damage;
    float stun_hint;
    std::uint32_t flags;
    std::uint32_t world_generation;
    std::uint32_t anchor_epoch;
    std::uint32_t reserved;
};

enum class DamageOutcome : std::uint32_t {
    Applied = 0, Dead = 1, Invincible = 2, Stale = 3, NotOwned = 4, Invalid = 5, Busy = 6, Unavailable = 7,
};

struct DamageResultPayload {
    std::uint64_t event_id;     // EnemyDamage being answered, within the Minecraft source session
    std::uint32_t enemy_id, spawn;
    std::uint32_t result;       // DamageOutcome
    float sifu_damage;          // observed health loss; may differ from requested damage
    float health_after;
    std::uint32_t world_generation;
};

inline constexpr std::uint32_t kHitCanDefend = 1u << 0;
inline constexpr std::uint32_t kHitParriable = 1u << 1;
inline constexpr std::uint32_t kHitCanBeLethal = 1u << 2;
// Attack power occupies bits 8..15; world/epoch scope takes the space of the draft's Z direction.
struct PlayerHitPayload {
    std::uint32_t attacker_id, spawn;
    float sifu_damage;
    std::uint32_t flags;
    std::uint32_t world_generation, anchor_epoch;
    float dir_x, dir_y;
};

struct Event {
    std::uint64_t id;              // strictly increasing per source session, starting at 1
    std::uint64_t source_session;
    std::uint64_t target_session;  // consumer session the event is addressed to
    std::uint32_t type;            // EventType
    std::uint32_t size;            // payload bytes in use
    std::uint8_t payload[32];
};

// Single producer, single consumer. Indices only grow; slot = index % kRingCapacity.
struct EventRing {
    std::uint64_t write_index;  // producer-owned
    std::uint8_t pad0[56];
    std::uint64_t read_index;   // consumer-owned
    std::uint64_t acked_id;     // consumer-owned: id of the newest consumed event
    std::uint8_t pad1[48];
    Event slots[kRingCapacity];
};

inline constexpr std::uint32_t kHeaderOffset = 0x0000;
inline constexpr std::uint32_t kSifuStatusOffset = 0x0040;
inline constexpr std::uint32_t kMcStatusOffset = 0x0080;
inline constexpr std::uint32_t kSifuSnapshotOffset = 0x0100;
inline constexpr std::uint32_t kMcSnapshotOffset = 0x0200;
inline constexpr std::uint32_t kSifuToMcRingOffset = 0x0400;
inline constexpr std::uint32_t kMcToSifuRingOffset = 0x4800;
inline constexpr std::uint32_t kCollisionManifestOffset = 0x9000;
inline constexpr std::uint32_t kBlockTableOffset = 0x9200;
inline constexpr std::uint32_t kEnemyTableOffset = 0xF300;

static_assert(sizeof(ProtocolHeader) == 0x40);
static_assert(sizeof(EndpointStatus) == 0x40);
static_assert(offsetof(EndpointStatus, session) == 24 && offsetof(EndpointStatus, heartbeat) == 32);
static_assert(sizeof(SifuSnapshot) == 0x100);
static_assert(offsetof(SifuSnapshot, player_location) == 32 && offsetof(SifuSnapshot, camera_location) == 68);
static_assert(offsetof(SifuSnapshot, camera_fov) == 92 && offsetof(SifuSnapshot, world_path) == 104);
static_assert(offsetof(SifuSnapshot, mouse_dx) == 200 && offsetof(SifuSnapshot, input_owner) == 224);
static_assert(offsetof(SifuSnapshot, capsule_half_height) == 228 && offsetof(SifuSnapshot, anchor_epoch) == 232);
static_assert(sizeof(McSnapshot) == 0x100);
static_assert(offsetof(McSnapshot, last_sifu_frame) == 24 && offsetof(McSnapshot, player_location) == 40);
static_assert(offsetof(McSnapshot, camera_location) == 76 && offsetof(McSnapshot, camera_fov) == 100);
static_assert(offsetof(McSnapshot, anchor_epoch) == 104 && offsetof(McSnapshot, collision_generation) == 108);
static_assert(sizeof(CollisionManifest) == 0x200 && offsetof(CollisionManifest, generation) == 8);
static_assert(offsetof(CollisionManifest, cell_count) == 24 && offsetof(CollisionManifest, map) == 32);
static_assert(offsetof(CollisionManifest, path) == 96);
static_assert(sizeof(BlockEntry) == 16 && offsetof(BlockTable, map) == 16 && offsetof(BlockTable, entries) == 128);
static_assert(offsetof(BlockTable, palette_generation) == 80);
static_assert(sizeof(PingPayload) == 16 && sizeof(InputPayload) == 16 && sizeof(OwnerPayload) == 8);
static_assert(sizeof(EnemyDamagePayload) == 32 && offsetof(EnemyDamagePayload, world_generation) == 20);
static_assert(sizeof(DamageResultPayload) == 32 && offsetof(DamageResultPayload, health_after) == 24);
static_assert(sizeof(PlayerHitPayload) == 32 && offsetof(PlayerHitPayload, world_generation) == 16);
static_assert(offsetof(McSnapshot, sifu_session) == 112 && offsetof(McSnapshot, world_generation) == 120);
static_assert(sizeof(Event) == 0x40 && offsetof(Event, payload) == 32);
static_assert(offsetof(EventRing, read_index) == 64 && offsetof(EventRing, slots) == 128);
static_assert(sizeof(EventRing) == 0x80 + kRingCapacity * sizeof(Event));
static_assert(kSifuToMcRingOffset >= kMcSnapshotOffset + sizeof(McSnapshot));
static_assert(kMcToSifuRingOffset >= kSifuToMcRingOffset + sizeof(EventRing));
static_assert(kCollisionManifestOffset >= kMcToSifuRingOffset + sizeof(EventRing));
static_assert(kBlockTableOffset >= kCollisionManifestOffset + sizeof(CollisionManifest));
static_assert(sizeof(EnemyEntry) == 64 && offsetof(EnemyEntry, location) == 8);
static_assert(offsetof(EnemyEntry, yaw) == 20 && offsetof(EnemyEntry, health) == 32);
static_assert(offsetof(EnemyEntry, flags) == 40 && offsetof(EnemyEntry, velocity) == 48);
static_assert(sizeof(EnemyTable) == 0x840 && offsetof(EnemyTable, entries) == 64);
static_assert(kEnemyTableOffset >= kBlockTableOffset + sizeof(BlockTable));
static_assert(kEnemyTableOffset + sizeof(EnemyTable) <= kMappingSize);

// Hashed in this order by both sides; keep it identical to Protocol.LAYOUT_TABLE in Java.
inline constexpr std::uint32_t kLayoutTable[] = {
    kVersion,
    kMappingSize,
    kRingCapacity,
    kHeaderOffset,
    sizeof(ProtocolHeader),
    kSifuStatusOffset,
    kMcStatusOffset,
    sizeof(EndpointStatus),
    offsetof(EndpointStatus, session),
    offsetof(EndpointStatus, heartbeat),
    kSifuSnapshotOffset,
    sizeof(SifuSnapshot),
    offsetof(SifuSnapshot, player_location),
    offsetof(SifuSnapshot, camera_location),
    offsetof(SifuSnapshot, camera_fov),
    offsetof(SifuSnapshot, health),
    offsetof(SifuSnapshot, world_path),
    offsetof(SifuSnapshot, mouse_dx),
    offsetof(SifuSnapshot, scroll),
    offsetof(SifuSnapshot, input_owner),
    offsetof(SifuSnapshot, capsule_half_height),
    offsetof(SifuSnapshot, anchor_epoch),
    kMcSnapshotOffset,
    sizeof(McSnapshot),
    offsetof(McSnapshot, last_sifu_frame),
    offsetof(McSnapshot, flags),
    offsetof(McSnapshot, perspective),
    offsetof(McSnapshot, player_location),
    offsetof(McSnapshot, camera_location),
    offsetof(McSnapshot, camera_fov),
    offsetof(McSnapshot, anchor_epoch),
    offsetof(McSnapshot, collision_generation),
    offsetof(McSnapshot, sifu_session),
    offsetof(McSnapshot, world_generation),
    kSifuToMcRingOffset,
    kMcToSifuRingOffset,
    sizeof(EventRing),
    offsetof(EventRing, read_index),
    offsetof(EventRing, acked_id),
    offsetof(EventRing, slots),
    sizeof(Event),
    offsetof(Event, type),
    offsetof(Event, payload),
    sizeof(InputPayload),
    kCollisionManifestOffset,
    sizeof(CollisionManifest),
    offsetof(CollisionManifest, generation),
    offsetof(CollisionManifest, world_generation),
    offsetof(CollisionManifest, block_count),
    offsetof(CollisionManifest, cell_count),
    offsetof(CollisionManifest, map),
    offsetof(CollisionManifest, path),
    kMaxBlocks,
    kBlockTableOffset,
    sizeof(BlockTable),
    offsetof(BlockTable, generation),
    offsetof(BlockTable, count),
    offsetof(BlockTable, map),
    offsetof(BlockTable, entries),
    sizeof(BlockEntry),
    offsetof(BlockTable, palette_generation),
    kMaxEnemies,
    kEnemyTableOffset,
    sizeof(EnemyTable),
    offsetof(EnemyTable, frame),
    offsetof(EnemyTable, world_generation),
    offsetof(EnemyTable, count),
    offsetof(EnemyTable, entries),
    sizeof(EnemyEntry),
    offsetof(EnemyEntry, id),
    offsetof(EnemyEntry, spawn),
    offsetof(EnemyEntry, location),
    offsetof(EnemyEntry, yaw),
    offsetof(EnemyEntry, capsule_radius),
    offsetof(EnemyEntry, capsule_half_height),
    offsetof(EnemyEntry, health),
    offsetof(EnemyEntry, max_health),
    offsetof(EnemyEntry, flags),
    offsetof(EnemyEntry, faction),
    offsetof(EnemyEntry, velocity),
    sizeof(EnemyDamagePayload),
    offsetof(EnemyDamagePayload, enemy_id),
    offsetof(EnemyDamagePayload, mc_damage),
    offsetof(EnemyDamagePayload, stun_hint),
    offsetof(EnemyDamagePayload, flags),
    offsetof(EnemyDamagePayload, world_generation),
    offsetof(EnemyDamagePayload, anchor_epoch),
    sizeof(DamageResultPayload),
    offsetof(DamageResultPayload, event_id),
    offsetof(DamageResultPayload, enemy_id),
    offsetof(DamageResultPayload, result),
    offsetof(DamageResultPayload, sifu_damage),
    offsetof(DamageResultPayload, health_after),
    offsetof(DamageResultPayload, world_generation),
    sizeof(PlayerHitPayload),
    offsetof(PlayerHitPayload, attacker_id),
    offsetof(PlayerHitPayload, spawn),
    offsetof(PlayerHitPayload, sifu_damage),
    offsetof(PlayerHitPayload, flags),
    offsetof(PlayerHitPayload, world_generation),
    offsetof(PlayerHitPayload, anchor_epoch),
    offsetof(PlayerHitPayload, dir_x),
    offsetof(PlayerHitPayload, dir_y),
};

// FNV-1a 64 over each table entry as 4 little-endian bytes.
constexpr std::uint64_t ComputeLayoutHash() {
    std::uint64_t hash = 0xCBF29CE484222325ull;
    for (std::uint32_t value : kLayoutTable) {
        for (int shift = 0; shift < 32; shift += 8) {
            hash ^= (value >> shift) & 0xFF;
            hash *= 0x100000001B3ull;
        }
    }
    return hash;
}

inline constexpr std::uint64_t kLayoutHash = ComputeLayoutHash();

}  // namespace sifucraft::protocol
