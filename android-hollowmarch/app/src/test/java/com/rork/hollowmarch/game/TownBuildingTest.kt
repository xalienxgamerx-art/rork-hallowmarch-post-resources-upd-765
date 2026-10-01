package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The town's own doors: a home takes its keeper's name, a home stands at least
 * four tiles of house plus one of entryway, every door is wood shut in the wall
 * — and only the door itself answers the hand, never the flank of the house.
 */
class TownBuildingTest {

    private val world = WorldGenerator.generate(424242L)
    private val settlements = world.sites
        .filter { it.isSettlement && !it.ruined && it.population > 0 }

    private fun village() = settlements.first { baseStageOf(it.kind) == SettlementStage.VILLAGE }

    @Test
    fun aHouseTakesItsKeepersName() {
        val surface = SiteGen.map(world, village(), 0, null, null, 1)
        val houses = surface.buildings.filter { it.kind == "house" }
        assertTrue("the village keeps houses", houses.isNotEmpty())
        houses.forEach { b ->
            assertTrue("${b.name} keeps a keeper", b.keeper.isNotBlank())
            assertTrue(
                "\"${b.name}\" is named for its keeper ${b.keeper}",
                b.name.startsWith("${b.keeper}'s ")
            )
            val i = surface.buildings.indexOf(b)
            val portal = surface.portals.first { it.targetFloor == SiteGen.BUILDING_FLOOR_BASE + i }
            assertEquals("the door of ${b.name}", portal.label)
            assertEquals("Enter ${b.name}", portal.prompt)
        }
        // the public works keep their own names, keeper or no
        surface.buildings.filter { it.kind != "house" && it.kind != "tent" }.forEach { b ->
            assertFalse(
                "${b.kind} keeps its own name",
                b.name.startsWith("${b.keeper}'s ")
            )
        }
    }

    @Test
    fun aHomeStandsFourTilesDeepPlusItsEntryway() {
        val surface = SiteGen.map(world, village(), 0, null, null, 1)
        val houses = surface.buildings.filter { it.kind == "house" }
        assertTrue(houses.isNotEmpty())
        houses.forEach { b ->
            var interior = 0
            for (y in b.y until b.y + b.h) {
                for (x in b.x until b.x + b.w) {
                    if (surface.walls[y * surface.width + x] == 0) interior++
                }
            }
            assertTrue(
                "${b.name} keeps four tiles of home and one of entryway, but only $interior interior tiles",
                interior >= 4
            )
            assertEquals(
                "${b.name} keeps its entryway shut in the wall",
                Textures.WALL_DOOR,
                surface.walls[b.doorY * surface.width + b.doorX]
            )
        }
    }

    @Test
    fun aHomeStandsFourTilesDeepInTownToo() {
        val town = settlements.first { baseStageOf(it.kind).ordinal >= SettlementStage.TOWN.ordinal }
        val surface = SiteGen.map(world, town, 0, null, null, 1)
        val houses = surface.buildings.filter { it.kind == "house" }
        assertTrue(houses.isNotEmpty())
        houses.forEach { b ->
            assertTrue(
                "${b.name} keeps a home of at least four across: ${b.w}x${b.h}",
                b.w >= 4 && b.h >= 4
            )
        }
    }

    @Test
    fun theInteriorKeepsItsOwnDoor() {
        val site = village()
        val surface = SiteGen.map(world, site, 0, null, null, 1)
        val b = surface.buildings.first { it.kind == "house" }
        val room = SiteGen.buildingInterior(
            world, site, surface.buildings.indexOf(b), b,
            SiteGen.cultureId(world, site), 1, null
        )
        assertEquals(
            "${b.name} keeps its door inside too",
            Textures.WALL_DOOR,
            room.walls[(b.doorY - b.y) * b.w + (b.doorX - b.x)]
        )
        assertEquals("the door", room.portals.first().label)
        assertEquals("Step out of ${b.name}", room.portals.first().prompt)
    }

    @Test
    fun theDoorAnswersOnlyAtTheThreshold() {
        val engine = GameEngine(world, null, null).apply { climbToOpenGround() }
        val site = village()
        engine.revealLandmark(site.id)
        val gate = engine.map.portals.first { it.targetSiteId == site.id }
        engine.camera.x = gate.x
        engine.camera.y = gate.y
        engine.usePortal()
        val yard = engine.map
        // a soul or a chest standing on the step answers before the door does:
        // take a door whose threshold stands clear
        val portal = yard.portals.first { p ->
            if (p.targetFloor < SiteGen.BUILDING_FLOOR_BASE) return@first false
            val s = yard.arrivalSpots.getOrNull(p.targetFloor - SiteGen.BUILDING_FLOOR_BASE)
                ?: return@first false
            val tx = p.x
            val ty = p.y + (s.second - p.y).coerceSignedUnit()
            yard.entities.none { e ->
                (e.resident || e.container != null) &&
                    (e.x - tx) * (e.x - tx) + (e.y - ty) * (e.y - ty) <= 1.0f
            }
        }
        val spot = yard.arrivalSpots[portal.targetFloor - SiteGen.BUILDING_FLOOR_BASE]
        // at the door: it answers
        engine.camera.x = portal.x
        engine.camera.y = portal.y + (spot.second - portal.y).coerceSignedUnit()
        assertTrue(
            "the door answers at its own threshold: \"${engine.usePrompt()}\"",
            engine.usePrompt().contains("Enter")
        )
        // two paces along the wall from the door: the house stays shut to you
        val flankX = portal.x + 2.4f
        val flankY = portal.y
        if (!yard.isWall(flankX, flankY) && !yard.doorwayAt(flankX, flankY)) {
            engine.camera.x = flankX
            engine.camera.y = flankY
            assertFalse(
                "the house ignores a hand at its flank: \"${engine.usePrompt()}\"",
                engine.usePrompt().contains("Enter")
            )
        }
    }

    private fun Float.coerceSignedUnit(): Float =
        if (this > 0f) 1f else if (this < 0f) -1f else 1f
}
