package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/**
 * The systems' own ledger: that the shared combat core plays fair for every
 * hand, that the marksmanship hand spends honest shot, that the shots in the
 * air stay honest physical things, that the systems speak in facts and the
 * engine alone turns them into words, and that the same seed tells the same
 * tale twice.
 */
class SystemRefactorTest {

    private val world = WorldGenerator.generate(424242L)

    @Before
    fun resetUids() = ItemUids.reset()

    // ------------------------------------------------------------- helpers

    private fun engine(): GameEngine = GameEngine(world, null, null).apply { climbToOpenGround() }

    private fun GameEngine.stepFor(seconds: Float) {
        var left = seconds
        while (left > 0f) {
            update(0.05f)
            left -= 0.05f
        }
    }

    private fun GameEngine.wield(item: Item) {
        inventory.remove(item)
        equipment.equip(item, Hand.RIGHT)
    }

    private fun bow(): Item =
        Item(ItemArchetype.BOW, Material.ASHWOOD, Quality.HONEST).marked()

    private fun arrows(count: Int = 20): Item =
        Item(ItemArchetype.ARROW, Material.ASHWOOD, Quality.HONEST, count = count)

    private fun GameEngine.spawnEnemy(dist: Float, hp: Int = 40, speed: Float = 0f): Entity {
        map.entities.removeAll { it.kind == EntityKind.ENEMY }
        val enemy = Entity(
            x = camera.x + camera.dirX * dist,
            y = camera.y + camera.dirY * dist,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1.0f,
            name = "husk", hp = hp, maxHp = hp, speed = speed
        )
        map.entities += enemy
        return enemy
    }

    private fun attacker() = Combatant(
        "you", true,
        StatBlock(mapOf(Attr.MIGHT to 18, Attr.FINESSE to 10)),
        Growth.forClass(null),
        Item(ItemArchetype.BLADE, Material.IRON).marked(),
        emptyList(), null, false, 1f, 0f, 0f, 0f
    )

    private fun defenderEntity(blocking: Boolean, facingTowardAttacker: Boolean, vararg worn: Item): Entity {
        val entity = Entity(
            x = 3f, y = 0f, spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = "husk", hp = 40, maxHp = 40, speed = 0f
        )
        val equipment = Equipment.empty()
        worn.forEach { equipment.equip(it) }
        entity.equipment = equipment
        entity.blocking = blocking
        entity.facingAngle = if (facingTowardAttacker) Math.PI.toFloat() else 0f
        return entity
    }

    /** Rolls the exchange from successive seeds until fate grants the wanted end. */
    private fun rollUntil(
        wanted: (MeleeOutcome) -> Boolean,
        roll: (Random) -> MeleeOutcome
    ): MeleeOutcome {
        for (seed in 1..500) {
            val outcome = roll(Random(seed))
            if (wanted(outcome)) return outcome
        }
        fail("no roll in five hundred produced the wanted end")
        throw IllegalStateException()
    }

    private fun shotAt(e: GameEngine, vz: Float, vx: Float = 0f): Projectile {
        val p = Projectile(
            id = e.shots.nextId(), fromPlayer = true, shooterName = "you", weaponName = "test bow",
            category = WeaponCategory.ARCHERY, proficiency = 0, mastery = 0, marks = 0,
            spreadDegrees = 0f, ammoItem = arrows(), label = "arrow",
            damageType = DamageType.SHARP, recoverable = true,
            x = e.camera.x, y = e.camera.y, z = e.map.heightAt(e.camera.x, e.camera.y) + 3f,
            vx = vx, vy = 0f, vz = vz, massKg = 0.05f, drag = 0.02f,
            quality = Quality.HONEST, headMaterial = Material.IRON, spriteId = Ranged.spriteFor(AmmoKind.ARROW)
        )
        e.shots.spawn(p)
        return p
    }

    // ------------------------------------------------------------- melee core

