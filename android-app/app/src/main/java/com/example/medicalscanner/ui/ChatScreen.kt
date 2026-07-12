package com.example.medicalscanner.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.medicalscanner.ai.SpeechEngine
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.local.LocalRepository
import com.example.medicalscanner.model.ChatMessage
import com.example.medicalscanner.model.ChatRequest
import com.example.medicalscanner.util.AudioPlayer
import com.example.medicalscanner.util.LanguageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SUGGESTED_QUESTIONS = listOf(
    "What do my latest test results mean?",
    "Explain my current medicines in simple words",
    "Are any of my values abnormal?",
    "What should I ask my doctor next?"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialPatientName: String = ""
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var patientName by remember { mutableStateOf(initialPatientName) }
    var language by remember { mutableStateOf(AppSettings.getPreferredLanguage(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val listState = rememberLazyListState()

    // Text-to-speech so answers can be read aloud (voice assistance for non-readers).
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching { ttsRef.value?.language = LanguageUtil.localeFor(language) }
            }
        }
        ttsRef.value = engine
        onDispose { runCatching { engine.stop(); engine.shutdown() }; AudioPlayer.stop() }
    }
    LaunchedEffect(language) { runCatching { ttsRef.value?.language = LanguageUtil.localeFor(language) } }

    // Read aloud using the engine chosen in Settings: Phone (on-device), or Sarvam/Gemini
    // (cloud voices, better for Indic languages). Cloud engines fall back to phone TTS.
    fun phoneSpeak(text: String) {
        runCatching {
            ttsRef.value?.language = LanguageUtil.localeFor(language)
            ttsRef.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat")
        }
    }
    fun speak(text: String) {
        val engine = AppSettings.getVoiceEngine(context)
        if (engine == "Phone") { phoneSpeak(text); return }
        coroutineScope.launch {
            val played = runCatching {
                val audios = withContext(Dispatchers.IO) {
                    SpeechEngine.synthesize(context, text, language, engine.lowercase())
                }
                audios.isNotEmpty() && AudioPlayer.playBase64Wavs(context, audios)
            }.getOrDefault(false)
            if (!played) phoneSpeak(text)
        }
    }

    LaunchedEffect(messages.size, isLoading) {
        val target = messages.size + (if (isLoading) 1 else 0)
        if (target > 0) listState.animateScrollToItem(target - 1)
    }

    val sendQuestion: (String) -> Unit = sendLambda@{ question ->
        val trimmed = question.trim()
        if (trimmed.isEmpty() || isLoading) return@sendLambda
        errorMessage = ""
        messages.add(ChatMessage(role = "user", content = trimmed))
        input = ""
        val history = messages.dropLast(1).toList()
        isLoading = true
        coroutineScope.launch {
            try {
                val response = LocalRepository.chat(
                    context,
                    ChatRequest(
                        question = trimmed,
                        patientName = patientName.trim().ifEmpty { null },
                        history = history,
                        language = language
                    )
                )
                messages.add(ChatMessage(role = "assistant", content = response.answer))
                speak(response.answer) // read the answer aloud
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Couldn't reach the assistant. Check that the server is running and configured in settings."
            } finally {
                isLoading = false
            }
        }
    }

    // Voice input (speech-to-text) in the selected language.
    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) sendQuestion(spoken)
        }
    }
    fun startVoiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, LanguageUtil.tagFor(language))
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your question in $language")
            }
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            errorMessage = "Voice input isn't available on this device. Please install/enable Google voice typing."
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Health Assistant", fontWeight = FontWeight.Bold)
                        Text(
                            "Ask by text or voice — in your language",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (messages.isNotEmpty()) {
                        IconButton(onClick = { messages.clear() }) {
                            Icon(imageVector = Icons.Default.DeleteSweep, contentDescription = "Clear chat")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                input = input,
                onInputChange = { input = it },
                onSend = { sendQuestion(input) },
                onVoice = { startVoiceInput() },
                enabled = !isLoading
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Language selector + optional patient scope
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LanguageDropdown(
                    selected = language,
                    onSelected = {
                        language = it
                        AppSettings.setPreferredLanguage(context, it)
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = patientName,
                    onValueChange = { patientName = it },
                    label = { Text("Patient (optional)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.2f)
                )
            }

            // Safety disclaimer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    "Explains your records in simple terms. Not a doctor — always confirm with your physician.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            if (errorMessage.isNotEmpty()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty() && !isLoading) {
                    ChatEmptyState(onQuestionClick = { sendQuestion(it) }, onVoice = { startVoiceInput() })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages) { msg ->
                            ChatBubble(
                                message = msg,
                                onSpeak = if (msg.role == "assistant") ({ speak(msg.content) }) else null
                            )
                        }
                        if (isLoading) {
                            item {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedCard(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Language",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = selected,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AppSettings.SUPPORTED_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang, fontWeight = FontWeight.Medium) },
                    onClick = {
                        onSelected(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatEmptyState(onQuestionClick: (String) -> Unit, onVoice: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.QuestionAnswer,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Ask by typing or tap the mic to speak",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "I explain test results, medicines, and doctor's notes in plain language.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
        Button(onClick = onVoice, shape = RoundedCornerShape(24.dp)) {
            Icon(Icons.Default.Mic, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Speak your question")
        }
        Spacer(Modifier.height(16.dp))
        SUGGESTED_QUESTIONS.forEach { q ->
            Surface(
                onClick = { onQuestionClick(q) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(q, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onSpeak: (() -> Unit)?) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp, topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp
                        )
                    )
                    .background(bubbleColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(text = message.content, style = MaterialTheme.typography.bodyMedium, color = textColor)
            }
            if (onSpeak != null) {
                TextButton(onClick = onSpeak, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Icon(Icons.Default.VolumeUp, contentDescription = "Read aloud", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Listen", fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    enabled: Boolean
) {
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onVoice) {
                Icon(imageVector = Icons.Default.Mic, contentDescription = "Speak", tint = MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = { Text("Type or tap mic…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { onSend() })
            )
            FilledIconButton(onClick = onSend, enabled = enabled && input.isNotBlank()) {
                Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
            }
        }
    }
}
