package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The masonry inspection: no roof ever floats — every house stands complete. */
class MasonryTest {

    /** Every roof keeps an unbroken wall ring beneath it, plank door in its place. */
    private fun assertMasonryWhole(map: GameMap, label: String) {
        assertEquals(
            "$label: every house wears its own roof", map.buildings.size, map.roofs.size
        )
        map.buildings.forEach { b ->
            val roof = map.roofs.firstOrNull {
                it.x0 == b.x && it.y0 == b.y && it.x1 == b.x + b.w && it.y1 == b.y + b.h
            }
            assertTrue("$label: no roof spans ${b.name}", roof != null)
            roof ?: return@forEach
            for (y in b.y until b.y + b.h) {
                for (x in b.x until b.x + b.w) {
                    val perimeter =
                        x == b.x || x == b.x + b.w - 1 || y == b.y || y == b.y + b.h - 1
                    val idx = y * map.width + x
                    val wall = map.walls[idx]
                    if (!perimeter) {
                        assertEquals(
                            "$label: ${b.name} keeps an open floor at $x,$y", 0, wall
                        )
                        continue
                    }
                    val isDoor = x == b.doorX && y == b.doorY
                    assertTrue("$label: gap in the wall of ${b.name} at $x,$y", wall != 0)
                    if (isDoor) {
                        assertEquals(
                            "$label: the door of ${b.name} is a plank door",
                            Textures.WALL_DOOR, wall
                        )
                    } else {
                        assertNotEquals(
                            "$label: stray door in the wall of ${b.name} at $x,$y",
                            Textures.WALL_DOOR, wall
                        )
                        assertEquals(
                            "$label: the wall of ${b.name} rises to its eave at $x,$y",
                            roof.eave, map.wallHeight(x, y), 1e-4f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `every house in every stage stands complete, seed after seed`() {
        for (seed in longArrayOf(424242L, 777L, 20260923L)) {
            val world = WorldGenerator.generate(seed)
            val stead = world.sites
                .filter { it.isSettlement && !it.ruined && it.population > 0 }
                .first { baseStageOf(it.kind) == SettlementStage.VILLAGE }
            for (folk in intArrayOf(100, 500, 3000, 8000)) {
                val map = SiteGen.map(world, stead, 0, null, null, 1, folk = folk)
                assertTrue("seed $seed folk $folk: no houses standing", map.buildings.isNotEmpty())
                assertMasonryWhole(map, "seed $seed folk $folk")
            }
        }
    }

    @Test
    fun `the warband's tents stand complete too`() {
        val world = WorldGenerator.generate(424242L)
        val stead = world.sites
            .filter { it.isSettlement && !it.ruined && it.population > 0 }
            .first { baseStageOf(it.kind) == SettlementStage.VILLAGE }
        val camp = SiteGen.map(world, stead, 0, null, null, 1, folk = 0)
        assertTrue("the camp has tents", camp.buildings.isNotEmpty())
        assertMasonryWhole(camp, "camp")
    }

    @Test
    fun `the province's landmark houses stand complete`() {
        val world = WorldGenerator.generate(424242L)
        val city = world.sites
            .filter { it.isSettlement && !it.ruined && it.population > 0 }
            .first { baseStageOf(it.kind) == SettlementStage.CITY }
        val map = GameMap(48, 48, outdoor = true, title = "province")
        for (i in map.floorTex.indices) map.floorTex[i] = Textures.FLOOR_GRASS
        OverlandGen.drawLandmark(map, city, SettlementStage.CITY, 24, 24)
        assertTrue("the landmark has houses", map.buildings.isNotEmpty())
        assertMasonryWhole(map, "landmark")
    }
}
