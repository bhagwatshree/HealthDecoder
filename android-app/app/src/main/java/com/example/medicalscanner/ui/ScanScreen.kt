package com.example.medicalscanner.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.local.LocalRepository
import com.example.medicalscanner.local.LocalStore
import com.example.medicalscanner.local.BackgroundScanScheduler
import com.example.medicalscanner.util.FileImportUtil
import com.example.medicalscanner.util.ImageUtil
import com.example.medicalscanner.local.SecureKeyManager
import com.example.medicalscanner.network.NetworkModule
import com.example.medicalscanner.model.ProcessedEmail

import java.util.Properties
import java.util.Calendar
import javax.mail.*
import javax.mail.internet.MimeMessage
import javax.mail.search.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // Memory guard; pages are sent to the AI chunk-by-chunk (see AppSettings scan tuning).
    val maxPagesPerScan = remember { AppSettings.getScanMaxPages(context) }
    val pages = remember { mutableStateListOf<Uri>() } // page images to display + analyze
    // Original imported files (uri, name, mime) preserved so the user can download them later.
    val sources = remember { mutableStateListOf<Triple<Uri, String, String>>() }
    var docText by remember { mutableStateOf("") } // text extracted from Word/text documents
    var uploadingState by remember { mutableStateOf<String?>(null) } // null, "uploading", "ocr", "saving", "error"
    var importingFiles by remember { mutableStateOf(false) }
    var showSizeWarningDialog by remember { mutableStateOf(false) }
    var pendingUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var warningSizeMb by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf("") }
    var showConsentDialog by remember { mutableStateOf(false) }
    var showSetupAlert by remember { mutableStateOf(false) }
    var showEmailResultDialog by remember { mutableStateOf(false) }
    var emailResultReportName by remember { mutableStateOf("") }
    var emailResultLocalPath by remember { mutableStateOf("") }
    var emailResultMessageId by remember { mutableStateOf("") }
    // Reports EmailScanWorker (background/Scan Now) downloaded but nobody has reviewed yet —
    // shown as a list below (see "Found in email" card) so the user analyzes them one at a
    // time, at their own pace, rather than an auto-popping dialog.
    var pendingEmailQueue by remember { mutableStateOf<List<ProcessedEmail>>(emptyList()) }

    // ON_RESUME (not LaunchedEffect(Unit)) so this re-checks every time the screen comes back
    // into view — e.g. after "Scan Now" finds something while the user was on AccountScreen —
    // not just the first time ScanScreen is composed.
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        coroutineScope.launch {
            val pending = withContext(Dispatchers.IO) {
                LocalStore.getDatabase(context).processedEmailDao().getPending()
            }
            if (pending.isNotEmpty()) {
                pendingEmailQueue = pending
            }
        }
    }


    // ML Kit Local OCR results
    var localOcrText by remember { mutableStateOf("") }
    var localOcrRunning by remember { mutableStateOf(false) }
    var autoUseSarvam by remember { mutableStateOf(false) }

    var patientName by remember { mutableStateOf("") } // optional override before analyzing
    var selectedScanType by remember { mutableStateOf("prescription") } // "prescription" or "report"
    var selectedReportCategory by remember { mutableStateOf("blood_test") } // "blood_test", "sonography", "2d_echo", "xray", "other"

    // Setup camera temp file URI
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }

    // Run local Google ML Kit text recognition to check script and computerized text
    val runLocalOcr = { uri: Uri ->
        coroutineScope.launch {
            localOcrRunning = true
            localOcrText = ""
            autoUseSarvam = false
            try {
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val inputImage = InputImage.fromFilePath(context, uri)
                
                recognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val recognizedText = visionText.text
                        localOcrText = recognizedText
                        
                        // Check if text has any Indic/regional characters (Devanagari, Gurmukhi, Telugu, etc.)
                        val hasIndic = recognizedText.any { it.code in 0x0900..0x0D7F }
                        
                        // If it contains Indic characters, or if it is empty/extremely short (Latin scanner couldn't read it),
                        // route to Sarvam AI. Otherwise, use Google Scanner + Gemini.
                        val isEnglish = !hasIndic && recognizedText.trim().length >= 15
                        
                        autoUseSarvam = !isEnglish
                        localOcrRunning = false
                    }
                    .addOnFailureListener { e ->
                        e.printStackTrace()
                        localOcrText = "Failed to run local OCR."
                        localOcrRunning = false
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                localOcrRunning = false
            }
        }
    }

    // Helper to estimate picked file size in MB
    fun getUriSizeInMb(context: Context, uri: Uri): Float {
        var bytes: Long = 0
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                bytes = it.length
            }
        } catch (e: Exception) {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        bytes = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return bytes / (1024f * 1024f)
    }

    fun triggerEmailScan() {
        importingFiles = true
        errorMessage = ""
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val email = AppSettings.getLinkedEmail(context)
                val type = AppSettings.getLinkedEmailType(context)
                if (email.isNullOrBlank() || type.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        importingFiles = false
                        showSetupAlert = true
                    }
                    return@withContext
                }

                val db = LocalStore.getDatabase(context)
                val dao = db.processedEmailDao()

                fun reportResult(fileName: String?, filePath: String?, msgId: String?) {
                    importingFiles = false
                    if (filePath != null && fileName != null && msgId != null) {
                        emailResultReportName = fileName
                        emailResultLocalPath = filePath
                        emailResultMessageId = msgId
                        showEmailResultDialog = true
                    } else {
                        android.widget.Toast.makeText(context, "No new reports found (already scanned or none available).", android.widget.Toast.LENGTH_LONG).show()
                    }
                }

                try {
                    if (type == "gmail") {
                        // Uses the Gmail REST API (gmail.readonly scope), not IMAP — the full
                        // mailbox IMAP scope (https://mail.google.com/) needs a paid Google
                        // security assessment to verify for production. See EmailScanWorker.kt.
                        val accessToken = try {
                            val token = NetworkModule.getApi(context).getGoogleAccessToken().access_token
                            SecureKeyManager.setEmailToken(context, token)
                            token
                        } catch (e: Exception) {
                            SecureKeyManager.getEmailToken(context)
                        }
                        if (accessToken.isNullOrBlank()) {
                            withContext(Dispatchers.Main) {
                                importingFiles = false
                                showSetupAlert = true
                            }
                            return@withContext
                        }

                        val cal = Calendar.getInstance()
                        cal.add(Calendar.DAY_OF_MONTH, -2)
                        val afterClause = "after:${java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US).format(cal.time)}"
                        val keywords = listOf("report", "lab", "diagnostic", "billing", "test", "health", "prescription", "invoice", "medical")
                        val subjectClause = "(" + keywords.joinToString(" OR ") { "subject:$it" } + ")"
                        val customSearch = AppSettings.getEmailSearchPrompt(context)
                        val customClause = if (customSearch.isNotBlank()) " subject:$customSearch" else ""
                        val query = "$afterClause $subjectClause has:attachment filename:pdf$customClause"

                        var foundFileName: String? = null
                        var foundFilePath: String? = null
                        var foundMsgId: String? = null

                        for (messageId in com.example.medicalscanner.network.GmailApiClient.searchMessageIds(accessToken, query)) {
                            if (dao.exists(messageId)) continue
                            val attachment = com.example.medicalscanner.network.GmailApiClient.findPdfAttachment(accessToken, messageId) ?: continue
                            val bytes = com.example.medicalscanner.network.GmailApiClient.downloadAttachment(accessToken, messageId, attachment.attachmentId)

                            val emailImportsDir = File(context.cacheDir, "email_imports").apply { mkdirs() }
                            val tempFile = File(emailImportsDir, "imported_${System.currentTimeMillis()}_${attachment.fileName}")
                            tempFile.writeBytes(bytes)

                            foundFileName = attachment.fileName
                            foundFilePath = tempFile.absolutePath
                            foundMsgId = messageId
                            break
                        }

                        withContext(Dispatchers.Main) { reportResult(foundFileName, foundFilePath, foundMsgId) }
                        return@withContext
                    }

                    // "Other (IMAP)" path — a plain IMAP mailbox with a host/port/app-password.
                    val password = SecureKeyManager.getImapPassword(context)
                    val host = AppSettings.getImapHost(context)
                    val port = AppSettings.getImapPort(context)

                    if (password.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            importingFiles = false
                            showSetupAlert = true
                        }
                        return@withContext
                    }

                    val props = Properties()
                    props.setProperty("mail.store.protocol", "imaps")
                    props.setProperty("mail.imaps.host", host)
                    props.setProperty("mail.imaps.port", port.toString())
                    props.setProperty("mail.imaps.ssl.enable", "true")
                    props.setProperty("mail.imaps.timeout", "8000")
                    props.setProperty("mail.imaps.connectiontimeout", "8000")

                    val session = Session.getInstance(props)
                    val store = session.getStore("imaps")
                    store.connect(host, port, email, password)

                    val inbox = store.getFolder("INBOX")
                    inbox.open(Folder.READ_ONLY)

                    val cal = Calendar.getInstance()
                    cal.add(Calendar.DAY_OF_MONTH, -2)
                    val twoDaysAgo = cal.time
                    val dateTerm = ReceivedDateTerm(ComparisonTerm.GE, twoDaysAgo)

                    val keywords = listOf("report", "lab", "diagnostic", "billing", "test", "health", "prescription", "invoice", "medical")
                    val subjectTerms = keywords.map { SubjectTerm(it) }.toTypedArray()
                    val subjectOrTerm = OrTerm(subjectTerms)

                    val customSearch = AppSettings.getEmailSearchPrompt(context)
                    val finalTerm = if (customSearch.isNotBlank()) {
                        val customTerm = OrTerm(SubjectTerm(customSearch), SubjectTerm(customSearch.lowercase()))
                        AndTerm(dateTerm, AndTerm(subjectOrTerm, customTerm))
                    } else {
                        AndTerm(dateTerm, subjectOrTerm)
                    }

                    val messages = inbox.search(finalTerm)

                    var foundFileName: String? = null
                    var foundFilePath: String? = null
                    var foundMsgId: String? = null

                    for (msg in messages) {
                        if (msg !is MimeMessage) continue
                        val messageId = msg.messageID ?: "${msg.subject}_${msg.sentDate?.time}"
                        if (dao.exists(messageId)) continue

                        val content = msg.content
                        if (content is Multipart) {
                            for (i in 0 until content.count) {
                                val part = content.getBodyPart(i)
                                if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true) || !part.fileName.isNullOrBlank()) {
                                    val fileName = part.fileName ?: "report.pdf"
                                    if (fileName.lowercase().endsWith(".pdf")) {
                                        val emailImportsDir = File(context.cacheDir, "email_imports").apply { mkdirs() }
                                        val tempFile = File(emailImportsDir, "imported_${System.currentTimeMillis()}_$fileName")
                                        part.inputStream.use { input ->
                                            tempFile.outputStream().use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                        foundFileName = fileName
                                        foundFilePath = tempFile.absolutePath
                                        foundMsgId = messageId
                                        break
                                    }
                                }
                            }
                        }
                        if (foundFilePath != null) break
                    }

                    inbox.close(false)
                    store.close()

                    withContext(Dispatchers.Main) { reportResult(foundFileName, foundFilePath, foundMsgId) }

                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        importingFiles = false
                        errorMessage = "Failed to connect and read email inbox. Please verify settings."
                    }
                }
            }
        }
    }

    // Handles document layout rendering and file bytes loading
    fun importSelectedFiles(uris: List<Uri>) {
        importingFiles = true
        errorMessage = ""
        coroutineScope.launch {
            try {
                val wasEmpty = pages.isEmpty()
                val imported = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        val cachedSource = FileImportUtil.cacheImage(context, uri) ?: uri
                        Triple(cachedSource, FileImportUtil.displayName(context, uri), FileImportUtil.mimeOf(context, uri)) to
                            FileImportUtil.importFile(context, uri)
                    }
                }
                val newImages = imported.flatMap { it.second.images }
                val newText = imported.joinToString("\n\n") { it.second.text }.trim()
                if (newImages.isEmpty() && newText.isBlank()) {
                    errorMessage = "Couldn't read the selected file(s). Try an image, PDF, or Word document."
                } else {
                    val room = maxPagesPerScan - pages.size
                    errorMessage = if (newImages.size > room)
                        "Page limit is $maxPagesPerScan per scan — extra pages were skipped. Analyze these first, then scan the rest."
                    else ""
                    if (newImages.isNotEmpty() && room > 0) {
                        pages.addAll(newImages.take(room))
                        if (wasEmpty) runLocalOcr(pages.first())
                    }
                    if (newText.isNotBlank()) docText = (docText + "\n\n" + newText).trim()
                    imported.forEach { (meta, _) -> sources.add(meta) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                errorMessage = "Failed to import selected files."
            } finally {
                importingFiles = false
            }
        }
    }

    // Launcher for picking one or more images from gallery (multi-page report)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                val totalSize = uris.sumOf { getUriSizeInMb(context, it).toDouble() }.toFloat()
                if (totalSize > 10.0f) {
                    pendingUris = uris
                    warningSizeMb = totalSize
                    showSizeWarningDialog = true
                } else {
                    importSelectedFiles(uris)
                }
            }
        }
    )

    // Launcher for picking files from ANY folder (Downloads, Documents, Drive…) incl. PDFs
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                val totalSize = uris.sumOf { getUriSizeInMb(context, it).toDouble() }.toFloat()
                if (totalSize > 10.0f) {
                    pendingUris = uris
                    warningSizeMb = totalSize
                    showSizeWarningDialog = true
                } else {
                    importSelectedFiles(uris)
                }
            }
        }
    )

    // Launcher for capturing a page from camera (can be tapped again to add more pages)
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            val captured = cameraTempUri
            if (success && captured != null) {
                val wasEmpty = pages.isEmpty()
                pages.add(captured)
                sources.add(Triple(captured, "Photo_${sources.size + 1}.jpg", "image/jpeg"))
                errorMessage = ""
                if (wasEmpty) runLocalOcr(captured)
            }
        }
    )

    // Helper function to create temporary URI for camera
    fun createCameraTempUri(): Uri {
        val cacheDir = context.cacheDir
        val tempFile = File.createTempFile("scan_capture_", ".jpg", cacheDir).apply {
            createNewFile()
            deleteOnExit()
        }
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, tempFile)
    }

    // Actually opens the camera (assumes permission already granted)
    fun launchCamera() {
        try {
            val uri = createCameraTempUri()
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            e.printStackTrace()
            errorMessage = "Camera initialization failed: ${e.localizedMessage}"
        }
    }

    // Runtime CAMERA permission request (required because CAMERA is declared in the manifest)
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                launchCamera()
            } else {
                errorMessage = "Camera permission is needed to take a photo. Please allow it, or use 'From Gallery' instead."
            }
        }
    )

    // Checks permission first, requesting it if needed, then opens the camera
    fun onTakePhotoClicked() {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Document", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Image preview or selection placeholder
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (importingFiles) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Importing files...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Rendering PDF pages and importing documents. This takes a brief moment.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else if (pages.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            itemsIndexed(pages) { index, uri ->
                                Box(
                                    modifier = Modifier
                                        .fillParentMaxHeight()
                                        .aspectRatio(0.72f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Black.copy(alpha = 0.06f))
                                ) {
                                    Image(
                                        painter = rememberAsyncImagePainter(model = uri),
                                        contentDescription = "Page ${index + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Fit
                                    )
                                    Box(
                                        Modifier.align(Alignment.BottomStart).padding(6.dp)
                                            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.6f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) { Text("Page ${index + 1}", color = Color.White, fontSize = 10.sp) }
                                    IconButton(
                                        onClick = {
                                            pages.removeAt(index)
                                            if (pages.isEmpty()) { localOcrText = ""; autoUseSarvam = false; sources.clear() }
                                        },
                                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove page", tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Camera Placeholder",
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Take a photo or upload a document",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Supports prescriptions, lab reports, and scan reports. Google ML Kit analyzes computerized text, and Sarvam Vision handles regional scripts.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Scan Type and Category Selection UI
                if (pages.isNotEmpty() || docText.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = patientName,
                                onValueChange = { patientName = it },
                                label = { Text("Patient name (optional)") },
                                placeholder = { Text("Leave blank to auto-detect from report") },
                                leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text(
                                text = "Select Scanning Mode",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val scanTypes = listOf(
                                    Pair("prescription", "Medicine Scan"),
                                    Pair("report", "Reports Scan")
                                )
                                scanTypes.forEach { (typeKey, typeLabel) ->
                                    val active = selectedScanType == typeKey
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (active) MaterialTheme.colorScheme.primaryContainer 
                                                else MaterialTheme.colorScheme.surface
                                            )
                                            .border(
                                                1.dp,
                                                if (active) MaterialTheme.colorScheme.primary 
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable { selectedScanType = typeKey }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = typeLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            
                            if (selectedScanType == "report") {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Report Category",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val row1 = listOf(
                                        Pair("blood_test", "Blood Test"),
                                        Pair("sonography", "Sonography"),
                                        Pair("2d_echo", "2D Echo")
                                    )
                                    val row2 = listOf(
                                        Pair("xray", "X-Ray"),
                                        Pair("other", "Other")
                                    )
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row1.forEach { (catKey, catLabel) ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .background(
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondaryContainer 
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondary 
                                                        else MaterialTheme.colorScheme.outlineVariant,
                                                        RoundedCornerShape(20.dp)
                                                    )
                                                    .clickable { selectedReportCategory = catKey }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = catLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selectedReportCategory == catKey) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selectedReportCategory == catKey) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        row2.forEach { (catKey, catLabel) ->
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(20.dp))
                                                    .background(
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondaryContainer 
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .border(
                                                        1.dp,
                                                        if (selectedReportCategory == catKey) MaterialTheme.colorScheme.secondary 
                                                        else MaterialTheme.colorScheme.outlineVariant,
                                                        RoundedCornerShape(20.dp)
                                                    )
                                                    .clickable { selectedReportCategory = catKey }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = catLabel,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selectedReportCategory == catKey) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selectedReportCategory == catKey) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // local ML Kit Analysis Panel
                if (pages.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Google ML Kit Scan Results",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (localOcrRunning) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Scan Done",
                                        tint = Color(0xFF2E7D32),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            
                            val detectedTextSummary = if (localOcrText.isEmpty()) {
                                if (localOcrRunning) "Analyzing image text..." else "No clear text detected locally."
                            } else {
                                if (localOcrText.length > 100) "${localOcrText.take(100)}..." else localOcrText
                            }
                            
                            Text(
                                text = "\"$detectedTextSummary\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                            )

                            Divider(modifier = Modifier.padding(vertical = 4.dp))

                            // Auto-decision notification
                            val useSarvam = autoUseSarvam
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (useSarvam) "Scan Mode: Regional / Indic Script (Sarvam AI)" else "Scan Mode: English (Google Scan + Gemini)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (useSarvam) Color(0xFFE65100) else Color(0xFF2E7D32)
                                    )
                                    Text(
                                        text = if (useSarvam) "Routing to Sarvam Vision & Translation APIs." else "Routing to local Google scanner & standard Gemini API.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Error Message Card
                if (errorMessage.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // Two clean ways to add: Camera and From Device Folder (images, PDF, Word — any folder)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { onTakePhotoClicked() },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.PhotoCamera, contentDescription = "Camera")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (pages.isEmpty()) "Camera" else "Add Page", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            fileLauncher.launch(arrayOf(
                                "image/*",
                                "application/pdf",
                                "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "text/plain"
                            ))
                        },
                        modifier = Modifier.weight(1f).height(54.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = "From device")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("From Device", fontWeight = FontWeight.Bold)
                    }
                }

                // Email scan integration manual option
                OutlinedButton(
                    onClick = {
                        if (!AppSettings.isEmailConsentGranted(context)) {
                            showConsentDialog = true
                        } else if (AppSettings.getLinkedEmail(context).isNullOrBlank()) {
                            showSetupAlert = true
                        } else {
                            triggerEmailScan()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Email, contentDescription = "Scan from Email")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Import from Linked Email", fontWeight = FontWeight.Bold)
                }

                // Reports a background scan (Scan Now / the 7 PM daily alarm) already downloaded
                // but nobody has reviewed yet — one row per report, analyzed one at a time.
                if (pendingEmailQueue.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Found in email (${pendingEmailQueue.size})",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            pendingEmailQueue.forEach { pending ->
                                val localPath = pending.pendingLocalPath
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        pending.attachmentName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                                        maxLines = 1
                                    )
                                    Row {
                                        TextButton(onClick = {
                                            pendingEmailQueue = pendingEmailQueue.filter { it.messageId != pending.messageId }
                                            coroutineScope.launch(Dispatchers.IO) {
                                                LocalStore.getDatabase(context).processedEmailDao().insert(
                                                    ProcessedEmail(
                                                        messageId = pending.messageId,
                                                        attachmentName = pending.attachmentName,
                                                        processedAt = System.currentTimeMillis()
                                                    )
                                                )
                                            }
                                            try { localPath?.let { File(it).delete() } } catch (_: Exception) {}
                                        }) { Text("Skip") }
                                        Button(onClick = {
                                            if (localPath == null) return@Button
                                            pendingEmailQueue = pendingEmailQueue.filter { it.messageId != pending.messageId }
                                            importSelectedFiles(listOf(Uri.fromFile(File(localPath))))
                                            coroutineScope.launch(Dispatchers.IO) {
                                                LocalStore.getDatabase(context).processedEmailDao().insert(
                                                    ProcessedEmail(
                                                        messageId = pending.messageId,
                                                        attachmentName = pending.attachmentName,
                                                        processedAt = System.currentTimeMillis()
                                                    )
                                                )
                                            }
                                            android.widget.Toast.makeText(context, "Report added to scan preview.", android.widget.Toast.LENGTH_SHORT).show()
                                        }) { Text("Analyze") }
                                    }
                                }
                            }
                        }
                    }
                }

                // Indicator when a Word/text document's text has been attached
                if (docText.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "Document text attached (${docText.length} chars). It will be analyzed with any images.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { docText = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove document text")
                            }
                        }
                    }
                }

                if (pages.isNotEmpty() || docText.isNotBlank()) {
                    Text(
                        text = if (pages.isNotEmpty()) "${pages.size} page(s) selected. Add more pages of the SAME report, then analyze."
                               else "Document attached. Add pages/images if needed, then analyze.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = {
                            if (pages.isEmpty() && docText.isBlank()) return@Button
                            val referenceText = listOf(localOcrText, docText).filter { it.isNotBlank() }.joinToString("\n\n")
                            val category = if (selectedScanType == "report") selectedReportCategory else "prescription"
                            
                            BackgroundScanScheduler.startScan(
                                context = context,
                                pageUris = pages.toList(),
                                sourceUris = sources.toList(),
                                referenceText = referenceText,
                                scanType = selectedScanType,
                                category = category,
                                patientName = patientName
                            )
                            
                            android.widget.Toast.makeText(
                                context,
                                "Scan started in background.",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            
                            onNavigateBack()
                        },
                        enabled = uploadingState == null,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(imageVector = Icons.Default.CloudUpload, contentDescription = "Analyze")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Analyze & Scan Document", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            if (showSizeWarningDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showSizeWarningDialog = false
                        pendingUris = emptyList()
                    },
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Text("Large Files Selected", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text(
                            text = "The selected files are very large (%.2f MB). Processing these files will take longer and might cause memory issues. We recommend keeping files under 10 MB. Do you want to proceed anyway?".format(warningSizeMb),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showSizeWarningDialog = false
                                importSelectedFiles(pendingUris)
                                pendingUris = emptyList()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Proceed Anyway")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showSizeWarningDialog = false
                                pendingUris = emptyList()
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (showConsentDialog) {
                AlertDialog(
                    onDismissRequest = { showConsentDialog = false },
                    title = { Text("Email Access Consent", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = "Medical Assist (MA) requires your permission to connect to your email inbox. We will only search for emails from the last 2 days containing potential medical report attachments (PDFs) and extract them locally on your phone. No email contents are sent to our servers. Do you consent to this?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showConsentDialog = false
                                AppSettings.setEmailConsentGranted(context, true)
                                if (AppSettings.getLinkedEmail(context).isNullOrBlank()) {
                                    showSetupAlert = true
                                } else {
                                    triggerEmailScan()
                                }
                            }
                        ) {
                            Text("I Consent")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConsentDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (showSetupAlert) {
                AlertDialog(
                    onDismissRequest = { showSetupAlert = false },
                    title = { Text("Email Integration Required", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = "You haven't linked an email account yet. Please go to Settings (Account tab) to link your Gmail account or IMAP details first.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(onClick = { showSetupAlert = false }) {
                            Text("OK")
                        }
                    }
                )
            }

            if (showEmailResultDialog) {
                AlertDialog(
                    onDismissRequest = {
                        showEmailResultDialog = false
                        try { File(emailResultLocalPath).delete() } catch (_: Exception) {}
                    },
                    title = { Text("New Medical Report Found", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            text = "We found report '$emailResultReportName' in your inbox. Do you want to download and upload it for scanning?",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showEmailResultDialog = false
                                val uri = Uri.fromFile(File(emailResultLocalPath))
                                importSelectedFiles(listOf(uri))
                                coroutineScope.launch(Dispatchers.IO) {
                                    LocalStore.getDatabase(context).processedEmailDao().insert(
                                        ProcessedEmail(
                                            messageId = emailResultMessageId,
                                            attachmentName = emailResultReportName,
                                            processedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                                android.widget.Toast.makeText(context, "Report added to scan preview.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Yes, Import")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showEmailResultDialog = false
                                coroutineScope.launch(Dispatchers.IO) {
                                    LocalStore.getDatabase(context).processedEmailDao().insert(
                                        ProcessedEmail(
                                            messageId = emailResultMessageId,
                                            attachmentName = emailResultReportName,
                                            processedAt = System.currentTimeMillis()
                                        )
                                    )
                                }
                                try { File(emailResultLocalPath).delete() } catch (_: Exception) {}
                            }
                        ) {
                            Text("Skip & Mark Read")
                        }
                    }
                )
            }

        }
    }
}

/**
 * Helper to convert Uri to MultipartBody.Part for Retrofit
 */
private fun uriToMultipartBodyPart(context: Context, uri: Uri, partName: String): MultipartBody.Part? {
    try {
        val contentResolver = context.contentResolver
        var fileName = "report_scan.jpg"
        
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                fileName = cursor.getString(nameIndex)
            }
        }
        
        // Clean special characters in filename to avoid HTTP header issues
        fileName = fileName.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
        
        val inputStream = contentResolver.openInputStream(uri) ?: return null
        val tempFile = File(context.cacheDir, fileName)
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val requestFile = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
        return MultipartBody.Part.createFormData(partName, tempFile.name, requestFile)
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}
