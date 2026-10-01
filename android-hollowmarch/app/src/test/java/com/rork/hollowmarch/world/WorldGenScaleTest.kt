package com.rork.hollowmarch.world

import com.rork.hollowmarch.game.MapFactory
import com.rork.hollowmarch.game.OverlandGen
import com.rork.hollowmarch.world.WORLD_LEAGUES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The province's scale, born not budgeted: whatever the centuries produce.
 * These tests pin the contract without pinning a number — determinism holds,
 * seeds and histories differ, and the land measures half of what Daggerfall
 * recorded, sized so a phone forges it in seconds without freezing.
 */
class WorldGenScaleTest {

    companion object {
        private val world777 by lazy { WorldGenerator.generate(777L) }
        private val world778 by lazy { WorldGenerator.generate(778L) }
        private val short777 by lazy { WorldGenerator.generate(777L, historyYears = 150) }
        private val long777 by lazy { WorldGenerator.generate(777L, historyYears = 600) }
    }

    private fun soulsOf(world: World): Int =
        world.sites.filter { it.isSettlement && !it.ruined }.sumOf { it.population }

    private fun livingRealms(world: World): Int = world.realms.count { !it.extinct }

    @Test
    fun landMeasuresHalfOfDaggerfall() {
        // The walked map spans the world's own measure: 42 leagues corner to corner.
        val leagues = OverlandGen.LEAGUES_PER_CELL * (OverlandGen.SIZE - 1)
        assertEquals(WORLD_LEAGUES, leagues, 0.01f)
        assertEquals("half of Daggerfall's recorded land is 42 leagues a side", 42f, WORLD_LEAGUES, 0f)
    }

    @Test
    fun journeysScaleWithTrueDistances() {
        val world = world777
        val far = world.sites.maxBy { it.x }
        val near = world.sites.minBy { it.x }
        if (far == null || near == null) return
        val leagues = MapFactory.distance(far.x, far.y, near.x, near.y) * WORLD_LEAGUES
        assertTrue(
            "crossing the province is a true expedition, measured $leagues leagues",
            leagues > 15f
        )
    }

    @Test
    fun sameSeedSameScale() {
        val a = WorldGenerator.generate(90210L)
        val b = WorldGenerator.generate(90210L)
        assertEquals(a.sites.size, b.sites.size)
        assertEquals(soulsOf(a), soulsOf(b))
        assertEquals(livingRealms(a), livingRealms(b))
        assertEquals(
            a.sites.map { it.name to it.population },
            b.sites.map { it.name to it.population }
        )
    }

    @Test
    fun countsEmergeFromTheCenturies() {
        // No number is told to the forge: longer histories fill more of the land,
        // and different seeds discover different provinces.
        assertTrue(
            "a longer history fills more of the land: " +
                "${short777.sites.size} vs ${long777.sites.size}",
            long777.sites.size > short777.sites.size
        )
        assertNotEquals(
            "seeds must not share one fate",
            world777.sites.map { it.name }.sorted(),
            world778.sites.map { it.name }.sorted()
        )
    }

    @Test
    fun theProvinceFillsTheLand() {
        val world = world777
        val living = world.sites.filter { it.isSettlement && !it.ruined }
        println(
            "SCALE seed=777: places=${world.sites.size} living=${living.size} " +
                "souls=${soulsOf(world)} realms=${livingRealms(world)} " +
                "cities=${world.sites.count { it.kind == SiteKind.CITY }} " +
                "towns=${world.sites.count { it.kind == SiteKind.TOWN }} " +
                "capitals=${world.sites.count { it.kind == SiteKind.CAPITAL }} " +
                "wild=${world.sites.count { !it.isSettlement }}"
        )
        println(
            "SCALE seed=778: places=${world778.sites.size} souls=${soulsOf(world778)} " +
                "realms=${livingRealms(world778)} short=${short777.sites.size} " +
                "long=${long777.sites.size}"
        )
        assertTrue(
            "centuries on half a continent cannot hold fewer than eight hundred places, had ${world.sites.size}",
            world.sites.size >= 800
        )
        assertTrue(
            "souls are counted, not set: had ${soulsOf(world)}",
            soulsOf(world) >= 200_000
        )
        assertTrue(
            "many realms may hold court at once: had ${livingRealms(world)}",
            livingRealms(world) >= 4
        )
        assertTrue(
            "great places live fully in the simulation",
            living.any { it.kind == SiteKind.CITY || it.kind == SiteKind.CAPITAL }
        )
    }

    @Test
    fun creationFinishesWithinBudget() {
        val start = System.currentTimeMillis()
        WorldGenerator.generate(90210L)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(
            "world creation must finish in seconds on device hardware, took ${elapsed}ms",
            elapsed < 20_000
        )
    }
}
