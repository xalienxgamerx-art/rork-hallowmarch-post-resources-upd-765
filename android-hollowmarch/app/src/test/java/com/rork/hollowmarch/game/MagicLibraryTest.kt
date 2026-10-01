package com.rork.hollowmarch.game

import com.rork.hollowmarch.world.WorldGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The component library, exercised end to end: every school's components
 * declared, aimed, and worked; resources kept apart from attributes, attributes
 * from skills, skills from proficiencies; held weaves lapsing exactly where they
 * bent; wards turning harm once and never chaining; summons serving and fading.
 */
class MagicLibraryTest {

    private val world = WorldGenerator.generate(90210L)

    private fun engine(): GameEngine = GameEngine(world, null, null)

    private fun GameEngine.wideMind() {
        stats.adjust(Attr.INTELLECT, 6)
        stats.adjust(Attr.WISDOM, 6)
    }

    private fun GameEngine.learn(id: String) {
        discoverComponent(id)
        assertTrue("the library offers $id", knownMagic.knownComponents.contains(id))
    }

    private fun enemyNear(engine: GameEngine, name: String = "a test husk"): Entity {
        val foe = Entity(
            x = engine.camera.x + 1f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = name, hp = 40, maxHp = 40
        )
        engine.map.entities += foe
        return foe
    }

