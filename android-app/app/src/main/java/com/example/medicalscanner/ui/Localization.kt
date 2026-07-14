package com.example.medicalscanner.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.example.medicalscanner.local.AppLanguageState
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.local.RemoteUiTranslations

/**
 * Translates [text] into the app-wide preferred language. Lookup order: the on-device
 * cache of the backend's ui_translations table (DB is the source of truth — edits there
 * reach every install without a build), then the bundled UiTranslations.kt seed (works
 * before the first successful fetch / fully offline), then the original English text.
 * No network call happens here — fetching is handled by RemoteUiTranslations, kicked off
 * once at app start and again whenever the user picks a language.
 */
@Composable
fun tr(text: String): String {
    if (text.isBlank()) return text
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppLanguageState.ensureInit(context) }
    val language = AppLanguageState.current

    if (language.equals("English", ignoreCase = true)) return text

    return RemoteUiTranslations.get(context, language, text)
        ?: UiTranslations.lookup(language, text)
        ?: text
}

@Composable
fun LanguagePickerIcon() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { AppLanguageState.ensureInit(context) }
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(imageVector = Icons.Default.Translate, contentDescription = "Change language")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        AppSettings.SUPPORTED_LANGUAGES.forEach { lang ->
            DropdownMenuItem(
                text = {
                    Text(
                        lang,
                        fontWeight = if (lang == AppLanguageState.current) FontWeight.Bold else FontWeight.Normal
                    )
                },
                trailingIcon = {
                    if (lang == AppLanguageState.current) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null)
                    }
                },
                onClick = {
                    AppLanguageState.select(context, lang)
                    expanded = false
                }
            )
        }
    }
}
