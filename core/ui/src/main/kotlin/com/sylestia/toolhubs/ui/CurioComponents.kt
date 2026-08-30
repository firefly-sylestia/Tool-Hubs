package com.sylestia.toolhubs.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sylestia.toolhubs.designsystem.CurioRoseDark
import com.sylestia.toolhubs.designsystem.CurioRoseWood

@Composable
fun CurioIcon(name: String, modifier: Modifier = Modifier) {
    val glyph = when (name) {
        "menu" -> "☰"
        "person" -> "●"
        "casino" -> "◈"
        "pets" -> "✦"
        "fire" -> "♨"
        "cabinet" -> "▣"
        "sparkles" -> "✧"
        else -> "✧"
    }
    Text(glyph, modifier = modifier, color = Color.White, fontWeight = FontWeight.Bold)
}

@Composable
fun CurioTopBar(onMenu: () -> Unit, onProfile: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CurioCircleButton("menu", onMenu)
        CurioCircleButton("person", onProfile)
    }
}

@Composable
fun CurioCircleButton(icon: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(CurioRoseWood.copy(alpha = .82f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { CurioIcon(icon) }
}

@Composable
fun CurioTornHero(title: String, subtitle: String, modifier: Modifier = Modifier, accent: Color = CurioRoseWood, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxWidth().height(300.dp)) {
        Canvas(Modifier.matchParentSize()) { drawPath(tornPath(size.width, size.height), accent) }
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 68.dp, bottom = 18.dp)) {
            Text(subtitle, color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            content()
        }
    }
}

@Composable
fun CurioStatsBar(streak: String, saved: String, topics: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = .10f)).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CurioStat("fire", streak, "Streak", Modifier.weight(1f)); CurioDivider()
        CurioStat("cabinet", saved, "Cabinet", Modifier.weight(1f)); CurioDivider()
        CurioStat("sparkles", topics, "Topics", Modifier.weight(1f))
    }
}

@Composable
private fun CurioStat(icon: String, value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CurioIcon(icon, Modifier.size(20.dp))
        Text(value, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
        Text(label, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun CurioDivider() = Box(Modifier.width(1.dp).height(34.dp).background(Color.White.copy(alpha = .2f)))

@Composable
fun CurioQuestRow(title: String = "Shuffle the deck", description: String = "A fresh mix of ideas, picked for you", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp).clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(CurioRoseWood.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Text("✦", color = CurioRoseWood) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("TODAY'S QUEST", color = CurioRoseDark, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(description, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f), style = MaterialTheme.typography.bodySmall)
        }
        Box(Modifier.size(54.dp).clip(CircleShape).background(CurioRoseWood), contentAlignment = Alignment.Center) { Text("◈", color = Color.White, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
fun CurioSectionTitle(title: String, action: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (action != null) Text("View all  ›", Modifier.clickable(onClick = action), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun CurioToolCard(title: String, description: String, icon: String, accent: Color = CurioRoseWood, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(14.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) { Text("✧", color = accent) }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f))
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
    }
}

private fun tornPath(width: Float, height: Float): Path {
    val path = Path().apply { moveTo(0f, 0f); lineTo(width, 0f); lineTo(width, height) }
    val steps = 72
    for (i in steps downTo 0) {
        val x = width * i / steps
        val wave = (kotlin.math.sin(i * .31 + 1.7) * 4.8 + kotlin.math.sin(i * .91 + 2.4) * 2.2).toFloat()
        path.lineTo(x, height + wave)
    }
    path.close(); return path
}
