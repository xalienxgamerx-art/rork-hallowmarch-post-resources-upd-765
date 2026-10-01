package com.rork.hollowmarch.game

import kotlin.random.Random

/**
 * The five steps of a caster's road, from first sparks to the deep lore.
 * Tiers describe the general complexity and power of a formula; they are not
 * inherently hard casting gates — the centralized spell-calculation system
 * still answers every number a formula asks for.
 */
enum class SpellTier(val label: String) {
    APPRENTICE("Apprentice"),
    NOVICE("Novice"),
    JOURNEYMAN("Journeyman"),
    EXPERT("Expert"),
    MASTER("Master")
}

/**
 * The province's pre-assembled spell library: ordinary persistent [Spell]
 * objects forged once through [SpellCalc] — the same door every player-made
 * formula passes, so cost, casting time, complexity, and value are never
 * assigned by hand here. Once registered, a grimoire spell is architecturally
 * identical to any other spell: it may be taken into any soul's book, player
 * or NPC alike.
 */
object SpellGrimoire {

    /** The keys of the province's caster callings, dealt the old lore at their making. */
    val CASTER_CLASS_KEYS = setOf("hedgewitch", "caller", "cantor")

    /** What a soul of a given Spellcrafting may carry: the road's step it walks. */
    fun tierFor(craft: Int): SpellTier = when {
        craft >= 17 -> SpellTier.MASTER
        craft >= 13 -> SpellTier.EXPERT
        craft >= 9 -> SpellTier.JOURNEYMAN
        craft >= 5 -> SpellTier.NOVICE
        else -> SpellTier.APPRENTICE
    }

    /** Forge one formula of the lore under a stable, deterministic identity. */
    private fun spell(id: String, name: String, vararg effects: SpellEffect): Spell =
        SpellCalc.forge(id = "grim-$id", name = name, creator = "the old lore", effects = effects.toList())

    private fun target(componentId: String, magnitude: Int, range: Int, target: String) =
        SpellEffect(componentId, magnitude = magnitude, delivery = Delivery.TARGET, range = range, target = target)

