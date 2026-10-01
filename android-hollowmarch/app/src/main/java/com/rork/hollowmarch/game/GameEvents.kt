package com.rork.hollowmarch.game

/**
 * What happened in the fight, told as facts rather than as words. The systems
 * report what they did; the engine alone decides what the log says. No system
 * below the engine knows how a line of prose is built.
 */
sealed interface GameEvent {
    /** A free-standing line of flavor: sparks on a wall, a shot in the dirt. */
    data class Note(val text: String) : GameEvent

    /** A swing that found only air. */
    data class StrikeMissed(val byPlayer: Boolean, val attacker: String, val defender: String) : GameEvent

    /** A body moved before the edge arrived. */
    data class StrikeDodged(val byPlayer: Boolean, val attacker: String, val defender: String) : GameEvent

    /** A raised board took the blow: the board's name, where it landed, what got through. */
    data class StrikeBlocked(
        val byPlayer: Boolean,
        val attacker: String,
        val defender: String,
        val guard: String,
        val zone: BlockZone,
        val dealt: Int
    ) : GameEvent

    /** Harm landed: the words follow whether the blow was sneaked, blessed, or warded. */
    data class DamageDealt(
        val byPlayer: Boolean,
        val attacker: String,
        val defender: String,
        val amount: Int,
        val sneak: Boolean = false,
        val critical: Boolean = false,
        val warded: Boolean = false,
        val element: String? = null
    ) : GameEvent

    /** A body came apart and did not rise. */
    data class EntityKilled(
        val defender: String,
        val klassName: String?,
        val level: Int,
        val lessons: Int
    ) : GameEvent

    /** A piece spoke: powder thunder or a string's snap, and whether it ran dry. */
    data class ProjectileFired(
        val weapon: String,
        val gunpowder: Boolean,
        val spent: Boolean,
        val crossbow: Boolean
    ) : GameEvent

    // ------------------------------------------------------------------ magic

    /** A formula was shaped at the station and named. */
    data class SpellCreated(val name: String, val creator: String) : GameEvent

    /** A spell joined a soul's book. */
    data class SpellLearned(val name: String) : GameEvent

    /** A spell left a soul's book. */
    data class SpellForgotten(val name: String) : GameEvent

    /** A formula was worked: whose hand, whose spell. */
    data class SpellCast(val name: String, val byPlayer: Boolean, val caster: String) : GameEvent

    /** A magical component's nature came to be understood. */
    data class MagicComponentDiscovered(val name: String) : GameEvent

    /** A school's proficiency rose through working its magic. */
    data class MagicalProficiencyIncreased(val school: String) : GameEvent

    /** A mend landed: whose hand, whose flesh, how much. */
    data class Healed(val byPlayer: Boolean, val healer: String, val target: String, val amount: Int) : GameEvent

    /** A held magical state took hold: poison, control, pace, ward, or the like. */
    data class MagicStateApplied(val byPlayer: Boolean, val state: String, val target: String) : GameEvent

    /** A held magical state lapsed — by its own end or by a cleansing hand. */
    data class MagicStateExpired(val state: String, val target: String) : GameEvent

    /** A held weave bent an Attribute: whose, which, and how far, until it lapses. */
    data class AttributeModified(
        val byPlayer: Boolean,
        val attribute: String,
        val amount: Int,
        val target: String,
        val temporary: Boolean
    ) : GameEvent

    /** A held weave bent a Skill: whose, which, and how far, until it lapses. */
    data class SkillModified(
        val byPlayer: Boolean,
        val skill: String,
        val amount: Int,
        val target: String,
        val temporary: Boolean
    ) : GameEvent

    /** A creature answered a summons. */
    data class Summoned(val byPlayer: Boolean, val name: String) : GameEvent

    /** A summoned creature was unmade when its weave ran out. */
    data class SummonExpired(val name: String) : GameEvent

    // ---------------------------------------------------------------- scrolls & tomes

    /** A scroll spoke: whose formula, and how many workings it has left. */
    data class ScrollUsed(val spell: String, val remaining: Int) : GameEvent

    /** A scroll's last working left it, and the vellum went to dust. */
    data class ScrollSpent(val spell: String) : GameEvent

    /** A tome was studied: what it taught, counted. */
    data class TomeStudied(val title: String, val spells: Int, val components: Int) : GameEvent

    // ------------------------------------------------------------------ enchantments

    /** A woven thing answered its trigger: the piece, and the working in it. */
    data class EnchantmentFired(val item: String, val enchantment: String, val byPlayer: Boolean) : GameEvent

    /** A woven thing's last spark left it; the weave sleeps until it is filled again. */
    data class EnchantmentSpent(val item: String) : GameEvent

    /** A woven thing was asked to answer with no charge left to pay. */
    data class EnchantmentDry(val item: String, val enchantment: String) : GameEvent

    /** A wielder's will flowed into a woven thing: the charge it now holds. */
    data class ItemRecharged(val item: String, val charge: Int, val max: Int, val magicka: Int) : GameEvent
}
