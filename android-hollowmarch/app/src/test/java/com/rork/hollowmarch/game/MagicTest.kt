package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole magical pipeline, exercised end to end: components registered and
 * known, formulas shaped and priced, spells saved and loaded unchanged, worked
 * by player and NPC alike, and every range governed by the caster's craft.
 */
class MagicTest {

    private val world = WorldGenerator.generate(424242L)

    private fun engine(): GameEngine = GameEngine(world, null, null)

    private fun enemyNear(engine: GameEngine, name: String = "a test husk"): Entity {
        val foe = Entity(
            x = engine.camera.x + 1f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = name, hp = 40, maxHp = 40
        )
        engine.map.entities += foe
        return foe
    }

    private fun GameEngine.wideMind() {
        stats.adjust(Attr.INTELLECT, 6)
        stats.adjust(Attr.WISDOM, 6)
    }

    @Test
    fun theRegistryRegistersGetsAndSearchesComponents() {
        val custom = MagicalComponent(
            id = "test_bloom", name = "Test Bloom", description = "A testing flower.",
            school = MagicalSchool.ALTERATION, behavior = MagicBehavior.LIGHT,
            baseCost = 3f, deliveries = setOf(Delivery.SELF)
        )
        ComponentRegistry.register(custom)
        assertEquals(custom, ComponentRegistry.get("test_bloom"))
        assertTrue(ComponentRegistry.search("bloom").any { it.id == "test_bloom" })
        assertTrue(ComponentRegistry.search("fire").any { it.id == "fire_damage" })
    }

    @Test
    fun unknownComponentsAreNeverOffered() {
        val engine = engine()
        val known = engine.knownComponents()
        assertTrue("a new life knows the starter craft", known.isNotEmpty())
        assertTrue(known.all { STARTER_MAGIC.contains(it.id) })
        assertFalse("fire is not yet known", known.any { it.id == "fire_damage" })

        assertTrue(engine.discoverComponent("fire_damage"))
        assertTrue(engine.knownComponents().any { it.id == "fire_damage" })
        assertFalse("discovery does not repeat", engine.discoverComponent("fire_damage"))
    }

    @Test
    fun aFormulaHoldsItsEffectsSeparately() {
        val engine = engine().apply { wideMind() }
        val spell = engine.craftSpell(
            "Ash and Ward",
            listOf(
                SpellEffect("damage_health", magnitude = 10, delivery = Delivery.TOUCH, range = 2),
                SpellEffect("shield", magnitude = 8, duration = 10, delivery = Delivery.SELF)
            )
        )
        assertNotNull(spell)
        assertEquals("effects stay distinct entries", 2, spell!!.effects.size)
        assertEquals("damage_health", spell.effects[0].componentId)
        assertEquals("shield", spell.effects[1].componentId)
        assertEquals(setOf(MagicalSchool.DESTRUCTION, MagicalSchool.ALTERATION), spell.schools)
    }

    @Test
    fun aSpellCannotUseAComponentUnknownToItsCaster() {
        val engine = engine().apply { wideMind() }
        assertNull(
            "fire damage is not known, so no formula may hold it",
            engine.craftSpell(
                "Stolen Flame",
                listOf(SpellEffect("fire_damage", magnitude = 5, delivery = Delivery.TOUCH, range = 2))
            )
        )
    }

    @Test
    fun costTimeComplexityAndValueGrowWithTheFormula() {
        val small = listOf(SpellEffect("damage_health", magnitude = 5, delivery = Delivery.TOUCH, range = 2))
        val large = listOf(
            SpellEffect("damage_health", magnitude = 40, delivery = Delivery.TARGET, range = 20),
            SpellEffect("shield", magnitude = 20, duration = 60, delivery = Delivery.SELF)
        )
        assertTrue(SpellCalc.spellCost(large) > SpellCalc.spellCost(small))
        assertTrue(SpellCalc.complexity(large) > SpellCalc.complexity(small))
        assertTrue(SpellCalc.castingTime(large, 1) > SpellCalc.castingTime(small, 1))
        assertTrue(SpellCalc.value(50, 3f) > SpellCalc.value(10, 1f))
        // and skill quickens the shaping without changing the price
        assertTrue(SpellCalc.castingTime(large, 20) < SpellCalc.castingTime(large, 1))
    }

    @Test
    fun aLowCraftShapesNarrowerRangesThanAMaster() {
        val stats = StatBlock.balanced()
        val damage = ComponentRegistry.get("damage_health")
        val lowMag = SpellForge.magnitudeCap(damage, 1, stats)
        val highMag = SpellForge.magnitudeCap(damage, Skill.MAX, stats)
        assertTrue("masters push magnitude further: $lowMag vs $highMag", highMag > lowMag)
        assertTrue("no gate blocks the apprentice entirely", lowMag >= damage.magnitude.first)

        val shield = ComponentRegistry.get("shield")
        assertTrue(
            "masters hold weaves longer",
            SpellForge.durationCap(shield, Skill.MAX, stats) > SpellForge.durationCap(shield, 1, stats)
        )
        assertTrue(
            "masters reach further",
            SpellForge.rangeCap(damage, Skill.MAX) > SpellForge.rangeCap(damage, 1)
        )
        assertTrue(
            "Intellect widens the formula",
            SpellForge.maxEffects(Skill.MAX, stats) > SpellForge.maxEffects(1, stats)
        )
    }