    @Test
    fun theHandCanMiss() {
        val e = engine()
        val defender = e.combat.combatantFor(defenderEntity(false, false))
        val outcome = e.combat.resolveMelee(
            Random(1), attacker(), defender, DamageType.SHARP,
            hitChance = 0, rawDamage = { 10 }, critChance = null, sneak = false, soakWhenBlocked = true
        )
        assertEquals(StrikeOutcome.MISSED, outcome.outcome)
        assertEquals("a miss lands no harm", 0, outcome.damage)
        assertEquals("no armor was reached", 0, outcome.soak)
    }

    @Test
    fun quickFeetSlipBlowsAside() {
        val e = engine()
        val defender = e.combat.combatantFor(
            Entity(
                x = 3f, y = 0f, spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
                name = "husk", hp = 40, maxHp = 40, speed = 0f,
                stats = StatBlock(mapOf(Attr.SWIFTNESS to 20))
            )
        )
        val outcome = rollUntil({ it.outcome == StrikeOutcome.DODGED }) { rng ->
            e.combat.resolveMelee(
                rng, attacker(), defender, DamageType.SHARP,
                hitChance = 100, rawDamage = { 10 }, critChance = null, sneak = false, soakWhenBlocked = true
            )
        }
        assertEquals("a dodge lands no harm", 0, outcome.damage)
        assertTrue("the dodge is kept with its rolls", outcome.dodgeRoll < outcome.dodgeChance)
    }

    @Test
    fun aRaisedBoardTurnsTheBlow() {
        val e = engine()
        val shielded = e.combat.combatantFor(
            defenderEntity(true, true, Item(ItemArchetype.ROUND_SHIELD, Material.IRON).marked())
        )
        val bare = e.combat.combatantFor(defenderEntity(false, true))
        val blocked = rollUntil({ it.outcome == StrikeOutcome.BLOCKED }) { rng ->
            e.combat.resolveMelee(
                rng, attacker(), shielded, DamageType.SHARP,
                hitChance = 100, rawDamage = { 10 }, critChance = null, sneak = false, soakWhenBlocked = true
            )
        }
        val clean = rollUntil({ it.outcome == StrikeOutcome.HIT }) { rng ->
            e.combat.resolveMelee(
                rng, attacker(), bare, DamageType.SHARP,
                hitChance = 100, rawDamage = { 10 }, critChance = null, sneak = false, soakWhenBlocked = true
            )
        }
        assertTrue("the board eats harm", blocked.damage < clean.damage)
        assertTrue("the board's name is kept", blocked.guardName.isNotEmpty())
        assertTrue("a board's edge records its zone", blocked.blockZone != BlockZone.MISS)
    }

    @Test
    fun armorSoaksWhatGetsThrough() {
        val e = engine()
        val unarmored = e.combat.combatantFor(defenderEntity(false, true))
        val armored = e.combat.combatantFor(
            defenderEntity(false, true, Item(ItemArchetype.HAUBERK, Material.STEEL).marked())
        )
        fun landed(defender: Combatant): MeleeOutcome = rollUntil({ it.outcome == StrikeOutcome.HIT }) { rng ->
            e.combat.resolveMelee(
                rng, attacker(), defender, DamageType.SHARP,
                hitChance = 100, rawDamage = { 10 }, critChance = null, sneak = false, soakWhenBlocked = true
            )
        }
        val throughMail = landed(armored)
        val onSkin = landed(unarmored)
        assertTrue("mail soaks", throughMail.soak > 0)
        assertTrue("what is soaked is not felt", throughMail.damage < onSkin.damage)
    }

    @Test
    fun theSameRulesServeTheProvincesHand() {
        val e = engine()
        val foe = Combatant(
            "barrow wolf", false,
            StatBlock(mapOf(Attr.MIGHT to 12, Attr.FINESSE to 12)),
            Growth.forClass(null), null, emptyList(), null, false,
            -1f, 0f, 3f, 0f
        )
        val delver = e.combat.combatantForPlayer(null)
        val miss = e.combat.resolveMelee(
            Random(3), foe, delver, DamageType.SHARP,
            hitChance = 0, rawDamage = { 5 }, critChance = null, sneak = false, soakWhenBlocked = false
        )
        assertEquals(StrikeOutcome.MISSED, miss.outcome)
        val landed = rollUntil({ it.outcome == StrikeOutcome.HIT }) { rng ->
            e.combat.resolveMelee(
                rng, foe, delver, DamageType.SHARP,
                hitChance = 100, rawDamage = { 5 }, critChance = null, sneak = false, soakWhenBlocked = false
            )
        }
        assertTrue("the wolf's harm gets through", landed.damage > 0)
    }