    private fun foeWithStats(engine: GameEngine, name: String = "a warded husk"): Entity {
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

    // ------------------------------------------------------------ declarations

    @Test
    fun theLibraryDeclaresItsSchoolsAndHands() {
        val table = mapOf(
            "damage_health" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DAMAGE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "fire_damage" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DAMAGE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "frost_damage" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DAMAGE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "shock_damage" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DAMAGE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "poison_damage" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DAMAGE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "damage_magicka" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DAMAGE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "damage_fatigue" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DAMAGE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "damage_attribute" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DRAIN, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "damage_skill" to Triple(MagicalSchool.DESTRUCTION, MagicBehavior.DRAIN, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "restore_health" to Triple(MagicalSchool.RESTORATION, MagicBehavior.RESTORE, setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET)),
            "restore_magicka" to Triple(MagicalSchool.RESTORATION, MagicBehavior.RESTORE, setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET)),
            "restore_fatigue" to Triple(MagicalSchool.RESTORATION, MagicBehavior.RESTORE, setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET)),
            "fortify_attribute" to Triple(MagicalSchool.RESTORATION, MagicBehavior.FORTIFY, setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET)),
            "fortify_skill" to Triple(MagicalSchool.RESTORATION, MagicBehavior.FORTIFY, setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET)),
            "cure_poison" to Triple(MagicalSchool.RESTORATION, MagicBehavior.CURE, setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET)),
            "shield" to Triple(MagicalSchool.ALTERATION, MagicBehavior.SHIELD, setOf(Delivery.SELF)),
            "reflect_damage" to Triple(MagicalSchool.ALTERATION, MagicBehavior.REFLECT, setOf(Delivery.SELF)),
            "haste" to Triple(MagicalSchool.ALTERATION, MagicBehavior.HASTE, setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET)),
            "slow" to Triple(MagicalSchool.ALTERATION, MagicBehavior.SLOW, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "paralyze" to Triple(MagicalSchool.ALTERATION, MagicBehavior.PARALYZE, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "light" to Triple(MagicalSchool.ALTERATION, MagicBehavior.LIGHT, setOf(Delivery.SELF)),
            "fear" to Triple(MagicalSchool.ILLUSION, MagicBehavior.FEAR, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "calm" to Triple(MagicalSchool.ILLUSION, MagicBehavior.CALM, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "frenzy" to Triple(MagicalSchool.ILLUSION, MagicBehavior.FRENZY, setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA)),
            "charm" to Triple(MagicalSchool.ILLUSION, MagicBehavior.CHARM, setOf(Delivery.TOUCH, Delivery.TARGET)),
            "command" to Triple(MagicalSchool.ILLUSION, MagicBehavior.COMMAND, setOf(Delivery.TOUCH, Delivery.TARGET)),
            "detect_life" to Triple(MagicalSchool.ILLUSION, MagicBehavior.DETECT_LIFE, setOf(Delivery.SELF)),
            "summon_creature" to Triple(MagicalSchool.CONJURATION, MagicBehavior.SUMMON, setOf(Delivery.SELF)),
            "summon_undead" to Triple(MagicalSchool.CONJURATION, MagicBehavior.SUMMON, setOf(Delivery.SELF)),
            "detect_magic" to Triple(MagicalSchool.MYSTICISM, MagicBehavior.DETECT_MAGIC, setOf(Delivery.SELF))
        )
        table.forEach { (id, expected) ->
            val component = ComponentRegistry.get(id)
            assertEquals("$id school", expected.first, component.school)
            assertEquals("$id behavior", expected.second, component.behavior)
            assertEquals("$id deliveries", expected.third, component.deliveries)
        }
        // the generic components select from the whole weaveable set
        assertEquals(
            MagicTargetKind.ATTRIBUTE, ComponentRegistry.get("damage_attribute").targetKind
        )
        assertEquals(
            MagicTargetKind.SKILL, ComponentRegistry.get("fortify_skill").targetKind
        )
        assertEquals("Fortune stands outside the weave", 7, MAGIC_TARGET_ATTRIBUTES.size)
    }

    // ----------------------------------------------------------------- stats

    @Test
    fun resourceMagicMovesOnlyThePools() {
        val engine = engine()
        engine.vitality = 95
        engine.magicka = 30
        engine.fatigue = 40
        val attrsBefore = MAGIC_TARGET_ATTRIBUTES.associateWith { engine.stats[it] }
        val skillsBefore = Skill.entries.associateWith { engine.growth.value(it) }

        SpellCasting.resolveEffect(engine, SpellEffect("damage_magicka", magnitude = 10, delivery = Delivery.SELF), "a test", byPlayer = false)
        assertEquals("the well drains", 20, engine.magicka)
        SpellCasting.resolveEffect(engine, SpellEffect("restore_magicka", magnitude = 5, delivery = Delivery.SELF), "you", byPlayer = true)
        assertEquals(25, engine.magicka)
        SpellCasting.resolveEffect(engine, SpellEffect("damage_fatigue", magnitude = 10, delivery = Delivery.SELF), "a test", byPlayer = false)
        assertEquals(30, engine.fatigue)
        SpellCasting.resolveEffect(engine, SpellEffect("restore_fatigue", magnitude = 10, delivery = Delivery.SELF), "you", byPlayer = true)
        assertEquals(40, engine.fatigue)
        SpellCasting.resolveEffect(engine, SpellEffect("restore_health", magnitude = 20, delivery = Delivery.SELF), "you", byPlayer = true)
        assertEquals("the mend cannot pass the measure", engine.maxVitality, engine.vitality)

        assertEquals("pools never bend attributes", attrsBefore, MAGIC_TARGET_ATTRIBUTES.associateWith { engine.stats[it] })
        assertEquals("pools never bend skills", skillsBefore, Skill.entries.associateWith { engine.growth.value(it) })
    }

    @Test
    fun healthHarmIsNotVigorHarm() {
        val engine = engine()
        val foe = foeWithStats(engine)
        val vigorBefore = foe.stats!![Attr.VIGOR]
        engine.learn("damage_health")
        engine.magicka = 500
        val spell = engine.craftSpell(
            "Ashen Grasp",
            listOf(SpellEffect("damage_health", magnitude = 20, delivery = Delivery.TOUCH, range = 2))
        )!!
        assertTrue(engine.castSpell(spell.id))
        assertEquals(20, foe.hp)
        assertEquals("flesh harm leaves Vigor alone", vigorBefore, foe.stats!![Attr.VIGOR])
    }

    // ------------------------------------------------------------ attributes

    @Test
    fun everyAttributeCanBeBentAndSpringsBack() {
        val engine = engine()
        val base = engine.stats[Attr.MIGHT]
        MAGIC_TARGET_ATTRIBUTES.forEach { attr ->
            SpellCasting.resolveEffect(
                engine,
                SpellEffect("fortify_attribute", magnitude = 4, duration = 10, delivery = Delivery.SELF, target = attr.name),
                "you", byPlayer = true
            )
            assertEquals("fortified ${attr.label}", base + 4, engine.stats[attr])
        }
        // the same component on different targets stacks; the same target refreshes
        assertEquals(MAGIC_TARGET_ATTRIBUTES.size, engine.playerEffects.size)
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("fortify_attribute", magnitude = 2, duration = 10, delivery = Delivery.SELF, target = Attr.MIGHT.name),
            "you", byPlayer = true
        )
        assertEquals("a re-cast refreshes, never piles", MAGIC_TARGET_ATTRIBUTES.size, engine.playerEffects.size)
        assertEquals(base + 2, engine.stats[Attr.MIGHT])

        repeat(11) { SpellCasting.step(engine, 1f) }
        MAGIC_TARGET_ATTRIBUTES.forEach { attr ->
            assertEquals("the weave lapsed from ${attr.label}", base, engine.stats[attr])
        }
    }

    @Test
    fun attributeHarmOnABodyClampsAndStillRestores() {
        val engine = engine()
        val foe = foeWithStats(engine)
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("damage_attribute", magnitude = 10, duration = 10, delivery = Delivery.TOUCH, range = 2, target = Attr.VIGOR.name),
            "you", byPlayer = true
        )
        assertEquals("harm clamps at the floor", 1, foe.stats!![Attr.VIGOR])
        repeat(11) { SpellCasting.step(engine, 1f) }
        assertEquals("the revert is symmetric with the clamp", 6, foe.stats!![Attr.VIGOR])
    }

    // ---------------------------------------------------------------- skills

    @Test
    fun everySkillCanBeBentAndSpringsBack() {
        val engine = engine()
        val before = Skill.entries.associateWith { engine.growth.value(it) }
        Skill.entries.forEach { skill ->
            SpellCasting.resolveEffect(
                engine,
                SpellEffect("fortify_skill", magnitude = 3, duration = 10, delivery = Delivery.SELF, target = skill.name),
                "you", byPlayer = true
            )
        }
        Skill.entries.forEach { skill ->
            assertEquals("fortified ${skill.label}", before[skill]!! + 3, engine.growth.value(skill))
        }
        repeat(11) { SpellCasting.step(engine, 1f) }
        Skill.entries.forEach { skill ->
            assertEquals("the weave lapsed from ${skill.label}", before[skill], engine.growth.value(skill))
        }
        // harm below the floor clamps, and the revert stays symmetric
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("fortify_skill", magnitude = 3, duration = 10, delivery = Delivery.SELF, target = Skill.MELEE.name),
            "you", byPlayer = true
        )
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("damage_skill", magnitude = 5, duration = 10, delivery = Delivery.SELF, target = Skill.MELEE.name),
            "you", byPlayer = true
        )
        assertEquals(1, engine.growth.value(Skill.MELEE))
        repeat(11) { SpellCasting.step(engine, 1f) }
        assertEquals("clamped harm restores cleanly", before[Skill.MELEE], engine.growth.value(Skill.MELEE))
    }

    @Test
    fun skillWeavesStayClearOfLearningAndProficiencies() {
        val engine = engine()
        engine.growth.feed(Skill.MELEE, engine.stats, 60f)
        val meleeBase = engine.growth.value(Skill.MELEE)
        val level = engine.growth.level
        val bar = engine.growth.progressFraction(Skill.MELEE)
        val prof = engine.schoolProficiencies.value(MagicalSchool.RESTORATION)

        SpellCasting.resolveEffect(
            engine,
            SpellEffect("fortify_skill", magnitude = 3, duration = 10, delivery = Delivery.SELF, target = Skill.MELEE.name),
            "you", byPlayer = true
        )
        assertEquals(bar, engine.growth.progressFraction(Skill.MELEE), 0.0001f)
        assertEquals("the weave does not teach", level, engine.growth.level)
        assertEquals("proficiencies stand apart from skills", prof, engine.schoolProficiencies.value(MagicalSchool.RESTORATION))
        repeat(11) { SpellCasting.step(engine, 1f) }
        assertEquals("the weave returns the learning it borrowed", meleeBase, engine.growth.value(Skill.MELEE))

        // and a body's held skill moves with its own weave
        val foe = foeWithStats(engine)
        val theirMelee = foe.skills.value(Skill.MELEE)
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("fortify_skill", magnitude = 2, duration = 5, delivery = Delivery.TOUCH, range = 2, target = Skill.MELEE.name),
            "you", byPlayer = true
        )
        assertEquals(theirMelee + 2, foe.skills.value(Skill.MELEE))
        repeat(6) { SpellCasting.step(engine, 1f) }
        assertEquals("the body's skill springs back", theirMelee, foe.skills.value(Skill.MELEE))
    }

    // -------------------------------------------------------------- movement

    @Test
    fun hasteAndSlowMoveThePace() {
        val engine = engine()
        assertEquals(1f, SpellCasting.moveFactor(engine.playerEffects), 0.0001f)
        engine.learn("haste")
        engine.wideMind()
        engine.magicka = 500
        val spell = engine.craftSpell(
            "Fleet",
            listOf(SpellEffect("haste", magnitude = 25, duration = 10, delivery = Delivery.SELF, range = 1))
        )!!
        assertTrue(engine.castSpell(spell.id))
        assertEquals("haste quickens the stride", 1.25f, SpellCasting.moveFactor(engine.playerEffects), 0.0001f)
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("slow", magnitude = 50, duration = 10, delivery = Delivery.SELF),
            "you", byPlayer = true
        )
        assertEquals(
            "haste and slow fold together",
            1.25f * 0.5f,
            SpellCasting.moveFactor(engine.playerEffects),
            0.0001f
        )
        repeat(11) { SpellCasting.step(engine, 1f) }
        assertEquals("the pace returns", 1f, SpellCasting.moveFactor(engine.playerEffects), 0.0001f)
    }

    // --------------------------------------------------------------- defense

    @Test
    fun aShieldSoaksAndAWardTurnsHarmBack() {
        val engine = engine()
        val warded = enemyNear(engine, "a shielded husk")
        warded.activeEffects += ActiveMagicEffect("shield", 50, 60f)
        SpellCasting.resolveEffect(engine, SpellEffect("damage_health", magnitude = 20, delivery = Delivery.TOUCH, range = 2), "you", byPlayer = true)
        assertEquals("the shield answers first", 40, warded.hp)
        assertTrue("soaking spends the shield", warded.activeEffects.first().remaining < 60f)

        val reflecting = enemyNear(engine, "a warded husk")
        reflecting.activeEffects += ActiveMagicEffect("reflect_damage", 50, 60f)
        val vitalityBefore = engine.vitality
        SpellCasting.resolveEffect(engine, SpellEffect("damage_health", magnitude = 20, delivery = Delivery.TOUCH, range = 2), "you", byPlayer = true)
        assertEquals("harm still lands", 20, reflecting.hp)
        assertEquals("the ward gives half back", vitalityBefore - 10, engine.vitality)
        assertTrue(engine.log.any { it.text.contains("ward turns a share") })

        reflecting.activeEffects.clear()
        assertNull("no ward, no share", SpellCasting.reflectShare(reflecting, 20))
        val warded2 = enemyNear(engine, "another shielded husk")
        warded2.activeEffects += ActiveMagicEffect("reflect_damage", 50, 60f)
        assertEquals(10, SpellCasting.reflectShare(warded2, 20)!!.toInt())
        assertNull("no share of nothing", SpellCasting.reflectShare(warded2, 0))
    }

    @Test
    fun aFrenziedBladeCannotLoopItsWard() {
        val engine = engine()
        val attacker = Entity(
            x = engine.camera.x + 1f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = "a maddened husk", hp = 40, maxHp = 40, damage = 10, speed = 2f
        )
        val target = Entity(
            x = engine.camera.x + 2f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = "a warded husk", hp = 40, maxHp = 40, damage = 10, speed = 2f
        )
        engine.map.entities += attacker
        engine.map.entities += target
        attacker.activeEffects += ActiveMagicEffect("frenzy", 10, 20f)
        target.activeEffects += ActiveMagicEffect("reflect_damage", 50, 60f)

        repeat(3) { engine.npc.update(0.05f) }
        assertTrue("the maddened blow landed", target.hp < 40)
        assertTrue("the ward turned a share back once", attacker.hp < 40)
        assertTrue("and the melee ended with both standing or fallen, not tangled", target.hp >= 0 && attacker.hp >= 0)
    }

    // ---------------------------------------------------------------- poison

    @Test
    fun poisonBitesThenLapsesAndCureWashesIt() {
        val engine = engine().apply { wideMind() }
        val foe = enemyNear(engine)
        engine.learn("poison_damage")
        engine.magicka = 500
        val spell = engine.craftSpell(
            "Venom",
            listOf(SpellEffect("poison_damage", magnitude = 5, duration = 6, delivery = Delivery.TOUCH, range = 2))
        )!!
        assertTrue(engine.castSpell(spell.id))
        assertEquals("the venom holds", 1, foe.activeEffects.size)
        assertEquals("held venom does not strike at once", 40, foe.hp)

        SpellCasting.step(engine, 1f)
        assertTrue("the venom bites", foe.hp < 40)
        assertTrue(engine.log.any { it.text.contains("poison gnaws") })
        val bitten = foe.hp
        repeat(6) { SpellCasting.step(engine, 1f) }
        assertTrue("the venom lapses", foe.activeEffects.isEmpty())
        val rested = foe.hp
        SpellCasting.step(engine, 1f)
        assertEquals("no venom, no bite", rested, foe.hp)

        // a second dose replaces the first rather than piling
        assertTrue(engine.castSpell(spell.id))
        assertEquals(1, foe.activeEffects.size)
        // cure poison washes venom from whatever body it lands on — a foe's poison is no exception
        foe.activeEffects += ActiveMagicEffect("shield", 10, 30f)
        engine.playerEffects += ActiveMagicEffect("poison_damage", 5, 6f)
        engine.playerEffects += ActiveMagicEffect("shield", 10, 30f)
        engine.learn("cure_poison")
        val cure = engine.craftSpell(
            "Clean Blood",
            listOf(SpellEffect("cure_poison", magnitude = 2, delivery = Delivery.TOUCH, range = 2))
        )!!
        assertTrue(engine.castSpell(cure.id))
        assertFalse("the foe's venom is gone", foe.activeEffects.any { it.componentId == "poison_damage" })
        assertTrue("the foe's ward stands untouched", foe.activeEffects.any { it.componentId == "shield" })
        assertTrue("your own venom is your own affair", engine.playerEffects.any { it.componentId == "poison_damage" })
        assertTrue("your own ward stands untouched", engine.playerEffects.any { it.componentId == "shield" })
    }

    // ---------------------------------------------------------- crowd control

    @Test
    fun crowdControlBindsAndLapses() {
        val controls = listOf(
            "fear", "calm", "frenzy", "charm", "command", "paralyze"
        )
        controls.forEach { id ->
            val engine = engine()
            val foe = enemyNear(engine)
            SpellCasting.resolveEffect(
                engine,
                SpellEffect(id, magnitude = 5, duration = 4, delivery = Delivery.TOUCH, range = 2),
                "you", byPlayer = true
            )
            assertTrue("$id takes hold", SpellCasting.controlsOf(foe).contains(id))
            assertEquals(
                "$id binds the will to the caster exactly when it should",
                id == "charm" || id == "command",
                SpellCasting.isAllied(foe)
            )
            if (id == "fear") assertEquals(Detection.AWARE, foe.detection)
            if (id == "calm") assertEquals(Detection.UNAWARE, foe.detection)
            assertTrue(engine.log.any { it.text.contains("takes hold of") })
            repeat(5) { SpellCasting.step(engine, 1f) }
            assertFalse("$id lapses", SpellCasting.controlsOf(foe).contains(id))
            assertTrue(engine.log.any { it.text.contains("lets go of") })
        }
    }

    @Test
    fun paralysisHoldsTheBodyAndFearDrivesItOff() {
        // fear: the foe flees — outdoors, where flight has room to run
        val engine = engine().apply { climbToOpenGround() }
        val afraid = enemyNear(engine, "a frightened husk")
        afraid.x = engine.camera.x + 3f
        afraid.speed = 2.4f
        afraid.detection = Detection.AWARE
        afraid.activeEffects += ActiveMagicEffect("fear", 10, 5f)
        engine.npc.update(0.2f)
        assertTrue(
            "the frightened flee",
            MapFactory.distance(engine.camera.x, engine.camera.y, afraid.x, afraid.y) > 3f
        )
        // calm: the foe forgets its anger
        val soothed = enemyNear(engine, "a soothed husk")
        soothed.detection = Detection.AWARE
        soothed.activeEffects += ActiveMagicEffect("calm", 10, 5f)
        engine.npc.update(0.1f)
        assertEquals(Detection.UNAWARE, soothed.detection)
        // paralyze: the body holds
        val held = Entity(
            x = engine.camera.x + 2f, y = engine.camera.y,
            spriteId = Sprites.HUSK, kind = EntityKind.ENEMY, height = 1f,
            name = "a held husk", hp = 40, maxHp = 40, speed = 2.4f
        )
        engine.map.entities += held
        held.detection = Detection.AWARE
        held.activeEffects += ActiveMagicEffect("paralyze", 5, 5f)
        val heldX = held.x
        engine.npc.update(0.2f)
        assertEquals("the held cannot move", heldX, held.x)
    }

    // -------------------------------------------------------------- summoning

    @Test
    fun summonsServeAndFade() {
        val engine = engine().apply { wideMind() }
        engine.learn("summon_creature")
        engine.magicka = 500
        val spell = engine.craftSpell(
            "Call the Beast",
            listOf(SpellEffect("summon_creature", magnitude = 4, duration = 10, delivery = Delivery.SELF, range = 1))
        )!!
        assertTrue(engine.castSpell(spell.id))
        val summon = engine.map.entities.firstOrNull { it.name.startsWith("a summoned") }
        assertNotNull("the summons answers", summon)
        assertTrue("the servant is bound to the caster", SpellCasting.isAllied(summon!!))
        assertTrue(engine.log.any { it.text.contains("summons answers") })

        repeat(11) { SpellCasting.step(engine, 1f) }
        assertNull("the weave releases its servant", engine.map.entities.firstOrNull { it === summon })
        assertTrue(engine.log.any { it.text.contains("releases") })
    }

    // -------------------------------------------------------------- detection

    @Test
    fun detectionSensesLifeAndMagic() {
        val engine = engine()
        val foe = enemyNear(engine)
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("detect_life", magnitude = 30, duration = 4, delivery = Delivery.SELF, range = 1),
            "you", byPlayer = true
        )
        assertTrue(
            "life is named",
            engine.log.any { it.text.contains("sense life") && it.text.contains("husk") }
        )
        // detect magic reads only what the world actually holds
        foe.activeEffects += ActiveMagicEffect("shield", 10, 30f)
        SpellCasting.resolveEffect(
            engine,
            SpellEffect("detect_magic", magnitude = 10, duration = 4, delivery = Delivery.SELF, range = 1),
            "you", byPlayer = true
        )
        assertTrue(
            "held magic is named",
            engine.log.any { it.text.contains("sense magic") && it.text.contains("husk") }
        )
    }

    // ------------------------------------------------------------- elemental

    @Test
    fun elementalHarmCarriesItsNature() {
        assertEquals(ElementalRules.FIRE, ComponentRegistry.get("fire_damage").element)
        assertEquals(ElementalRules.FROST, ComponentRegistry.get("frost_damage").element)
        assertEquals(ElementalRules.SHOCK, ComponentRegistry.get("shock_damage").element)
        assertEquals(ElementalRules.POISON, ComponentRegistry.get("poison_damage").element)
        assertNull("raw harm carries no element", ComponentRegistry.get("damage_health").element)

        val engine = engine()
        val foe = enemyNear(engine)
        engine.learn("frost_damage")
        engine.magicka = 500
        val spell = engine.craftSpell(
            "Rime",
            listOf(SpellEffect("frost_damage", magnitude = 8, delivery = Delivery.TOUCH, range = 2))
        )!!
        assertTrue(engine.castSpell(spell.id))
        assertEquals(32, foe.hp)
        assertTrue(engine.log.any { it.text.contains("Frost bites deep") })
        assertEquals(setOf(MagicalSchool.DESTRUCTION), spell.schools)
    }

    // ------------------------------------------------------------ light

    @Test
    fun lightKindlesAndLetsGo() {
        val engine = engine().apply { wideMind() }
        engine.torch = 0.4f
        engine.learn("light")
        engine.magicka = 500
        val spell = engine.craftSpell(
            "Glim",
            listOf(SpellEffect("light", magnitude = 4, duration = 10, delivery = Delivery.SELF, range = 1))
        )!!
        assertTrue(engine.castSpell(spell.id))
        assertEquals("the glow rises", 0.5f, engine.torch, 0.0001f)
        repeat(11) { SpellCasting.step(engine, 1f) }
        assertEquals("the glow returns what the caster carried", 0.4f, engine.torch, 0.0001f)
    }

    // ------------------------------------------------------------- integration

    @Test
    fun newFormulasForgeSaveCastAndKeepTheirShape() {
        val engine = engine().apply { wideMind() }
        listOf("damage_attribute", "haste").forEach { engine.learn(it) }
        val spell = engine.craftSpell(
            "Wearying Fleetness",
            listOf(
                SpellEffect("damage_attribute", magnitude = 3, duration = 20, delivery = Delivery.TOUCH, range = 2, target = "VIGOR"),
                SpellEffect("haste", magnitude = 20, duration = 30, delivery = Delivery.SELF, range = 1)
            )
        )!!
        assertEquals(setOf(MagicalSchool.DESTRUCTION, MagicalSchool.ALTERATION), spell.schools)
        // the target travels with the formula, whole
        val encoded = SpellEffect.fromEncoded(spell.effects[0].encode())!!
        assertEquals("VIGOR", encoded.target)

        val slot = engine.toSaveSlot()
        val woken = GameEngine(world, slot, null)
        assertEquals("the formula keeps its identity", spell, woken.spellRegistry.get(spell.id))

        // casting works both weaves at once
        val foe = foeWithStats(engine)
        val vigorBefore = foe.stats!![Attr.VIGOR]
        engine.magicka = 500
        assertTrue(engine.castSpell(spell.id))
        assertEquals(vigorBefore - 3, foe.stats!![Attr.VIGOR])
        assertTrue(1f < SpellCasting.moveFactor(engine.playerEffects))
    }

    @Test
    fun aFormulaWithoutItsTargetIsNotShapable() {
        val engine = engine().apply { wideMind() }
        engine.learn("damage_attribute")
        engine.learn("damage_skill")
        assertNull(
            "no target, no weave",
            engine.craftSpell(
                "Blank",
                listOf(SpellEffect("damage_attribute", magnitude = 3, duration = 20, delivery = Delivery.TOUCH, range = 2))
            )
        )
        assertNull(
            "Fortune stands outside the weave",
            engine.craftSpell(
                "Fate-Bender",
                listOf(SpellEffect("damage_attribute", magnitude = 3, duration = 20, delivery = Delivery.TOUCH, range = 2, target = "FORTUNE"))
            )
        )
        assertNull(
            "no such skill",
            engine.craftSpell(
                "Fumble",
                listOf(SpellEffect("damage_skill", magnitude = 2, duration = 10, delivery = Delivery.TOUCH, range = 2, target = "NOT_A_SKILL"))
            )
        )
        assertNotNull(
            "a named skill shapes",
            engine.craftSpell(
                "Leaden Hand",
                listOf(SpellEffect("damage_skill", magnitude = 2, duration = 10, delivery = Delivery.TOUCH, range = 2, target = "MELEE"))
            )
        )
    }
}
