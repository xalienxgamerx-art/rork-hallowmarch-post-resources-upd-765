package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The USE hand: what it takes, what it leaves, and what the chronicle says of it. */
class InteractionSystemTest {

    private val world = WorldGenerator.generate(4242L)

    private fun engine(): GameEngine = GameEngine(world, null, null).apply { climbToOpenGround() }

    /** A stretch of the open road with no way out and no soul within a shout. */
    private fun emptySpot(engine: GameEngine) {
        val portals = engine.map.portals
        val map = engine.map
        outer@ for (y in 2 until map.height - 2) {
            for (x in 2 until map.width - 2) {
                if (map.isWall(x.toFloat(), y.toFloat())) continue
                val fx = x + 0.5f
                val fy = y + 0.5f
                val clear = portals.all { MapFactory.distance(fx, fy, it.x, it.y) > 8f } &&
                    map.entities.none {
                        MapFactory.distance(fx, fy, it.x, it.y) < 3f && (it.traveler || it.resident || it.alive)
                    }
                if (clear) {
                    engine.camera.x = fx
                    engine.camera.y = fy
                    return
                }
            }
        }
        error("the open road keeps no empty stretch")
    }

    private fun foe(): Entity = MapFactory.rollEnemy(Random(13L), 2f, 2f, 2, null)

    @Test
    fun theLaidDownThingComesBackToTheHand() {
        val engine = engine()
        emptySpot(engine)
        val thing = Item(ItemArchetype.BLADE, Material.STEEL, Quality.HONEST)
        engine.inventory.add(thing)
        engine.dropItem(thing)
        assertEquals(1, engine.groundItems.size)
        assertTrue(engine.usePrompt().startsWith("Take up the "))

        val taken = engine.interact()
        assertEquals(Interact.PICKED, taken)
        assertTrue(engine.inventory.all.contains(thing))
        assertTrue(engine.groundItems.isEmpty())
        assertTrue(
            "the chronicle notes the taking: ${engine.log.last().text}",
            engine.log.last().text.startsWith("You take up the ")
        )
    }

    @Test
    fun anEmptyStretchOfRoadGivesTheHandNothing() {
        val engine = engine()
        emptySpot(engine)
        assertEquals(Interact.NONE, engine.interact())
        assertEquals("", engine.usePrompt())
    }

    @Test
    fun theHandReachesOnlyAnArmsLength() {
        val engine = engine()
        emptySpot(engine)
        val thing = Item(ItemArchetype.BLADE, Material.STEEL, Quality.HONEST)
        engine.groundItems += GroundItem(engine.camera.x + 3.0f, engine.camera.y, thing)
        assertNull(engine.interaction.nearestDrop())

        engine.groundItems.clear()
        engine.groundItems += GroundItem(engine.camera.x + 0.5f, engine.camera.y, thing)
        assertEquals(thing, engine.interaction.nearestDrop()?.item)
    }

    @Test
    fun theNearestThingClaimsTheHand() {
        val engine = engine()
        emptySpot(engine)
        val near = Item(ItemArchetype.BLADE, Material.STEEL, Quality.HONEST)
        val far = Item(ItemArchetype.BLADE, Material.IRON, Quality.HONEST)
        engine.groundItems += GroundItem(engine.camera.x + 0.9f, engine.camera.y, far)
        engine.groundItems += GroundItem(engine.camera.x + 0.2f, engine.camera.y, near)

        assertEquals(Interact.PICKED, engine.interact())
        assertTrue(engine.inventory.all.contains(near))
        assertFalse(engine.inventory.all.contains(far))
        assertEquals(1, engine.groundItems.size)
        assertEquals(far, engine.groundItems.first().item)
    }

    @Test
    fun aCorpseYieldsItsSpoilsToTheHand() {
        val engine = engine()
        emptySpot(engine)
        engine.map.entities.removeAll { it.kind == EntityKind.ENEMY }
        val dead = foe()
        dead.x = engine.camera.x + 0.5f
        dead.y = engine.camera.y
        dead.alive = false
        val keepsake = Item(ItemArchetype.BLADE, Material.STEEL, Quality.HONEST)
        dead.loot += keepsake
        dead.lootBrass = 7
        engine.map.entities += dead

        assertEquals(dead, engine.interaction.lootableHere())
        val brassBefore = engine.brass
        val taken = engine.interaction.takeAll(dead)
        assertTrue(taken >= 1)
        assertEquals(brassBefore + 7, engine.brass)
        assertTrue(dead.loot.isEmpty())
        assertEquals(0, dead.lootBrass)
        assertTrue(engine.inventory.all.contains(keepsake))
        assertTrue(
            "the chronicle notes the emptying: ${engine.log.last().text}",
            engine.log.last().text ==
                "You empty the ${dead.name}: $taken ${if (taken == 1) "thing" else "things"} and 7 brass."
        )
        assertNull("an emptied corpse is no longer spoils", engine.interaction.lootableHere())
    }