    @Test
    fun aLandedBlowKillsAFrailFoe() {
        val e = engine()
        val foe = e.spawnEnemy(0.9f, hp = 1)
        var swings = 0
        while (foe.alive && swings < 40) {
            e.strike()
            e.stepFor(0.35f)
            swings++
        }
        assertFalse("the frail foe falls", foe.alive)
        assertEquals("the kill is the delver's", 1, e.kills)
        assertTrue("the ledger keeps the exchange", e.combatTrail.isNotEmpty())
    }

    @Test
    fun theProvincesBlowsLandByTheSameRules() {
        val e = engine()
        val foe = e.spawnEnemy(0.9f, hp = 200)
        foe.detection = Detection.AWARE
        val before = e.vitality
        var ticks = 0
        while (e.vitality >= before && ticks < 1500) {
            e.update(0.05f)
            ticks++
        }
        assertTrue("the creature lands a blow in time", ticks < 1500)
        assertTrue("the exchange is written down", e.combatTrail.isNotEmpty())
    }

    // ------------------------------------------------------------- ranged

    @Test
    fun everyShotSpendsOneArrow() {
        val e = engine()
        e.inventory.add(arrows(20))
        e.wield(bow())
        e.strike()
        e.stepFor(1.2f)
        e.strike()
        assertEquals("one shot in the air", 1, e.shots.projectiles.size)
        assertEquals("one arrow spent", 19, e.inventory.all.first { it.archetype == ItemArchetype.ARROW }.count)
    }

    @Test
    fun theShotIsHonestMassAndSpeed() {
        val e = engine()
        e.inventory.add(arrows(5))
        e.wield(bow())
        e.strike()
        e.stepFor(1.2f)
        e.strike()
        val p = e.shots.projectiles.single()
        assertEquals(Ranged.ammoMassKg(arrows()), p.massKg, 1e-5f)
        assertTrue("the bow drives the shot", p.speedMps > 0f)
        assertTrue(p.fromPlayer)
    }

    @Test
    fun thrownSteelLeavesTheHand() {
        val e = engine()
        e.wield(Item(ItemArchetype.SHURIKEN, Material.STEEL, Quality.HONEST).marked())
        e.strike()
        val p = e.shots.projectiles.single()
        assertTrue("a thrown steel can be gathered again", p.recoverable)
        assertTrue(p.fromPlayer)
    }

    @Test
    fun theProvincesArcherLooses() {
        val e = engine()
        val archer = e.spawnEnemy(5f, hp = 40)
        val kit = Equipment.empty()
        kit.equip(bow())
        archer.equipment = kit
        archer.ammoCount = 3
        archer.detection = Detection.AWARE
        e.update(0.05f)
        val shot = e.shots.projectiles.singleOrNull()
        assertNotNull("the archer looses", shot)
        assertFalse(shot!!.fromPlayer)
        assertEquals("the quiver is finite", 2, archer.ammoCount)
    }

    // ------------------------------------------------------------- projectiles

    @Test
    fun gravityAndDragAreHonest() {
        val e = engine()
        val p = shotAt(e, vz = 40f, vx = 20f)
        val vz0 = p.vz
        val vx0 = p.vx
        e.update(0.05f)
        assertTrue("gravity pulls the shot down", p.vz < vz0)
        assertTrue("drag wears the speed", p.vx < vx0)
        assertTrue("the shot still flies", e.shots.projectiles.contains(p))
    }

    @Test
    fun spentShotsPassOutOfTheTale() {
        val e = engine()
        val p = shotAt(e, vz = 0f)
        p.age = 9.96f
        e.update(0.05f)
        assertFalse("nothing flies forever", e.shots.projectiles.contains(p))
    }

