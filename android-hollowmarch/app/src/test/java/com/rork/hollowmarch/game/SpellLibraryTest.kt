package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The assembled lore, exercised end to end: five tiers of pre-forged formulas
 * built only from the working component library; every number answered by the
 * one centralized calculator; every formula an ordinary persistent Spell that
 * any soul's book may hold; and the syphon, which takes from the target and
 * pours what it honestly took into the caster.
 */
class SpellLibraryTest {

    private val world = WorldGenerator.generate(90210L)

    private fun engine(): GameEngine = GameEngine(world, null, null)

    private fun enemyNear(engine: GameEngine, hp: Int = 40, name: String = "a syphoned husk"): Entity {
        val foe = Entity(
            x = engine.camera.x + 1f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = name, hp = hp, maxHp = hp
        )
        engine.map.entities += foe
        return foe
    }

    private fun foeWithStats(engine: GameEngine, name: String = "a syphoned wight"): Entity {
        val foe = Entity(
            x = engine.camera.x + 1f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = name, hp = 40, maxHp = 40, stats = StatBlock.balanced()
        )
        engine.map.entities += foe
        return foe
    }

    /** Register and cast a formula straight through the spellbook door. */
    private var rawSpells = 0
    private fun GameEngine.castRaw(effects: List<SpellEffect>): Boolean {
        val spell = SpellCalc.forge("lib-${rawSpells++}", "A Working", "you", effects)
        spellRegistry.register(spell)
        knownMagic.learnSpell(spell.id)
        magicka = 500
        return castSpell(spell.id)
    }

    // ------------------------------------------------------------ the library

    @Test
    fun theLibraryHoldsFiveTiersOfTwelveFormulas() {
        assertEquals(60, SpellGrimoire.all().size)
        SpellTier.entries.forEach { tier ->
            assertEquals("tier ${tier.label}", 12, SpellGrimoire.byTier(tier).size)
        }
        val ids = SpellGrimoire.all().map { it.id }
        assertEquals("every formula has its own id", ids.size, ids.toSet().size)
        val names = SpellGrimoire.all().map { it.name }
        assertEquals("every formula has its own name", names.size, names.toSet().size)
        assertTrue("the lore carries its own mark", ids.all { it.startsWith("grim-") })
        ids.forEach { id -> assertNotNull("the tier answers for $id", SpellGrimoire.tierOf(id)) }
    }

    @Test
    fun everyFormulaIsForgedByTheOneCalculator() {
        SpellGrimoire.all().forEach { spell ->
            assertEquals("cost of ${spell.name}", SpellCalc.spellCost(spell.effects), spell.cost)
            assertEquals("schools of ${spell.name}", SpellCalc.schools(spell.effects), spell.schools)
            assertEquals("complexity of ${spell.name}", SpellCalc.complexity(spell.effects), spell.complexity, 0.0001f)
            assertEquals("value of ${spell.name}", SpellCalc.value(spell.cost, spell.complexity), spell.value)
            assertTrue("casting time of ${spell.name}", spell.castingTime > 0f)
        }
    }

    @Test
    fun everyEffectStaysWithinItsComponentsLimits() {
        SpellGrimoire.all().forEach { spell ->
            spell.effects.forEach { effect ->
                val component = ComponentRegistry.get(effect.componentId)
                assertTrue("${spell.name} delivery", effect.delivery in component.deliveries)
                assertTrue("${spell.name} magnitude", effect.magnitude in component.magnitude)
                assertTrue("${spell.name} duration", effect.duration in component.duration)
                assertTrue("${spell.name} area", effect.area in component.area)
                assertTrue("${spell.name} range", effect.range in component.range)
                when (component.targetKind) {
                    MagicTargetKind.ATTRIBUTE ->
                        assertTrue(
                            "${spell.name} aims at a weaveable attribute",
                            MAGIC_TARGET_ATTRIBUTES.any { it.name == effect.target }
                        )
                    MagicTargetKind.SKILL ->
                        assertTrue(
                            "${spell.name} aims at a real craft",
                            Skill.entries.any { it.name == effect.target }
                        )
                    MagicTargetKind.NONE ->
                        assertEquals("${spell.name} is unaimed", "", effect.target)
                }
            }
        }
    }

    @Test
    fun areaZeroMeansSingleAimAndAreaCarriesARadius() {
        SpellGrimoire.all().forEach { spell ->
            spell.effects.forEach { effect ->
                if (effect.delivery == Delivery.AREA) {
                    assertTrue("${spell.name} spreads over ground", effect.area > 0f)
                } else {
                    assertEquals("${spell.name} keeps to its aim", 0f, effect.area, 0.0001f)
                }
            }
        }
    }