    /** The library itself, tier by tier, school by school. Area: 0 means no radius. */
    val LIBRARY: List<Pair<SpellTier, Spell>> = listOf(
        // ---------------------------------------------------------- apprentice
        spell("ember-bolt", "Ember Bolt",
            SpellEffect("fire_damage", magnitude = 8, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.APPRENTICE),
        spell("frost-needle", "Frost Needle",
            SpellEffect("frost_damage", magnitude = 7, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.APPRENTICE),
        spell("mending-touch", "Mending Touch",
            SpellEffect("restore_health", magnitude = 12, delivery = Delivery.TOUCH)
        ).at(SpellTier.APPRENTICE),
        spell("clear-mind", "Clear Mind",
            SpellEffect("restore_magicka", magnitude = 10, delivery = Delivery.SELF)
        ).at(SpellTier.APPRENTICE),
        spell("swiftstep", "Swiftstep",
            SpellEffect("haste", magnitude = 15, duration = 20, delivery = Delivery.SELF)
        ).at(SpellTier.APPRENTICE),
        spell("lesser-ward", "Lesser Ward",
            SpellEffect("shield", magnitude = 10, duration = 30, delivery = Delivery.SELF)
        ).at(SpellTier.APPRENTICE),
        spell("lesser-fear", "Lesser Fear",
            SpellEffect("fear", magnitude = 1, duration = 5, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.APPRENTICE),
        spell("soothing-word", "Soothing Word",
            SpellEffect("calm", magnitude = 1, duration = 8, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.APPRENTICE),
        spell("call-lesser-beast", "Call Lesser Beast",
            SpellEffect("summon_creature", magnitude = 1, duration = 30, delivery = Delivery.SELF)
        ).at(SpellTier.APPRENTICE),
        spell("grave-servant", "Grave Servant",
            SpellEffect("summon_undead", magnitude = 1, duration = 25, delivery = Delivery.SELF)
        ).at(SpellTier.APPRENTICE),
        spell("arcane-sight", "Arcane Sight",
            SpellEffect("detect_magic", magnitude = 20, duration = 20, delivery = Delivery.SELF)
        ).at(SpellTier.APPRENTICE),
        spell("mana-leech", "Mana Leech",
            SpellEffect("damage_magicka", magnitude = 10, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.APPRENTICE),
        // -------------------------------------------------------------- novice
        spell("burning-touch", "Burning Touch",
            SpellEffect("fire_damage", magnitude = 14, delivery = Delivery.TOUCH)
        ).at(SpellTier.NOVICE),
        spell("venomous-dart", "Venomous Dart",
            SpellEffect("poison_damage", magnitude = 3, duration = 12, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.NOVICE),
        spell("greater-mending", "Greater Mending",
            SpellEffect("restore_health", magnitude = 25, delivery = Delivery.TOUCH)
        ).at(SpellTier.NOVICE),
        spell("second-wind", "Second Wind",
            SpellEffect("restore_fatigue", magnitude = 30, delivery = Delivery.SELF),
            SpellEffect("restore_health", magnitude = 10, delivery = Delivery.SELF)
        ).at(SpellTier.NOVICE),
        spell("stoneskin", "Stoneskin",
            SpellEffect("shield", magnitude = 25, duration = 30, delivery = Delivery.SELF)
        ).at(SpellTier.NOVICE),
        spell("fleetstride", "Fleetstride",
            SpellEffect("haste", magnitude = 25, duration = 30, delivery = Delivery.SELF)
        ).at(SpellTier.NOVICE),
        spell("terrifying-presence", "Terrifying Presence",
            SpellEffect("fear", magnitude = 1, duration = 8, area = 8f, delivery = Delivery.AREA, range = 10)
        ).at(SpellTier.NOVICE),
        spell("bewitch", "Bewitch",
            SpellEffect("charm", magnitude = 1, duration = 20, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.NOVICE),
        spell("summon-wolf", "Summon Wolf",
            SpellEffect("summon_creature", magnitude = 1, duration = 60, delivery = Delivery.SELF)
        ).at(SpellTier.NOVICE),
        spell("raise-servant", "Raise Servant",
            SpellEffect("summon_undead", magnitude = 1, duration = 60, delivery = Delivery.SELF)
        ).at(SpellTier.NOVICE),
        spell("arcane-drain", "Arcane Drain",
            SpellEffect("damage_magicka", magnitude = 20, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.NOVICE),
        spell("mystic-ward", "Mystic Ward",
            SpellEffect("shield", magnitude = 20, duration = 30, delivery = Delivery.SELF),
            SpellEffect("reflect_damage", magnitude = 10, duration = 30, delivery = Delivery.SELF)
        ).at(SpellTier.NOVICE),
        // ---------------------------------------------------------- journeyman
        spell("scorching-grasp", "Scorching Grasp",
            SpellEffect("fire_damage", magnitude = 20, delivery = Delivery.TOUCH),
            SpellEffect("poison_damage", magnitude = 2, duration = 10, delivery = Delivery.TOUCH)
        ).at(SpellTier.JOURNEYMAN),
        spell("winters-grasp", "Winter's Grasp",
            SpellEffect("frost_damage", magnitude = 24, delivery = Delivery.TARGET, range = 10),
            SpellEffect("slow", magnitude = 25, duration = 8, delivery = Delivery.TARGET, range = 10)
        ).at(SpellTier.JOURNEYMAN),
        spell("greater-healing", "Greater Healing",
            SpellEffect("restore_health", magnitude = 45, delivery = Delivery.TOUCH)
        ).at(SpellTier.JOURNEYMAN),
        spell("restorative-renewal", "Restorative Renewal",
            SpellEffect("restore_health", magnitude = 20, delivery = Delivery.SELF),
            SpellEffect("restore_fatigue", magnitude = 35, delivery = Delivery.SELF),
            SpellEffect("restore_magicka", magnitude = 15, delivery = Delivery.SELF)
        ).at(SpellTier.JOURNEYMAN),
        spell("ironhide", "Ironhide",
            SpellEffect("fortify_attribute", magnitude = 15, duration = 60, delivery = Delivery.SELF, target = "VIGOR"),
            SpellEffect("shield", magnitude = 35, duration = 60, delivery = Delivery.SELF)
        ).at(SpellTier.JOURNEYMAN),
        spell("crippling-grasp", "Crippling Grasp",
            target("damage_attribute", 12, 10, "SWIFTNESS").held(15),
            SpellEffect("slow", magnitude = 30, duration = 15, delivery = Delivery.TARGET, range = 10)
        ).at(SpellTier.JOURNEYMAN),
        spell("blood-frenzy", "Blood Frenzy",
            SpellEffect("frenzy", magnitude = 1, duration = 20, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.JOURNEYMAN),
        spell("hunters-revelation", "Hunter's Revelation",
            SpellEffect("detect_life", magnitude = 30, duration = 45, delivery = Delivery.SELF)
        ).at(SpellTier.JOURNEYMAN),
        spell("summon-dire-beast", "Summon Dire Beast",
            SpellEffect("summon_creature", magnitude = 1, duration = 120, delivery = Delivery.SELF)
        ).at(SpellTier.JOURNEYMAN),
        spell("guardian-of-the-ancient-way", "Guardian of the Ancient Way",
            SpellEffect("summon_undead", magnitude = 1, duration = 120, delivery = Delivery.SELF)
        ).at(SpellTier.JOURNEYMAN),
        spell("greater-arcane-sight", "Greater Arcane Sight",
            SpellEffect("detect_magic", magnitude = 35, duration = 60, delivery = Delivery.SELF),
            SpellEffect("detect_life", magnitude = 25, duration = 30, delivery = Delivery.SELF)
        ).at(SpellTier.JOURNEYMAN),
        spell("arcane-siphon", "Arcane Siphon",
            SpellEffect("damage_magicka", magnitude = 30, delivery = Delivery.TARGET, range = 8),
            SpellEffect("restore_magicka", magnitude = 20, delivery = Delivery.SELF)
        ).at(SpellTier.JOURNEYMAN),
        // -------------------------------------------------------------- expert
        spell("inferno", "Inferno",
            SpellEffect("fire_damage", magnitude = 35, area = 6f, delivery = Delivery.AREA, range = 10),
            SpellEffect("poison_damage", magnitude = 4, duration = 10, area = 6f, delivery = Delivery.AREA, range = 10)
        ).at(SpellTier.EXPERT),
        spell("winters-curse", "Winter's Curse",
            SpellEffect("frost_damage", magnitude = 40, delivery = Delivery.TARGET, range = 10),
            SpellEffect("slow", magnitude = 45, duration = 15, delivery = Delivery.TARGET, range = 10)
        ).at(SpellTier.EXPERT),
        spell("greater-renewal", "Greater Renewal",
            SpellEffect("restore_health", magnitude = 60, delivery = Delivery.SELF),
            SpellEffect("restore_fatigue", magnitude = 50, delivery = Delivery.SELF),
            SpellEffect("restore_magicka", magnitude = 35, delivery = Delivery.SELF)
        ).at(SpellTier.EXPERT),
        spell("purifying-restoration", "Purifying Restoration",
            SpellEffect("restore_health", magnitude = 45, delivery = Delivery.TARGET, range = 8),
            SpellEffect("cure_poison", magnitude = 1, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.EXPERT),
        spell("adamant-ward", "Adamant Ward",
            SpellEffect("shield", magnitude = 65, duration = 90, delivery = Delivery.SELF),
            SpellEffect("fortify_attribute", magnitude = 20, duration = 90, delivery = Delivery.SELF, target = "VIGOR")
        ).at(SpellTier.EXPERT),
        spell("windwalker", "Windwalker",
            SpellEffect("haste", magnitude = 50, duration = 60, delivery = Delivery.SELF),
            SpellEffect("fortify_attribute", magnitude = 15, duration = 60, delivery = Delivery.SELF, target = "SWIFTNESS")
        ).at(SpellTier.EXPERT),
        spell("terror-unbound", "Terror Unbound",
            SpellEffect("fear", magnitude = 1, duration = 20, area = 10f, delivery = Delivery.AREA, range = 10)
        ).at(SpellTier.EXPERT),
        spell("dominion", "Dominion",
            SpellEffect("charm", magnitude = 1, duration = 45, delivery = Delivery.TARGET, range = 8),
            SpellEffect("command", magnitude = 1, duration = 15, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.EXPERT),
        spell("summon-greater-beast", "Summon Greater Beast",
            SpellEffect("summon_creature", magnitude = 2, duration = 180, delivery = Delivery.SELF)
        ).at(SpellTier.EXPERT),
        spell("summon-greater-undead", "Summon Greater Undead",
            SpellEffect("summon_undead", magnitude = 2, duration = 180, delivery = Delivery.SELF)
        ).at(SpellTier.EXPERT),
        spell("true-sight", "True Sight",
            SpellEffect("detect_life", magnitude = 40, duration = 90, delivery = Delivery.SELF),
            SpellEffect("detect_magic", magnitude = 40, duration = 90, delivery = Delivery.SELF)
        ).at(SpellTier.EXPERT),
        spell("mana-devourer", "Mana Devourer",
            SpellEffect("syphon_magicka", magnitude = 60, delivery = Delivery.TARGET, range = 8),
            SpellEffect("restore_magicka", magnitude = 35, delivery = Delivery.SELF)
        ).at(SpellTier.EXPERT),
        // -------------------------------------------------------------- master
        spell("kings-pyre", "King's Pyre",
            SpellEffect("fire_damage", magnitude = 70, delivery = Delivery.TARGET, range = 12),
            SpellEffect("poison_damage", magnitude = 6, duration = 15, delivery = Delivery.TARGET, range = 12)
        ).at(SpellTier.MASTER),
        spell("storm-of-ruin", "Storm of Ruin",
            SpellEffect("shock_damage", magnitude = 55, area = 12f, delivery = Delivery.AREA, range = 10),
            SpellEffect("damage_magicka", magnitude = 35, area = 12f, delivery = Delivery.AREA, range = 10)
        ).at(SpellTier.MASTER),
        spell("hand-of-renewal", "Hand of Renewal",
            SpellEffect("restore_health", magnitude = 100, delivery = Delivery.TOUCH),
            SpellEffect("restore_fatigue", magnitude = 75, delivery = Delivery.TOUCH),
            SpellEffect("restore_magicka", magnitude = 50, delivery = Delivery.TOUCH)
        ).at(SpellTier.MASTER),
        spell("purifying-grace", "Purifying Grace",
            SpellEffect("restore_health", magnitude = 80, delivery = Delivery.TARGET, range = 8),
            SpellEffect("cure_poison", magnitude = 1, delivery = Delivery.TARGET, range = 8),
            target("fortify_attribute", 20, 8, "VIGOR").held(60)
        ).at(SpellTier.MASTER),
        spell("aegis-eternal", "Aegis Eternal",
            SpellEffect("shield", magnitude = 100, duration = 120, delivery = Delivery.SELF),
            SpellEffect("reflect_damage", magnitude = 25, duration = 120, delivery = Delivery.SELF),
            SpellEffect("fortify_attribute", magnitude = 25, duration = 120, delivery = Delivery.SELF, target = "VIGOR")
        ).at(SpellTier.MASTER),
        spell("unbound-stride", "Unbound Stride",
            SpellEffect("haste", magnitude = 75, duration = 90, delivery = Delivery.SELF),
            SpellEffect("fortify_attribute", magnitude = 25, duration = 90, delivery = Delivery.SELF, target = "SWIFTNESS"),
            SpellEffect("fortify_skill", magnitude = 15, duration = 90, delivery = Delivery.SELF, target = "ATHLETICS")
        ).at(SpellTier.MASTER),
        spell("voice-of-dominion", "Voice of Dominion",
            SpellEffect("charm", magnitude = 1, duration = 90, delivery = Delivery.TARGET, range = 8),
            SpellEffect("command", magnitude = 1, duration = 30, delivery = Delivery.TARGET, range = 8)
        ).at(SpellTier.MASTER),
        spell("nightmare-made-flesh", "Nightmare Made Flesh",
            SpellEffect("fear", magnitude = 1, duration = 30, area = 15f, delivery = Delivery.AREA, range = 12),
            SpellEffect(
                "damage_attribute", magnitude = 20, duration = 30, area = 15f,
                delivery = Delivery.AREA, range = 12, target = "SWIFTNESS"
            )
        ).at(SpellTier.MASTER),
        spell("call-of-the-ancient", "Call of the Ancient",
            SpellEffect("summon_creature", magnitude = 1, duration = 300, delivery = Delivery.SELF)
        ).at(SpellTier.MASTER),
        spell("legion-of-the-dead", "Legion of the Dead",
            SpellEffect("summon_undead", magnitude = 1, duration = 300, delivery = Delivery.SELF)
        ).at(SpellTier.MASTER),
        spell("eye-beyond-the-veil", "Eye Beyond the Veil",
            SpellEffect("detect_life", magnitude = 60, duration = 180, delivery = Delivery.SELF),
            SpellEffect("detect_magic", magnitude = 60, duration = 180, delivery = Delivery.SELF)
        ).at(SpellTier.MASTER),
        spell("devouring-void", "Devouring Void",
            SpellEffect("damage_magicka", magnitude = 80, delivery = Delivery.TARGET, range = 8),
            SpellEffect("damage_fatigue", magnitude = 40, delivery = Delivery.TARGET, range = 8),
            SpellEffect("restore_magicka", magnitude = 50, delivery = Delivery.SELF)
        ).at(SpellTier.MASTER)
    )

    /** All of the lore, flat. */
    fun all(): List<Spell> = LIBRARY.map { it.second }

    /** The formulas of one step of the road. */
    fun byTier(tier: SpellTier): List<Spell> = LIBRARY.filter { it.first == tier }.map { it.second }

    /** Which step a formula belongs to; null for spells outside the lore. */
    fun tierOf(spellId: String): SpellTier? = LIBRARY.firstOrNull { it.second.id == spellId }?.first

    /** One formula of the lore by its stable id. */
    fun get(spellId: String): Spell? = LIBRARY.firstOrNull { it.second.id == spellId }?.second

    /** Every formula of the lore joins a registry once; identity is stable by id. */
    fun registerInto(registry: SpellRegistry) = all().forEach { registry.register(it) }

    /**
     * Deal a caster their spells, once, at their making: a couple of formulas
     * from the step their Spellcrafting reaches, with their components and a
     * working hand in each school the formulas belong to. Deterministic under
     * the given rng — the same seed, the same lore.
     */
    fun outfitCaster(entity: Entity, rng: Random) {
        val tier = tierFor(entity.skills.value(Skill.SPELLCRAFTING))
        // only what the soul's well can pay for is dealt: a spell costs its
        // caster the same as it would cost you, and a formula beyond the
        // well's measure would sit dead in the book
        byTier(tier).filter { it.cost <= entity.maxMagicka }
            .shuffled(rng).take(2).forEach { picked ->
            entity.magic.learnSpell(picked.id)
            picked.effects.forEach { entity.magic.learnComponent(it.componentId) }
            picked.schools.forEach { entity.magicProficiencies.feed(it, 8f) }
        }
    }

    private fun Spell.at(tier: SpellTier): Pair<SpellTier, Spell> = Pair(tier, this)

    private fun SpellEffect.held(duration: Int): SpellEffect = copy(duration = duration)
}
