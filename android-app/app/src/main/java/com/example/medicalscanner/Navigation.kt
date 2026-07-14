package com.example.medicalscanner

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.local.SecureKeyManager
import com.example.medicalscanner.network.NetworkModule

import com.example.medicalscanner.network.httpCode
import com.example.medicalscanner.ui.AccountScreen
import com.example.medicalscanner.ui.ChatScreen
import com.example.medicalscanner.ui.CompareScreen
import com.example.medicalscanner.ui.DetailedAnalysisScreen
import com.example.medicalscanner.ui.HomeScreen
import com.example.medicalscanner.ui.IPConfigScreen
import com.example.medicalscanner.ui.LoginScreen
import com.example.medicalscanner.ui.MedicationTrackerScreen
import com.example.medicalscanner.ui.PendingTestsScreen
import com.example.medicalscanner.ui.RecordsScreen
import com.example.medicalscanner.ui.RegisterScreen
import com.example.medicalscanner.ui.RemindersScreen
import com.example.medicalscanner.ui.ReportDetailScreen
import com.example.medicalscanner.ui.ScanScreen
import com.example.medicalscanner.ui.TrendsScreen
import kotlinx.coroutines.launch

private data class DisclaimerTranslation(
    val title: String,
    val subtitle: String,
    val point1: String,
    val point2: String,
    val point3: String,
    val consent: String,
    val buttonText: String
)

