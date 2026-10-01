package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The presentation boundary's own ledger: the hands reach the engine through
 * one immutable [PlayerInput] and nothing else; the HUD and the frame the
 * renderer draws are built wholly inside the engine, carrying exactly what the
 * screen was handed before the boundary closed.
 */
class PresentationBoundaryTest {

    private val world = WorldGenerator.generate(90210L)

    @Before
    fun resetUids() = ItemUids.reset()

    private fun overlandEngine(): GameEngine =
        GameEngine(world, null, null).apply { climbToOpenGround() }

    private fun vaultEngine(): GameEngine = GameEngine(world, null, null)

    // ------------------------------------------------------------- input

    @Test
    fun `acceptInput replaces every held channel whole`() {
        val engine = overlandEngine()
        engine.acceptInput(PlayerInput(move = 0.7f, strafe = -0.3f, turn = 0.2f, look = 0.4f))
        assertEquals(0.7f, engine.moveInput, 1e-6f)
        assertEquals(-0.3f, engine.strafeInput, 1e-6f)
        assertEquals(0.2f, engine.turnInput, 1e-6f)
        assertEquals(0.4f, engine.lookInput, 1e-6f)

        // a later command replaces, never merges: an axis released reads zero
        engine.acceptInput(PlayerInput(move = -1f))
        assertEquals(-1f, engine.moveInput, 1e-6f)
        assertEquals(0f, engine.strafeInput, 1e-6f)
        assertEquals(0f, engine.turnInput, 1e-6f)
        assertEquals(0f, engine.lookInput, 1e-6f)
    }

    @Test
    fun `held input persists across ticks until replaced`() {
        val engine = overlandEngine()
        engine.acceptInput(PlayerInput(move = 0.5f, turn = 0.6f))
        engine.update(0.05f)
        assertEquals(0.5f, engine.moveInput, 1e-6f)
        assertEquals(0.6f, engine.turnInput, 1e-6f)
    }

    @Test
    fun `input channels have no write path outside the engine`() {
        val channels = setOf("moveInput", "strafeInput", "turnInput", "lookInput")
        val fields = GameEngine::class.java.declaredFields.filter { it.name in channels }
        assertEquals(channels, fields.map { it.name }.toSet())
        fields.forEach { field ->
            assertTrue(
                "${field.name} must be private so only acceptInput writes it",
                Modifier.isPrivate(field.modifiers)
            )
        }
    }

    @Test
    fun `player input rests at zero`() {
        assertEquals(PlayerInput(), PlayerInput(move = 0f, strafe = 0f, turn = 0f, look = 0f))
    }

    // ------------------------------------------------------------- HUD snapshot

    @Test
    fun `hudSnapshot carries what the screen was handed before`() {
        val engine = overlandEngine()
        engine.pushLog("A word goes into the chronicle.")
        engine.pushLog("And a second word after it.")

        val snap = engine.hudSnapshot()

        assertEquals(engine.map.title, snap.locationTitle)
        assertEquals(engine.heading, snap.heading)
        assertEquals(
            "${engine.terrainLabel()} · ${engine.skyWord()} · " +
                "${engine.hour}:${engine.minute.toString().padStart(2, '0')}",
            snap.depthLabel
        )
        assertEquals(engine.vitality, snap.vitality)
        assertEquals(engine.maxVitality, snap.maxVitality)
        assertEquals(engine.fatigue, snap.fatigue)
        assertEquals(engine.maxFatigue, snap.maxFatigue)
        assertEquals(engine.magicka, snap.magicka)
        assertEquals(engine.maxMagicka, snap.maxMagicka)
        assertEquals(engine.torch, snap.torch, 1e-6f)
        assertEquals(engine.outdoor, snap.outdoor)
        assertEquals(engine.usePrompt(), snap.usePrompt)
        assertEquals(engine.day, snap.day)
        assertEquals(
            "${engine.hour}:${engine.minute.toString().padStart(2, '0')}",
            snap.clock
        )
        assertEquals(engine.timeOfDayLabel, snap.timeOfDay)
        assertEquals(engine.brass, snap.brass)
        assertEquals(engine.kills, snap.kills)
        assertEquals((engine.map.exploredFraction() * 100).roundToInt(), snap.explored)
        assertEquals(engine.dead, snap.dead)
        assertEquals(engine.wardTimer > 0f, snap.warded)
        assertEquals(engine.growth.level, snap.level)
        assertEquals(engine.klass?.name ?: "", snap.className)
        assertEquals(engine.crouched, snap.crouched)
        assertEquals(engine.blocking, snap.blocking)
        assertEquals(
            when (engine.detectionState()) {
                DetectionState.SEEN -> "seen"
                DetectionState.SEARCHING -> "searching"
                DetectionState.HIDDEN -> "hidden"
                DetectionState.NONE -> ""
            },
            snap.detection
        )
        assertEquals(engine.inventory.weight(), snap.load, 1e-4f)
        assertEquals(engine.carryCapacity(), snap.capacity, 1e-4f)
        assertEquals(engine.inventory.all.size, snap.satchelCount)
        assertEquals(engine.rangedHud() ?: "", snap.rangedLine)
        assertTrue("on the open province the HUD says so", snap.overland)
    }

