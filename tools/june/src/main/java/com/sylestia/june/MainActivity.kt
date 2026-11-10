package com.sylestia.june

import android.app.DatePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sylestia.june.data.JournalDatabase
import com.sylestia.june.data.JournalEntry
import com.sylestia.june.ui.JUNETheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = JournalDatabase(applicationContext)
        setContent { JUNETheme { JuneApp(database) } }
    }
}

private enum class Destination { TODAY, JOURNAL, YEARSTREAM, ARCHIVE }

@Composable
private fun JuneApp(database: JournalDatabase) {
    var destination by rememberSaveable { mutableStateOf(Destination.TODAY) }
    var entries by remember { mutableStateOf(emptyList<JournalEntry>()) }
    var editing by remember { mutableStateOf<JournalEntry?>(null) }
    var reading by remember { mutableStateOf<JournalEntry?>(null) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        entries = withContext(Dispatchers.IO) { database.all(includeArchived = true) }
    }

    LaunchedEffect(Unit) { refresh() }

    fun newEntry(date: LocalDate = LocalDate.now()) {
        editing = JournalEntry(journalDate = date)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(destination == Destination.TODAY, { destination = Destination.TODAY }, { Icon(Icons.Outlined.Today, null) }, label = { Text("Today") }, colors = juneNavColors())
                NavigationBarItem(destination == Destination.JOURNAL, { destination = Destination.JOURNAL }, { Icon(Icons.Outlined.Book, null) }, label = { Text("Journal") }, colors = juneNavColors())
                NavigationBarItem(destination == Destination.YEARSTREAM, { destination = Destination.YEARSTREAM }, { Icon(Icons.Outlined.CalendarMonth, null) }, label = { Text("Yearstream") }, colors = juneNavColors())
                NavigationBarItem(destination == Destination.ARCHIVE, { destination = Destination.ARCHIVE }, { Icon(Icons.Outlined.Archive, null) }, label = { Text("Archive") }, colors = juneNavColors())
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newEntry() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = { Text("+", fontSize = 23.sp, fontWeight = FontWeight.Light) },
                text = { Text("New entry") }
            )
        }
    ) { padding ->
        when (destination) {
            Destination.TODAY -> TodayScreen(entries, Modifier.padding(padding), { newEntry() }, { reading = it })
            Destination.JOURNAL -> JournalScreen(entries.filter { !it.archived }, Modifier.padding(padding), { reading = it })
            Destination.YEARSTREAM -> YearstreamScreen(entries.filter { !it.archived }, Modifier.padding(padding), { selectedDay = it })
            Destination.ARCHIVE -> ArchiveScreen(
                entries, Modifier.padding(padding), { reading = it },
                { entry -> scope.launch { withContext(Dispatchers.IO) { database.setArchived(entry.id, false) }; refresh() } }
            )
        }
    }

    editing?.let { entry ->
        EntryEditor(entry, { editing = null }) { updated ->
            scope.launch {
                withContext(Dispatchers.IO) { database.save(updated) }
                refresh()
                editing = null
            }
        }
    }

    reading?.let { entry ->
        EntryReader(
            entry = entry,
            onClose = { reading = null },
            onEdit = { reading = null; editing = entry },
            onArchive = {
                scope.launch {
                    withContext(Dispatchers.IO) { database.setArchived(entry.id, true) }
                    refresh()
                    reading = null
                }
            },
            onDelete = {
                scope.launch {
                    withContext(Dispatchers.IO) { database.moveToTrash(entry.id) }
                    refresh()
                    reading = null
                }
            }
        )
    }

    selectedDay?.let { date ->
        DayDetail(
            date, entries.filter { it.journalDate == date && !it.archived },
            { selectedDay = null }, { reading = it; selectedDay = null },
            { selectedDay = null; newEntry(date) }
        )
    }
}