private val translations = mapOf(
    "en" to DisclaimerTranslation(
        title = "Medical Disclaimer",
        subtitle = "Please read and accept this disclaimer before using Medical Assist:",
        point1 = "1. Not Medical Advice: This application provides automated analysis and summaries of medical records using artificial intelligence. It does NOT provide medical advice, diagnosis, treatment, or clinical recommendations.",
        point2 = "2. Not a Medical Device: Medical Assist is not a certified medical device. The information shown is for informational and educational purposes only.",
        point3 = "3. Always Consult a Professional: You must always consult a qualified physician or healthcare provider before making any healthcare decisions or changing your medications. Never ignore professional medical advice because of something you read in this app.",
        consent = "By clicking 'Accept and Continue', you acknowledge that you have read, understood, and agree to these terms.",
        buttonText = "Accept and Continue"
    ),
    "hi" to DisclaimerTranslation(
        title = "चिकित्सा अस्वीकरण",
        subtitle = "मेडिकल असिस्ट का उपयोग करने से पहले कृपया इस अस्वीकरण को पढ़ें और स्वीकार करें:",
        point1 = "1. चिकित्सा सलाह नहीं: यह एप्लिकेशन कृत्रिम बुद्धिमत्ता (AI) का उपयोग करके चिकित्सा रिकॉर्ड का स्वचालित विश्लेषण और सारांश प्रदान करता है। यह चिकित्सा सलाह, निदान, उपचार या नैदानिक सिफारिशें प्रदान नहीं करता है।",
        point2 = "2. कोई चिकित्सा उपकरण नहीं: मेडिकल असिस्ट एक प्रमाणित चिकित्सा उपकरण नहीं है। दिखाई गई जानकारी केवल सूचनात्मक और शैक्षिक उद्देश्यों के लिए है।",
        point3 = "3. हमेशा किसी पेशेवर से सलाह लें: किसी भी स्वास्थ्य निर्णय लेने या अपनी दवाओं को बदलने से पहले आपको हमेशा एक योग्य चिकित्सक या स्वास्थ्य सेवा प्रदाता से परामर्श करना चाहिए। इस ऐप में पढ़ी गई किसी बात के कारण पेशेवर चिकित्सा सलाह को कभी भी अनदेखा न करें।",
        consent = "'स्वीकार करें और जारी रखें' पर क्लिक करके, आप स्वीकार करते हैं कि आपने इन शर्तों को पढ़ लिया है, समझ लिया है और इनसे सहमत हैं।",
        buttonText = "स्वीकार करें और जारी रखें"
    ),
    "te" to DisclaimerTranslation(
        title = "వైద్య నిరాకరణ",
        subtitle = "మెడికల్ అసిస్ట్ ఉపయోగించే ముందు దయచేసి ఈ నిరాకరణను చదివి అంగీకరించండి:",
        point1 = "1. వైద్య సలహా కాదు: ఈ అప్లిকেషన్ కృత్రిమ మేధస్సు (AI) ఉపయోగించి వైద్య రికార్డుల స్వయంచాలక విశ్లేషణ మరియు సారాంశాలను అందిస్తుంది. ఇది వైద్య సలహా, రోగ నిర్ధారణ, చికిత్స లేదా క్లినికల్ సిఫార్సులను అందించదు.",
        point2 = "2. వైద్య పరికరం కాదు: మెడికల్ అసిస్ట్ అనేది ధృవీకరించబడిన వైద్య పరికరం కాదు. చూపబడిన సమాచారం కేవలం సమాచారం మరియు విద్యా ప్రయోజనాల కోసం మాత్రమే.",
        point3 = "3. ఎల్లప్పుడూ నిపుణుడిని సంప్రదించండి: ఏదైనా ఆరోగ్య నిర్ణయాలు గురించి గానీ లేదా మీ మందులను మార్చడానికి ముందు గానీ మీరు ఎల్లప్పుడూ అర్హత కలిగిన వైద్యుడిని లేదా ఆరోగ్య సంరక్షణ ప్రదాతను సంప్రదించాలి. ఈ యాప్‌లో చదివిన విషయాల వల్ల వృत्तीపరమైన వైద్య సలహాను ఎప్పుడూ విస్మరించవద్దు.",
        consent = "'అంగీకరించి కొనసాగించు' క్లిక్ చేయడం ద్వారా, మీరు ఈ నిబంధనలను చదివారని, అర్థం చేసుకున్నారని మరియు అంగీకరిస్తున్నారని ధృవీకరిస్తున్నారు.",
        buttonText = "అంగీకరించి కొనసాగించు"
    ),
    "ta" to DisclaimerTranslation(
        title = "மருத்துவ மறுப்பு",
        subtitle = "மெடிக்கல் அசிஸ்ட்-ஐப் பயன்படுத்துவதற்கு முன்பு இந்த மறுப்பைத் தயவுசெய்து படித்து ஏற்கவும்:",
        point1 = "1. மருத்துவ ஆலோசனை அல்ல: இந்தச் செயலி செயற்கை நுண்ணறிவைப் (AI) பயன்படுத்தி மருத்துவப் பதிவுகளின் தானியங்கி பகுப்பாய்வு மற்றும் சுருக்கங்களை வழங்குகிறது. இது மருத்துவ ஆலோசனை, நோயறிதல், சிகிச்சை அல்லது மருத்துவப் பரிந்துரைகளை வழங்காது.",
        point2 = "2. மருத்துவ உபகரணம் அல்ல: மெடிசின் அசிஸ்ட் ஒரு சான்றளிக்கப்பட்ட மருத்துவ உபகரணம் அல்ல. காட்டப்படும் தகவல்கள் தகவல் மற்றும் கல்வி நோக்கங்களுக்காக மட்டுமே.",
        point3 = "3. எப்போதும் நிபுணரை அணுகவும்: ஏதேனும் சுகாதார முடிவுகளை எடுப்பதற்கு முன் அல்லது உங்கள் மருந்துகளை மாற்றுவதற்கு முன் நீங்கள் எப்போதும் ஒரு தகுதி வாய்ந்த மருத்துவர் அல்லது சுகாதார வழங்குநரை அணுக வேண்டும். இந்தச் செயலியில் நீங்கள் படித்தவற்றின் காரணமாக தொழில்முறை மருத்துவ ஆலோசனையை ஒருபோதும் புறக்கணிக்காதீர்கள்.",
        consent = "'ஏற்றுக்கொண்டு தொடரவும்' என்பதைக் கிளிக் செய்வதன் மூலம், இந்த விதிமுறைகளைப் படித்து, புரிந்து கொண்டு, ஒப்புக்கொள்கிறீர்கள் என்பதை உறுதிப்படுத்துகை செய்கிறீர்கள்.",
        buttonText = "ஏற்றுக்கொண்டு தொடரவும்"
    ),
    "bn" to DisclaimerTranslation(
        title = "চিকিৎসা সংক্রান্ত দাবিত্যাগ",
        subtitle = "মেডিকেল অ্যাসিস্ট ব্যবহার করার আগে দয়া করে এই দাবিত্যাগটি পড়ুন এবং গ্রহণ করুন:",
        point1 = "1. চিকিৎসা পরামর্শ নয়: এই অ্যাপ্লিকেশনটি কৃত্রিম বুদ্ধিমত্তা (AI) ব্যবহার করে মেডিকেল রেকর্ডের স্বয়ংক্রিয় বিশ্লেষণ এবং সারাংশ প্রদান করে। এটি চিকিৎসা পরামর্শ, রোগ নির্ণয়, চিকিৎসা বা ক্লিনিকাল সুপারিশ প্রদান করে না।",
        point2 = "2. কোনো চিকিৎসা সরঞ্জাম নয়: মেডিকেল অ্যাসিস্ট কোনো প্রত্যয়িত চিকিৎসা সরঞ্জাম নয়। প্রদর্শিত তথ্য শুধুমাত্র তথ্যগত এবং শিক্ষামূলক উদ্দেশ্যে।",
        point3 = "3. সর্বদা একজন পেশাদারের সাথে পরামর্শ করুন: যেকোনো স্বাস্থ্য সংক্রান্ত সিদ্ধান্ত নেওয়ার আগে বা আপনার ওষুধ পরিবর্তন করার আগে আপনাকে সর্বদা একজন যোগ্যতাসম্পন্ন চিকিৎসক বা স্বাস্থ্যসেবা প্রদানকারীর সাথে পরামর্শ করতে হবে। এই অ্যাপে পড়ার কারণে পেশাদার চিকিৎসা পরামর্শকে কখনো উপেক্ষা করবেন না।",
        consent = "'গ্রহণ করুন এবং এগিয়ে যান' এ ক্লিক করে, আপনি স্বীকার করছেন যে আপনি এই শর্তাবলী পড়েছেন, বুঝেছেন এবং এতে সম্মত হয়েছেন।",
        buttonText = "গ্রহণ করুন এবং এগিয়ে যান"
    ),
    "mr" to DisclaimerTranslation(
        title = "वैद्यकीय अस्वीकरण",
        subtitle = "मेडिकल असिस्ट वापरण्यापूर्वी कृपया हे अस्वीकरण वाचा आणि स्वीकारा:",
        point1 = "1. वैद्यकीय सल्ला नाही: हे ॲप्लिकेशन कृत्रिम बुद्धिमत्ता (AI) वापरून वैद्यकीय नोंदींचे स्वयंचलित विश्लेषण आणि सारांश प्रदान करते. हे वैद्यकीय सल्ला, निदान, उपचार किंवा क्लिनिकल शिफारसी प्रदान करत नाही.",
        point2 = "2. कोणतेही वैद्यकीय उपकरण नाही: मेडिकल असिस्ट हे प्रमाणित वैद्यकीय उपकरण नाही. दाखवलेली माहिती केवळ माहितीच्या आणि शैक्षणिक हेतूंसाठी आहे.",
        point3 = "3. नेहमी तज्ज्ञांचा सल्ला घ्या: कोणताही आरोग्यविषय निर्णय घेण्यापूर्वी किंवा तुमची औषधे बदलण्यापूर्वी तुम्ही नेहमी पात्र डॉक्टर किंवा आरोग्य सेवा प्रदात्याचा सल्ला घेतला पाहिजे. या ॲपमध्ये वाचलेल्या कोणत्याही गोष्टीमुळे व्यावसायिक वैद्यकीय सल्ल्याकडे दुर्लक्ष करू नका.",
        consent = "'स्वीकारा आणि पुढे जा' वर क्लिक करून, आपण या अटी वाचल्या आहेत, समजल्या आहेत आणि त्यांच्याशी सहमत आहात हे मान्य करता.",
        buttonText = "स्वीकारा आणि पुढे जा"
    )
)

