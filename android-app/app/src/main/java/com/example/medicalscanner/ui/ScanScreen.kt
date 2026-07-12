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
import com.example.medicalscanner.util.FileImportUtil
import com.example.medicalscanner.util.ImageUtil
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
    var errorMessage by remember { mutableStateOf("") }
    
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

    // Launcher for picking one or more images from gallery (multi-page report)
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(10),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                coroutineScope.launch {
                    val wasEmpty = pages.isEmpty()
                    val room = maxPagesPerScan - pages.size
                    // Copy into the app's cache right away — the picker's read grant on these
                    // URIs is transient and can be revoked before the user taps Analyze.
                    val cached = withContext(Dispatchers.IO) {
                        uris.take(room.coerceAtLeast(0)).mapNotNull { FileImportUtil.cacheImage(context, it) }
                    }
                    pages.addAll(cached)
                    errorMessage = if (uris.size > room)
                        "Page limit is $maxPagesPerScan per scan — extra pages were skipped. Analyze these first, then scan the rest."
                    else ""
                    if (wasEmpty && pages.isNotEmpty()) runLocalOcr(pages.first())
                }
            }
        }
    )

    // Launcher for picking files from ANY folder (Downloads, Documents, Drive…) incl. PDFs
    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            if (uris.isNotEmpty()) {
                coroutineScope.launch {
                    val wasEmpty = pages.isEmpty()
                    val imported = withContext(Dispatchers.IO) {
                        uris.map { uri ->
                            // Cache the original bytes now too, so "download original" still
                            // works later even if the picker's read grant on `uri` is gone by then.
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
                        // Preserve every picked original for later download.
                        imported.forEach { (meta, _) -> sources.add(meta) }
                    }
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
                    if (pages.isNotEmpty()) {
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
                            coroutineScope.launch {
                                if (pages.isEmpty() && docText.isBlank()) return@launch
                                uploadingState = "uploading"
                                errorMessage = ""
                                try {
                                    // Downscale pages one at a time — full camera photos held all
                                    // at once (plus their Base64 copies for the AI request) used to
                                    // run out of memory and crash multi-document scans.
                                    val pageData = withContext(Dispatchers.IO) {
                                        pages.mapNotNull { uri ->
                                            ImageUtil.compressForScan(context, uri)?.let { it to "image/jpeg" }
                                        }
                                    }
                                    if (pageData.isEmpty() && docText.isBlank()) {
                                        uploadingState = null
                                        errorMessage = "Failed to read the selected file(s)."
                                        return@launch
                                    }
                                    // Preserve the original files so the user can download them later.
                                    val sourceData = withContext(Dispatchers.IO) {
                                        sources.mapNotNull { (uri, name, mime) ->
                                            val b = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                            if (b != null) Triple(b, name, mime) else null
                                        }
                                    }
                                    val referenceText = listOf(localOcrText, docText).filter { it.isNotBlank() }.joinToString("\n\n")
                                    val category = if (selectedScanType == "report") selectedReportCategory else "prescription"
                                    uploadingState = "ocr" // On-device Gemini extraction (images and/or document text)
                                    val savedReports = LocalRepository.saveScan(context, pageData, sourceData, referenceText, selectedScanType, category, patientName)
                                    uploadingState = "saving"
                                    if (savedReports.size > 1) {
                                        android.widget.Toast.makeText(
                                            context,
                                            "Found ${savedReports.size} reports in this scan — each saved with its own date.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    onNavigateToDetail(savedReports.first().id)
                                } catch (dup: com.example.medicalscanner.local.DuplicateReportException) {
                                    uploadingState = null
                                    val who = dup.existing.patientName ?: "this patient"
                                    val date = dup.existing.reportDate ?: ""
                                    errorMessage = "This report is already saved for $who${if (date.isNotBlank()) " (dated $date)" else ""}. It was not added again."
                                } catch (e: OutOfMemoryError) {
                                    uploadingState = null
                                    errorMessage = "Too many pages to process at once. Please scan fewer documents at a time."
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    uploadingState = null
                                    errorMessage = "Scan failed. Please try again — check your internet connection for AI analysis."
                                }
                            }
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

            // Upload Overlay Modal (Full-Screen loading screen)
            AnimatedVisibility(
                visible = uploadingState != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(24.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(56.dp)
                            )
                            
                            val useSarvam = autoUseSarvam
                            val statusText = when (uploadingState) {
                                "uploading" -> "Uploading Document..."
                                "ocr" -> if (useSarvam) {
                                    "Sarvam AI OCR Running...\nExtracting Marathi/Hindi/English script\n& Translating to English..."
                                } else {
                                    "Standard AI OCR Running...\nExtracting dates, comments, and medicines"
                                }
                                "saving" -> "Saving to Secure Database..."
                                else -> "Processing..."
                            }
                            
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Please do not close the app. This takes a few moments for accurate parsing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
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