    @Test
    fun aForgedSpellSurvivesSaveAndLoadUnchanged() {
        val engine = engine().apply { wideMind() }
        val spell = engine.craftSpell(
            "Ash and Ward",
            listOf(
                SpellEffect("damage_health", magnitude = 10, delivery = Delivery.TOUCH, range = 2),
                SpellEffect("shield", magnitude = 8, duration = 10, delivery = Delivery.SELF)
            )
        )!!
        engine.schoolProficiencies.feed(MagicalSchool.DESTRUCTION, 4f)
        val slot = engine.toSaveSlot()

        val woken = GameEngine(world, slot, null)
        assertEquals("the formula keeps its identity", spell, woken.spellRegistry.get(spell.id))
        assertTrue("known spells persist", woken.knownMagic.knownSpells.contains(spell.id))
        assertEquals(
            "known components persist",
            engine.knownMagic.knownComponents,
            woken.knownMagic.knownComponents
        )
    }

    @Test
    fun castingSpendsWillAndWorksTheFormula() {
        val engine = engine().apply { wideMind() }
        val foe = enemyNear(engine)
        engine.magicka = 100
        val spell = engine.craftSpell(
            "Ashen Grasp",
            listOf(SpellEffect("damage_health", magnitude = 12, delivery = Delivery.TOUCH, range = 2))
        )!!
        val willBefore = engine.magicka
        val hpBefore = foe.hp

        assertTrue(engine.castSpell(spell.id))
        assertEquals("the will is spent", spell.cost, willBefore - engine.magicka)
        assertEquals("the formula found its target", hpBefore - 12, foe.hp)
        assertTrue(
            "the cast is told",
            engine.log.any { it.text.contains("You cast ${spell.name}") }
        )
        // casting feeds the craft and its school
        assertTrue(engine.growth.value(Skill.SPELLCRAFTING) > 1 || engine.growth.progressFraction(Skill.SPELLCRAFTING) > 0f)
    }

    @Test
    fun castingWithoutTheWillDeclines() {
        val engine = engine().apply { wideMind() }
        enemyNear(engine)
        engine.magicka = 0
        val spell = engine.craftSpell(
            "Ashen Grasp",
            listOf(SpellEffect("damage_health", magnitude = 12, delivery = Delivery.TOUCH, range = 2))
        )!!
        assertFalse("no will, no spell", engine.castSpell(spell.id))
    }

    @Test
    fun heldMagicTricklesAndLapses() {
        val engine = engine().apply { wideMind() }
        assertTrue(engine.discoverComponent("fire_damage"))
        val foe = enemyNear(engine)
        engine.magicka = 100
        val spell = engine.craftSpell(
            "Smoulder",
            listOf(
                SpellEffect("fire_damage", magnitude = 30, duration = 5, delivery = Delivery.TOUCH, range = 2)
            )
        )!!
        assertTrue(engine.castSpell(spell.id))
        assertEquals("held harm does not land at once", 40, foe.hp)
        assertEquals("the weave is held", 1, foe.activeEffects.size)

        SpellCasting.step(engine, 1f)
        assertTrue("the trickle bites", foe.hp < 40)
        repeat(6) { SpellCasting.step(engine, 1f) }
        assertTrue("the weave lapses", foe.activeEffects.isEmpty())
    }

    @Test
    fun anNpcCanWorkTheSameFormula() {
        val engine = engine().apply { wideMind() }
        val foe = enemyNear(engine)
        val spell = SpellCalc.forge(
            "npc-spell-1", "A Warband's Ash", "a warband",
            listOf(SpellEffect("damage_health", magnitude = 9, delivery = Delivery.TOUCH, range = 2))
        )
        engine.spellRegistry.register(spell)

        val hpBefore = foe.hp
        SpellCasting.resolve(engine, spell, casterName = "a husk", byPlayer = false)
        assertEquals("the NPC's spell lands", hpBefore - 9, foe.hp)
        assertTrue(
            "the cast is told of them",
            engine.log.any { it.text.contains("casts ${spell.name}") }
        )
    }

    @Test
    fun aScrollHoldsAWholeFormulaAndYieldsItBack() {
        val spell = SpellCalc.forge(
            "scroll-1", "Scorching Grasp", "the old roads",
            listOf(SpellEffect("fire_damage", magnitude = 20, delivery = Delivery.TOUCH, range = 2))
        )
        val restored = Spell.fromEncoded(spell.encode())
        assertEquals("the scroll is the spell", spell, restored)
        assertNotEquals("and not some mangled other thing", "x", restored!!.id)
        // studying the scroll teaches its components
        val engine = engine()
        spell.effects.map { it.componentId }.forEach { engine.discoverComponent(it) }
        assertTrue(engine.knownMagic.knownComponents.contains("fire_damage"))
    }

    @Test
    fun theStationAnswersTheHandAndOpensTheCraft() {
        val engine = engine()
        // the vault's own props and doors keep clear, so the bench alone answers
        engine.map.entities.clear()
        engine.map.portals.clear()
        engine.map.entities += Entity(
            x = engine.camera.x + 1f, y = engine.camera.y,
            spriteId = Sprites.STANDING_STONE, kind = EntityKind.PROP, height = 0.95f,
            name = MagicStations.NAME
        )
        assertTrue(
            "the station names itself to the hand",
            engine.usePrompt().contains("Study at")
        )
        engine.useSpellcraftingStation()
        assertTrue("the bench accepts the worker", engine.atSpellcraftingStation)
    }

    @Test
    fun selfWorkMendsTheCaster() {
        val engine = engine().apply { wideMind() }
        engine.vitality = 10
        engine.magicka = 100
        val spell = engine.craftSpell(
            "Warm Knit",
            listOf(SpellEffect("restore_health", magnitude = 15, delivery = Delivery.SELF, range = 1))
        )!!
        assertTrue(engine.castSpell(spell.id))
        assertEquals(25, engine.vitality)
    }
}
