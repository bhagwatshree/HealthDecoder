package com.healthdecoder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private data class HealthTip(val headline: String, val detail: String)

// Local, hardcoded rotation — no ad SDK, no network call. Every string is wrapped in tr() where
// it's rendered so this rotates through the app's existing translation pipeline like everything
// else, without needing its own localized copies here.
private val HEALTH_TIPS = listOf(
    HealthTip(
        "Hydrate before a blood draw",
        "Drinking water in the hours before a blood test makes your veins easier to find and can make results like electrolytes more accurate."
    ),
    HealthTip(
        "Take medicines the same way each day",
        "Some medicines work best with food, others on an empty stomach. Sticking to one routine helps your body absorb them consistently."
    ),
    HealthTip(
        "Keep a symptom log before your visit",
        "Jot down when a symptom started, how often it happens, and what makes it better or worse — it helps your doctor a lot more than \"it's been a while.\""
    ),
    HealthTip(
        "Bring your last 2–3 reports to appointments",
        "Trends matter more than a single number. Having recent reports on hand lets your doctor see the direction things are moving, not just today's snapshot."
    ),
    HealthTip(
        "Fasting tests mean fasting tests",
        "For fasting blood sugar or lipid panels, even black coffee or chewing gum can skew results. Water is usually fine — check with your lab if unsure."
    ),
    HealthTip(
        "Don't stop a medicine without asking first",
        "Some medications (like steroids or blood pressure pills) can cause problems if stopped suddenly, even if you're feeling better."
    ),
    HealthTip(
        "Store medicines away from heat and moisture",
        "The bathroom cabinet is often the worst place — humidity can degrade tablets faster than a cool, dry drawer."
    ),
    HealthTip(
        "Double-check dosage units",
        "Mixing up mg and mcg, or ml and mg, is one of the most common medication mistakes. When in doubt, ask your pharmacist to confirm."
    ),
    HealthTip(
        "Set reminders for refills, not just doses",
        "Running out of a medicine for a few days can undo weeks of consistent treatment — a refill reminder a week early avoids the gap."
    ),
    HealthTip(
        "Share your full medicine list with every new doctor",
        "Interactions are easy to miss when each doctor only sees the prescriptions they wrote. A full list — including over-the-counter drugs — helps catch them."
    ),
)

private const val ROTATE_INTERVAL_MS = 15_000L

/**
 * Renamed from the earlier draft's "SPONSORED HEALTH INSIGHT" — that wording implies paid
 * content, which would contradict the app's Play Console "Ads: No" declaration. This card is
 * local, rotating content only: no ad SDK, no advertising ID, no third-party call.
 */
@Composable
fun HealthTipCard(modifier: Modifier = Modifier) {
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffectRotate(tipCount = HEALTH_TIPS.size, onTick = { index = (index + 1) % HEALTH_TIPS.size })

    var showDetail by remember { mutableStateOf(false) }
    val tip = HEALTH_TIPS[index]

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = statusContainerColor(MaterialTheme.colorScheme.tertiary, alpha = 0.16f)),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Default.Lightbulb, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp)) }
                Text(
                    tr("HEALTH TIP"), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.tertiary
                )
            }
            Text(tr(tip.headline), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                tr(tip.detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
            TextButton(onClick = { showDetail = true }, contentPadding = PaddingValues(0.dp)) {
                Text(tr("Learn More"), fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showDetail) {
        AlertDialog(
            onDismissRequest = { showDetail = false },
            title = { Text(tr(tip.headline), fontWeight = FontWeight.Bold) },
            text = { Text(tr(tip.detail)) },
            confirmButton = { TextButton(onClick = { showDetail = false }) { Text(tr("Got it")) } }
        )
    }
}

/** Rotates on a fixed timer while this composable stays alive (i.e. while Home is visible). */
@Composable
private fun LaunchedEffectRotate(tipCount: Int, onTick: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(tipCount) {
        while (true) {
            delay(ROTATE_INTERVAL_MS)
            onTick()
        }
    }
}

/**
 * Slim tappable banner for Smart Health Lens — not a floating overlay icon (competes with other
 * chrome for space) and not a 7th grid tile (breaks the 6-tile rule). Sits near Scan Report
 * conceptually (capture-then-analyze vs. live-analyze) without taking a full tile slot.
 */
@Composable
fun SmartHealthLensBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = statusContainerColor(com.healthdecoder.app.theme.AiAccent, alpha = 0.16f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("🔬", fontSize = 20.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    tr("Try Smart Health Lens"), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = com.healthdecoder.app.theme.AiAccent
                )
                Text(
                    tr("Live camera scan — point and get instant answers"), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = com.healthdecoder.app.theme.AiAccent)
        }
    }
}