    @Test
    fun `hudSnapshot fades the last two log lines by their age`() {
        val engine = overlandEngine()
        engine.log += LogLine("an old line", age = 4.5f) // half faded
        engine.pushLog("a fresh line")

        val lines = engine.hudSnapshot().lines

        assertEquals(listOf("an old line", "a fresh line"), lines.map { it.text })
        assertEquals(0.6f, lines[0].alpha, 1e-4f)
        assertEquals(1f, lines[1].alpha, 1e-4f)
    }

    @Test
    fun `hudSnapshot reads the deep by depth and torch underground`() {
        val engine = vaultEngine()
        assertFalse(engine.outdoor)
        assertFalse(engine.onOverland)

        val snap = engine.hudSnapshot()

        assertEquals("${engine.depth}·${(engine.torch * 100).roundToInt()}", snap.depthLabel)
        assertEquals("", snap.rangedLine)
        assertFalse(snap.overland)
    }

    @Test
    fun `hudSnapshot is a pure reading of the moment`() {
        val engine = overlandEngine()
        assertEquals(engine.hudSnapshot(), engine.hudSnapshot())
    }

    // ------------------------------------------------------------- render scene

    @Test
    fun `buildRenderScene carries what the renderer was handed before, outdoors`() {
        val engine = overlandEngine()

        val scene = engine.buildRenderScene()

        assertSame(engine.map, scene.map)
        assertSame(engine.camera, scene.camera)
        assertEquals(engine.litTorch, scene.torch, 1e-6f)
        assertEquals(engine.skyLight, scene.outdoorLight, 1e-6f)
        val expectedBearing = engine.skylineBearing()
            ?: atan2(2f - engine.camera.y, (engine.map.width / 2f) - engine.camera.x)
        assertEquals(expectedBearing, scene.townBearing, 1e-5f)
        assertEquals(1f, scene.townDistance, 1e-6f)
        assertEquals(engine.hurtFlash, scene.hurtFlash, 1e-6f)
        assertEquals(engine.strikeArc, scene.strikeArc, 1e-6f)
        assertEquals(engine.swingPhase, scene.swingPhase, 1e-6f)
        assertSame(engine.groundItems, scene.groundItems)
        assertEquals(engine.holdsTorch, scene.heldTorch)
        assertEquals(engine.heldWeaponSpriteId, scene.heldWeaponSprite)
        assertEquals(engine.heldWeaponTint, scene.heldWeaponTint)
        assertEquals(engine.heldShieldSpriteId, scene.heldShieldSprite)
        assertEquals(engine.heldShieldTint, scene.heldShieldTint)
        assertEquals(engine.shieldRaise, scene.shieldRaise, 1e-6f)
        assertSame(engine.sky, scene.sky)
        assertEquals(engine.animTime, scene.clock, 1e-6f)
        assertEquals(engine.day, scene.dayNumber)
        assertEquals(engine.timeOfDay, scene.timeOfDay, 1e-6f)
        assertEquals(engine.weather, scene.weather)
        assertEquals(engine.auroraStrength(), scene.aurora, 1e-6f)
        assertEquals(
            SkyEvents.meteors(
                world.seed, engine.day, engine.animTime,
                engine.outdoorLight < 0.25f, engine.weather.cloud
            ),
            scene.meteors
        )
        assertSame(engine.projectiles, scene.projectiles)
    }

    @Test
    fun `buildRenderScene keeps the underground bare of sky`() {
        val engine = vaultEngine()

        val scene = engine.buildRenderScene()

        assertNull(scene.sky)
        assertEquals(0f, scene.townDistance, 1e-6f)
        assertTrue(scene.meteors.isEmpty())
        assertEquals(engine.skyLight, scene.outdoorLight, 1e-6f)
    }
}
