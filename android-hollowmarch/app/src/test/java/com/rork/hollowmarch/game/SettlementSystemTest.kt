package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import com.rork.hollowmarch.world.isSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settlement's own life under its new boundary: souls keep their trade's
 * hours, the world's memory stays the one authority, and Blood-owed riders
 * still find you on the road — all of it deterministic, tick for tick.
 */
class SettlementSystemTest {

    private val world = WorldGenerator.generate(424242L)
    private val settlements = world.sites
        .filter { it.isSettlement && !it.ruined && it.population > 0 }

    /** A settlement to stand in with a sovereign-held settlement within two overland leagues. */
    private val patrolStage = settlements.first { site ->
        world.sites.any { other ->
            other.id != site.id && other.isSettlement && other.sovereignRealmId != null &&
                MapFactory.distance(other.x, other.y, site.x, site.y) <= 2.2f
        }
    }

    private val patrolRealm = world.realms.first { realm ->
        world.sites.any { site ->
            site.id != patrolStage.id && site.isSettlement && site.sovereignRealmId == realm.id &&
                MapFactory.distance(site.x, site.y, patrolStage.x, patrolStage.y) <= 2.2f
        }
    }

    private fun enter(siteId: Int): GameEngine {
        val engine = GameEngine(world, null, null).apply { climbToOpenGround() }
        engine.revealLandmark(siteId)
        val door = engine.map.portals.first { it.targetSiteId == siteId }
        engine.camera.x = door.x
        engine.camera.y = door.y
        engine.usePortal()
        return engine
    }

    private fun GameEngine.wield(item: Item) {
        inventory.remove(item)
        equipment.equip(item, Hand.RIGHT)
    }

    private fun morning(engine: GameEngine) {
        engine.minutes = 4 * 1440f + 10 * 60f
    }

    @Test
    fun theSystemKeepsTheSoulsToTheirDay() {
        val engine = enter(settlements.first().id)
        morning(engine)
        val soul = engine.map.entities.first { it.resident && it.persistId.isNotBlank() }
        val start = Pair(soul.x, soul.y)
        repeat(20) { engine.settlement.update(0.2f) }
        // the soul walks its duty at the settlement's own pace, never a sprint
        val walked = MapFactory.distance(start.first, start.second, soul.x, soul.y)
        assertTrue("the soul goes about its day ($walked)", walked > 0.1f)
        assertTrue("no soul teleports", walked < 5f)
        // and the world's memory is the one authority on where it stands
        val record = engine.worldState.npc(soul.persistId)
        assertNotNull(record)
        assertEquals(soul.x, record!!.x, 0.001f)
        assertEquals(soul.y, record.y, 0.001f)
        assertTrue("the record knows what it does", record.activity.isNotBlank())
    }

    @Test
    fun theSoulsWalkHomeByTheirOwnDusk() {
        val engine = enter(settlements.first().id)
        // the day runs first: souls go out to their duties, far from their doors
        morning(engine)
        repeat(120) { engine.settlement.update(0.2f) }
        engine.minutes = 4 * 1440f + 20 * 60f
        val soul = engine.map.entities.first { entity ->
            entity.resident && entity.alive && entity.homeBuilding >= 0 &&
                entity.persistId.isNotBlank() && run {
                    val home = engine.map.buildings[entity.homeBuilding]
                    MapFactory.distance(
                        entity.x, entity.y, home.doorX + 0.5f, home.doorY + 0.5f
                    ) * MapFactory.distance(
                        entity.x, entity.y, home.doorX + 0.5f, home.doorY + 0.5f
                    ) >= 5.3f
                }
        }
        val home = engine.map.buildings[soul.homeBuilding]
        val doorBefore = MapFactory.distance(soul.x, soul.y, home.doorX + 0.5f, home.doorY + 0.5f)
        engine.settlement.update(0.2f)
        val doorAfter = MapFactory.distance(soul.x, soul.y, home.doorX + 0.5f, home.doorY + 0.5f)
        assertTrue("the soul is homeward bound", doorAfter < doorBefore)
        // the world's memory heard it fall asleep behind its barred door, in time
        repeat(300) { engine.settlement.update(0.2f) }
        assertFalse(
            "${soul.name} went in through its door",
            engine.map.entities.contains(soul)
        )
        assertEquals("asleep behind a barred door", engine.worldState.npc(soul.persistId)?.activity)
    }

