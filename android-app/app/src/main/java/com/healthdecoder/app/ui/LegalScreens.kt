package com.healthdecoder.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Renders [sections] as a scrollable list of heading + body — shared by the Privacy Policy and
 *  Terms & Conditions screens, and by the consent dialog shown at signup. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalDocumentScreen(
    title: String,
    sections: List<LegalSection>,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = tr("Back"))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(sections) { section ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(section.heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(section.body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun PrivacyPolicyScreen(onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    LegalDocumentScreen(tr("Privacy Policy"), PRIVACY_POLICY_SECTIONS, onNavigateBack, modifier)
}

@Composable
fun TermsScreen(onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    LegalDocumentScreen(tr("Terms & Conditions"), TERMS_AND_CONDITIONS_SECTIONS, onNavigateBack, modifier)
}
