package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The province's casters work their lore by the same rules you do: the same
 * price, paid from their own well, through the same resolver; a soul's mends
 * fall on its own side while your mend lands on whatever body it is cast at,
 * summons answer beside their caller, and only what the well can pay for is
 * ever dealt into a book.
 */
class AiCastingTest {

    private val world = WorldGenerator.generate(4711L)

    private fun engine(): GameEngine = GameEngine(world, null, null)

    private fun casterNear(engine: GameEngine, name: String = "a hedge-mage", paces: Float = 2f): Entity {
        val mage = Entity(
            x = engine.camera.x + paces, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = name, hp = 40, maxHp = 40, stats = StatBlock.balanced()
        )
        engine.map.entities += mage
        return mage
    }

    /** Put a grimoire formula in a soul's book, with will enough to pay for it. */
    private fun GameEngine.teach(mage: Entity, spellId: String, well: Int): Spell {
        val spell = SpellGrimoire.get(spellId) ?: error("the lore lacks $spellId")
        spellRegistry.register(spell)
        assertTrue(mage.magic.learnSpell(spellId))
        mage.magicka = well
        return spell
    }

    /** Put a grimoire formula in your own book, with will enough to pay for it. */
    private fun GameEngine.learnSelf(spellId: String): Spell {
        val spell = SpellGrimoire.get(spellId) ?: error("the lore lacks $spellId")
        spellRegistry.register(spell)
        assertTrue(knownMagic.learnSpell(spellId))
        magicka = 500
        return spell
    }

    // ---------------------------------------------------------------- price

    @Test
    fun anNpcPaysTheSamePriceTheSpellCostsYou() {
        val engine = engine()
        val mage = casterNear(engine)
        val spell = engine.teach(mage, "grim-ember-bolt", well = 500)
        mage.magicka = spell.cost + 30
        val before = mage.magicka
        assertTrue(engine.castSpellAs(mage, spell))
        assertEquals(before - spell.cost, mage.magicka)
    }

    @Test
    fun aSpellBeyondTheWellWillNotTake() {
        val engine = engine()
        val mage = casterNear(engine)
        val spell = engine.teach(mage, "grim-ember-bolt", well = 500)
        mage.magicka = spell.cost - 1
        val vitalityBefore = engine.vitality
        assertFalse(engine.castSpellAs(mage, spell))
        assertEquals(spell.cost - 1, mage.magicka)
        assertEquals(vitalityBefore, engine.vitality)
    }

    @Test
    fun anNpcEmberBoltLandsOnTheDelver() {
        val engine = engine()
        val mage = casterNear(engine)
        val spell = engine.teach(mage, "grim-ember-bolt", well = 500)
        val before = engine.vitality
        assertTrue(engine.castSpellAs(mage, spell))
        assertTrue("the bolt lands", engine.vitality < before)
    }

    // --------------------------------------------------------------- sides

    @Test
    fun aMendCastAtAFoeMendsTheFoe() {
        val engine = engine()
        val foe = casterNear(engine, paces = 1.2f)
        val spell = engine.learnSelf("grim-mending-touch")
        foe.hp = 10
        val vitalityBefore = engine.vitality
        assertTrue(engine.castSpell(spell.id))
        assertEquals("the foe's cup is filled", 10 + spell.effects.first().magnitude, foe.hp)
        assertEquals(vitalityBefore, engine.vitality)
    }

    @Test
    fun anNpcMendHealsItsOwnHandNotTheDelver() {
        val engine = engine()
        val mage = casterNear(engine)
        val spell = engine.teach(mage, "grim-mending-touch", well = 500)
        mage.hp = 10
        val vitalityBefore = engine.vitality
        assertTrue(engine.castSpellAs(mage, spell))
        assertEquals(10 + spell.effects.first().magnitude, mage.hp)
        assertEquals(vitalityBefore, engine.vitality)
    }

    @Test
    fun aSelfWeaveBlessesItsCasterAlone() {
        val engine = engine()
        val mage = casterNear(engine)
        val spell = engine.teach(mage, "grim-lesser-ward", well = 500)
        assertTrue(engine.castSpellAs(mage, spell))
        assertTrue(mage.activeEffects.any { it.componentId == "shield" })
        assertTrue(engine.playerEffects.none { it.componentId == "shield" })
    }

    @Test
    fun aServantAnswersBesideItsCaller() {
        val engine = engine()
        val mage = casterNear(engine)
        val spell = engine.teach(mage, "grim-grave-servant", well = 500)
        val before = engine.map.entities.size
        assertTrue(engine.castSpellAs(mage, spell))
        assertEquals(before + 1, engine.map.entities.size)
        val servant = engine.map.entities.last()
        assertTrue(
            "the servant stands by its caller",
            MapFactory.distance(servant.x, servant.y, mage.x, mage.y) < 2f
        )
    }

