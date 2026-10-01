package com.rork.hollowmarch.game

/**
 * The registries of magic: every component the world knows, and every spell
 * formula in play. Custom spells are ordinary spells here — a player's forging
 * is architecturally the same object as a developer's or a discovery's.
 */
object ComponentRegistry {

    private val components = LinkedHashMap<String, MagicalComponent>()

    fun register(component: MagicalComponent) {
        components[component.id] = component
    }

    fun get(id: String): MagicalComponent =
        components[id] ?: throw IllegalArgumentException("no such component: $id")

    fun all(): List<MagicalComponent> = components.values.toList()

    /** A name or school search over the registry. */
    fun search(query: String): List<MagicalComponent> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().filter {
            it.name.lowercase().contains(q) || it.school.name.lowercase().contains(q)
        }
    }

    /**
     * The province's working library of components. Every entry is data: the
     * resolver applies it by behavior, the schools and deliveries are declared
     * here, and a new component is a registration, never a rewrite.
     */
    private val CATALOG = listOf(
        // ------------------------------------------------------- destruction
        MagicalComponent(
            id = "damage_health", name = "Damage Health",
            description = "Harms the living flesh it touches.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DAMAGE,
            baseCost = 8f, deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..100, duration = 0..0, aspect = "health"
        ),
        MagicalComponent(
            id = "fire_damage", name = "Fire Damage",
            description = "Washes the target in burning flame.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DAMAGE,
            baseCost = 10f, baseComplexity = 1.2f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..100, duration = 0..30, aspect = "health", element = ElementalRules.FIRE
        ),
        MagicalComponent(
            id = "frost_damage", name = "Frost Damage",
            description = "Saps the warmth from the blood.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DAMAGE,
            baseCost = 10f, baseComplexity = 1.2f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..100, duration = 0..0, aspect = "health", element = ElementalRules.FROST
        ),
        MagicalComponent(
            id = "shock_damage", name = "Shock Damage",
            description = "A snapping charge that seeks the quick.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DAMAGE,
            baseCost = 9f, deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..100, duration = 0..0, aspect = "health", element = ElementalRules.SHOCK
        ),
        MagicalComponent(
            id = "poison_damage", name = "Poison Damage",
            description = "A creeping venom that gnaws with every beat.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DAMAGE,
            baseCost = 6f, baseComplexity = 1.2f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..20, duration = 3..60, aspect = "health", element = ElementalRules.POISON
        ),
        MagicalComponent(
            id = "damage_magicka", name = "Damage Magicka",
            description = "Drains the well of another's will.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DAMAGE,
            baseCost = 9f, deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..100, duration = 0..0, aspect = "magicka"
        ),
        MagicalComponent(
            id = "damage_fatigue", name = "Damage Fatigue",
            description = "Weighs the limbs until they fail.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DAMAGE,
            baseCost = 7f, deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..50, duration = 0..0, aspect = "fatigue"
        ),
        MagicalComponent(
            id = "damage_attribute", name = "Damage Attribute",
            description = "Bends one of the body's eight powers down, for a while.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DRAIN,
            baseCost = 9f, baseComplexity = 1.3f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..20, duration = 5..120, attributes = setOf(Attr.INTELLECT),
            aspect = "", targetKind = MagicTargetKind.ATTRIBUTE
        ),
        MagicalComponent(
            id = "damage_skill", name = "Damage Skill",
            description = "Unminds one learned craft, for a while.",
            school = MagicalSchool.DESTRUCTION, behavior = MagicBehavior.DRAIN,
            baseCost = 9f, baseComplexity = 1.3f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..5, duration = 5..120, attributes = setOf(Attr.INTELLECT),
            aspect = "", targetKind = MagicTargetKind.SKILL
        ),
        // ------------------------------------------------------- restoration
        MagicalComponent(
            id = "restore_health", name = "Restore Health",
            description = "Knits flesh closed with warmth.",
            school = MagicalSchool.RESTORATION, behavior = MagicBehavior.RESTORE,
            baseCost = 9f, deliveries = setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..100, duration = 0..0, aspect = "health"
        ),
        MagicalComponent(
            id = "restore_magicka", name = "Restore Magicka",
            description = "Draws the will back into the well.",
            school = MagicalSchool.RESTORATION, behavior = MagicBehavior.RESTORE,
            baseCost = 10f, deliveries = setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..100, duration = 0..0, aspect = "magicka"
        ),
        MagicalComponent(
            id = "restore_fatigue", name = "Restore Fatigue",
            description = "Loosens the weight from wearied limbs.",
            school = MagicalSchool.RESTORATION, behavior = MagicBehavior.RESTORE,
            baseCost = 8f, deliveries = setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..100, duration = 0..0, aspect = "fatigue"
        ),
        MagicalComponent(
            id = "fortify_attribute", name = "Fortify Attribute",
            description = "Raises one of the body's eight powers, for a while.",
            school = MagicalSchool.RESTORATION, behavior = MagicBehavior.FORTIFY,
            baseCost = 8f, baseComplexity = 1.3f,
            deliveries = setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..25, duration = 5..120, attributes = setOf(Attr.WISDOM),
            aspect = "", targetKind = MagicTargetKind.ATTRIBUTE
        ),
        MagicalComponent(
            id = "fortify_skill", name = "Fortify Skill",
            description = "Sharpens one learned craft beyond its learning, for a while.",
            school = MagicalSchool.RESTORATION, behavior = MagicBehavior.FORTIFY,
            baseCost = 8f, baseComplexity = 1.3f,
            deliveries = setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..15, duration = 5..120, attributes = setOf(Attr.WISDOM),
            aspect = "", targetKind = MagicTargetKind.SKILL
        ),
        MagicalComponent(
            id = "cure_poison", name = "Cure Poison",
            description = "Washes venom from the blood and no other weave.",
            school = MagicalSchool.RESTORATION, behavior = MagicBehavior.CURE,
            baseCost = 7f, deliveries = setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..5, duration = 0..0, attributes = setOf(Attr.WISDOM)
        ),
        // -------------------------------------------------------- alteration
        MagicalComponent(
            id = "shield", name = "Shield",
            description = "A shimmering skin that turns harm aside.",
            school = MagicalSchool.ALTERATION, behavior = MagicBehavior.SHIELD,
            baseCost = 8f, deliveries = setOf(Delivery.SELF),
            magnitude = 1..100, duration = 10..120, attributes = setOf(Attr.WISDOM)
        ),
        MagicalComponent(
            id = "reflect_damage", name = "Reflect Damage",
            description = "A warding skin that gives back what lands on it.",
            school = MagicalSchool.ALTERATION, behavior = MagicBehavior.REFLECT,
            baseCost = 12f, baseComplexity = 1.4f,
            deliveries = setOf(Delivery.SELF),
            magnitude = 1..50, duration = 10..120, attributes = setOf(Attr.WISDOM)
        ),
        MagicalComponent(
            id = "haste", name = "Haste",
            description = "Quickens the feet beyond their weariness.",
            school = MagicalSchool.ALTERATION, behavior = MagicBehavior.HASTE,
            baseCost = 8f, deliveries = setOf(Delivery.SELF, Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..75, duration = 5..120, attributes = setOf(Attr.WISDOM, Attr.SWIFTNESS)
        ),
        MagicalComponent(
            id = "slow", name = "Slow",
            description = "Lays a burden on the target's heels.",
            school = MagicalSchool.ALTERATION, behavior = MagicBehavior.SLOW,
            baseCost = 8f, deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..60, duration = 5..180, attributes = setOf(Attr.WISDOM)
        ),
        MagicalComponent(
            id = "paralyze", name = "Paralyze",
            description = "Holds the body fast while the weave lasts.",
            school = MagicalSchool.ALTERATION, behavior = MagicBehavior.PARALYZE,
            baseCost = 14f, baseComplexity = 1.6f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..10, duration = 1..10, attributes = setOf(Attr.WISDOM)
        ),
        MagicalComponent(
            id = "light", name = "Light",
            description = "Conjures a pale glow about the caster.",
            school = MagicalSchool.ALTERATION, behavior = MagicBehavior.LIGHT,
            baseCost = 4f, deliveries = setOf(Delivery.SELF),
            magnitude = 1..10, duration = 10..300, attributes = setOf(Attr.WISDOM)
        ),
        // ---------------------------------------------------------- illusion
        MagicalComponent(
            id = "fear", name = "Fear",
            description = "Fills the target with the need to flee.",
            school = MagicalSchool.ILLUSION, behavior = MagicBehavior.FEAR,
            baseCost = 12f, baseComplexity = 1.5f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..25, duration = 3..30, attributes = setOf(Attr.PRESENCE)
        ),
        MagicalComponent(
            id = "calm", name = "Calm",
            description = "Unmakes the target's anger into fog.",
            school = MagicalSchool.ILLUSION, behavior = MagicBehavior.CALM,
            baseCost = 9f, baseComplexity = 1.4f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..25, duration = 3..30, attributes = setOf(Attr.PRESENCE)
        ),
        MagicalComponent(
            id = "frenzy", name = "Frenzy",
            description = "Turns the target's ire on the nearest living thing.",
            school = MagicalSchool.ILLUSION, behavior = MagicBehavior.FRENZY,
            baseCost = 13f, baseComplexity = 1.5f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET, Delivery.AREA),
            magnitude = 1..25, duration = 3..30, attributes = setOf(Attr.PRESENCE)
        ),
        MagicalComponent(
            id = "charm", name = "Charm",
            description = "Warms the target's heart toward its caster.",
            school = MagicalSchool.ILLUSION, behavior = MagicBehavior.CHARM,
            baseCost = 12f, baseComplexity = 1.5f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..25, duration = 5..120, attributes = setOf(Attr.PRESENCE)
        ),
        MagicalComponent(
            id = "command", name = "Command",
            description = "Binds the target to heel and service, for a while.",
            school = MagicalSchool.ILLUSION, behavior = MagicBehavior.COMMAND,
            baseCost = 14f, baseComplexity = 1.5f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..25, duration = 5..120, attributes = setOf(Attr.PRESENCE)
        ),
        MagicalComponent(
            id = "detect_life", name = "Detect Life",
            description = "Whispers of every living thing within the sense's reach.",
            school = MagicalSchool.ILLUSION, behavior = MagicBehavior.DETECT_LIFE,
            baseCost = 6f, deliveries = setOf(Delivery.SELF),
            magnitude = 1..60, duration = 5..180, attributes = setOf(Attr.WISDOM)
        ),
        // ------------------------------------------------------- conjuration
        MagicalComponent(
            id = "summon_creature", name = "Summon Creature",
            description = "Calls a living beast from elsewhere to serve, briefly.",
            school = MagicalSchool.CONJURATION, behavior = MagicBehavior.SUMMON,
            baseCost = 15f, baseComplexity = 1.5f,
            deliveries = setOf(Delivery.SELF),
            magnitude = 1..10, duration = 10..300, attributes = setOf(Attr.INTELLECT, Attr.WISDOM)
        ),
        MagicalComponent(
            id = "summon_undead", name = "Summon Undead",
            description = "Calls a restless husk up to serve, briefly.",
            school = MagicalSchool.CONJURATION, behavior = MagicBehavior.SUMMON,
            baseCost = 16f, baseComplexity = 1.6f,
            deliveries = setOf(Delivery.SELF),
            magnitude = 1..10, duration = 10..300, attributes = setOf(Attr.INTELLECT, Attr.WISDOM)
        ),
        // -------------------------------------------------------- mysticism
        MagicalComponent(
            id = "detect_magic", name = "Detect Magic",
            description = "Feels for held weaves and working benches nearby.",
            school = MagicalSchool.MYSTICISM, behavior = MagicBehavior.DETECT_MAGIC,
            baseCost = 6f, deliveries = setOf(Delivery.SELF),
            magnitude = 1..60, duration = 5..180, attributes = setOf(Attr.WISDOM)
        ),
        MagicalComponent(
            id = "drain_swiftness", name = "Drain Swiftness",
            description = "Weighs the target's heels with dread.",
            school = MagicalSchool.MYSTICISM, behavior = MagicBehavior.DRAIN,
            baseCost = 7f, deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..20, duration = 5..120, attributes = setOf(Attr.PRESENCE),
            aspect = "swiftness"
        ),
        MagicalComponent(
            id = "syphon_health", name = "Syphon Health",
            description = "Draws the life out of another body into the caster's own.",
            school = MagicalSchool.MYSTICISM, behavior = MagicBehavior.SYPHON,
            baseCost = 11f, baseComplexity = 1.4f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..80, duration = 0..0, attributes = setOf(Attr.PRESENCE),
            aspect = "health"
        ),
        MagicalComponent(
            id = "syphon_magicka", name = "Syphon Magicka",
            description = "Draws the will out of another's well into the caster's own.",
            school = MagicalSchool.MYSTICISM, behavior = MagicBehavior.SYPHON,
            baseCost = 10f, baseComplexity = 1.4f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..80, duration = 0..0, attributes = setOf(Attr.PRESENCE),
            aspect = "magicka"
        ),
        MagicalComponent(
            id = "syphon_fatigue", name = "Syphon Fatigue",
            description = "Draws the strength out of weary limbs into the caster's own.",
            school = MagicalSchool.MYSTICISM, behavior = MagicBehavior.SYPHON,
            baseCost = 9f, baseComplexity = 1.4f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..60, duration = 0..0, attributes = setOf(Attr.PRESENCE),
            aspect = "fatigue"
        ),
        MagicalComponent(
            id = "syphon_attribute", name = "Syphon Attribute",
            description = "Bends one of the body's powers out of the target and into the caster, for a while.",
            school = MagicalSchool.MYSTICISM, behavior = MagicBehavior.SYPHON,
            baseCost = 10f, baseComplexity = 1.5f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..10, duration = 5..120, attributes = setOf(Attr.PRESENCE),
            aspect = "", targetKind = MagicTargetKind.ATTRIBUTE
        ),
        MagicalComponent(
            id = "syphon_skill", name = "Syphon Skill",
            description = "Unminds one learned craft of the target and lends its edge to the caster, for a while.",
            school = MagicalSchool.MYSTICISM, behavior = MagicBehavior.SYPHON,
            baseCost = 10f, baseComplexity = 1.5f,
            deliveries = setOf(Delivery.TOUCH, Delivery.TARGET),
            magnitude = 1..5, duration = 5..120, attributes = setOf(Attr.PRESENCE),
            aspect = "", targetKind = MagicTargetKind.SKILL
        )
    )

    init {
        CATALOG.forEach { register(it) }
    }
}

