package com.sylestia.toolhubs.calculator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sylestia.toolhubs.designsystem.CurioCream
import com.sylestia.toolhubs.designsystem.CurioInk
import com.sylestia.toolhubs.designsystem.CurioRoseWood
import com.sylestia.toolhubs.ui.CurioQuestRow
import com.sylestia.toolhubs.ui.CurioSectionTitle
import com.sylestia.toolhubs.ui.CurioStatsBar
import com.sylestia.toolhubs.ui.CurioTornHero
import com.sylestia.toolhubs.ui.CurioToolCard
import com.sylestia.toolhubs.ui.CurioTopBar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ToolHubsHome() }
    }
}

@Composable
private fun ToolHubsHome() {
    var menuOpen by remember { mutableStateOf(false) }
    var profileOpen by remember { mutableStateOf(false) }
    var selectedTool by remember { mutableStateOf<String?>(null) }

    MaterialTheme {
        Column(Modifier.fillMaxSize().background(CurioCream).verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth()) {
                CurioTornHero(title = "Tool Hubs", subtitle = "Good day, Explorer") {
                    CurioStatsBar("0", "0", "12+")
                }
                CurioTopBar(onMenu = { menuOpen = !menuOpen }, onProfile = { profileOpen = !profileOpen })
            }

            CurioQuestRow(
                title = selectedTool ?: "Explore your tools",
                description = if (selectedTool == null) "A small collection, picked for you" else "Your selected tool is ready",
                onClick = { selectedTool = "Calculator" },
            )

            Spacer(Modifier.height(4.dp))
            CurioSectionTitle("Tools")
            Spacer(Modifier.height(6.dp))
            CurioToolCard("Calculator", "Fast everyday calculations", "cabinet", CurioRoseWood) { selectedTool = "Calculator" }
            CurioToolCard("Unit Converter", "Convert common measurements", "sparkles", Color(0xFF8B5E6B)) { selectedTool = "Unit Converter" }
            CurioToolCard("More tools", "Your toolbox grows from here", "pets", CurioRoseWood) { selectedTool = "More tools" }

            if (selectedTool != null) {
                Spacer(Modifier.height(22.dp))
                CurioSectionTitle("Selected")
                Text(selectedTool!!, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = CurioInk)
            }
            Spacer(Modifier.height(100.dp))
        }

        if (menuOpen) Text("Menu", Modifier.padding(24.dp), fontWeight = FontWeight.Bold)
        if (profileOpen) Text("Profile", Modifier.padding(24.dp), fontWeight = FontWeight.Bold)
    }
}
