package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.isSettlement
import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Test

/** A testing choice on the forge sheet: wake behind any door in the province. */
class SpawnDoorTest {

    private val world = WorldGenerator.generate(424242L)

    @Test
    fun aDelverMayWakeBehindAnySettlementTheyAskFor() {
        val vault = world.site(world.vaultSiteId)
        val asked = world.sites.first { it.isSettlement && it.id != vault.id }

        val engine = GameEngine(world, null, DelverCreation(startSiteId = asked.id))

        assertEquals("the delver wakes behind the asked door", asked.id, engine.currentSiteId)
    }

    @Test
    fun aRuinedDoorMayBeAskedToo() {
        val asked = world.sites.first { it.ruined }

        val engine = GameEngine(world, null, DelverCreation(startSiteId = asked.id))

        assertEquals(asked.id, engine.currentSiteId)
    }

    @Test
    fun withoutTheChoiceTheVaultStillReceivesThem() {
        val engine = GameEngine(world, null, DelverCreation())
        assertEquals(world.vaultSiteId, engine.currentSiteId)
    }

    @Test
    fun anUnknownIdFallsBackToTheVault() {
        val engine = GameEngine(world, null, DelverCreation(startSiteId = 99999))
        assertEquals(world.vaultSiteId, engine.currentSiteId)
    }

    @Test
    fun anOldSaveStillWakesWhereItStood() {
        val vault = world.site(world.vaultSiteId)
        val asked = world.sites.first { it.isSettlement && it.id != vault.id }
        val engine = GameEngine(world, null, DelverCreation(startSiteId = asked.id))
        val saved = engine.toSaveSlot()

        // loading ignores the forge choice entirely: the save's own door wins
        val other = world.sites.last { it.isSettlement && it.id != asked.id }
        val woken = GameEngine(world, saved, DelverCreation(startSiteId = other.id))
        assertEquals(saved.siteId, woken.currentSiteId)
    }
}