/**
 * Every spell formula in play: registered lore and forged customs alike.
 * Persists as one encoded string in the save; a loaded spell keeps its identity.
 */
class SpellRegistry {

    private val spells = LinkedHashMap<String, Spell>()

    fun register(spell: Spell) {
        spells[spell.id] = spell
    }

    fun get(id: String): Spell? = spells[id]

    fun all(): List<Spell> = spells.values.toList()

    fun search(query: String): List<Spell> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().filter {
            it.name.lowercase().contains(q) || it.schools.any { s -> s.name.lowercase().contains(q) }
        }
    }

    fun remove(id: String): Boolean = spells.remove(id) != null

    fun encode(): String = spells.values.joinToString(ENTRY) { it.encode() }

    fun load(raw: String?): List<Spell> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(ENTRY).mapNotNull { Spell.fromEncoded(it) }
            .onEach { register(it) }
    }

    companion object {
        /** Spells are joined by \u0002: their own effect lists already use \u001E. */
        private const val ENTRY = "\u0002"

        /** A fresh, deterministic identity for a new formula. */
        fun newSpellId(seed: Long, count: Int): String = "spell-${seed * 31 + count}"
    }
}

/** The whole magical ledger in one save string: knowledge, proficiencies, formulas. */
object MagicSave {
    private const val SEP = "\u0003"

    fun encode(known: KnownMagic, prof: MagicProficiencies, spells: SpellRegistry): String =
        listOf(known.encode(), prof.encode(), spells.encode()).joinToString(SEP)

    fun decodeKnown(raw: String?): KnownMagic {
        val parts = raw?.split(SEP) ?: emptyList()
        return KnownMagic.fromEncoded(parts.getOrNull(0))
    }

    fun decodeProf(raw: String?): MagicProficiencies {
        val parts = raw?.split(SEP) ?: emptyList()
        return MagicProficiencies.fromEncoded(parts.getOrNull(1))
    }

    fun decodeSpells(raw: String?): List<Spell> {
        val parts = raw?.split(SEP) ?: emptyList()
        return parts.getOrNull(2)?.let { spells ->
            spells.split("\u0002").mapNotNull { Spell.fromEncoded(it) }
        } ?: emptyList()
    }
}

/** What a new life wakes knowing of the hedge-craft: enough to shape a first formula. */
val STARTER_MAGIC = listOf("damage_health", "restore_health", "shield")
