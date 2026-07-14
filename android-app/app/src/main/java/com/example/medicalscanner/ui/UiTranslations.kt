package com.example.medicalscanner.ui

/**
 * Static, hand-maintained translations for UI chrome — button labels, titles, empty states.
 * One map per language, keyed by the English source string. A string missing from a
 * language's map just renders in English until someone adds it here — no build step, no
 * network call. Keep this separate from AI-generated content (chat answers, report
 * summaries), which is translated live via LanguageUtil/Sarvam since that text can't be
 * pre-written.
 *
 * These are a starting pass for the strings introduced by the Home redesign; extend this
 * map as more screens get covered, and have a native speaker review before shipping widely.
 */
object UiTranslations {
    private val hindi = mapOf(
        "Scan Report" to "रिपोर्ट स्कैन करें",
        "Records" to "रिकॉर्ड्स",
        "Reminders" to "रिमाइंडर",
        "Medication Tracker" to "दवा ट्रैकर",
        "Pending Tests" to "लंबित जांच",
        "Trends" to "रुझान",
        "Account" to "खाता",
        "Ask AI Assistant" to "एआई सहायक से पूछें",
        "Ask about this page" to "इस पेज के बारे में पूछें",
        "Compare Reports" to "रिपोर्ट की तुलना करें",
        "Refresh" to "रीफ्रेश करें",
        "Back" to "वापस",
        "Search" to "खोजें",
        "Today's Meds" to "आज की दवाएं",
        "Reports History" to "रिपोर्ट इतिहास",
        "All Time" to "सभी समय",
        "1 Month" to "1 महीना",
        "3 Months" to "3 महीने",
        "6 Months" to "6 महीने"
    )

    private val marathi = mapOf(
        "Scan Report" to "अहवाल स्कॅन करा",
        "Records" to "नोंदी",
        "Reminders" to "स्मरणपत्रे",
        "Medication Tracker" to "औषध ट्रॅकर",
        "Pending Tests" to "प्रलंबित चाचण्या",
        "Trends" to "कल",
        "Account" to "खाते",
        "Ask AI Assistant" to "एआय सहाय्यकाला विचारा",
        "Ask about this page" to "या पानाबद्दल विचारा",
        "Compare Reports" to "अहवालांची तुलना करा",
        "Refresh" to "रिफ्रेश करा",
        "Back" to "मागे",
        "Search" to "शोधा",
        "Today's Meds" to "आजची औषधे",
        "Reports History" to "अहवाल इतिहास",
        "All Time" to "सर्व काळ",
        "1 Month" to "1 महिना",
        "3 Months" to "3 महिने",
        "6 Months" to "6 महिने"
    )

    private val gujarati = mapOf(
        "Scan Report" to "રિપોર્ટ સ્કેન કરો",
        "Records" to "રેકોર્ડ્સ",
        "Reminders" to "રિમાઇન્ડર્સ",
        "Medication Tracker" to "દવા ટ્રેકર",
        "Pending Tests" to "બાકી પરીક્ષણો",
        "Trends" to "વલણો",
        "Account" to "ખાતું",
        "Ask AI Assistant" to "AI સહાયકને પૂછો",
        "Ask about this page" to "આ પેજ વિશે પૂછો",
        "Compare Reports" to "રિપોર્ટ્સની સરખામણી કરો",
        "Refresh" to "તાજું કરો",
        "Back" to "પાછળ",
        "Search" to "શોધો"
    )

    private val tamil = mapOf(
        "Scan Report" to "அறிக்கையை ஸ்கேன் செய்யவும்",
        "Records" to "பதிவுகள்",
        "Reminders" to "நினைவூட்டல்கள்",
        "Medication Tracker" to "மருந்து டிராக்கர்",
        "Pending Tests" to "நிலுவையிலுள்ள பரிசோதனைகள்",
        "Trends" to "போக்குகள்",
        "Account" to "கணக்கு",
        "Ask AI Assistant" to "AI உதவியாளரிடம் கேளுங்கள்",
        "Ask about this page" to "இந்தப் பக்கத்தைப் பற்றி கேளுங்கள்",
        "Compare Reports" to "அறிக்கைகளை ஒப்பிடுக",
        "Refresh" to "புதுப்பிக்கவும்",
        "Back" to "பின்செல்",
        "Search" to "தேடு"
    )

    private val telugu = mapOf(
        "Scan Report" to "నివేదికను స్కాన్ చేయండి",
        "Records" to "రికార్డులు",
        "Reminders" to "రిమైండర్‌లు",
        "Medication Tracker" to "మందుల ట్రాకర్",
        "Pending Tests" to "పెండింగ్ పరీక్షలు",
        "Trends" to "ధోరణులు",
        "Account" to "ఖాతా",
        "Ask AI Assistant" to "AI సహాయకుడిని అడగండి",
        "Ask about this page" to "ఈ పేజీ గురించి అడగండి",
        "Compare Reports" to "నివేదికలను పోల్చండి",
        "Refresh" to "రిఫ్రెష్ చేయండి",
        "Back" to "వెనుకకు",
        "Search" to "వెతకండి"
    )

