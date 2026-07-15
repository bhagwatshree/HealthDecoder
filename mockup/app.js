/* Medical Assist Figma Spec & Clickable Mockup Interactive Script */

document.addEventListener('DOMContentLoaded', () => {
  // --- Spec Definitions Database for Figma Inspector ---
  const specs = {
    "LoginScreen": {
      class: "LoginScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "24.dp",
      alignment: "Center / Column",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalTeal (#0D7377)",
      desc: "Main login gateway supporting email/password, firebase phone OTP, and local biometrics.",
      code: `@Composable\nfun LoginScreen(\n    onLoggedIn: () -> Unit,\n    onNavigateToRegister: (String?) -> Unit,\n    modifier: Modifier = Modifier\n) {\n    Column(\n        modifier = modifier\n            .fillMaxSize()\n            .background(MedicalBackground)\n            .verticalScroll(rememberScrollState())\n            .padding(24.dp),\n        horizontalAlignment = Alignment.CenterHorizontally\n    ) {\n        AppBranding()\n        LoginForm(onLoggedIn, onNavigateToRegister)\n    }\n}`
    },
    "AuthTabs": {
      class: "TabRow / ScrollableTabRow",
      width: "312 dp (fillMaxWidth)",
      height: "48 dp",
      padding: "4.dp",
      alignment: "Horizontal Row",
      bg: "MedicalSurfaceVariant (#ECF5F5)",
      primary: "MedicalTeal (#0D7377)",
      desc: "Switches between Email authentication and Firebase SMS Phone Verification authentication pathways.",
      code: `TabRow(\n    selectedTabIndex = selectedTab,\n    containerColor = MedicalSurfaceVariant,\n    shape = RoundedCornerShape(10.dp)\n) {\n    Tab(selected = (selectedTab == 0), onClick = { selectedTab = 0 }) { \n        Text(tr("Email")) \n    }\n    Tab(selected = (selectedTab == 1), onClick = { selectedTab = 1 }) { \n        Text(tr("Phone OTP")) \n    }\n}`
    },
    "EmailTextField": {
      class: "OutlinedTextField",
      width: "312 dp (fillMaxWidth)",
      height: "64 dp",
      padding: "0.dp",
      alignment: "Default",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalTeal (#0D7377)",
      desc: "Text field accepting the user's email. Includes custom keyboard navigation and visual outline validations.",
      code: `OutlinedTextField(\n    value = email,\n    onValueChange = { email = it },\n    label = { Text(tr("Email Address")) },\n    leadingIcon = { Icon(Icons.Default.Email, null) },\n    shape = RoundedCornerShape(10.dp),\n    singleLine = true,\n    modifier = Modifier.fillMaxWidth()\n)`
    },
    "PasswordTextField": {
      class: "OutlinedTextField",
      width: "312 dp (fillMaxWidth)",
      height: "64 dp",
      padding: "0.dp",
      alignment: "Default",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalTeal (#0D7377)",
      desc: "Password input utilizing secure visual transformations (bullet characters) and togglable visibility action button.",
      code: `OutlinedTextField(\n    value = password,\n    onValueChange = { password = it },\n    label = { Text(tr("Password")) },\n    leadingIcon = { Icon(Icons.Default.Lock, null) },\n    trailingIcon = {\n        IconButton(onClick = { passwordVisible = !passwordVisible }) {\n            Icon(if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff, null)\n        }\n    },\n    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),\n    shape = RoundedCornerShape(10.dp),\n    modifier = Modifier.fillMaxWidth()\n)`
    },
    "LoginButton": {
      class: "Button",
      width: "312 dp (fillMaxWidth)",
      height: "48 dp",
      padding: "12.dp",
      alignment: "Center",
      bg: "MedicalTeal (#0D7377)",
      primary: "MedicalTeal (#0D7377)",
      desc: "Triggers the authentication endpoint with a credentials payload. Displays circular progress indicators on loading states.",
      code: `Button(\n    onClick = { loginWithEmail() },\n    shape = RoundedCornerShape(12.dp),\n    colors = ButtonDefaults.buttonColors(containerColor = MedicalTeal),\n    modifier = Modifier.fillMaxWidth().height(48.dp)\n) {\n    if (isLoading) {\n        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))\n    } else {\n        Text(tr("Sign In"), fontWeight = FontWeight.Bold)\n    }\n}`
    },
    "GoogleSignInButton": {
      class: "OutlinedButton",
      width: "312 dp (fillMaxWidth)",
      height: "48 dp",
      padding: "12.dp",
      alignment: "Center",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalOutline (#74777F)",
      desc: "Invokes Google OAuth credential flows to log in using account credentials registered in Google Cloud Console.",
      code: `OutlinedButton(\n    onClick = { loginWithGoogle() },\n    shape = RoundedCornerShape(12.dp),\n    modifier = Modifier.fillMaxWidth().height(48.dp)\n) {\n    Image(painterResource(R.drawable.ic_google), null, modifier = Modifier.size(18.dp))\n    Spacer(Modifier.width(10.dp))\n    Text(tr("Sign In with Google"), color = MaterialTheme.colorScheme.onSurface)\n}`
    },
    "BiometricButton": {
      class: "Box / Clickable",
      width: "312 dp (fillMaxWidth)",
      height: "44 dp",
      padding: "10.dp",
      alignment: "Center",
      bg: "MedicalSurfaceVariant (#ECF5F5)",
      primary: "MedicalTeal (#0D7377)",
      desc: "Initiates native Android BiometricPrompt APIs to unlock the user session securely without requiring typing passwords.",
      code: `Box(\n    modifier = Modifier\n        .fillMaxWidth()\n        .clip(RoundedCornerShape(10.dp))\n        .background(MedicalSurfaceVariant)\n        .clickable { authenticateBiometrics(context) }\n        .padding(12.dp),\n    contentAlignment = Alignment.Center\n) {\n    Text(text = "🔒 " + tr("Tap to use Fingerprint / Face ID"), color = MedicalTeal)\n}`
    },
    "HomeScreen": {
      class: "HomeScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / Grid",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Primary portal containing a Redesigned Material Scaffold top bar and grid layout with 6 main action modules.",
      code: `@Composable\nfun HomeScreen(\n    onNavigateToScan: () -> Unit,\n    onNavigateToRecords: () -> Unit,\n    onNavigateToReminders: () -> Unit,\n    onNavigateToMedicationTracker: () -> Unit,\n    onNavigateToPendingTests: () -> Unit,\n    onNavigateToTrends: () -> Unit\n) {\n    Scaffold(\n        topBar = { HomeTopBar(...) }\n    ) { innerPadding ->\n        Column(modifier = Modifier.padding(innerPadding)) {\n            BackgroundScanProgressBar()\n            HomeActionGrid(listOf(...))\n        }\n    }\n}`
    },
    "HomeTopBar": {
      class: "TopAppBar",
      width: "360 dp (fillMaxWidth)",
      height: "56 dp",
      padding: "12.dp",
      alignment: "Horizontal Row",
      bg: "MedicalSurface (elevation 3dp)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Top bar with localized title, logo badge, and navigation hooks for the user profile, AI Chat, report comparison and language picker.",
      code: `TopAppBar(\n    title = { LogoBadge() },\n    navigationIcon = {\n        Row { \n            IconButton(onClick = onNavigateToAccount) { Icon(Icons.Default.AccountCircle, null) }\n            IconButton(onClick = onNavigateToChat) { Icon(Icons.Default.QuestionAnswer, null) }\n        }\n    },\n    actions = {\n        IconButton(onClick = onNavigateToCompare) { Icon(Icons.Default.CompareArrows, null) }\n        LanguagePickerIcon()\n    },\n    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))\n)`
    },
    "ScanReportTile": {
      class: "ActionSquare (Card)",
      width: "148 dp (aspectRatio 1.0)",
      height: "148 dp",
      padding: "12.dp",
      alignment: "Center / Column",
      bg: "StatusNormalBg (#E8F5E9)",
      primary: "StatusNormal (#2E7D32)",
      desc: "Medically themed card launcher routing the user to the OCR Scanner camera and document import screen.",
      code: `Card(\n    modifier = modifier.aspectRatio(1f).clickable { onClick() },\n    shape = RoundedCornerShape(20.dp),\n    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))\n) {\n    Column(verticalArrangement = Arrangement.Center) {\n        Icon(Icons.Default.AddPhotoAlternate, null, tint = Color(0xFF2E7D32), modifier = Modifier.size(40.dp))\n        Text(tr("Scan Report"), color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)\n    }\n}`
    },
    "RecordsTile": {
      class: "ActionSquare (Card)",
      width: "148 dp (aspectRatio 1.0)",
      height: "148 dp",
      padding: "12.dp",
      alignment: "Center / Column",
      bg: "Gray (#ECEFF1)",
      primary: "Navy-Grey (#455A64)",
      desc: "Launcher card that redirects the user to the historic scanned medical reports database.",
      code: `Card(\n    modifier = modifier.aspectRatio(1f).clickable { onClick() },\n    shape = RoundedCornerShape(20.dp),\n    colors = CardDefaults.cardColors(containerColor = Color(0xFFECEFF1))\n) {\n    Column(verticalArrangement = Arrangement.Center) {\n        Icon(Icons.Default.History, null, tint = Color(0xFF455A64), modifier = Modifier.size(40.dp))\n        Text(tr("Records"), color = Color(0xFF455A64), fontWeight = FontWeight.Bold)\n    }\n}`
    },
    "RemindersTile": {
      class: "ActionSquare (Card)",
      width: "148 dp (aspectRatio 1.0)",
      height: "148 dp",
      padding: "12.dp",
      alignment: "Center / Column",
      bg: "StatusLowBg (#FFF3E0)",
      primary: "StatusLow (#E65100)",
      desc: "Launches the daily pill scheduler, reminding users of upcoming dosages based on times extracted from prescriptions.",
      code: `Card(\n    modifier = modifier.aspectRatio(1f).clickable { onClick() },\n    shape = RoundedCornerShape(20.dp),\n    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))\n) {\n    Column(verticalArrangement = Arrangement.Center) {\n        Icon(Icons.Default.Alarm, null, tint = Color(0xFFE65100), modifier = Modifier.size(40.dp))\n        Text(tr("Reminders"), color = Color(0xFFE65100), fontWeight = FontWeight.Bold)\n    }\n}`
    },
    "MedicationTrackerTile": {
      class: "ActionSquare (Card)",
      width: "148 dp (aspectRatio 1.0)",
      height: "148 dp",
      padding: "12.dp",
      alignment: "Center / Column",
      bg: "Purple (#F3E5F5)",
      primary: "Deep Purple (#6A1B9A)",
      desc: "Launches the bulk drug catalog list containing extracted medication name records and dosage frequency details.",
      code: `Card(\n    modifier = modifier.aspectRatio(1f).clickable { onClick() },\n    shape = RoundedCornerShape(20.dp),\n    colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))\n) {\n    Column(verticalArrangement = Arrangement.Center) {\n        Icon(Icons.Default.Medication, null, tint = Color(0xFF6A1B9A), modifier = Modifier.size(40.dp))\n        Text(tr("Medication Tracker"), color = Color(0xFF6A1B9A), fontWeight = FontWeight.Bold)\n    }\n}`
    },
    "PendingTestsTile": {
      class: "ActionSquare (Card)",
      width: "148 dp (aspectRatio 1.0)",
      height: "148 dp",
      padding: "12.dp",
      alignment: "Center / Column",
      bg: "StatusHighBg (#FFFFEBEE)",
      primary: "StatusHigh (#C62828)",
      desc: "Launches the list of recommended clinical diagnostics that the patient needs to schedule or complete.",
      code: `Card(\n    modifier = modifier.aspectRatio(1f).clickable { onClick() },\n    shape = RoundedCornerShape(20.dp),\n    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))\n) {\n    Column(verticalArrangement = Arrangement.Center) {\n        Icon(Icons.Default.NotificationsActive, null, tint = Color(0xFFC62828), modifier = Modifier.size(40.dp))\n        Text(tr("Pending Tests"), color = Color(0xFFC62828), fontWeight = FontWeight.Bold)\n    }\n}`
    },
    "TrendsTile": {
      class: "ActionSquare (Card)",
      width: "148 dp (aspectRatio 1.0)",
      height: "148 dp",
      padding: "12.dp",
      alignment: "Center / Column",
      bg: "Blue (#E3F2FD)",
      primary: "Blue Accent (#1565C0)",
      desc: "Triggers analytical visualization graphs graphing historical lab values (like Blood Sugar and Lipids) over time.",
      code: `Card(\n    modifier = modifier.aspectRatio(1f).clickable { onClick() },\n    shape = RoundedCornerShape(20.dp),\n    colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))\n) {\n    Column(verticalArrangement = Arrangement.Center) {\n        Icon(Icons.Default.ShowChart, null, tint = Color(0xFF1565C0), modifier = Modifier.size(40.dp))\n        Text(tr("Trends"), color = Color(0xFF1565C0), fontWeight = FontWeight.Bold)\n    }\n}`
    },
    "ScanProgressBar": {
      class: "Column",
      width: "328 dp (fillMaxWidth)",
      height: "82 dp",
      padding: "16.dp",
      alignment: "Column Flow",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "A dashboard banner widget tracking current OCR scans happening in the background via background alarm sync.",
      code: `@Composable\nfun BackgroundScanProgressBar(onNavigateToDetail: (String) -> Unit) {\n    val activeJob by BackgroundScanScheduler.activeJobState.collectAsState()\n    if (activeJob != null) {\n        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {\n            Column(modifier = Modifier.padding(16.dp)) {\n                Text("Active Ingestion", fontWeight = FontWeight.Bold)\n                LinearProgressIndicator(progress = activeJob.progress, modifier = Modifier.fillMaxWidth())\n            }\n        }\n    }\n}`
    },
    "ScanScreen": {
      class: "ScanScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / Scroll",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Main scanner terminal supporting camera imports, PDF rendering, QR code scanning, and Gmail report downloads.",
      code: `@Composable\nfun ScanScreen(\n    onNavigateToDetail: (String) -> Unit,\n    onNavigateBack: () -> Unit\n) {\n    Scaffold(\n        topBar = { CompactTopBar("Scan Document", onNavigateBack) }\n    ) { innerPadding ->\n        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())) {\n            PreviewBox()\n            ScanControls()\n        }\n    }\n}`
    },
    "ScanViewport": {
      class: "Box (Border / Clip)",
      width: "312 dp (fillMaxWidth)",
      height: "260 dp",
      padding: "24.dp",
      alignment: "Center",
      bg: "SurfaceVariant (0.5 opacity)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "A custom dotted layout container that lists scanned pages as previews or renders placeholder tips.",
      code: `Box(\n    modifier = Modifier\n        .fillMaxWidth()\n        .height(300.dp)\n        .clip(RoundedCornerShape(16.dp))\n        .border(2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))\n        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),\n    contentAlignment = Alignment.Center\n) {\n    if (pages.isEmpty()) { CameraPlaceholder() } else { LazyRowPreview(pages) }\n}`
    },
    "ScanForm": {
      class: "Column",
      width: "312 dp (fillMaxWidth)",
      height: "220 dp",
      padding: "0.dp",
      alignment: "Vertical Column",
      bg: "Transparent",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Contains optional patient override name fields, toggle tabs for scanning mode (Prescription vs Report), and Category selector chips.",
      code: `Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {\n    OutlinedTextField(value = patientName, onValueChange = { patientName = it }, label = { Text("Patient Name") })\n    SegmentControl(selected = selectedScanType) { selectedScanType = it }\n    if (selectedScanType == "report") { CategoryPillSelector() }\n}`
    },
    "ScanActionMatrix": {
      class: "Row / Arrangement",
      width: "312 dp (fillMaxWidth)",
      height: "64 dp",
      padding: "0.dp",
      alignment: "Horizontal Matrix",
      bg: "Transparent",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Grid matrix hosting secondary camera launch, file selection, QR scanner screen redirection, and manual Email scan triggers.",
      code: `Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n    ScanIconButton(label = "Camera", icon = Icons.Default.PhotoCamera, onClick = { launchCamera() })\n    ScanIconButton(label = "From Device", icon = Icons.Default.OpenDocument, onClick = { launchPicker() })\n    ScanIconButton(label = "QR Code", icon = Icons.Default.QrCodeScanner, onClick = { startQrScanner() })\n    ScanIconButton(label = "Email Scan", icon = Icons.Default.Email, onClick = { triggerEmailScan() })\n}`
    },
    "AnalyzeButton": {
      class: "Button",
      width: "312 dp (fillMaxWidth)",
      height: "48 dp",
      padding: "12.dp",
      alignment: "Center",
      bg: "MedicalPrimary (#0D7377)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Submits selected document bytes to backend Express endpoints to run OCR and call Gemini structure recognition model.",
      code: `Button(\n    onClick = { submitReportForAnalysis() },\n    colors = ButtonDefaults.buttonColors(containerColor = MedicalTeal),\n    modifier = Modifier.fillMaxWidth().height(48.dp)\n) {\n    Text(tr("Analyze Document"), fontWeight = FontWeight.Bold)\n}`
    },
    "EmailQueueList": {
      class: "Column",
      width: "312 dp (fillMaxWidth)",
      height: "90 dp",
      padding: "0.dp",
      alignment: "Vertical flow",
      bg: "Transparent",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Lists inbox attachments waiting for authorization, allowing the user to select and run AI parsing one-by-one.",
      code: `LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n    items(pendingEmailQueue) { email ->\n        EmailItemCard(email, onAnalyze = { analyzeEmailReport(email) })\n    }\n}`
    },
    "RecordsScreen": {
      class: "RecordsScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column Flow",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Histories catalog container showing filter chips, clinical highlights panels, and scanned report result items.",
      code: `@Composable\nfun RecordsScreen(\n    onNavigateBack: () -> Unit,\n    onNavigateToDetail: (String) -> Unit,\n    onNavigateToScan: () -> Unit\n) {\n    Scaffold(\n        floatingActionButton = { FabScan(onNavigateToScan) }\n    ) { innerPadding ->\n        Column(modifier = Modifier.padding(innerPadding)) {\n            SearchBar()\n            FilterChipsRow()\n            ClinicalInsightsPanel()\n            ReportsList(onNavigateToDetail)\n        }\n    }\n}`
    },
    "SearchField": {
      class: "OutlinedTextField",
      width: "328 dp (fillMaxWidth)",
      height: "48 dp",
      padding: "16.dp",
      alignment: "Row Alignment",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Integrated text field that filters reports by title, clinician, drugs name, or raw transcribed text using background SQLite FTS searches.",
      code: `OutlinedTextField(\n    value = searchQuery,\n    onValueChange = { searchQuery = it },\n    placeholder = { Text(tr("Search reports, patient, or text...")) },\n    leadingIcon = { Icon(Icons.Default.Search, null) },\n    shape = RoundedCornerShape(12.dp),\n    modifier = Modifier.fillMaxWidth().padding(16.dp)\n)`
    },
    "FilterChips": {
      class: "LazyRow",
      width: "360 dp (fillMaxWidth)",
      height: "36 dp",
      padding: "16.dp",
      alignment: "Horizontal Row",
      bg: "Transparent",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Chips mapping selection values to date boundaries (All, 1 month, 3 months, 6 months) for API dashboard filtering.",
      code: `LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n    items(periods) { (value, label) ->\n        FilterChip(\n            selected = (selectedPeriod == value),\n            onClick = { selectedPeriod = value; loadDashboard() },\n            label = { Text(label) }\n        )\n    }\n}`
    },
    "ClinicalInsightsPanel": {
      class: "Card",
      width: "328 dp (fillMaxWidth)",
      height: "76 dp",
      padding: "12.dp",
      alignment: "Column Alignment",
      bg: "MedicalSurfaceVariant (#ECF5F5)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Analytically flags longitudinal biomarkers directions (e.g. glucose trends) calculated across past record sets.",
      code: `Card(\n    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),\n    colors = CardDefaults.cardColors(containerColor = MedicalSurfaceVariant)\n) {\n    Column(modifier = Modifier.padding(12.dp)) {\n        Row { Icon(Icons.Default.Lightbulb, null); Text("Clinical Insights") }\n        Text(text = insightsText, style = MaterialTheme.typography.bodySmall)\n    }\n}`
    },
    "ReportItemCard": {
      class: "Card / Clickable",
      width: "328 dp (fillMaxWidth)",
      height: "94 dp",
      padding: "14.dp",
      alignment: "Vertical layout",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Renders essential report identifiers (Date, clinic/doctor, patient) and colored tags representing critical anomalies.",
      code: `Card(\n    modifier = Modifier.fillMaxWidth().clickable { onClick() },\n    shape = RoundedCornerShape(16.dp),\n    colors = CardDefaults.cardColors(containerColor = MedicalSurface)\n) {\n    Column(modifier = Modifier.padding(14.dp)) {\n        ReportBadge(report.reportType)\n        Text("Patient: \${report.patientName}", fontWeight = FontWeight.Bold)\n        Text(report.dateString, style = MaterialTheme.typography.bodySmall)\n    }\n}`
    },
    "FloatingActionButton": {
      class: "FloatingActionButton",
      width: "56 dp",
      height: "56 dp",
      padding: "0.dp",
      alignment: "Fixed bottomEnd",
      bg: "MedicalPrimary (#0D7377)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Renders a Floating Action Button in the bottom-right corner that allows users to instantly open the scanning viewport.",
      code: `FloatingActionButton(\n    onClick = onNavigateToScan,\n    containerColor = MaterialTheme.colorScheme.primary,\n    contentColor = MaterialTheme.colorScheme.onPrimary\n) {\n    Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Scan Report")\n}`
    },
    "RemindersScreen": {
      class: "RemindersScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / LazyList",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Medication scheduler alarms screen grouping active prescriptions schedules into structured time-of-day slots.",
      code: `@Composable\nfun RemindersScreen(onNavigateBack: () -> Unit, onNavigateToChat: () -> Unit) {\n    Scaffold(\n        topBar = { RemindersTopBar(onNavigateBack, onNavigateToChat) }\n    ) { innerPadding ->\n        LazyColumn(contentPadding = innerPadding) {\n            PermissionBanners()\n            QuickActionsRow()\n            MorningSlot()\n            NightSlot()\n            AppointmentsSection()\n        }\n    }\n}`
    },
    "PermissionBanner": {
      class: "Card",
      width: "328 dp (fillMaxWidth)",
      height: "88 dp",
      padding: "16.dp",
      alignment: "Horizontal Row",
      bg: "WarningContainer (#FFF3CD)",
      primary: "WarningAccent (#E65100)",
      desc: "Alert banner displaying details on system level notifications and Android 12+ exact alarm scheduling access statuses.",
      code: `Card(\n    modifier = Modifier.fillMaxWidth(),\n    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))\n) {\n    Row(modifier = Modifier.padding(16.dp)) {\n        Icon(Icons.Default.NotificationsOff, null, tint = Color(0xFFE65100))\n        Column(modifier = Modifier.weight(1f)) {\n            Text("Allow Notifications", fontWeight = FontWeight.Bold)\n            Text("Tap to allow so reminders show.", style = MaterialTheme.typography.bodySmall)\n        }\n        Button(onClick = { requestPermission() }) { Text("Allow") }\n    }\n}`
    },
    "QuickAddRemindersRow": {
      class: "Row / Layout",
      width: "328 dp (fillMaxWidth)",
      height: "48 dp",
      padding: "4.dp",
      alignment: "Horizontal grid",
      bg: "Transparent",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Two side-by-side action buttons allowing users to manually create custom drug dosing schedules or checkup reminders.",
      code: `Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {\n    Button(onClick = { showAddMedDialog = true }) { Text("Add Med") }\n    Button(onClick = { showAddApptDialog = true }) { Text("Add Appt") }\n}`
    },
    "TimeSlotSection": {
      class: "Column",
      width: "328 dp (fillMaxWidth)",
      height: "128 dp",
      padding: "0.dp",
      alignment: "Vertical Group",
      bg: "Transparent",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Grouped layout containing a styled Time Slot header banner (Morning/Night) and associated Medicine cards.",
      code: `Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {\n    SlotHeader(slot = "Morning", isCurrent = true, time = timeConfig)\n    MedicineCard(schedule = sched, slot = "Morning")\n}`
    },
    "MedicineCard": {
      class: "Card",
      width: "328 dp (fillMaxWidth)",
      height: "82 dp",
      padding: "18.dp",
      alignment: "Horizontal Row",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Renders pill details (Dosage, name, patient) alongside editable time stamps and alarm toggle state controls.",
      code: `Card(\n    modifier = Modifier.fillMaxWidth(),\n    shape = RoundedCornerShape(18.dp),\n    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)\n) {\n    Row(modifier = Modifier.padding(18.dp)) {\n        PillIconCircle()\n        Column { Text(med.name, fontWeight = FontWeight.Bold); Text(med.dosage) }\n        TimeBadgeAndSwitch(med.time, med.enabled)\n    }\n}`
    },
    "AppointmentCard": {
      class: "Card",
      width: "328 dp (fillMaxWidth)",
      height: "64 dp",
      padding: "12.dp",
      alignment: "Row Flow",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalSecondary (#1B3A4B)",
      desc: "Renders patient details, clinical facility info, and alarm editing tools for doctor visits.",
      code: `Card(\n    modifier = Modifier.fillMaxWidth(),\n    shape = RoundedCornerShape(12.dp)\n) {\n    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {\n        Column { Text(appt.doctorName); Text(appt.dateString) }\n        ControlButtons(onEdit, onDelete)\n    }\n}`
    },
    "MedicationTrackerScreen": {
      class: "MedicationTrackerScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / List",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Launches the comprehensive list of extracted drugs, with selection hooks for bulk editing and deletion.",
      code: `@Composable\nfun MedicationTrackerScreen(onNavigateBack: () -> Unit, onNavigateToChat: () -> Unit) {\n    Scaffold(\n        bottomBar = { if (selectionMode) SelectionModeBottomBar(...) }\n    ) { innerPadding ->\n        Column(modifier = Modifier.padding(innerPadding)) {\n            SearchBar()\n            MedicationsHistoryList()\n        }\n    }\n}`
    },
    "SelectionModeBottomBar": {
      class: "Surface / Row",
      width: "360 dp (fillMaxWidth)",
      height: "56 dp",
      padding: "10.dp",
      alignment: "Horizontal Row",
      bg: "SurfaceVariant (elevation 3dp)",
      primary: "MedicalTeal (#0D7377)",
      desc: "Appears during selection mode, facilitating bulk deletion or bulk dosing schedule adjustments.",
      code: `Surface(tonalElevation = 3.dp) {\n    Row(modifier = Modifier.fillMaxWidth().padding(10.dp)) {\n        IconButton(onClick = { exitSelection() }) { Icon(Icons.Default.Close, null) }\n        Text("\${selected.size} selected")\n        Spacer(Modifier.weight(1f))\n        OutlinedButton(onClick = { bulkEditFreq() }) { Text("Frequency") }\n        Button(onClick = { bulkDelete() }, colors = ButtonDefaults.buttonColors(containerColor = Error)) { Text("Delete") }\n    }\n}`
    },
    "MedicationHistoryCard": {
      class: "Card (LongClickable)",
      width: "328 dp (fillMaxWidth)",
      height: "98 dp",
      padding: "14.dp",
      alignment: "Vertical flow",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Drug record card supporting long-press triggers to enter Selection Mode.",
      code: `Card(\n    modifier = Modifier\n        .fillMaxWidth()\n        .combinedClickable(\n            onClick = { if (selectionMode) toggleSelection() else viewDetails() },\n            onLongClick = { if (!selectionMode) enterSelectionMode() }\n        )\n) {\n    Row { if (selectionMode) CheckBox(); MedDetailsContent() }\n}`
    },
    "PendingTestsScreen": {
      class: "PendingTestsScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / List",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Displays clinical test prescriptions recommended in past records, with status linkages.",
      code: `@Composable\nfun PendingTestsScreen(onNavigateBack: () -> Unit) {\n    Scaffold(\n        floatingActionButton = { AddTestFab { showAddDialog = true } }\n    ) { innerPadding ->\n        Column(modifier = Modifier.padding(innerPadding)) {\n            SearchBar()\n            PendingTestsList()\n        }\n    }\n}`
    },
    "PendingTestCard": {
      class: "Card",
      width: "328 dp (fillMaxWidth)",
      height: "94 dp",
      padding: "14.dp",
      alignment: "Vertical / Grid",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalSecondary (#1B3A4B)",
      desc: "Pending test reminder detailing clinician recommendations, dates, and dynamic linkages to processed lab results.",
      code: `Card(modifier = Modifier.fillMaxWidth()) {\n    Column(modifier = Modifier.padding(14.dp)) {\n        Row { Text(test.name); StatusIndicator(test.status) }\n        Text("Due: \+ test.dueDateString")\n        LinkReportButton(test.resolvedReportId)\n    }\n}`
    },
    "TrendsScreen": {
      class: "TrendsScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / Vertical Scroll",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Renders longitudinal charts showing biomarker fluctuations over time.",
      code: `@Composable\nfun TrendsScreen(onNavigateBack: () -> Unit) {\n    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {\n        MetricDropdown()\n        TrendsChart(selectedMetric)\n        TrendsSummaryCard()\n    }\n}`
    },
    "TrendsChart": {
      class: "Canvas / Custom Painting",
      width: "328 dp (fillMaxWidth)",
      height: "180 dp",
      padding: "12.dp",
      alignment: "Drawing Canvas",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Renders multi-point health parameters coordinates over time. Implemented via native Canvas drawing lines and grid matrices.",
      code: `Canvas(modifier = Modifier.fillMaxWidth().height(180.dp)) {\n    drawGridLines()\n    drawChartLine(coordinates, color = MedicalTeal)\n    drawPointsAndLabels(coordinates)\n}`
    },
    "ReportDetailScreen": {
      class: "DetailedAnalysisScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / Scroll",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Detailed extracted parameters dashboard showing lab results, reference thresholds, and AI translation/interpretation cards.",
      code: `@Composable\nfun ReportDetailScreen(reportId: String, onNavigateBack: () -> Unit) {\n    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {\n        ReportMetaHeader(report)\n        ParametersSection(report.parameters)\n        AiExplanationCard(report.interpretation)\n    }\n}`
    },
    "ParameterRow": {
      class: "Row / Layout",
      width: "328 dp (fillMaxWidth)",
      height: "56 dp",
      padding: "12.dp",
      alignment: "Horizontal Row",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalSecondary (#1B3A4B)",
      desc: "Renders individual parameters, patient values, normal thresholds, and color-coded status tags.",
      code: `Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {\n    Column { Text(param.name); Text("Ref: \${param.reference}") }\n    Row { Text(param.value); StatusBadge(param.status) }\n}`
    },
    "AiExplanationCard": {
      class: "Card",
      width: "328 dp (fillMaxWidth)",
      height: "96 dp",
      padding: "14.dp",
      alignment: "Vertical flow",
      bg: "AiAccentBg (#E8EAF6)",
      primary: "AiAccent (#3F51B5)",
      desc: "Contains natural language clinical interpretations generated by Gemini, with translation systems powered by Sarvam AI.",
      code: `Card(\n    modifier = Modifier.fillMaxWidth(),\n    colors = CardDefaults.cardColors(containerColor = AiAccentBg)\n) {\n    Column(modifier = Modifier.padding(14.dp)) {\n        Row { Text("AI Interpretation", color = AiAccent); TranslationButtons() }\n        Text(interpretationText, color = AiAccentDark)\n    }\n}`
    },
    "ChatScreen": {
      class: "ChatScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column flow",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Conversational AI assistant. Folds screen context identifiers directly into the query payload for contextual accuracy.",
      code: `@Composable\nfun ChatScreen(contextPage: String, onNavigateBack: () -> Unit) {\n    Column(modifier = Modifier.fillMaxSize()) {\n        ChatContextIndicator(contextPage)\n        ChatMessagesList(messages)\n        ChatInputDock(onSend = { sendMessage(it) })\n    }\n}`
    },
    "ChatContextIndicator": {
      class: "Row / Box",
      width: "360 dp (fillMaxWidth)",
      height: "32 dp",
      padding: "8.dp",
      alignment: "Center",
      bg: "MedicalSurfaceVariant (#ECF5F5)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Indicates context parameters currently included in queries, narrowing assistant scope (e.g. asking about Reminders).",
      code: `Box(\n    modifier = Modifier.fillMaxWidth().background(MedicalSurfaceVariant).padding(8.dp),\n    contentAlignment = Alignment.Center\n) {\n    Text(text = "Asking about: " + contextPage, fontWeight = FontWeight.Bold, color = MedicalTeal)\n}`
    },
    "ChatInputDock": {
      class: "Row / Arrangement",
      width: "360 dp (fillMaxWidth)",
      height: "56 dp",
      padding: "12.dp",
      alignment: "Horizontal row",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Chat keyboard field and trigger button that sends user queries.",
      code: `Row(modifier = Modifier.fillMaxWidth().background(MedicalSurface).padding(12.dp)) {\n    TextField(value = queryText, onValueChange = { queryText = it })\n    Button(onClick = { sendQuery() }) { Text("Send") }\n}`
    },
    "AccountScreen": {
      class: "AccountScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column / Scroll",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Account dashboard for language configurations, background scan schedules, and security credentials settings.",
      code: `@Composable\nfun AccountScreen(onNavigateBack: () -> Unit) {\n    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {\n        ProfileHeader()\n        LanguagePreferencesSelector()\n        GmailScannerConfigGroup()\n        SecurityConfigGroup()\n    }\n}`
    },
    "ProfileHeader": {
      class: "Row / Surface",
      width: "328 dp (fillMaxWidth)",
      height: "80 dp",
      padding: "16.dp",
      alignment: "Row Flow",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Renders patient profile avatars alongside email indicators, DOBs, and age information.",
      code: `Row(modifier = Modifier.fillMaxWidth().background(MedicalSurface).padding(16.dp)) {\n    AvatarCircle(user.initials)\n    Column { Text(user.fullName, fontWeight = FontWeight.Bold); Text(user.email) }\n}`
    },
    "LanguagePreferenceSelector": {
      class: "Column / Card",
      width: "328 dp (fillMaxWidth)",
      height: "64 dp",
      padding: "12.dp",
      alignment: "Column Flow",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalPrimary (#0D7377)",
      desc: "Saves language selection values globally, translating AI responses dynamically.",
      code: `Column { \n    Text("Preferred Language")\n    DropdownMenu(expanded = expanded) {\n        DropdownMenuItem(onClick = { setLang("Hindi") }) { Text("Hindi") }\n    }\n}`
    },
    "GmailScannerConfigGroup": {
      class: "Column",
      width: "328 dp (fillMaxWidth)",
      height: "220 dp",
      padding: "12.dp",
      alignment: "Vertical flow",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Configures automatic Google/IMAP mailbox scanning, daily polling alarm clocks, and history resets.",
      code: `Column(modifier = Modifier.fillMaxWidth()) {\n    Switch(checked = autoScanEnabled, onCheckedChange = { toggleAutoScan() })\n    TimeRow(time = "07:00 PM", onClick = { showTimePicker() })\n    AccountLinkButton(googleLinked)\n    ResetHistoryButton()\n}`
    },
    "CompareScreen": {
      class: "CompareScreen",
      width: "360 dp (fillMaxSize)",
      height: "740 dp (fillMaxSize)",
      padding: "0.dp",
      alignment: "Column Flow",
      bg: "MedicalBackground (#F8F9FA)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Loads double-column layouts mapping biomarker changes between two chosen reports.",
      code: `@Composable\nfun CompareScreen(onNavigateBack: () -> Unit) {\n    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {\n        CompareSelectors(reportA, reportB)\n        ComparisonMatrix(reportA, reportB)\n        CompareSummaryCard()\n    }\n}`
    },
    "ComparisonMatrix": {
      class: "Column / Table",
      width: "328 dp (fillMaxWidth)",
      height: "164 dp",
      padding: "12.dp",
      alignment: "Vertical stack",
      bg: "MedicalSurface (#FFFFFF)",
      primary: "MedicalNavy (#1B3A4B)",
      desc: "Table comparing parameter values side-by-side. Highlights differences with custom red alert rows.",
      code: `Column(modifier = Modifier.fillMaxWidth().border(1.dp, Outline)) {\n    HeaderRow("Parameter", "Report A", "Report B")\n    reports.parameters.forEach { param ->\n        ComparisonRow(param.name, param.valA, param.valB, isCritical = param.hasShift)\n    }\n}`
    }
  };

  // --- Core State Variables ---
  let currentMode = 'design'; // 'design' or 'prototype'
  let activeScreenId = 'login'; // active screen inside Prototype simulator mode
  let selectedComponentId = 'LoginScreen'; // inspect selection component target
  let activePageTab = 'mobile-app'; // left sidebar page list: 'mobile-app' or 'design-tokens'

  // --- DOM References ---
  const canvasContainer = document.getElementById('canvasContainer');
  const tokensContainer = document.getElementById('tokensContainer');
  const panelProperties = document.getElementById('panelProperties');
  const panelCode = document.getElementById('panelCode');
  const layerList = document.getElementById('layerList');
  const canvasViewport = document.getElementById('canvasViewport');
  
  // Modals & Popups
  const dlgBioOnboard = document.getElementById('dlgBioOnboard');
  const dlgMedicalDisclaimer = document.getElementById('dlgMedicalDisclaimer');
  const globalLoader = document.getElementById('globalLoader');
  const loaderText = document.getElementById('loaderText');
  const toastMessage = document.getElementById('toastMessage');

  // Interactive content forms elements
  const btnTabEmail = document.getElementById('btnTabEmail');
  const btnTabPhone = document.getElementById('btnTabPhone');
  const emailFormGroup = document.getElementById('emailFormGroup');
  const phoneFormGroup = document.getElementById('phoneFormGroup');
  const otpInputRow = document.getElementById('otpInputRow');
  const btnSendOtp = document.getElementById('btnSendOtp');
  const inputPhoneNum = document.getElementById('inputPhoneNum');
  const inputOtpCode = document.getElementById('inputOtpCode');
  const btnLoginEmail = document.getElementById('btnLoginEmail');
  const btnBiometricLogin = document.getElementById('btnBiometricLogin');
  const btnGoogleSignIn = document.getElementById('btnGoogleSignIn');

  // --- Dynamic Layers Builder ---
  const allScreens = [
    { id: 'login', name: 'LoginScreen', comp: 'LoginScreen' },
    { id: 'register', name: 'RegisterScreen', comp: 'RegisterScreen' },
    { id: 'home', name: 'HomeScreen', comp: 'HomeScreen' },
    { id: 'scan', name: 'ScanScreen', comp: 'ScanScreen' },
    { id: 'records', name: 'RecordsScreen', comp: 'RecordsScreen' },
    { id: 'reminders', name: 'RemindersScreen', comp: 'RemindersScreen' },
    { id: 'meds', name: 'MedicationTrackerScreen', comp: 'MedicationTrackerScreen' },
    { id: 'tests', name: 'PendingTestsScreen', comp: 'PendingTestsScreen' },
    { id: 'trends', name: 'TrendsScreen', comp: 'TrendsScreen' },
    { id: 'detail', name: 'ReportDetailScreen', comp: 'ReportDetailScreen' },
    { id: 'chat', name: 'ChatScreen', comp: 'ChatScreen' },
    { id: 'account', name: 'AccountScreen', comp: 'AccountScreen' },
    { id: 'compare', name: 'CompareScreen', comp: 'CompareScreen' }
  ];

  function buildLayersList() {
    layerList.innerHTML = '';
    allScreens.forEach(screen => {
      const li = document.createElement('li');
      li.className = `layer-item ${screen.id === activeScreenId ? 'active' : ''}`;
      li.setAttribute('data-screen-target', screen.id);
      li.innerHTML = `
        <svg class="sidebar-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="5" y="2" width="14" height="20" rx="2" ry="2"/>
          <line x1="12" y1="18" x2="12.01" y2="18"/>
        </svg>
        ${screen.name}
      `;
      layerList.appendChild(li);
    });
  }

  // --- Figma Inspector Sync ---
  function updateInspector(componentKey) {
    const data = specs[componentKey];
    if (!data) return;

    selectedComponentId = componentKey;

    // Remove old selections, highlight new one
    document.querySelectorAll('[data-inspect-component]').forEach(el => {
      el.classList.remove('selected');
    });
    
    // Highlight all instances of this component in the canvas
    document.querySelectorAll(`[data-inspect-component="${componentKey}"]`).forEach(el => {
      el.classList.add('selected');
    });

    // Populate Inspector UI fields
    document.getElementById('inspectClassName').textContent = data.class;
    document.getElementById('inspectDescription').textContent = data.desc;
    document.getElementById('inspectWidth').textContent = data.width;
    document.getElementById('inspectHeight').textContent = data.height;
    document.getElementById('inspectPadding').textContent = data.padding;
    document.getElementById('inspectAlignment').textContent = data.alignment;
    document.getElementById('inspectBg').textContent = data.bg;
    document.getElementById('inspectPrimary').textContent = data.primary;
    document.getElementById('codeSpecBlock').textContent = data.code;
  }

  // Handle Canvas inspect clicks
  canvasContainer.addEventListener('click', (e) => {
    if (currentMode !== 'design') return; // only inspect in design mode

    const inspectElement = e.target.closest('[data-inspect-component]');
    if (inspectElement) {
      e.stopPropagation();
      const key = inspectElement.getAttribute('data-inspect-component');
      updateInspector(key);
    }
  });

  // Sidebar layers click -> scroll screen into view (Design view) or switch active (Prototype view)
  layerList.addEventListener('click', (e) => {
    const item = e.target.closest('.layer-item');
    if (!item) return;

    const screenId = item.getAttribute('data-screen-target');
    
    // update layers active state
    document.querySelectorAll('.layer-item').forEach(el => el.classList.remove('active'));
    item.classList.add('active');

    if (currentMode === 'design') {
      // Scroll to screen wrapper on canvas
      const wrapper = document.querySelector(`.screen-wrapper[data-screen-id="${screenId}"]`);
      if (wrapper) {
        wrapper.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
        // Inspect this screen's main layer
        const targetScreen = allScreens.find(s => s.id === screenId);
        if (targetScreen) {
          updateInspector(targetScreen.comp);
        }
      }
    } else {
      // In prototype mode, set active phone screen
      navigateToScreen(screenId);
    }
  });

  // --- Clickable Mockup Navigation Logic (Prototype Mode) ---
  function navigateToScreen(screenId) {
    if (currentMode === 'prototype') {
      // Set active screen on phone simulator
      document.querySelectorAll('.screen-wrapper').forEach(el => {
        el.classList.remove('active-prototype');
      });
      const activeWrap = document.querySelector(`.screen-wrapper[data-screen-id="${screenId}"]`);
      if (activeWrap) {
        activeWrap.classList.add('active-prototype');
      }

      // Sync left sidebar layer highlights
      document.querySelectorAll('.layer-item').forEach(el => el.classList.remove('active'));
      const activeLayer = document.querySelector(`.layer-item[data-screen-target="${screenId}"]`);
      if (activeLayer) {
        activeLayer.classList.add('active');
      }
    }
    activeScreenId = screenId;
  }

  // Universal navigator binder helper
  function bindNavClick(elementId, targetScreenId) {
    const btn = document.getElementById(elementId);
    if (btn) {
      btn.addEventListener('click', (e) => {
        if (currentMode === 'prototype') {
          e.stopPropagation();
          navigateToScreen(targetScreenId);
        }
      });
    }
  }

  // --- Setup Navigation Map ---
  // Login & Register Screen Hook
  if (btnLoginEmail) {
    btnLoginEmail.addEventListener('click', () => {
      showLoader("Authenticating credentials...", 1200, () => {
        showToast("Login successful!");
        navigateToScreen('home');
      });
    });
  }
  const btnRegisterSubmit = document.getElementById('btnRegisterSubmit');
  if (btnRegisterSubmit) {
    btnRegisterSubmit.addEventListener('click', () => {
      showLoader("Verifying phone number & registering profile...", 1500, () => {
        showToast("Registration successful!");
        navigateToScreen('home');
      });
    });
  }
  bindNavClick('lnkGoRegister', 'register');
  bindNavClick('lnkGoLogin', 'login');
  bindNavClick('btnRegisterBack', 'login');
  
  // Home Screen Hooks
  bindNavClick('tileScan', 'scan');
  bindNavClick('tileRecords', 'records');
  bindNavClick('tileReminders', 'reminders');
  bindNavClick('tileMeds', 'meds');
  bindNavClick('tileTests', 'tests');
  bindNavClick('tileTrends', 'trends');
  bindNavClick('btnHomeAccount', 'account');
  bindNavClick('btnHomeChat', 'chat');
  bindNavClick('btnHomeCompare', 'compare');
  
  // Scan Screen Hooks
  bindNavClick('btnScanBack', 'home');
  
  // Records Screen Hooks
  bindNavClick('btnRecordsBack', 'home');
  bindNavClick('btnRecordsChat', 'chat');
  bindNavClick('fabScan', 'scan');
  // Record items -> Details
  document.querySelectorAll('.record-item').forEach(item => {
    item.addEventListener('click', () => {
      if (currentMode === 'prototype') {
        navigateToScreen('detail');
      }
    });
  });

  // Reminders Screen Hooks
  bindNavClick('btnRemindersBack', 'home');
  bindNavClick('btnRemindersChat', 'chat');
  
  // Medications Screen Hooks
  bindNavClick('btnMedsBack', 'home');
  bindNavClick('btnMedsChat', 'chat');

  // Pending Tests Screen Hooks
  bindNavClick('btnTestsBack', 'home');
  bindNavClick('btnTestsChat', 'chat');
  
  // Trends Screen Hooks
  bindNavClick('btnTrendsBack', 'home');
  
  // Details Screen Hooks
  bindNavClick('btnDetailBack', 'records');
  
  // Chat Screen Hooks
  bindNavClick('btnChatBack', 'home');
  
  // Account Screen Hooks
  bindNavClick('btnAccountBack', 'home');
  bindNavClick('btnAccountLogout', 'login');
  
  // Compare Screen Hooks
  bindNavClick('btnCompareBack', 'home');

  // --- Interactive Form Logics & Mock Actions ---
  
  // 1. Switch Email/Phone Tabs on Login Screen
  btnTabEmail.addEventListener('click', () => {
    btnTabEmail.classList.add('active');
    btnTabPhone.classList.remove('active');
    emailFormGroup.classList.remove('hidden');
    phoneFormGroup.classList.add('hidden');
  });

  btnTabPhone.addEventListener('click', () => {
    btnTabPhone.classList.add('active');
    btnTabEmail.classList.remove('active');
    phoneFormGroup.classList.remove('hidden');
    emailFormGroup.classList.add('hidden');
  });

  // 2. OTP Sender Simulation
  let otpSent = false;
  btnSendOtp.addEventListener('click', () => {
    const phone = inputPhoneNum.value.trim();
    if (phone.length < 10) {
      showToast("Please enter a valid phone number");
      return;
    }

    if (!otpSent) {
      // Send code step
      btnSendOtp.textContent = "Verify OTP";
      otpInputRow.classList.remove('hidden');
      inputPhoneNum.disabled = true;
      otpSent = true;
      showToast("OTP sent to +91 " + phone);
    } else {
      // Verify step
      const otp = inputOtpCode.value.trim();
      if (otp.length !== 6) {
        showToast("Invalid verification code. Enter 6 digits.");
        return;
      }
      showToast("Authentication confirmed!");
      setTimeout(() => {
        // Reset states
        btnSendOtp.textContent = "Send OTP";
        otpInputRow.classList.add('hidden');
        inputPhoneNum.disabled = false;
        otpSent = false;
        inputPhoneNum.value = '';
        inputOtpCode.value = '';
        navigateToScreen('home');
      }, 800);
    }
  });

  // 3. Google Sign-In & Biometrics
  btnGoogleSignIn.addEventListener('click', () => {
    showLoader("Connecting Google Account...", 1200, () => {
      showToast("Signed in via Google successfully!");
      navigateToScreen('home');
    });
  });

  btnBiometricLogin.addEventListener('click', () => {
    dlgBioOnboard.classList.remove('hidden');
  });

  document.getElementById('btnBioDismiss').addEventListener('click', () => {
    dlgBioOnboard.classList.add('hidden');
  });

  document.getElementById('btnBioConfirm').addEventListener('click', () => {
    dlgBioOnboard.classList.add('hidden');
    showToast("Fingerprint authentication configured!");
    setTimeout(() => {
      navigateToScreen('home');
    }, 500);
  });

  // 4. Scanning Modes toggle (Scan Screen)
  const segmentItems = document.querySelectorAll('.segment-item');
  const reportCategoryRow = document.getElementById('reportCategoryRow');
  segmentItems.forEach(item => {
    item.addEventListener('click', () => {
      segmentItems.forEach(i => i.classList.remove('active'));
      item.classList.add('active');
      const scanType = item.getAttribute('data-type');
      if (scanType === 'report') {
        reportCategoryRow.classList.remove('hidden');
      } else {
        reportCategoryRow.classList.add('hidden');
      }
    });
  });

  // Scan Category pills select
  const pills = document.querySelectorAll('.pill');
  pills.forEach(pill => {
    pill.addEventListener('click', () => {
      pills.forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
    });
  });

  // 5. Mock ingestion trigger
  const btnAnalyze = document.getElementById('btnAnalyze');
  const btnFromDevice = document.getElementById('btnFromDevice');
  const btnCamera = document.getElementById('btnCamera');
  const btnQrCode = document.getElementById('btnQrCode');
  const viewportPlaceholder = document.getElementById('viewportPlaceholder');
  const viewportPreview = document.getElementById('viewportPreview');
  let selectedFileUri = null;

  function loadFileMock() {
    viewportPlaceholder.classList.add('hidden');
    viewportPreview.classList.remove('hidden');
    viewportPreview.innerHTML = `
      <div style="background-color:#E2E8F0; width:100%; height:100%; display:flex; flex-direction:column; align-items:center; justify-content:center; border-radius:8px;">
        <span style="font-size:32px;">📄</span>
        <strong style="margin-top:6px; font-size:12px;">JohnDoe_CBC_14Jul.pdf</strong>
        <span style="font-size:10px; color:#666;">2 Pages • Click to remove</span>
      </div>
    `;
    selectedFileUri = "mock_file_cbc";
  }

  viewportPreview.addEventListener('click', () => {
    viewportPreview.classList.add('hidden');
    viewportPlaceholder.classList.remove('hidden');
    viewportPreview.innerHTML = '';
    selectedFileUri = null;
  });

  btnFromDevice.addEventListener('click', loadFileMock);
  btnCamera.addEventListener('click', loadFileMock);
  btnQrCode.addEventListener('click', () => {
    showToast("QR camera scanner running... Decoded lab URL");
    setTimeout(loadFileMock, 1000);
  });

  // Analyze trigger
  btnAnalyze.addEventListener('click', () => {
    if (!selectedFileUri) {
      showToast("Please choose a file to analyze first.");
      return;
    }
    showLoader("Uploading to serverless Node API...", 1500, () => {
      showLoader("Running OCR with Google Vision...", 1500, () => {
        showLoader("Gemini AI analyzing report structures...", 2000, () => {
          showToast("Scanned Successfully! Added to Records.");
          // Clear scan screen state
          viewportPreview.classList.add('hidden');
          viewportPlaceholder.classList.remove('hidden');
          viewportPreview.innerHTML = '';
          selectedFileUri = null;
          // Route to detail
          navigateToScreen('detail');
        });
      });
    });
  });

  // Found in Email card trigger
  const btnAnalyzeEmailCard = document.getElementById('btnAnalyzeEmailCard');
  btnAnalyzeEmailCard.addEventListener('click', () => {
    showLoader("Downloading PDF attachment from Gmail...", 1200, () => {
      showLoader("Running OCR and extraction pipeline...", 1800, () => {
        showToast("Analysis Complete! Record added.");
        // Hide email queue
        document.querySelector('.email-queue-section').classList.add('hidden');
        navigateToScreen('detail');
      });
    });
  });

  // 6. Medication multi-select checkbox tracker
  const medRows = document.querySelectorAll('.med-item-row');
  const bulkActionBar = document.getElementById('bulkActionBar');
  const selectedCountText = document.getElementById('selectedCountText');
  let isSelectionMode = false;
  let selectedMeds = new Set();

  medRows.forEach(row => {
    // long click mockup
    row.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      if (currentMode !== 'prototype') return;

      isSelectionMode = true;
      document.querySelectorAll('.select-checkbox').forEach(cb => cb.classList.remove('hidden'));
      bulkActionBar.classList.remove('hidden');
      toggleMedSelection(row);
    });

    row.addEventListener('click', () => {
      if (currentMode !== 'prototype') return;

      if (isSelectionMode) {
        toggleMedSelection(row);
      } else {
        // Show detail of medicine
        showToast("Opening medication dosage details...");
      }
    });
  });

  function toggleMedSelection(row) {
    const key = row.getAttribute('data-key');
    const checkbox = row.querySelector('.select-checkbox');
    if (selectedMeds.has(key)) {
      selectedMeds.delete(key);
      checkbox.classList.remove('checked');
    } else {
      selectedMeds.add(key);
      checkbox.classList.add('checked');
    }
    
    selectedCountText.textContent = selectedMeds.size;

    if (selectedMeds.size === 0) {
      exitSelection();
    }
  }

  function exitSelection() {
    isSelectionMode = false;
    selectedMeds.clear();
    document.querySelectorAll('.select-checkbox').forEach(cb => {
      cb.classList.remove('hidden');
      cb.classList.remove('checked');
      cb.classList.add('hidden');
    });
    bulkActionBar.classList.add('hidden');
  }

  document.getElementById('btnExitSelection').addEventListener('click', exitSelection);
  document.getElementById('btnBulkDel').addEventListener('click', () => {
    showToast("Deleted " + selectedMeds.size + " medication reminders.");
    // remove them from list mockup
    selectedMeds.forEach(key => {
      document.querySelector(`[data-key="${key}"]`).remove();
    });
    exitSelection();
  });

  document.getElementById('btnBulkFreq').addEventListener('click', () => {
    const f = prompt("Enter new dosing frequency (e.g. 1-1-1, twice daily):");
    if (f) {
      showToast("Updated " + selectedMeds.size + " medications frequency to: " + f);
      selectedMeds.forEach(key => {
        const row = document.querySelector(`[data-key="${key}"]`);
        row.querySelector('.med-freq').textContent = "Frequency: " + f;
      });
      exitSelection();
    }
  });

  // 7. Add Med / Add Appt dialog prompts
  document.getElementById('btnAddMed').addEventListener('click', () => {
    const name = prompt("Enter Medicine Name:");
    if (!name) return;
    const dose = prompt("Enter Dosage (e.g. 500mg, 1 tablet):", "1 tablet");
    showToast("Added reminder alarm for " + name);
  });

  document.getElementById('btnAddAppt').addEventListener('click', () => {
    const doc = prompt("Doctor Name:");
    if (!doc) return;
    showToast("Appointment reminder set for Dr. " + doc);
  });

  // 8. Add Test Fab
  document.getElementById('fabAddTest').addEventListener('click', () => {
    const t = prompt("Recommended Test Name:");
    if (t) {
      showToast("Created manual reminder to schedule: " + t);
    }
  });

  // 9. Records list item clicks
  document.querySelectorAll('.record-item').forEach(item => {
    item.addEventListener('click', () => {
      if (currentMode === 'prototype') {
        navigateToScreen('detail');
      }
    });
  });

  // 10. Links pending tests
  document.getElementById('btnViewResolvedReport').addEventListener('click', (e) => {
    if (currentMode === 'prototype') {
      e.stopPropagation();
      navigateToScreen('detail');
    }
  });

  // 11. Chat assistant prompt submit
  const btnSendMessage = document.getElementById('btnSendMessage');
  const chatInput = document.getElementById('chatInput');
  const chatMessagesContainer = document.getElementById('chatMessagesContainer');

  function sendChatMessage() {
    const query = chatInput.value.trim();
    if (!query) return;

    // Create user message
    const userBubble = document.createElement('div');
    userBubble.className = 'msg bubble-user';
    userBubble.textContent = query;
    chatMessagesContainer.appendChild(userBubble);
    
    chatInput.value = '';
    chatMessagesContainer.scrollTop = chatMessagesContainer.scrollHeight;

    // Create assistant answer delay simulation
    setTimeout(() => {
      const helperBubble = document.createElement('div');
      helperBubble.className = 'msg bubble-assistant';
      helperBubble.textContent = "Analyzing database models relating to: '" + query + "'... Consult your practitioner for certified checkups.";
      chatMessagesContainer.appendChild(helperBubble);
      chatMessagesContainer.scrollTop = chatMessagesContainer.scrollHeight;
    }, 1200);
  }

  btnSendMessage.addEventListener('click', sendChatMessage);
  chatInput.addEventListener('keypress', (e) => {
    if (e.key === 'Enter') sendChatMessage();
  });

  // 12. Account Page resets
  document.getElementById('btnResetEmailHistory').addEventListener('click', () => {
    showToast("Gmail cache scanning history cleared successfully!");
  });

  // Toggle buttons in settings
  document.querySelectorAll('.setting-row .toggle-switch').forEach(sw => {
    sw.addEventListener('click', () => {
      if (currentMode === 'prototype') {
        sw.classList.toggle('active');
        const state = sw.classList.contains('active') ? "enabled" : "disabled";
        showToast("Setting " + state);
      }
    });
  });
  
  // Medication toggles
  document.querySelectorAll('.slot-card .toggle-switch').forEach(sw => {
    sw.addEventListener('click', (e) => {
      if (currentMode === 'prototype') {
        e.stopPropagation();
        sw.classList.toggle('active');
        const state = sw.classList.contains('active') ? "alarm active" : "alarm off";
        showToast("Medicine reminder " + state);
      }
    });
  });

  // --- Modes Navigation Controls ---
  const modeTabs = document.querySelectorAll('.mode-tab');
  
  modeTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      modeTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      const mode = tab.getAttribute('data-mode');
      currentMode = mode;

      if (mode === 'design') {
        // Reset classes
        canvasContainer.classList.remove('mode-prototype-layout');
        canvasContainer.classList.add('mode-design-layout');
        
        // Show left layer items as spec sheets, hide tokens
        tokensContainer.classList.add('hidden');
        canvasContainer.classList.remove('hidden');
        document.querySelector('.layers-section').classList.remove('hidden');
        
        // Restore layout scrolls and sidebar inspectors
        document.querySelector('.sidebar-right').classList.remove('hidden');
        
        // Remove active class from prototype screens so they all display side-by-side
        document.querySelectorAll('.screen-wrapper').forEach(el => {
          el.classList.remove('active-prototype');
        });

        // Sync inspector
        updateInspector(selectedComponentId);
      } else {
        // Exit selection mode if active
        exitSelection();

        // Prototype view configuration
        canvasContainer.classList.remove('mode-design-layout');
        canvasContainer.classList.add('mode-prototype-layout');
        
        tokensContainer.classList.add('hidden');
        canvasContainer.classList.remove('hidden');
        document.querySelector('.layers-section').classList.remove('hidden');

        // Hide right design properties sidebar
        document.querySelector('.sidebar-right').classList.add('hidden');
        
        // Navigate inside prototype
        navigateToScreen(activeScreenId || 'home');
      }
    });
  });

  // Page List selection (Left Sidebar: Mobile screens vs Design Tokens)
  const pageItems = document.querySelectorAll('.page-item');
  pageItems.forEach(item => {
    item.addEventListener('click', () => {
      pageItems.forEach(i => i.classList.remove('active'));
      item.classList.add('active');

      const page = item.getAttribute('data-page');
      activePageTab = page;

      if (page === 'design-tokens') {
        // Show tokens page, hide screen canvas and layer groups
        canvasContainer.classList.add('hidden');
        tokensContainer.classList.remove('hidden');
        document.querySelector('.layers-section').classList.add('hidden');
        document.querySelector('.sidebar-right').classList.add('hidden');
        
        // Force design mode tab active
        modeTabs.forEach(t => t.classList.remove('active'));
        document.querySelector('[data-mode="design"]').classList.add('active');
        currentMode = 'design';
      } else {
        // Restore screen canvas
        tokensContainer.classList.add('hidden');
        canvasContainer.classList.remove('hidden');
        document.querySelector('.layers-section').classList.remove('hidden');
        if (currentMode === 'design') {
          document.querySelector('.sidebar-right').classList.remove('hidden');
          updateInspector(selectedComponentId);
        }
      }
    });
  });

  // Right Sidebar Tab Switch (Properties vs Code)
  const inspectTabs = document.querySelectorAll('.inspect-tab');
  inspectTabs.forEach(tab => {
    tab.addEventListener('click', () => {
      inspectTabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      const tabId = tab.getAttribute('data-tab');
      if (tabId === 'properties') {
        panelProperties.classList.remove('hidden');
        panelCode.classList.add('hidden');
      } else {
        panelProperties.classList.add('hidden');
        panelCode.classList.remove('hidden');
      }
    });
  });

  // Copy code spec button helper
  document.getElementById('btnCopyCode').addEventListener('click', () => {
    const code = document.getElementById('codeSpecBlock').textContent;
    navigator.clipboard.writeText(code).then(() => {
      showToast("Source code copied to clipboard!");
    }).catch(() => {
      showToast("Copying failed. Please select text manually.");
    });
  });

  // --- Helper Dialog / Toast Functions ---
  function showLoader(msg, duration, callback) {
    loaderText.textContent = msg;
    globalLoader.classList.remove('hidden');
    setTimeout(() => {
      globalLoader.classList.add('hidden');
      if (callback) callback();
    }, duration);
  }

  function showToast(msg) {
    toastMessage.textContent = msg;
    toastMessage.classList.add('show');
    setTimeout(() => {
      toastMessage.classList.remove('show');
    }, 2500);
  }

  // --- Init Bootstraps ---
  buildLayersList();
  updateInspector('LoginScreen');
  
  // Show medical disclaimer first time inside prototype
  setTimeout(() => {
    // Trigger disclaimer on first load
    dlgMedicalDisclaimer.classList.remove('hidden');
  }, 1000);

  document.getElementById('btnDisclaimerAccept').addEventListener('click', () => {
    dlgMedicalDisclaimer.classList.add('hidden');
  });

});
