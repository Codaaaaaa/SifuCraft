if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "sifu-native must be built for x64: Sifu is a 64-bit process. "
                        "Use the mingw-release preset (x86_64-w64-mingw32 toolchain).")
endif()

set(SIFUCRAFT_COMMON_DEFINITIONS
    SIFUCRAFT_VERSION="${PROJECT_VERSION}"
    WIN32_LEAN_AND_MEAN
    NOMINMAX
    UNICODE
    _UNICODE
)
set(SIFUCRAFT_INCLUDE_DIRS src ${PROJECT_SOURCE_DIR}/protocol)

# Bridge + logging, shared by the in-game module and the console tools.
set(SIFUCRAFT_BRIDGE_SOURCES
    src/core/log.cpp
    src/bridge/shared_mapping.cpp
    src/bridge/sifu_bridge.cpp
)

add_library(sifucraft_native SHARED
    src/dllmain.cpp
    src/runtime.cpp
    src/proxy/dsound_proxy.cpp
    src/proxy/dsound.def
    src/core/build_guard.cpp
    src/core/config.cpp
    src/core/safe_memory.cpp
    src/combat/enemies.cpp
    src/hooks/camera_fade.cpp
    src/hooks/game_hooks.cpp
    src/hooks/hook_plan.cpp
    src/hooks/vtable_hook.cpp
    src/input/input_router.cpp
    src/input/key_map.cpp
    src/ue/function_call.cpp
    src/ue/names.cpp
    src/ue/objects.cpp
    src/ue/reflection.cpp
    src/ue/transform.cpp
    src/probe/probe.cpp
    src/probe/block_census.cpp
    src/probe/combat_census.cpp
    src/probe/collision_census.cpp
    src/blocks/block_mirror.cpp
    src/collision/builder.cpp
    src/collision/components.cpp
    src/collision/export.cpp
    src/collision/physx_mesh.cpp
    src/collision/shape_file.cpp
    src/collision/shapes.cpp
    src/collision/voxels.cpp
    src/collision/walk_check.cpp
    ${SIFUCRAFT_BRIDGE_SOURCES}
)

# Sifu loads the proxy as dsound.dll from its Binaries/Win64 folder.
set_target_properties(sifucraft_native PROPERTIES OUTPUT_NAME dsound PREFIX "")

# Fake Sifu endpoint for testing the Minecraft side without the game.
add_executable(sifucraft_fake_sifu tools/fake_sifu.cpp ${SIFUCRAFT_BRIDGE_SOURCES})

# Re-runs the voxelization on shapes saved by the game and scores it against recorded walks.
set(SIFUCRAFT_VOXEL_SOURCES
    src/core/log.cpp
    src/collision/export.cpp
    src/collision/shape_file.cpp
    src/collision/voxels.cpp
    src/collision/walk_check.cpp
)
add_executable(sifucraft_collision_replay tools/collision_replay.cpp ${SIFUCRAFT_VOXEL_SOURCES})

# Offline tests: run without the game.
add_executable(sifucraft_native_tests
    tests/native_tests.cpp
    src/core/safe_memory.cpp
    src/core/build_guard.cpp
    src/core/log.cpp
    src/hooks/vtable_hook.cpp
    src/input/input_router.cpp
    src/input/key_map.cpp
    src/ue/transform.cpp
    src/collision/export.cpp
    src/collision/shape_file.cpp
    src/collision/voxels.cpp
    src/collision/walk_check.cpp
)
add_dependencies(sifucraft_native_tests sifucraft_native)
add_test(NAME native_tests COMMAND sifucraft_native_tests $<TARGET_FILE:sifucraft_native>)

foreach(target sifucraft_native sifucraft_fake_sifu sifucraft_collision_replay sifucraft_native_tests)
    set_target_properties(${target} PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF)
    target_include_directories(${target} PRIVATE ${SIFUCRAFT_INCLUDE_DIRS})
    target_compile_definitions(${target} PRIVATE ${SIFUCRAFT_COMMON_DEFINITIONS})
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wno-cast-function-type)
    # No runtime DLL dependencies besides the OS: the game folder must not need libstdc++/libgcc.
    target_link_options(${target} PRIVATE -static -static-libgcc -static-libstdc++)
endforeach()

target_link_libraries(sifucraft_native PRIVATE psapi)
