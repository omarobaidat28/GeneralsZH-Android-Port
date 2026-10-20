# GeneralsX @feature fbraz 03/05/2026
# Deterministic cross-platform math library integration (Phase 4)
# GameMath: fdlibm-based deterministic math functions for bit-exact replay validation
#
# Strategy:
# - Enable deterministic math via FetchContent (not on VC6, which uses x87 asm)
# - Define USE_DETERMINISTIC_MATH compile flag when enabled
# - Wrappers in wwmath.h conditionally dispatch to GameMath (deterministic) or CRT (fast fallback)
#
# Reference: Okladnoj et al., PR #2670, TheSuperHackers/GeneralsGameCode
# https://github.com/TheSuperHackers/GeneralsGameCode/pull/2670
#
# Note: GameMath source location is configurable via SAGE_GAMEMATH_GIT_REPO
# Default: TheSuperHackers fork with deterministic math integration
#
# Upstream reference: fdlibm (Berkeley math library) provides platform-independent
# implementations of standard math functions (sin, cos, sqrt, atan2, etc.) that produce
# identical results on all architectures when compiled with the same precision flags.

# Enable deterministic math only for non-VC6 builds (VC6 uses native x87 asm)
#
# GeneralsX @build Android port 20/10/2026 Default stays OFF on purpose. Upstream PR #2670
# (merged 18/10/2026) makes GameMath the default for non-VC6 builds, but the deployed
# GeneralsOnline PC client (last commit 03/10/2026) still computes the pre-GameMath math
# (32-bit MSVC CRT: float transcendentals promote to double, evaluated once). This port
# matches that contract bit-for-bit today (docs/WORKDIR/lessons/LESSON-cross-play-desync-method.md),
# so flipping this on prematurely would desync every Android vs PC client that has not yet
# updated. Flip it ON the day the GeneralsOnline client ships GameMath; see
# docs/WORKDIR/planning/PLAN-024_UPSTREAM_DETERMINISTIC_MATH.md.
if(NOT IS_VS6_BUILD)
    option(SAGE_USE_DETERMINISTIC_MATH "Use fdlibm-based deterministic math for cross-platform replay validation" OFF)
else()
    # VC6 uses native x87 inline asm; deterministic mode not applicable
    set(SAGE_USE_DETERMINISTIC_MATH OFF)
endif()

if(SAGE_USE_DETERMINISTIC_MATH)
    message(STATUS "Configuring GameMath (fdlibm-based deterministic math)...")

    include(FetchContent)

    # FORCE is required to guarantee cross-platform bit-exact determinism.
    # Intrinsics would use platform-specific SIMD, breaking CRC parity between architectures.
    set(GM_ENABLE_INTRINSICS OFF CACHE BOOL "Disable intrinsics for cross-arch determinism" FORCE)
    set(GM_ENABLE_TESTS OFF CACHE BOOL "Disable GameMath tests" FORCE)
    set(gamemath_SHARED_LIBS OFF CACHE BOOL "Force GameMath as static lib" FORCE)

    # FetchContent declaration for GameMath library
    # Source: TheSuperHackers standalone fdlibm-based GameMath library.
    # Can be overridden via cmake -DSAGE_GAMEMATH_GIT_REPO=<url> -DSAGE_GAMEMATH_GIT_TAG=<tag>
    if(NOT SAGE_GAMEMATH_GIT_REPO)
        set(SAGE_GAMEMATH_GIT_REPO "https://github.com/TheSuperHackers/GameMath.git")
    endif()

    if(NOT SAGE_GAMEMATH_GIT_TAG)
        # Pinned SHA: tracking the repo let the deterministic-math source drift silently.
        set(SAGE_GAMEMATH_GIT_TAG "59f7ccd494f7e7c916a784ac26ef266f9f09d78d")
    endif()

    FetchContent_Declare(
        gamemath
        GIT_REPOSITORY ${SAGE_GAMEMATH_GIT_REPO}
        GIT_TAG ${SAGE_GAMEMATH_GIT_TAG}
    )

    # Make GameMath available (FetchContent_MakeAvailable is idempotent)
    FetchContent_MakeAvailable(gamemath)

    # Ensure GameMath includes are available to ALL targets (wwmath.h includability drives
    # the conditional dispatch), preventing one-definition-rule violations and ensuring
    # USE_DETERMINISTIC_MATH activates consistently.
    include_directories(${gamemath_SOURCE_DIR}/include)

    # Add USE_DETERMINISTIC_MATH to all compile definitions for this project
    # This enables conditional compilation in wwmath.h and trig wrappers
    add_compile_definitions(USE_DETERMINISTIC_MATH)

    message(STATUS "GameMath deterministic math enabled (fdlibm backend)")
    message(STATUS "  Math operations will be bit-exact across platforms")
    message(STATUS "  Performance: Slightly slower than CRT but guarantees replay determinism")

else()
    message(STATUS "Deterministic math disabled (SAGE_USE_DETERMINISTIC_MATH=OFF) -- shipped default")
    message(STATUS "  Math operations will use the measured VC6-contract double-and-narrow path")
    message(STATUS "  Note: Flip ON only when the GeneralsOnline PC client ships GameMath (PLAN-024)")
endif()
