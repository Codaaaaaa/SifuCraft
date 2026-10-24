#pragma once

#include <cstdint>
#include <vector>

#include "ue/types.h"

// Cooked PhysX 3.4 triangle meshes (UBodySetup::TriMeshes), read without calling PhysX: the
// vertex / triangle counts and their arrays are located by layout (two counts followed by two
// pointers) and accepted only if the data checks out (finite vertices, indices below the vertex
// count). The PhysX DLLs ship without symbols, so the data itself is the proof.
namespace sifucraft::collision {

struct TriangleMeshInfo {
    bool found = false;
    int layout = -1;  // byte offset of the vertex count in the object
    std::uint32_t vertices = 0, triangles = 0;
    bool wide = false;  // 32-bit indices
    std::uintptr_t vertex_data = 0, index_data = 0;
};

// Locates and validates the arrays (samples the indices).
TriangleMeshInfo InspectTriangleMesh(std::uintptr_t px_mesh);

// Copies the whole mesh (mesh-local space) and validates every index.
bool ReadTriangleMesh(std::uintptr_t px_mesh, std::vector<ue::FVector>* vertices, std::vector<std::uint32_t>* indices);

}  // namespace sifucraft::collision