private val languages = listOf(
    "en" to "English",
    "hi" to "हिंदी (Hindi)",
    "te" to "తెలుగు (Telugu)",
    "ta" to "தமிழ் (Tamil)",
    "bn" to "বাংলা (Bengali)",
    "mr" to "मराठी (Marathi)"
)

@Composable

fun MainNavigation() {
  val context = LocalContext.current
  val startKey = remember { if (AppSettings.isLoggedIn(context)) Main else Login }
  val backStack = rememberNavBackStack(startKey)
  val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

  // A stored token can be stale (e.g. the account was deleted server-side). Validate it once
  // per launch; on 401 wipe the session and force a fresh login. Network failures are ignored
  // so the app still opens offline.
  var showDisclaimer by remember { mutableStateOf(!AppSettings.isDisclaimerAccepted(context)) }
  var selectedLangCode by remember { mutableStateOf("en") }
  var dropdownExpanded by remember { mutableStateOf(false) }

  if (showDisclaimer) {
      val currentTranslation = translations[selectedLangCode] ?: translations["en"]!!
      AlertDialog(
          onDismissRequest = {},
          title = {
              Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                  Icon(
                      imageVector = Icons.Default.Gavel,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary
                  )
                  Text(currentTranslation.title, fontWeight = FontWeight.Bold)
              }
          },
          text = {
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .verticalScroll(rememberScrollState()),
                  verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                  Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier
                          .fillMaxWidth()
                          .clickable { dropdownExpanded = true }
                          .padding(vertical = 8.dp, horizontal = 4.dp),
                      horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                      Text(
                          text = "Language / भाषा:",
                          fontWeight = FontWeight.Bold,
                          style = MaterialTheme.typography.bodyMedium
                      )
                      Box {
                          Text(
                              text = (languages.firstOrNull { it.first == selectedLangCode }?.second ?: "English") + " ▼",
                              color = MaterialTheme.colorScheme.primary,
                              fontWeight = FontWeight.Bold,
                              style = MaterialTheme.typography.bodyMedium
                          )
                          DropdownMenu(
                              expanded = dropdownExpanded,
                              onDismissRequest = { dropdownExpanded = false }
                          ) {
                              languages.forEach { (code, name) ->
                                  DropdownMenuItem(
                                      text = { Text(name) },
                                      onClick = {
                                          selectedLangCode = code
                                          dropdownExpanded = false
                                      }
                                  )
                              }
                          }
                      }
                  }
                  
                  HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                  
                  Text(
                      text = currentTranslation.subtitle,
                      fontWeight = FontWeight.SemiBold,
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Text(
                      text = currentTranslation.point1,
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Text(
                      text = currentTranslation.point2,
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Text(
                      text = currentTranslation.point3,
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                      text = currentTranslation.consent,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
              }
          },
          confirmButton = {
              Button(
                  onClick = {
                      AppSettings.setDisclaimerAccepted(context, true)
                      showDisclaimer = false
                  },
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
              ) {
                  Text(currentTranslation.buttonText)
              }
          }
      )
  }
  val deepLink = MainActivity.deepLinkUri.value

  LaunchedEffect(deepLink) {
    if (deepLink != null) {
      val scheme = deepLink.scheme
      val host = deepLink.host
      if (scheme == "medicalscanner") {
        val token = deepLink.getQueryParameter("token")
        val email = deepLink.getQueryParameter("email")
        val googleEmail = deepLink.getQueryParameter("google_email")
        val googleAccessToken = deepLink.getQueryParameter("google_access_token")

        if (host == "oauth2") {
          if (token != null && email != null) {
            AppSettings.setAuthToken(context, token)
            AppSettings.setUserEmail(context, email)
            if (googleEmail != null) {
              AppSettings.setLinkedEmail(context, googleEmail)
              AppSettings.setLinkedEmailType(context, "gmail")
              AppSettings.setEmailConsentGranted(context, true)
            }
            if (googleAccessToken != null) {
              SecureKeyManager.setEmailToken(context, googleAccessToken)
            }

            // Schedule periodic daily worker
            val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.medicalscanner.local.EmailScanWorker>(
              24, java.util.concurrent.TimeUnit.HOURS
            ).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
              "DailyEmailScanWork",
              androidx.work.ExistingPeriodicWorkPolicy.KEEP,
              workRequest
            )

            MainActivity.deepLinkUri.value = null
            backStack.clear()
            backStack.add(Main)
          }
        } else if (host == "oauth2-link") {
          if (googleEmail != null) {
            AppSettings.setLinkedEmail(context, googleEmail)
            AppSettings.setLinkedEmailType(context, "gmail")
            AppSettings.setEmailConsentGranted(context, true)
          }
          if (googleAccessToken != null) {
            SecureKeyManager.setEmailToken(context, googleAccessToken)
          }

          // Schedule periodic daily worker
          val workRequest = androidx.work.PeriodicWorkRequestBuilder<com.example.medicalscanner.local.EmailScanWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
          ).build()
          androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "DailyEmailScanWork",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            workRequest
          )

          MainActivity.deepLinkUri.value = null
          android.widget.Toast.makeText(context, "Google Account Linked successfully!", android.widget.Toast.LENGTH_SHORT).show()
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    if (AppSettings.isLoggedIn(context)) {
      runCatching { NetworkModule.getApi(context).getMe() }
        .onFailure { e ->
          if (e.httpCode() == 401) {
            AppSettings.logout(context)
            backStack.clear()
            backStack.add(Login)
          }
        }
    }
  }

  // One-time (per install, retried until it succeeds) pull of UI-chrome translations from
  // the backend so DB edits reach the phone without an app update; see RemoteUiTranslations.
  LaunchedEffect(Unit) {
    com.example.medicalscanner.local.RemoteUiTranslations.fetchAllIfNeverFetched(context)
  }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Login> {
          LoginScreen(
            onLoggedIn = {
              backStack.clear()
              backStack.add(Main)
            },
            onNavigateToRegister = { msisdn -> backStack.add(Register(msisdn)) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Register> { key ->
          RegisterScreen(
            prepopulatedMsisdn = key.msisdn,
            onRegistered = {
              backStack.clear()
              backStack.add(Main)
            },
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Account> {
          AccountScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onLoggedOut = {
              backStack.clear()
              backStack.add(Login)
            },
            onNavigateToSettings = { backStack.add(IPConfig) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Main> {
          HomeScreen(
            onNavigateToScan = { backStack.add(Scan) },
            onNavigateToDetail = { reportId -> backStack.add(ReportDetail(reportId)) },
            onNavigateToCompare = { backStack.add(Compare) },
            onNavigateToChat = { backStack.add(Chat()) },
            onNavigateToTrends = { backStack.add(Trends) },
            onNavigateToAccount = { backStack.add(Account) },
            onNavigateToRecords = { backStack.add(Records) },
            onNavigateToMedicationTracker = { backStack.add(MedicationTracker) },
            onNavigateToReminders = { backStack.add(Reminders) },
            onNavigateToPendingTests = { backStack.add(PendingTests) },
            onRefresh = {
              coroutineScope.launch {
                runCatching { NetworkModule.getApi(context).getMe() }
              }
            },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Records> {
          RecordsScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToDetail = { reportId -> backStack.add(ReportDetail(reportId)) },
            onNavigateToScan = { backStack.add(Scan) },
            onNavigateToChat = { backStack.add(Chat(contextHint = "Records")) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<MedicationTracker> {
          MedicationTrackerScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToChat = { backStack.add(Chat(contextHint = "Medication Tracker")) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Reminders> {
          RemindersScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToChat = { backStack.add(Chat(contextHint = "Reminders")) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<PendingTests> {
          PendingTestsScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToDetail = { reportId -> backStack.add(ReportDetail(reportId)) },
            onNavigateToChat = { backStack.add(Chat(contextHint = "Pending Tests")) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Trends> {
          TrendsScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToReport = { reportId, param -> backStack.add(ReportDetail(reportId, param)) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<IPConfig> {
          IPConfigScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Compare> {
          CompareScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Chat> { key ->
          ChatScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding(),
            contextHint = key.contextHint
          )
        }
        entry<Scan> {
          ScanScreen(
            onNavigateToDetail = { reportId -> 
                backStack.removeLastOrNull() // Pop the scan screen
                backStack.add(ReportDetail(reportId)) // Navigate to detail screen
            },
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<ReportDetail> { key ->
          ReportDetailScreen(
            reportId = key.reportId,
            highlightParam = key.highlightParam,
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToDetail = { id ->
              backStack.add(ReportDetail(id))
            },
            onNavigateToAnalysis = { id ->
              backStack.add(DetailedAnalysis(id))
            },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<DetailedAnalysis> { key ->
          DetailedAnalysisScreen(
            reportId = key.reportId,
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
      },
  )
}