    @Test
    fun everyFormulaSurvivesTheLedgerRoundTrip() {
        SpellGrimoire.all().forEach { spell ->
            val back = Spell.fromEncoded(spell.encode())
            assertNotNull(spell.name, back)
            assertEquals("${spell.name} rides the ledger whole", spell, back)
        }
    }

    @Test
    fun theLoreStandsReadyInEveryRegistry() {
        val engine = engine()
        SpellGrimoire.all().forEach { spell ->
            assertEquals("the lore waits in the registry: ${spell.name}", spell, engine.spellRegistry.get(spell.id))
        }
    }

    @Test
    fun theTiersMarkTheRoadOfACastersCraft() {
        assertEquals(SpellTier.APPRENTICE, SpellGrimoire.tierFor(1))
        assertEquals(SpellTier.APPRENTICE, SpellGrimoire.tierFor(4))
        assertEquals(SpellTier.NOVICE, SpellGrimoire.tierFor(5))
        assertEquals(SpellTier.JOURNEYMAN, SpellGrimoire.tierFor(9))
        assertEquals(SpellTier.EXPERT, SpellGrimoire.tierFor(13))
        assertEquals(SpellTier.MASTER, SpellGrimoire.tierFor(17))
        assertEquals(SpellTier.MASTER, SpellGrimoire.tierFor(20))
    }

    @Test
    fun theLoreIsBuiltOnlyFromTheWorkingLibrary() {
        val known = setOf(
            "damage_health", "fire_damage", "frost_damage", "shock_damage", "poison_damage",
            "damage_magicka", "damage_fatigue", "damage_attribute",
            "restore_health", "restore_magicka", "restore_fatigue",
            "fortify_attribute", "fortify_skill", "cure_poison",
            "shield", "reflect_damage", "haste", "slow",
            "fear", "calm", "frenzy", "charm", "command",
            "detect_life", "summon_creature", "summon_undead", "detect_magic",
            "syphon_health", "syphon_magicka", "syphon_fatigue", "syphon_attribute", "syphon_skill"
        )
        SpellGrimoire.all().forEach { spell ->
            spell.effects.forEach { effect ->
                assertTrue(
                    "${spell.name} leans on a working component: ${effect.componentId}",
                    effect.componentId in known
                )
            }
        }
    }

    // ---------------------------------------------------------------- syphon

    @Test
    fun syphonTakesLifeAndPoursItIntoTheCaster() {
        val engine = engine()
        engine.vitality = engine.maxVitality - 20
        val foe = enemyNear(engine)
        assertTrue(engine.castRaw(listOf(SpellEffect("syphon_health", magnitude = 15, delivery = Delivery.TOUCH))))
        assertEquals("the body's life is drawn", 25, foe.hp)
        assertEquals("the caster drinks what was drawn", engine.maxVitality - 20 + 15, engine.vitality)
    }

    @Test
    fun aSyphonTakesNoMoreThanTheBodyHolds() {
        val engine = engine()
        engine.vitality = engine.maxVitality - 5
        val foe = enemyNear(engine, hp = 10)
        assertTrue(engine.castRaw(listOf(SpellEffect("syphon_health", magnitude = 60, delivery = Delivery.TOUCH))))
        assertFalse("the drained body falls", foe.alive)
        assertEquals(0, foe.hp)
        assertEquals("only what the body had comes out", engine.maxVitality, engine.vitality)
    }

    @Test
    fun aFullCasterGainsNothingMore() {
        val engine = engine()
        engine.vitality = engine.maxVitality
        val foe = enemyNear(engine)
        assertTrue(engine.castRaw(listOf(SpellEffect("syphon_health", magnitude = 10, delivery = Delivery.TOUCH))))
        assertEquals("the body still bleeds", 30, foe.hp)
        assertEquals("the caster holds no more than before", engine.maxVitality, engine.vitality)
    }

    @Test
    fun aBeastHasNoWellOfWillToDrain() {
        val engine = engine()
        engine.vitality = engine.maxVitality - 30
        val foe = enemyNear(engine)
        val before = foe.hp
        assertTrue(engine.castRaw(listOf(SpellEffect("syphon_magicka", magnitude = 20, delivery = Delivery.TOUCH))))
        assertEquals("the beast's blood is untouched", before, foe.hp)
        assertEquals("nothing was taken, nothing granted", engine.maxVitality - 30, engine.vitality)
    }