@Composable
private fun juneNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun PageHeader(kicker: String, title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(kicker.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, letterSpacing = 1.2.sp)
        Text(title, style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TodayScreen(
    entries: List<JournalEntry>,
    modifier: Modifier,
    onNew: () -> Unit,
    onOpen: (JournalEntry) -> Unit
) {
    val today = LocalDate.now()
    val todayEntries = entries.filter { it.journalDate == today && !it.archived && it.deletedAt == null }
    val recent = entries.filter { it.journalDate != today && !it.archived && it.deletedAt == null }.take(8)
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 22.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("june.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(today.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())).uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(34.dp))
        PageHeader("Your journal · " + today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())), "Today", "A little space for everything.")
        Spacer(Modifier.height(24.dp))
        if (todayEntries.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onNew),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Nothing written yet.", style = MaterialTheme.typography.titleLarge)
                    Text("Start wherever you are. It doesn't have to be an extraordinary day.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    Text("Write about today  ↗", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                }
            }
        } else {
            todayEntries.forEach { EntryRow(it) { onOpen(it) } }
            TextButton(onClick = onNew) { Text("Add another entry") }
        }
        Spacer(Modifier.height(32.dp))
        Text("Recently written", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        if (recent.isEmpty()) Text("Your earlier entries will find a place here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        else recent.forEach { EntryRow(it) { onOpen(it) } }
        Spacer(Modifier.height(90.dp))
    }
}

@Composable
private fun JournalScreen(entries: List<JournalEntry>, modifier: Modifier, onOpen: (JournalEntry) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = entries.filter { query.isBlank() || it.title.contains(query, true) || it.body.contains(query, true) }
    Column(modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(24.dp))
        PageHeader("Everything you've kept", "Journal", entries.size.toString() + " entries")
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search your writing") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, null) } },
            singleLine = true, shape = RoundedCornerShape(16.dp)
        )
        Spacer(Modifier.height(12.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtered, key = { it.id }) { entry -> EntryRow(entry) { onOpen(entry) } }
            if (filtered.isEmpty()) item { Text("No entries found.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun YearstreamScreen(entries: List<JournalEntry>, modifier: Modifier, onDate: (LocalDate) -> Unit) {
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    val grouped = entries.filter { it.deletedAt == null }.groupBy { it.journalDate }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("YOUR TIME", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, letterSpacing = 1.4.sp)
                Text("Yearstream", style = MaterialTheme.typography.displayMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { year-- }) { Text("‹") }
                Text(year.toString(), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { year++ }) { Text("›") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 110.dp)) {
            items((1..12).toList()) { month ->
                val first = LocalDate.of(year, month, 1)
                Text(first.format(DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                (1..first.lengthOfMonth()).forEach { day ->
                    val date = first.withDayOfMonth(day)
                    val dayEntries = grouped[date].orEmpty()
                    Row(
                        Modifier.fillMaxWidth().clickable { onDate(date) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(Modifier.width(52.dp)) {
                            Text(date.dayOfMonth.toString().padStart(2, '0'), style = MaterialTheme.typography.titleLarge)
                            Text(date.dayOfWeek.name.take(3), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (dayEntries.isEmpty()) {
                                Text("No entry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                            } else {
                                dayEntries.forEach { entry ->
                                    Text(entry.title.ifBlank { entry.body.lineSequence().firstOrNull().orEmpty().take(58).ifBlank { "Untitled entry" } }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(dayEntries.size.toString() + if (dayEntries.size == 1) " entry" else " entries", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                        if (dayEntries.isNotEmpty()) Box(Modifier.padding(top = 7.dp).size(7.dp).background(MaterialTheme.colorScheme.secondary, CircleShape))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                }
            }
        }
    }
}

@Composable
private fun ArchiveScreen(
    entries: List<JournalEntry>,
    modifier: Modifier,
    onOpen: (JournalEntry) -> Unit,
    onRestore: (JournalEntry) -> Unit
) {
    val visible = entries.filter { it.deletedAt == null }
    val archived = visible.filter { it.archived }
    val years = visible.filter { !it.archived }.groupBy { it.journalDate.year }.toSortedMap(compareByDescending { it })
    Column(modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Spacer(Modifier.height(24.dp))
        PageHeader("Your personal library", "Archive", "A record of the days you've kept.")
        Spacer(Modifier.height(22.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
            years.forEach { (year, yearEntries) ->
                item {
                    Text(year.toString(), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Text(yearEntries.size.toString() + if (yearEntries.size == 1) " entry" else " entries", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(yearEntries, key = { it.id }) { entry -> EntryRow(entry) { onOpen(entry) } }
            }
            if (archived.isNotEmpty()) {
                item { Text("Filed away", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 26.dp, bottom = 8.dp)) }
                items(archived, key = { it.id }) { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { EntryRow(entry) { onOpen(entry) } }
                        TextButton(onClick = { onRestore(entry) }) { Text("Restore") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: JournalEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp)) {
        Text(entry.journalDate.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())).uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, letterSpacing = 0.7.sp)
        Spacer(Modifier.height(4.dp))
        Text(entry.title.ifBlank { "Untitled entry" }, style = MaterialTheme.typography.titleLarge)
        if (entry.body.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(entry.body.replace(Regex("[#*_>]"), "").take(140), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
    }
}

@Composable
private fun DayDetail(
    date: LocalDate,
    entries: List<JournalEntry>,
    onClose: () -> Unit,
    onOpen: (JournalEntry) -> Unit,
    onNew: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("DAY DETAIL", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, null) }
                }
                Text(date.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())), style = MaterialTheme.typography.headlineMedium)
                Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())), style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(24.dp))
                if (entries.isEmpty()) Text("Nothing written on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else entries.forEach { EntryRow(it) { onOpen(it) } }
                Spacer(Modifier.weight(1f))
                Button(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Write about this day") }
            }
        }
    }
}

@Composable
private fun EntryEditor(entry: JournalEntry, onClose: () -> Unit, onSave: (JournalEntry) -> Unit) {
    var title by remember(entry.id) { mutableStateOf(entry.title) }
    var body by remember(entry.id) { mutableStateOf(TextFieldValue(entry.body)) }
    var date by remember(entry.id) { mutableStateOf(entry.journalDate) }
    var mode by remember(entry.id) { mutableStateOf(entry.mode) }
    val context = LocalContext.current

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close editor") }
                    Text(if (entry.id == 0L) "NEW ENTRY" else "EDIT ENTRY", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, letterSpacing = 1.2.sp)
                    TextButton(onClick = {
                        if (title.isNotBlank() || body.text.isNotBlank()) onSave(entry.copy(title = title.trim(), body = body.text, journalDate = date, mode = mode))
                    }) { Text("Save") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        DatePickerDialog(context, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    }) {
                        Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Change", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { mode = "classic" }) { Text(if (mode == "classic") "• Classic" else "Classic") }
                        TextButton(onClick = { mode = "rich" }) { Text(if (mode == "rich") "• Rich journal" else "Rich journal") }
                    }
                    Spacer(Modifier.height(20.dp))
                    BasicTextField(
                        value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (title.isEmpty()) Text("Give this day a title", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f))
                            inner()
                        }
                    )
                    Spacer(Modifier.height(20.dp))
                    if (mode == "rich") {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { body = wrapSelection(body, "**") }) { Icon(Icons.Outlined.FormatBold, "Bold") }
                            IconButton(onClick = { body = wrapSelection(body, "_") }) { Icon(Icons.Outlined.FormatItalic, "Italic") }
                            IconButton(onClick = { body = prefixLine(body, "## ") }) { Text("H", fontWeight = FontWeight.Bold) }
                            IconButton(onClick = { body = prefixLine(body, "- ") }) { Icon(Icons.Outlined.FormatListBulleted, "Bulleted list") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    }
                    Spacer(Modifier.height(14.dp))
                    BasicTextField(
                        value = body, onValueChange = { body = it },
                        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 300.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontFamily = if (mode == "classic") FontFamily.Serif else FontFamily.SansSerif,
                            lineHeight = 29.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (body.text.isEmpty()) Text(
                                if (mode == "classic") "Dear diary,\n\nStart wherever you are..." else "Write freely. Add structure when you need it...",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = if (mode == "classic") FontFamily.Serif else FontFamily.SansSerif,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                    lineHeight = 29.sp
                                )
                            )
                            inner()
                        }
                    )
                }
                Text(
                    if (body.text.isBlank()) "Your words stay on this device." else body.text.trim().split(Regex("\\s+")).size.toString() + " words · Tap Save to keep this entry",
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun wrapSelection(value: TextFieldValue, marker: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val selected = value.text.substring(start, end)
    val replacement = if (selected.isEmpty()) marker + marker else marker + selected + marker
    val updated = value.text.replaceRange(start, end, replacement)
    val cursor = if (selected.isEmpty()) start + marker.length else start + replacement.length
    return TextFieldValue(updated, TextRange(cursor))
}

private fun prefixLine(value: TextFieldValue, prefix: String): TextFieldValue {
    val start = value.text.lastIndexOf('\n', (value.selection.start - 1).coerceAtLeast(0)) + 1
    val updated = value.text.substring(0, start) + prefix + value.text.substring(start)
    return TextFieldValue(updated, TextRange(value.selection.start + prefix.length))
}

@Composable
private fun EntryReader(
    entry: JournalEntry,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var showDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close entry") }
                    Row {
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit entry") }
                        IconButton(onClick = onArchive) { Icon(Icons.Outlined.Archive, "Archive entry") }
                        IconButton(onClick = { showDelete = true }) { Icon(Icons.Outlined.Delete, "Move to trash") }
                    }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 26.dp, vertical = 24.dp)) {
                    Text(entry.journalDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())).uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary, letterSpacing = 1.sp)
                    Spacer(Modifier.height(18.dp))
                    Text(entry.title.ifBlank { "Untitled entry" }, style = MaterialTheme.typography.displayMedium)
                    Spacer(Modifier.height(28.dp))
                    Text(entry.body, style = MaterialTheme.typography.bodyLarge.copy(
                        fontFamily = if (entry.mode == "classic") FontFamily.Serif else FontFamily.SansSerif,
                        lineHeight = 30.sp
                    ))
                }
            }
        }
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Move this entry to Trash?") },
            text = { Text("You can restore it later when Trash is available in the archive.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Move to Trash") } },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }
}