    @Test
    fun theSameDayUnfoldsTwiceTheSame() {
        val a = enter(settlements.first().id)
        val b = enter(settlements.first().id)
        morning(a)
        morning(b)
        repeat(40) {
            a.update(0.05f)
            b.update(0.05f)
        }
        val soulsA = a.map.entities.filter { it.resident }.sortedBy { it.persistId }
        val soulsB = b.map.entities.filter { it.resident }.sortedBy { it.persistId }
        assertEquals("the same souls stand in both days", soulsA.map { it.persistId }, soulsB.map { it.persistId })
        soulsA.zip(soulsB).forEach { (first, second) ->
            assertEquals(first.x, second.x, 0.001f)
            assertEquals(first.y, second.y, 0.001f)
            assertEquals(
                first.persistId,
                a.worldState.npc(first.persistId)?.activity,
                b.worldState.npc(second.persistId)?.activity
            )
        }
    }

    @Test
    fun bloodOwedRidersFindYouOnTheRoad() {
        val engine = enter(patrolStage.id)
        engine.reputation.adjust(Layer.POWER, patrolRealm.id, -100, "the test's grudge")
        repeat(40) { engine.settlement.update(30f) }
        val riders = engine.map.entities.filter { it.name == "${patrolRealm.name} outrider" }
        assertTrue("the riders come", riders.isNotEmpty())
        assertTrue("the riders keep to open ground", riders.all { !engine.map.isWall(it.x, it.y) })
        // and a twin world, ridden the same, is ridden identically
        val twin = enter(patrolStage.id)
        twin.reputation.adjust(Layer.POWER, patrolRealm.id, -100, "the test's grudge")
        repeat(40) { twin.settlement.update(30f) }
        assertEquals(
            riders.map { Pair(it.name, Pair(it.x, it.y)) },
            twin.map.entities.filter { it.name == "${patrolRealm.name} outrider" }
                .map { Pair(it.name, Pair(it.x, it.y)) }
        )
    }

    @Test
    fun theRidersComeThroughTheTickInTheirPlace() {
        val engine = enter(patrolStage.id)
        engine.reputation.adjust(Layer.POWER, patrolRealm.id, -100, "the test's grudge")
        // the tick runs the settlement in its place, and the log speaks of it the same tick
        val fullVitality = engine.vitality
        var spoken: String? = null
        var ticks = 0
        while (spoken == null && ticks < 20000) {
            // a long watch: the watcher stays fresh, or the watch ends early
            engine.fatigue = engine.maxFatigue
            engine.vitality = fullVitality
            engine.update(0.05f)
            ticks++
            if (engine.map.entities.any { it.name == "${patrolRealm.name} outrider" }) {
                spoken = engine.log.lastOrNull()?.text
            }
        }
        assertNotNull("the riders come through the ordinary tick", spoken)
        assertTrue("the chronicle speaks of them the same tick", spoken!!.startsWith("Riders of "))
    }

    @Test
    fun theShotFliesBeforeTheSettlementKeepsStep() {
        val engine = enter(settlements.first().id)
        morning(engine)
        val soul = engine.map.entities.first { it.resident && it.alive && it.persistId.isNotBlank() }
        soul.hp = 1
        // the bow comes up and draws while the town goes about its morning
        engine.inventory.add(Item(ItemArchetype.ARROW, Material.ASHWOOD, Quality.HONEST, count = 5))
        engine.wield(Item(ItemArchetype.BOW, Material.ASHWOOD, Quality.HONEST).marked())
        engine.strike()
        repeat(24) { engine.update(0.05f) }
        // then the aim is taken fresh, and the arrow flies
        engine.camera.x = soul.x - 1.5f
        engine.camera.y = soul.y
        engine.camera.angle = 0f
        engine.strike()
        engine.update(0.05f)
        // one tick: the arrow lands, and the settlement's step — after it — remembers
        assertFalse("the arrow finds its mark", soul.alive)
        assertTrue("the world remembers it slain", engine.worldState.isDead(soul.persistId))
        assertEquals(false, engine.worldState.npc(soul.persistId)?.alive)
    }

    @Test
    fun theSettlementRemembersItsSoulsThroughTheSave() {
        val engine = enter(settlements.first().id)
        morning(engine)
        repeat(10) { engine.update(0.05f) }
        val soul = engine.map.entities.first { it.resident && it.persistId.isNotBlank() }
        val before = engine.worldState.npc(soul.persistId)!!
        val saved = engine.toSaveSlot()
        val loaded = GameEngine(world, saved, null)
        val after = loaded.worldState.npc(soul.persistId)
        assertNotNull(after)
        assertEquals(before.x, after!!.x, 0.01f)
        assertEquals(before.y, after.y, 0.01f)
        assertEquals(before.alive, after.alive)
        assertNotEquals("", after.activity)
    }
}