    @Test
    fun aHealerSpendsItsMendOnItsWoundedFellow() {
        val engine = engine()
        val mage = casterNear(engine, paces = 1.2f)
        mage.detection = Detection.AWARE
        engine.teach(mage, "grim-mending-touch", well = 500)
        val fellow = casterNear(engine, name = "a fellow husk")
        fellow.x = mage.x + 0.8f
        fellow.y = mage.y
        fellow.hp = 5
        val willBefore = mage.magicka
        engine.npc.update(0.05f)
        assertTrue("the mend is spent", mage.magicka < willBefore)
        assertTrue("the fellow is mended", fellow.hp > 5)
        assertEquals("the healer's own hand was whole", 40, mage.hp)
    }

    @Test
    fun aWholeHealerKeepsItsMendWhenNoFellowHurts() {
        val engine = engine()
        val mage = casterNear(engine, paces = 1.2f)
        mage.detection = Detection.AWARE
        engine.teach(mage, "grim-mending-touch", well = 500)
        val willBefore = mage.magicka
        engine.npc.update(0.05f)
        assertEquals("no hurt hand in reach, no mend spent", willBefore, mage.magicka)
    }

    // ---------------------------------------------------------------- wells

    @Test
    fun aWellBleedsWillBeforeBlood() {
        val engine = engine()
        val mage = casterNear(engine)
        mage.magicka = 50
        val spell = engine.learnSelf("grim-mana-leech")
        val hpBefore = mage.hp
        val willBefore = mage.magicka
        assertTrue(engine.castSpell(spell.id))
        assertEquals(willBefore - spell.effects.first().magnitude, mage.magicka)
        assertEquals(hpBefore, mage.hp)
    }

    @Test
    fun aWellLessBeastLosesBloodToTheLeech() {
        val engine = engine()
        val beast = Entity(
            x = engine.camera.x + 2f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = "a beast", hp = 40, maxHp = 40
        )
        engine.map.entities += beast
        assertEquals("the well-less carry no will", 0, beast.maxMagicka)
        val spell = engine.learnSelf("grim-mana-leech")
        val hpBefore = beast.hp
        assertTrue(engine.castSpell(spell.id))
        assertTrue("the leech takes blood", beast.hp < hpBefore)
    }

    // ------------------------------------------------------------- dealing

    @Test
    fun aSoulIsDealtOnlyWhatItsWellCanPay() {
        val mage = Entity(
            x = 0f, y = 0f, spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = "a hedge-witch", hp = 30, maxHp = 30, stats = StatBlock.balanced()
        )
        assertEquals(
            "the well follows the delver's own rule",
            Derived.maxMagicka(StatBlock.balanced(), mage.skills), mage.maxMagicka
        )
        SpellGrimoire.outfitCaster(mage, kotlin.random.Random(9L))
        assertTrue("a caster is dealt its lore", mage.magic.knownSpells.isNotEmpty())
        mage.magic.knownSpells.forEach { id ->
            val spell = SpellGrimoire.get(id)!!
            assertTrue("the well can pay for ${spell.name}", spell.cost <= mage.maxMagicka)
        }
    }

    // --------------------------------------------------------------- reach

    @Test
    fun touchWillNotFlyButTargetWill() {
        assertFalse(SpellCasting.inReach(SpellEffect("fire_damage", magnitude = 1, delivery = Delivery.TOUCH), 3f))
        assertTrue(SpellCasting.inReach(SpellEffect("fire_damage", magnitude = 1, delivery = Delivery.TOUCH), 1.5f))
        assertTrue(SpellCasting.inReach(SpellEffect("fire_damage", magnitude = 1, delivery = Delivery.TARGET, range = 8), 8f))
        assertFalse(SpellCasting.inReach(SpellEffect("fire_damage", magnitude = 1, delivery = Delivery.TARGET, range = 8), 9f))
        assertTrue(SpellCasting.inReach(SpellEffect("shield", magnitude = 1, delivery = Delivery.SELF), 30f))
    }

    @Test
    fun theCasterWorksItsLoreInTheTick() {
        val engine = engine()
        val mage = casterNear(engine, paces = 1.2f)
        mage.detection = Detection.AWARE
        engine.teach(mage, "grim-ember-bolt", well = 500)
        val willBefore = mage.magicka
        engine.npc.update(0.05f)
        assertTrue("the tick casts", mage.magicka < willBefore)
    }
}
