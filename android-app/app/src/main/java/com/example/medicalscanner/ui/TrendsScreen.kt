package com.example.medicalscanner.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medicalscanner.ai.DashboardEngine
import com.example.medicalscanner.local.LocalRepository
import com.example.medicalscanner.model.ParameterTrend
import com.example.medicalscanner.model.TrendDataPoint
import com.example.medicalscanner.util.TestReference
import kotlinx.coroutines.launch

private val statusHigh = Color(0xFFC62828)
private val statusLow = Color(0xFFE65100)
private val statusNormal = Color(0xFF2E7D32)

private fun parseNum(s: String): Float? {
    val m = Regex("-?\\d+(\\.\\d+)?").find(s) ?: return null
    return m.value.toFloatOrNull()
}

private fun statusColor(status: String, fallback: Color): Color = when (status.lowercase()) {
    "high" -> statusHigh
    "low" -> statusLow
    "normal" -> statusNormal
    else -> fallback
}

private val MONTHS = listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Compact date WITH year, e.g. "9 Jul '25". */
private fun shortDate(iso: String): String {
    val parts = iso.split("T")[0].split("-")
    if (parts.size < 3) return iso
    val m = parts[1].toIntOrNull() ?: return iso
    val day = parts[2].toIntOrNull()?.toString() ?: parts[2]
    val yy = parts[0].takeLast(2)
    return "$day ${MONTHS.getOrElse(m) { parts[1] }} '$yy"
}

