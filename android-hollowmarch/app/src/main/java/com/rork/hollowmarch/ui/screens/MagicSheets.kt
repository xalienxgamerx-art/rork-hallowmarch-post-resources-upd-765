package com.rork.hollowmarch.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.hollowmarch.game.Attr
import com.rork.hollowmarch.game.Delivery
import com.rork.hollowmarch.game.GameEngine
import com.rork.hollowmarch.game.MAGIC_TARGET_ATTRIBUTES
import com.rork.hollowmarch.game.MagicalComponent
import com.rork.hollowmarch.game.MagicTargetKind
import com.rork.hollowmarch.game.Skill
import com.rork.hollowmarch.game.SpellCalc
import com.rork.hollowmarch.game.SpellEffect
import com.rork.hollowmarch.game.SpellForge
import com.rork.hollowmarch.ui.components.MonoText
import com.rork.hollowmarch.ui.theme.Ink

/** The spellcrafter's bench: know a component, shape the formula, forge the spell. */
@Composable
fun SpellCraftingSheet(
    engine: GameEngine,
    onCraft: (String, List<SpellEffect>) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf(listOf<SpellEffect>()) }
    val craft = engine.craft()
    val maxEffects = SpellForge.maxEffects(craft, engine.stats)
    val budget = SpellForge.complexityBudget(craft, engine.stats)
    val complexity = SpellCalc.complexity(formula)
    val shapable = formula.isNotEmpty() && formula.all { engine.canShape(it) } &&
        formula.size <= maxEffects && complexity <= budget && name.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        MonoText("SPELLCRAFTING", color = Ink.Brass, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Shape known components into a formula, name it, and bind it as a spell.",
            color = Ink.Faded,
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Spell name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        MonoText("KNOWN COMPONENTS", color = Ink.Brass, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        engine.knownComponents().chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { component ->
                    val full = formula.size >= maxEffects
                    Text(
                        text = component.name,
                        color = if (full) Ink.Dim else Ink.Parchment,
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (full) Ink.Overlay else Ink.Surface)
                            .clickable(enabled = !full) {
                                formula = formula + defaultEffect(component, craft, engine)
                            }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .wrapContentSize(Alignment.CenterStart)
                    )
                }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(10.dp))
        MonoText("CURRENT FORMULA", color = Ink.Brass, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        if (formula.isEmpty()) {
            Text("Nothing shaped yet. Tap a known component.", color = Ink.Dim, fontSize = 12.sp)
        }
        formula.forEachIndexed { index, effect ->
            val component = effect.component()
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(component.name, color = Ink.Parchment, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "remove",
                        color = Ink.Blood,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable {
                            formula = formula.filterIndexed { i, _ -> i != index }
                        }.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                MonoText(effectLine(effect), color = Ink.Faded, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ParamChip("Delivery: ${effect.delivery.label}") {
                        formula = formula.replace(index) {
                            cycleDelivery(it, component)
                        }
                    }
                    if (component.targetKind != MagicTargetKind.NONE) {
                        ParamChip("Target: ${prettyTarget(effect.target)}") {
                            formula = formula.replace(index) { cycleTarget(it, component) }
                        }
                    }
                    ParamChip("Mag ${effect.magnitude}") {
                        formula = formula.replace(index) {
                            clampEffect(it.copy(magnitude = it.magnitude + 1), component, craft, engine)
                        }
                    }
                    ParamChip("−") {
                        formula = formula.replace(index) {
                            clampEffect(it.copy(magnitude = it.magnitude - 1), component, craft, engine)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ParamChip("Dur ${effect.duration}s") {
                        formula = formula.replace(index) {
                            clampEffect(it.copy(duration = it.duration + 1), component, craft, engine)
                        }
                    }
                    ParamChip("−") {
                        formula = formula.replace(index) {
                            clampEffect(it.copy(duration = it.duration - 1), component, craft, engine)
                        }
                    }
                    ParamChip("Area ${effect.area.toInt()}") {
                        formula = formula.replace(index) {
                            clampEffect(it.copy(area = it.area + 1f), component, craft, engine)
                        }
                    }
                    ParamChip("Rng ${effect.range}") {
                        formula = formula.replace(index) {
                            clampEffect(it.copy(range = it.range + 1), component, craft, engine)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val cost = if (formula.isEmpty()) 0 else SpellCalc.spellCost(formula)
        MonoText(
            "MAGICKA $cost · casting %.1fs · complexity %.1f of %.1f · worth %d brass"
                .format(SpellCalc.castingTime(formula, craft), complexity, budget, SpellCalc.value(cost, complexity)),
            color = Ink.Verdigris,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { onCraft(name, formula) },
            enabled = shapable,
            colors = ButtonDefaults.buttonColors(containerColor = Ink.Verdigris),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text("CREATE SPELL", fontSize = 13.sp)
        }
        Spacer(Modifier.padding(bottom = 12.dp))
    }
}

/** The spellbook: every formula this soul keeps, castable where it stands. */
@Composable
fun SpellbookSheet(
    engine: GameEngine,
    onCast: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        MonoText("YOUR SPELLBOOK", color = Ink.Brass, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        val spells = engine.knownSpells()
        if (spells.isEmpty()) {
            Text(
                "No spells yet — shape one at the spellcrafting station.",
                color = Ink.Faded,
                fontSize = 12.sp
            )
        }
        spells.forEach { spell ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(spell.name, color = Ink.Parchment, fontSize = 14.sp)
                    MonoText(
                        "${spell.effects.size} effect(s) · MAG ${spell.cost} · " +
                            "casting %.1fs · worth ${spell.value}".format(spell.castingTime),
                        color = Ink.Faded,
                        fontSize = 11.sp
                    )
                }
                Button(
                    onClick = { onCast(spell.id) },
                    enabled = engine.magicka >= spell.cost,
                    colors = ButtonDefaults.buttonColors(containerColor = Ink.Verdigris)
                ) {
                    Text("Cast", fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.padding(bottom = 12.dp))
    }
}

// ------------------------------------------------------------------ helpers

private fun defaultEffect(component: MagicalComponent, craft: Int, engine: GameEngine): SpellEffect {
    val cap = SpellForge.magnitudeCap(component, craft, engine.stats)
    return SpellEffect(
        componentId = component.id,
        magnitude = maxOf(component.magnitude.first, cap / 2),
        duration = SpellForge.durationCap(component, craft, engine.stats).coerceAtMost(component.duration.last),
        area = 0f,
        delivery = component.deliveries.first(),
        range = SpellForge.rangeCap(component, craft).coerceAtMost(component.range.last),
        target = targetOptions(component).firstOrNull() ?: ""
    )
}

private fun effectLine(effect: SpellEffect): String =
    "${effect.delivery.label} · magnitude ${effect.magnitude}" +
        (if (effect.duration > 0) " · ${effect.duration}s" else "") +
        (if (effect.area > 0f) " · area ${effect.area.toInt()}" else "") +
        " · range ${effect.range}" +
        (if (effect.target.isNotBlank()) " · ${prettyTarget(effect.target)}" else "")

private fun cycleDelivery(effect: SpellEffect, component: MagicalComponent): SpellEffect {
    val options = component.deliveries.toList()
    val next = options[(options.indexOf(effect.delivery) + 1) % options.size]
    return effect.copy(delivery = next)
}

/** What a generic component may select: the seven weavable attributes, or every skill. */
private fun targetOptions(component: MagicalComponent): List<String> = when (component.targetKind) {
    MagicTargetKind.ATTRIBUTE -> MAGIC_TARGET_ATTRIBUTES.map { it.name }
    MagicTargetKind.SKILL -> Skill.entries.map { it.name }
    MagicTargetKind.NONE -> emptyList()
}

private fun cycleTarget(effect: SpellEffect, component: MagicalComponent): SpellEffect {
    val options = targetOptions(component)
    if (options.isEmpty()) return effect
    val next = options[((options.indexOf(effect.target).coerceAtLeast(0)) + 1) % options.size]
    return effect.copy(target = next)
}

private fun prettyTarget(name: String): String =
    Attr.entries.firstOrNull { it.name == name }?.label
        ?: Skill.entries.firstOrNull { it.name == name }?.label
        ?: name

private fun clampEffect(
    effect: SpellEffect,
    component: MagicalComponent,
    craft: Int,
    engine: GameEngine
): SpellEffect {
    val magCap = SpellForge.magnitudeCap(component, craft, engine.stats)
    val durCap = SpellForge.durationCap(component, craft, engine.stats)
    val areaCap = SpellForge.areaCap(component, craft, engine.stats)
    val rangeCap = SpellForge.rangeCap(component, craft)
    return effect.copy(
        magnitude = effect.magnitude.coerceIn(component.magnitude.first, magCap),
        duration = effect.duration.coerceIn(component.duration.first, durCap),
        area = effect.area.coerceIn(component.area.start, areaCap),
        range = effect.range.coerceIn(component.range.first, rangeCap)
    )
}

private fun List<SpellEffect>.replace(index: Int, transform: (SpellEffect) -> SpellEffect): List<SpellEffect> =
    mapIndexed { i, e -> if (i == index) transform(e) else e }

@Composable
private fun ParamChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Ink.Parchment,
        fontSize = 11.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(Ink.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .wrapContentSize(Alignment.Center)
    )
}