    @Test
    fun anEmptiedKeepersChestCostsThePlaceItsRegard() {
        val engine = engine()
        val site = world.sites.first { it.isSettlement && !it.ruined && it.population > 0 }
        engine.revealLandmark(site.id)
        val door = engine.map.portals.first { it.targetSiteId == site.id }
        engine.camera.x = door.x
        engine.camera.y = door.y
        engine.usePortal()
        emptySpot(engine)

        val chest = Entity(
            x = engine.camera.x + 0.5f, y = engine.camera.y,
            spriteId = Sprites.PILGRIM, kind = EntityKind.PROP, height = 1.0f,
            name = "keeper's chest", container = true, persistId = "keepers-chest"
        )
        chest.loot += Item(ItemArchetype.BLADE, Material.STEEL, Quality.HONEST)
        engine.map.entities += chest

        val regardBefore = engine.reputation.regardFor(site.id)
        assertEquals(1, engine.interaction.takeAll(chest))
        assertTrue(engine.worldState.isEmptied("keepers-chest"))
        assertEquals(regardBefore - 2, engine.reputation.regardFor(site.id))
        assertTrue(
            engine.log.last().text == "The keeper's chest will be missed, and the loss remembered."
        )
    }

    @Test
    fun theSameFingersMeetTheSameTraveler() {
        fun rigged(): GameEngine {
            val engine = engine()
            emptySpot(engine)
            engine.map.entities.removeAll { it.kind == EntityKind.ENEMY }
            engine.map.entities += Entity(
                x = engine.camera.x + 0.8f, y = engine.camera.y,
                spriteId = Sprites.PILGRIM, kind = EntityKind.PROP, height = 1.05f,
                name = "peddler", traveler = true
            )
            return engine
        }
        val a = rigged()
        val b = rigged()

        assertEquals(Interact.TRAVELER, a.interact())
        assertEquals(Interact.TRAVELER, b.interact())
        assertTrue(a.map.entities.none { it.traveler })
        assertTrue(b.map.entities.none { it.traveler })
        // The same road, the same rolls: the two meetings tell the same tale.
        assertEquals(a.log.map { it.text }, b.log.map { it.text })
        assertTrue(
            "the chronicle notes the meeting",
            a.log.any { it.text.startsWith("The peddler") }
        )
    }

    @Test
    fun pocketsAskACrouchAndTwinFingersStealAlike() {
        fun rigged(): Pair<GameEngine, Entity> {
            val engine = engine()
            emptySpot(engine)
            engine.map.entities.removeAll { it.kind == EntityKind.ENEMY }
            val mark = foe()
            mark.x = engine.camera.x + 1.0f
            mark.y = engine.camera.y
            engine.map.entities += mark
            return engine to mark
        }

        // Standing, the fingers stay at your sides.
        val (standing, mark) = rigged()
        assertFalse(standing.pickpocket())
        assertEquals(Detection.UNAWARE, mark.detection)
        assertTrue(standing.log.last().text == "You cannot pick a pocket standing up.")

        // A wary pocket keeps its coins when the hand is too far.
        val (crouched, farMark) = rigged()
        crouched.toggleCrouch()
        farMark.x = crouched.camera.x + 2.0f
        assertFalse(crouched.pickpocket())
        assertEquals(Detection.UNAWARE, farMark.detection)
        assertTrue(crouched.log.last().text == "No unwary pocket within reach.")

        // The same seed, the same fingers: the same theft or the same start.
        val (a, markA) = rigged()
        val (b, markB) = rigged()
        a.toggleCrouch()
        b.toggleCrouch()
        val brassA = a.brass
        val brassB = b.brass
        assertEquals(a.pickpocket(), b.pickpocket())
        assertEquals(brassA, brassB)
        assertEquals(a.brass, b.brass)
        assertEquals(markA.detection, markB.detection)
        assertEquals(a.log.map { it.text }, b.log.map { it.text })
    }

    @Test
    fun theWorldRemembersTheEmptiedChestThroughTheSave() {
        val engine = engine()
        val site = world.sites.first { it.isSettlement && !it.ruined && it.population > 0 }
        engine.revealLandmark(site.id)
        val door = engine.map.portals.first { it.targetSiteId == site.id }
        engine.camera.x = door.x
        engine.camera.y = door.y
        engine.usePortal()
        emptySpot(engine)

        val chest = Entity(
            x = engine.camera.x + 0.5f, y = engine.camera.y,
            spriteId = Sprites.PILGRIM, kind = EntityKind.PROP, height = 1.0f,
            name = "keeper's chest", container = true, persistId = "keepers-chest"
        )
        chest.loot += Item(ItemArchetype.BLADE, Material.STEEL, Quality.HONEST)
        engine.map.entities += chest
        assertEquals(1, engine.interaction.takeAll(chest))

        val restored = GameEngine(world, engine.toSaveSlot(), null)
        assertTrue(restored.worldState.isEmptied("keepers-chest"))
        val restoredChest = restored.map.entities.firstOrNull { it.persistId == "keepers-chest" }
        if (restoredChest != null) {
            assertTrue(restoredChest.loot.isEmpty())
        }
    }

    @Test
    fun theHandTakesTheWayThroughTheDoor() {
        val engine = engine()
        val door = engine.map.portals.first { it.targetSiteId == world.vaultSiteId }
        engine.camera.x = door.x
        engine.camera.y = door.y

        assertEquals(door.prompt, engine.usePrompt())
        assertEquals(Interact.PORTAL, engine.interact())
        assertFalse("the way led under", engine.onOverland)
    }
}