    @Test
    fun syphonBendsAttributesFromTargetToCaster() {
        val engine = engine()
        val foe = foeWithStats(engine)
        val base = engine.stats[Attr.MIGHT]
        val theirs = foe.stats?.get(Attr.MIGHT) ?: 0
        assertTrue(
            engine.castRaw(
                listOf(
                    SpellEffect(
                        "syphon_attribute", magnitude = 3, duration = 5,
                        delivery = Delivery.TOUCH, range = 1, target = "MIGHT"
                    )
                )
            )
        )
        assertEquals("the target's might wanes", theirs - 3, foe.stats?.get(Attr.MIGHT))
        assertEquals("the caster's might swells", base + 3, engine.stats[Attr.MIGHT])
        repeat(6) { SpellCasting.step(engine, 1f) }
        assertEquals("the target's weave returns", theirs, foe.stats?.get(Attr.MIGHT))
        assertEquals("the gift returns", base, engine.stats[Attr.MIGHT])
    }

    @Test
    fun syphonGrantsOnlyWhatItActuallyTook() {
        val engine = engine()
        val foe = enemyNear(engine)
        val base = engine.growth.value(Skill.MELEE)
        val theirs = foe.skills.value(Skill.MELEE)
        assertTrue(
            engine.castRaw(
                listOf(
                    SpellEffect(
                        "syphon_skill", magnitude = 4, duration = 5,
                        delivery = Delivery.TOUCH, range = 1, target = "MELEE"
                    )
                )
            )
        )
        val drained = theirs - maxOf(Skill.MIN, theirs - 4)
        assertEquals("the craft gives only what it had", base + drained, engine.growth.value(Skill.MELEE))
        assertEquals(
            "nothing is held on the caster that was never taken",
            if (drained > 0) 1 else 0,
            engine.playerEffects.count { it.skillTouched == Skill.MELEE }
        )
    }

    // ---------------------------------------------------------- distribution

    @Test
    fun aCasterOfTheProvinceCarriesTheOldLore() {
        val witch = Entity(
            x = 5f, y = 5f, spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = "a hedgewitch", hp = 40, maxHp = 40,
            stats = StatBlock.balanced(),
            skills = Growth.forClass(ClassRoster(world).byKey("hedgewitch"))
        )
        SpellGrimoire.outfitCaster(witch, Random(7L))
        assertTrue("the witch knows formulas", witch.magic.knownSpells.isNotEmpty())
        assertTrue("no more than a couple", witch.magic.knownSpells.size <= 2)
        val tier = SpellGrimoire.tierFor(witch.skills.value(Skill.SPELLCRAFTING))
        witch.magic.knownSpells.forEach { id ->
            assertEquals("the witch's formulas fit its craft", tier, SpellGrimoire.tierOf(id))
            SpellGrimoire.get(id)?.effects?.forEach { effect ->
                assertTrue("the witch knows its parts", witch.magic.knownComponents.contains(effect.componentId))
            }
        }
        assertTrue("the witch trained the schools it carries", witch.magicProficiencies.value(MagicalSchool.MYSTICISM) >= MagicProficiencies.MIN)
    }

    @Test
    fun theSameDealingIsDeterministic() {
        fun dealt(): Set<String> {
            val witch = Entity(
                x = 5f, y = 5f, spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
                name = "a caller", hp = 40, maxHp = 40,
                stats = StatBlock.balanced(),
                skills = Growth.forClass(ClassRoster(world).byKey("caller"))
            )
            SpellGrimoire.outfitCaster(witch, Random(11L))
            return witch.magic.knownSpells
        }
        assertEquals("the same seed, the same lore", dealt(), dealt())
    }

    @Test
    fun aFormulaPassesIntoAnotherSoulsBook() {
        val engine = engine()
        val pupil = enemyNear(engine, name = "a pupil")
        assertTrue(engine.teachSpell(pupil, "grim-mana-leech"))
        assertTrue(pupil.magic.knownSpells.contains("grim-mana-leech"))
        assertFalse("a soul does not learn twice", engine.teachSpell(pupil, "grim-mana-leech"))
    }

    @Test
    fun aGrimoireSpellCastsLikeAnyOther() {
        val engine = engine()
        val foe = enemyNear(engine)
        val before = foe.hp
        assertTrue(engine.learnGrimoireSpell("grim-ember-bolt"))
        assertTrue(engine.knownSpells().any { it.id == "grim-ember-bolt" })
        engine.magicka = 500
        assertTrue(engine.castSpell("grim-ember-bolt"))
        assertEquals("the bolt lands like any formula", before - 8, foe.hp)
        val back = Spell.fromEncoded(engine.knownSpells().first { it.id == "grim-ember-bolt" }.encode())
        assertEquals("Ember Bolt", back?.name)
    }
}