    @Test
    fun aFallingShotEndsInTheGroundAndIsGathered() {
        val e = engine()
        val p = shotAt(e, vz = -1f)
        e.stepFor(3f)
        assertFalse("the shot is spent", e.shots.projectiles.contains(p))
        assertTrue("recoverable shot waits on the ground", e.groundItems.isNotEmpty())
    }

    @Test
    fun aShotOnTheBodyTakesHarmToIt() {
        val e = engine()
        e.inventory.add(arrows(5))
        e.wield(bow())
        val foe = e.spawnEnemy(1.8f, hp = 40)
        e.camera.pitch = 0f
        e.strike()
        e.stepFor(1.2f)
        e.strike()
        e.stepFor(1.5f)
        assertTrue("the arrow finds the body", foe.hp < 40 || !foe.alive)
        assertTrue("no shot lingers in the air", e.shots.projectiles.isEmpty())
    }

    // ------------------------------------------------------------- events

    @Test
    fun theWordsComeFromTheFacts() {
        val e = engine()
        e.emit(GameEvent.StrikeMissed(true, "you", "husk"))
        assertEquals("Your swing finds only air.", e.log.last().text)
        e.emit(GameEvent.StrikeDodged(false, "barrow wolf", "you"))
        assertEquals("You slip the barrow wolf's blow aside.", e.log.last().text)
        e.emit(GameEvent.StrikeBlocked(false, "barrow wolf", "you", "hide board", BlockZone.EDGE, 3))
        assertEquals("The barrow wolf's blow slides along the hide board's edge. 3 lost.", e.log.last().text)
        e.emit(GameEvent.StrikeBlocked(true, "you", "husk", "hide board", BlockZone.EDGE, 0))
        assertEquals("The husk's hide board turns your blow aside.", e.log.last().text)
        e.emit(GameEvent.DamageDealt(false, "barrow wolf", "you", 4, warded = true))
        assertEquals("The ward takes most of the barrow wolf's blow. 4 lost.", e.log.last().text)
        e.emit(GameEvent.DamageDealt(true, "you", "husk", 6, critical = true))
        assertEquals("A telling blow — Fortune was with you.", e.log.last().text)
        e.emit(GameEvent.EntityKilled("husk", null, 2, 1))
        assertEquals("The husk comes apart and does not rise. It dies wiser by 1 lesson.", e.log.last().text)
        e.emit(GameEvent.EntityKilled("old guard", "warden", 3, 0))
        assertEquals("The old guard — warden, of level 3 — comes apart and does not rise.", e.log.last().text)
        e.emit(GameEvent.ProjectileFired("thunder piece", true, true, false))
        assertEquals("The pan is empty. The thunder piece wants a fresh charge.", e.log.last().text)
    }

    // ------------------------------------------------------------- determinism

    @Test
    fun theSameSeedTellsTheSameTale() {
        fun launch(): Projectile {
            val e = engine()
            e.wield(Item(ItemArchetype.SHURIKEN, Material.STEEL, Quality.HONEST).marked())
            e.strike()
            return e.shots.projectiles.single()
        }
        val a = launch()
        val b = launch()
        assertEquals("the same hand, the same doubt", a.vx, b.vx, 1e-9f)
        assertEquals(a.vy, b.vy, 1e-9f)
        assertEquals(a.vz, b.vz, 1e-9f)
    }

    @Test
    fun theSameRollsGiveTheSameExchange() {
        val e = engine()
        val defender = e.combat.combatantFor(defenderEntity(false, false))
        fun exchange(rng: Random) = e.combat.resolveMelee(
            rng, attacker(), defender, DamageType.SHARP,
            hitChance = 90, rawDamage = { 7 }, critChance = 10, sneak = false, soakWhenBlocked = true
        )
        val first = exchange(Random(99))
        val second = exchange(Random(99))
        assertEquals(first.outcome, second.outcome)
        assertEquals(first.damage, second.damage)
        assertEquals(first.hitRoll, second.hitRoll)
        assertEquals(first.dodgeRoll, second.dodgeRoll)
    }
}