/** Parses yyyy-MM-dd to epoch millis so points can be spaced by real time. */
private fun isoToMillis(iso: String): Long? = try {
    val parts = iso.split("T")[0].split("-")
    val cal = java.util.Calendar.getInstance()
    cal.clear()
    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
    cal.timeInMillis
} catch (e: Exception) {
    null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrendsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToReport: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var patients by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPatient by remember { mutableStateOf<String?>(null) }
    var period by remember { mutableStateOf<String?>(null) }
    var keyOnly by remember { mutableStateOf(true) }
    var trends by remember { mutableStateOf<List<ParameterTrend>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var patientMenu by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    // Build the patient list once.
    LaunchedEffect(Unit) {
        val reports = LocalRepository.getReports(context)
        val byCount = reports.mapNotNull { it.patientName }.groupingBy { it }.eachCount()
        patients = byCount.entries.sortedByDescending { it.value }.map { it.key }
        selectedPatient = patients.firstOrNull()
        if (selectedPatient == null) isLoading = false
    }

    // Reload trends when patient/period changes, or the user taps refresh.
    LaunchedEffect(selectedPatient, period, refreshTick) {
        val p = selectedPatient ?: return@LaunchedEffect
        isLoading = true
        trends = try {
            LocalRepository.getHealthSummary(context, p, period).parameterTrends
        } catch (e: Exception) {
            e.printStackTrace(); emptyList()
        }
        isLoading = false
    }

    val visibleTrends = remember(trends, keyOnly) {
        val withData = trends.filter { t -> t.dataPoints.any { parseNum(it.value) != null } }
        if (keyOnly) withData.filter { DashboardEngine.isKeyParameter(it.name) }.ifEmpty { withData } else withData
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Health Trends", fontWeight = FontWeight.Bold)
                        Text("Tap any point to open that report", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { refreshTick++ }, enabled = !isLoading && selectedPatient != null) {
                        if (isLoading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Refresh trends")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .appWatermark()
        ) {
            // Patient + key-only controls
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (patients.size > 1) {
                    ExposedDropdownMenuBox(expanded = patientMenu, onExpandedChange = { patientMenu = it }, modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = selectedPatient ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Patient") },
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = patientMenu) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = patientMenu, onDismissRequest = { patientMenu = false }) {
                            patients.forEach { p ->
                                DropdownMenuItem(text = { Text(p) }, onClick = { selectedPatient = p; patientMenu = false })
                            }
                        }
                    }
                } else {
                    Text(selectedPatient ?: "", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                FilterChip(
                    selected = keyOnly,
                    onClick = { keyOnly = !keyOnly },
                    label = { Text(if (keyOnly) "Key tests" else "All tests", fontSize = 12.sp) },
                    leadingIcon = { Icon(if (keyOnly) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            // Period chips
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(listOf(null to "All", "3m" to "3M", "6m" to "6M", "1y" to "1Y", "2y" to "2Y")) { (value, label) ->
                    FilterChip(
                        selected = period == value,
                        onClick = { period = value },
                        label = { Text(label, fontSize = 12.sp) },
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    selectedPatient == null -> EmptyStateView(Icons.Default.ShowChart, "No reports yet", "Scan lab reports (blood test, thyroid, etc.) and their trends will appear here.")
                    visibleTrends.isEmpty() -> EmptyStateView(Icons.Default.ShowChart, "No test values to chart", "Trends appear once you have reports with numeric test values like TSH, sugar, hemoglobin, cholesterol.")
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        item { OverviewCard(trends = visibleTrends, period = period) }
                        items(visibleTrends) { trend ->
                            TrendCard(trend = trend, onPointClick = { dp -> if (dp.reportId.isNotEmpty()) onNavigateToReport(dp.reportId, trend.name) })
                        }
                    }
                }
            }
        }
    }
}

private fun periodLabel(period: String?): String = when (period) {
    null, "all" -> "all time"
    "1m" -> "last 1 month"
    "3m" -> "last 3 months"
    "6m" -> "last 6 months"
    "1y" -> "last 1 year"
    "2y" -> "last 2 years"
    else -> period
}

@Composable
private fun OverviewCard(trends: List<ParameterTrend>, period: String?) {
    if (trends.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EAF6)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Dashboard, contentDescription = null, tint = Color(0xFF3F51B5))
                Text("Overview — ${periodLabel(period)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color(0xFF1A237E))
            }
            // Two-column grid of compact metric tiles.
            trends.chunked(2).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t -> Box(Modifier.weight(1f)) { OverviewTile(t) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun OverviewTile(trend: ParameterTrend) {
    val latest = trend.dataPoints.lastOrNull()
    val (icon, tint) = when (trend.trend) {
        "improving", "decreasing" -> Icons.Default.TrendingDown to statusNormal
        "worsening", "increasing" -> Icons.Default.TrendingUp to statusHigh
        else -> Icons.Default.TrendingFlat to Color(0xFF607D8B)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(trend.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = latest?.let { "${it.value} ${it.unit}".trim() } ?: "—",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = statusColor(latest?.status ?: "", MaterialTheme.colorScheme.onSurface)
            )
            Icon(icon, contentDescription = trend.trend, tint = tint, modifier = Modifier.size(16.dp))
        }
        val statusText = latest?.status?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: ""
        Text(
            text = listOfNotNull(statusText.ifBlank { null }, "${trend.dataPoints.size} reading(s)").joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TrendCard(trend: ParameterTrend, onPointClick: (TrendDataPoint) -> Unit) {
    val latest = trend.dataPoints.lastOrNull()
    val (trendIcon, trendColor) = when (trend.trend) {
        "improving", "decreasing" -> Icons.Default.TrendingDown to statusNormal
        "worsening", "increasing" -> Icons.Default.TrendingUp to statusHigh
        else -> Icons.Default.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        val info = TestReference.describe(trend.name)
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = info?.title ?: trend.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                latest?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${it.value} ${it.unit}".trim() + (it.context.takeIf { c -> c.isNotBlank() }?.let { c -> " ($c)" } ?: ""),
                            fontWeight = FontWeight.Bold,
                            color = statusColor(it.status, MaterialTheme.colorScheme.onSurface)
                        )
                        Icon(trendIcon, contentDescription = trend.trend, tint = trendColor, modifier = Modifier.size(18.dp))
                    }
                }
            }
            // Latest status in words, so the number has meaning to the reader.
            latest?.status?.takeIf { it.isNotBlank() }?.let { st ->
                Text(
                    text = "Latest reading is ${st.uppercase()}" +
                        (latest.unit.takeIf { it.isNotBlank() }?.let { " (measured in $it)" } ?: "") +
                        (latest.context.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(st, MaterialTheme.colorScheme.onSurfaceVariant),
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(8.dp))
            TrendLineChart(points = trend.dataPoints, onPointClick = onPointClick)
            val unitsMismatch = trend.dataPoints.mapNotNull { it.unit.takeIf { u -> u.isNotBlank() } }.distinct().size > 1
            if (unitsMismatch) {
                Text(
                    text = "⚠ Readings use different units across reports (shown per point) — compare with care.",
                    style = MaterialTheme.typography.labelSmall,
                    color = statusLow,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Plain-language explanation of what this test means (curated, accurate).
            if (info != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(info.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun TrendLineChart(points: List<TrendDataPoint>, onPointClick: (TrendDataPoint) -> Unit) {
    val nums = points.map { parseNum(it.value) }
    val validIdx = points.indices.filter { nums[it] != null }
    if (validIdx.isEmpty()) {
        Text("No numeric values to chart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val minV = validIdx.minOf { nums[it]!! }
    val maxV = validIdx.maxOf { nums[it]!! }
    val range = (maxV - minV).let { if (it == 0f) 1f else it }

    // Different labs can report the same test in different units (e.g. T3 in ng/mL vs nmol/L) —
    // when that happens, tag each point with its own unit so the raw numbers aren't read as
    // directly comparable.
    val unitsMismatch = points.mapNotNull { it.unit.takeIf { u -> u.isNotBlank() } }.distinct().size > 1

    // Position points by their real date (full year+month+day), not evenly by index.
    val times = points.map { isoToMillis(it.date) }
    val distinctTimes = validIdx.mapNotNull { times[it] }.distinct()
    val useTime = distinctTimes.size >= 2 && validIdx.all { times[it] != null }
    val minT = distinctTimes.minOrNull() ?: 0L
    val spanT = ((distinctTimes.maxOrNull() ?: 0L) - minT).let { if (it == 0L) 1L else it }

    val density = LocalDensity.current
    val primary = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val labelPaint = remember {
        Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
    }
    labelPaint.color = labelColor.toArgb()
    labelPaint.textSize = with(density) { 10.sp.toPx() }

    var positions by remember { mutableStateOf<List<Pair<Offset, TrendDataPoint>>>(emptyList()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .pointerInput(points) {
                detectTapGestures { tap ->
                    val hit = positions.minByOrNull { (o, _) -> (o - tap).getDistance() }
                    if (hit != null && (hit.first - tap).getDistance() <= 56f) onPointClick(hit.second)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val padL = with(density) { 6.dp.toPx() }
            val padR = with(density) { 6.dp.toPx() }
            val padT = with(density) { 22.dp.toPx() }
            val padB = with(density) { 22.dp.toPx() }
            val w = size.width; val h = size.height
            val n = points.size
            fun xFor(i: Int): Float {
                if (n <= 1) return w / 2f
                return if (useTime) padL + (w - padL - padR) * ((times[i]!! - minT).toFloat() / spanT.toFloat())
                else padL + (w - padL - padR) * i / (n - 1)
            }
            fun yFor(v: Float) = padT + (h - padT - padB) * (1f - (v - minV) / range)

            // baseline
            drawLine(axisColor, Offset(padL, h - padB), Offset(w - padR, h - padB), strokeWidth = 1.5f)

            val pos = validIdx.map { i -> Offset(xFor(i), yFor(nums[i]!!)) to points[i] }
            positions = pos

            if (pos.size >= 2) {
                val path = Path()
                pos.forEachIndexed { idx, (o, _) -> if (idx == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                drawPath(path, color = primary, style = Stroke(width = with(density) { 2.5.dp.toPx() }))
            }
            // Draw date labels with a minimum gap so they don't overlap when dates are close.
            var lastLabelX = -10000f
            val minGap = with(density) { 46.dp.toPx() }
            pos.forEach { (o, dp) ->
                drawCircle(color = statusColor(dp.status, primary), radius = with(density) { 6.dp.toPx() }, center = o)
                drawCircle(color = Color.White, radius = with(density) { 2.5.dp.toPx() }, center = o)
                val pointLabel = buildString {
                    append(dp.value)
                    if (dp.context.isNotBlank()) append(" (${dp.context.first()})")
                    if (unitsMismatch && dp.unit.isNotBlank()) append(" ${dp.unit}")
                }
                drawContext.canvas.nativeCanvas.drawText(pointLabel, o.x, o.y - with(density) { 10.dp.toPx() }, labelPaint)
                if (o.x - lastLabelX >= minGap) {
                    drawContext.canvas.nativeCanvas.drawText(shortDate(dp.date), o.x, h - with(density) { 6.dp.toPx() }, labelPaint)
                    lastLabelX = o.x
                }
            }
        }
    }
}