    private val kannada = mapOf(
        "Scan Report" to "ವರದಿ ಸ್ಕ್ಯಾನ್ ಮಾಡಿ",
        "Records" to "ದಾಖಲೆಗಳು",
        "Reminders" to "ಜ್ಞಾಪನೆಗಳು",
        "Medication Tracker" to "ಔಷಧಿ ಟ್ರ್ಯಾಕರ್",
        "Pending Tests" to "ಬಾಕಿ ಪರೀಕ್ಷೆಗಳು",
        "Trends" to "ಪ್ರವೃತ್ತಿಗಳು",
        "Account" to "ಖಾತೆ",
        "Ask AI Assistant" to "AI ಸಹಾಯಕರನ್ನು ಕೇಳಿ",
        "Ask about this page" to "ಈ ಪುಟದ ಬಗ್ಗೆ ಕೇಳಿ",
        "Compare Reports" to "ವರದಿಗಳನ್ನು ಹೋಲಿಸಿ",
        "Refresh" to "ರಿಫ್ರೆಶ್ ಮಾಡಿ",
        "Back" to "ಹಿಂದೆ",
        "Search" to "ಹುಡುಕಿ"
    )

    private val bengali = mapOf(
        "Scan Report" to "রিপোর্ট স্ক্যান করুন",
        "Records" to "রেকর্ড",
        "Reminders" to "রিমাইন্ডার",
        "Medication Tracker" to "ওষুধ ট্র্যাকার",
        "Pending Tests" to "মুলতুবি পরীক্ষা",
        "Trends" to "প্রবণতা",
        "Account" to "অ্যাকাউন্ট",
        "Ask AI Assistant" to "AI সহকারীকে জিজ্ঞাসা করুন",
        "Ask about this page" to "এই পৃষ্ঠা সম্পর্কে জিজ্ঞাসা করুন",
        "Compare Reports" to "রিপোর্ট তুলনা করুন",
        "Refresh" to "রিফ্রেশ করুন",
        "Back" to "পিছনে",
        "Search" to "খুঁজুন"
    )

    private val punjabi = mapOf(
        "Scan Report" to "ਰਿਪੋਰਟ ਸਕੈਨ ਕਰੋ",
        "Records" to "ਰਿਕਾਰਡ",
        "Reminders" to "ਰਿਮਾਈਂਡਰ",
        "Medication Tracker" to "ਦਵਾਈ ਟਰੈਕਰ",
        "Pending Tests" to "ਬਕਾਇਆ ਟੈਸਟ",
        "Trends" to "ਰੁਝਾਨ",
        "Account" to "ਖਾਤਾ",
        "Ask AI Assistant" to "AI ਸਹਾਇਕ ਨੂੰ ਪੁੱਛੋ",
        "Ask about this page" to "ਇਸ ਪੰਨੇ ਬਾਰੇ ਪੁੱਛੋ",
        "Compare Reports" to "ਰਿਪੋਰਟਾਂ ਦੀ ਤੁਲਨਾ ਕਰੋ",
        "Refresh" to "ਤਾਜ਼ਾ ਕਰੋ",
        "Back" to "ਪਿੱਛੇ",
        "Search" to "ਖੋਜੋ"
    )

    private val malayalam = mapOf(
        "Scan Report" to "റിപ്പോർട്ട് സ്കാൻ ചെയ്യുക",
        "Records" to "രേഖകൾ",
        "Reminders" to "ഓർമ്മപ്പെടുത്തലുകൾ",
        "Medication Tracker" to "മരുന്ന് ട്രാക്കർ",
        "Pending Tests" to "തീർച്ചയാകാത്ത പരിശോധനകൾ",
        "Trends" to "പ്രവണതകൾ",
        "Account" to "അക്കൗണ്ട്",
        "Ask AI Assistant" to "AI സഹായിയോട് ചോദിക്കുക",
        "Ask about this page" to "ഈ പേജിനെക്കുറിച്ച് ചോദിക്കുക",
        "Compare Reports" to "റിപ്പോർട്ടുകൾ താരതമ്യം ചെയ്യുക",
        "Refresh" to "പുതുക്കുക",
        "Back" to "തിരികെ",
        "Search" to "തിരയുക"
    )

    private val odia = mapOf(
        "Scan Report" to "ରିପୋର୍ଟ ସ୍କାନ୍ କରନ୍ତୁ",
        "Records" to "ରେକର୍ଡ",
        "Reminders" to "ସ୍ମାରକ",
        "Medication Tracker" to "ଔଷଧ ଟ୍ରାକର୍",
        "Pending Tests" to "ବକେୟା ପରୀକ୍ଷା",
        "Trends" to "ଧାରା",
        "Account" to "ଖାତା",
        "Ask AI Assistant" to "AI ସହାୟକଙ୍କୁ ପଚାରନ୍ତୁ",
        "Ask about this page" to "ଏହି ପୃଷ୍ଠା ବିଷୟରେ ପଚାରନ୍ତୁ",
        "Compare Reports" to "ରିପୋର୍ଟଗୁଡ଼ିକୁ ତୁଳନା କରନ୍ତୁ",
        "Refresh" to "ସତେଜ କରନ୍ତୁ",
        "Back" to "ପଛକୁ",
        "Search" to "ଖୋଜନ୍ତୁ"
    )

    private val byLanguage: Map<String, Map<String, String>> = mapOf(
        "Hindi" to hindi,
        "Marathi" to marathi,
        "Gujarati" to gujarati,
        "Tamil" to tamil,
        "Telugu" to telugu,
        "Kannada" to kannada,
        "Bengali" to bengali,
        "Punjabi" to punjabi,
        "Malayalam" to malayalam,
        "Odia" to odia
    )

    /** Returns the translation if this exact string is in the language's map, else null (caller falls back to English). */
    fun lookup(language: String, text: String): String? = byLanguage[language]?.get(text)
}
