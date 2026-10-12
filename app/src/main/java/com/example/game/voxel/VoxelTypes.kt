package com.example.game.voxel

object VoxelTypes {
    const val V_EMPTY: Byte = 0
    const val V_FLOOR: Byte = 1
    const val V_WALL: Byte = 2
    const val V_CRACK: Byte = 3
    const val V_COVER: Byte = 4

    const val VOX_SIZE: Float = 0.5f // 0.5 meters per base voxel
    const val GRID_W: Int = 128      // 64 meters
    const val GRID_D: Int = 128      // 64 meters
    const val GRID_H: Int = 16       // 8 meters per level
    const val WALL_HEIGHT_VOXELS: Int = 6 // 3.0 meters tall
    const val COVER_HEIGHT_VOXELS: Int = 2 // 1.0 meter waist height

    // Palette Colors (RGB float arrays)
    val COLOR_FLOOR = floatArrayOf(0.11f, 0.16f, 0.20f, 1.0f)     // Lifted dark tactical blue
    val COLOR_WALL = floatArrayOf(0.20f, 0.26f, 0.30f, 1.0f)      // Tactical concrete slate (#34424D)
    val COLOR_CRACK = floatArrayOf(0.42f, 0.24f, 0.22f, 1.0f)     // Thermite breachable reddish tint
    val COLOR_COVER = floatArrayOf(0.41f, 0.48f, 0.52f, 1.0f)     // Steel crate cover (#687984)
    val COLOR_ACCENT_CYAN = floatArrayOf(0.37f, 0.82f, 0.79f, 1.0f)
    val COLOR_ACCENT_AMBER = floatArrayOf(0.91f, 0.72f, 0.29f, 1.0f)
    val COLOR_ACCENT_RED = floatArrayOf(0.85f, 0.29f, 0.29f, 1.0f)
}
