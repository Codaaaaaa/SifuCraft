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
    src/bridge/world_motion_mailbox.cpp
    src/bridge/shape_reader.cpp
    src/bridge/world_reader.cpp
    src/core/log.cpp
    src/bridge/shared_mapping.cpp
    src/bridge/sifu_bridge.cpp
)

add_library(sifucraft_native SHARED
    src/render/pipeline_state.cpp
    src/render/world_scene.cpp
    src/render/world_compositor.cpp
    src/render/depth_copy.cpp
    src/render/depth_probe.cpp
    src/render/depth_observer.cpp
    src/render/view_parameters.cpp
    src/dllmain.cpp
    src/runtime.cpp
    src/render/overlay.cpp
    src/ui/player_hud.cpp
    src/render/compositor.cpp
    src/bridge/overlay_reader.cpp
    src/proxy/dsound_proxy.cpp
    src/proxy/dsound.def
    src/core/build_guard.cpp
    src/core/config.cpp
    src/core/safe_memory.cpp
    src/combat/enemies.cpp
    src/combat/motion_probe.cpp
    src/combat/world_motion_service.cpp
    src/combat/base_sync.cpp
    src/combat/ai_tick_gate.cpp
    src/combat/damage.cpp
    src/combat/player_hits.cpp
    src/hooks/camera_fade.cpp
    src/hooks/streaming_guard.cpp
    src/interaction/native_interaction.cpp
    src/hooks/game_hooks.cpp
    src/hooks/hook_plan.cpp
    src/hooks/vtable_hook.cpp
    src/input/input_router.cpp
    src/input/key_map.cpp
    src/ue/function_call.cpp
    src/ue/language.cpp
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
add_executable(sifucraft_fake_sifu tools/fake_sifu.cpp src/bridge/overlay_reader.cpp ${SIFUCRAFT_BRIDGE_SOURCES})
add_executable(sifucraft_overlay_tests tests/overlay_tests.cpp src/bridge/overlay_reader.cpp src/bridge/shared_mapping.cpp)
add_test(NAME overlay_tests COMMAND sifucraft_overlay_tests)
add_executable(sifucraft_world_tests tests/world_tests.cpp src/bridge/world_reader.cpp src/bridge/shared_mapping.cpp)
add_test(NAME world_tests COMMAND sifucraft_world_tests)
add_executable(sifucraft_world_render_tests tests/world_render_tests.cpp src/render/world_scene.cpp src/render/world_compositor.cpp src/render/depth_copy.cpp src/render/compositor.cpp src/render/pipeline_state.cpp)
target_link_libraries(sifucraft_world_render_tests PRIVATE d3d11 d3dcompiler dxgi dxguid)
add_test(NAME world_render_tests COMMAND sifucraft_world_render_tests)
set(SIFUCRAFT_PREPARE_TEST_SOURCES tests/world_prepare_cases.cpp src/render/world_scene.cpp src/render/world_compositor.cpp src/render/depth_copy.cpp src/render/compositor.cpp src/render/pipeline_state.cpp)
add_executable(sifucraft_prepare_simd_tests ${SIFUCRAFT_PREPARE_TEST_SOURCES})
add_executable(sifucraft_prepare_scalar_tests ${SIFUCRAFT_PREPARE_TEST_SOURCES})
foreach(target sifucraft_prepare_simd_tests sifucraft_prepare_scalar_tests)
    target_compile_definitions(${target} PRIVATE SIFUCRAFT_GEOMETRY_DIAGNOSTICS)
    target_link_libraries(${target} PRIVATE d3d11 d3dcompiler dxgi dxguid)
endforeach()
target_compile_definitions(sifucraft_prepare_scalar_tests PRIVATE SIFUCRAFT_SCALAR_PREPARE)
add_test(NAME world_prepare_equivalence COMMAND ${CMAKE_COMMAND}
    "-DSIMD=$<TARGET_FILE:sifucraft_prepare_simd_tests>" "-DSCALAR=$<TARGET_FILE:sifucraft_prepare_scalar_tests>"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/compare_prepare.cmake")
add_executable(sifucraft_render_tests tests/render_tests.cpp src/render/compositor.cpp src/render/pipeline_state.cpp)
target_link_libraries(sifucraft_render_tests PRIVATE d3d11 d3dcompiler dxgi dxguid)
add_test(NAME render_tests COMMAND sifucraft_render_tests)
add_executable(sifucraft_depth_tests tests/depth_tests.cpp src/render/depth_copy.cpp src/render/depth_observer.cpp src/render/view_parameters.cpp
    src/render/compositor.cpp src/render/pipeline_state.cpp
    src/hooks/vtable_hook.cpp src/core/safe_memory.cpp src/core/log.cpp)
target_link_libraries(sifucraft_depth_tests PRIVATE d3d11 d3dcompiler dxgi dxguid)
add_test(NAME depth_tests COMMAND sifucraft_depth_tests)
add_executable(sifucraft_hud_tests tests/hud_tests.cpp)
add_executable(sifucraft_shape_tests tests/shape_tests.cpp src/bridge/shape_reader.cpp src/bridge/shared_mapping.cpp)
add_test(NAME shape_tests COMMAND sifucraft_shape_tests)
add_test(NAME hud_tests COMMAND sifucraft_hud_tests)
add_executable(sifucraft_motion_tests tests/motion_tests.cpp)
add_test(NAME motion_tests COMMAND sifucraft_motion_tests)
add_executable(sifucraft_world_motion_engine_tests tests/world_motion_engine_tests.cpp)
add_test(NAME world_motion_engine_tests COMMAND sifucraft_world_motion_engine_tests)
add_executable(sifucraft_world_motion_tests tests/world_motion_tests.cpp src/bridge/world_motion_mailbox.cpp src/bridge/shared_mapping.cpp)
add_test(NAME world_motion_tests COMMAND sifucraft_world_motion_tests "${PROJECT_SOURCE_DIR}/protocol/fixtures/world_motion_command.hex" "${PROJECT_SOURCE_DIR}/protocol/fixtures/world_motion_reply.hex")

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
    src/ue/language.cpp
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

foreach(target sifucraft_native sifucraft_fake_sifu sifucraft_collision_replay sifucraft_native_tests sifucraft_overlay_tests sifucraft_world_tests sifucraft_world_render_tests sifucraft_prepare_simd_tests sifucraft_prepare_scalar_tests sifucraft_render_tests sifucraft_depth_tests sifucraft_hud_tests sifucraft_shape_tests sifucraft_motion_tests sifucraft_world_motion_tests sifucraft_world_motion_engine_tests)
    set_target_properties(${target} PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON CXX_EXTENSIONS OFF)
    target_include_directories(${target} PRIVATE ${SIFUCRAFT_INCLUDE_DIRS})
    target_compile_definitions(${target} PRIVATE ${SIFUCRAFT_COMMON_DEFINITIONS})
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wno-cast-function-type)
    # No runtime DLL dependencies besides the OS: the game folder must not need libstdc++/libgcc.
    target_link_options(${target} PRIVATE -static -static-libgcc -static-libstdc++)
endforeach()

target_link_libraries(sifucraft_native PRIVATE psapi d3d11 d3dcompiler dxgi dxguid)
