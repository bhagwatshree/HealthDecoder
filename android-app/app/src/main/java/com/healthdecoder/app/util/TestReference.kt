package com.healthdecoder.app.util

/**
 * Curated, factual, plain-language descriptions of common lab tests, so a non-medical person
 * understands what each level on a graph means. This is a fixed reference (NOT AI-generated)
 * to avoid any inaccuracy in medical data. Descriptions explain what the test measures and
 * the general meaning of high/low — they are educational, not a diagnosis.
 */
data class TestInfo(val title: String, val description: String)

object TestReference {

    // Order matters: more specific entries are matched before general ones.
    private val entries: List<Pair<List<String>, TestInfo>> = listOf(
        listOf("hba1c", "glycated", "glycosylated") to TestInfo(
            "HbA1c (Average Blood Sugar)",
            "Your average blood sugar over the past 2–3 months. Used to diagnose and monitor diabetes."
        ),
        listOf("mchc") to TestInfo(
            "MCHC — Mean Corpuscular Hemoglobin Concentration",
            "The average concentration of hemoglobin packed inside your red blood cells. Used with MCH and MCV to understand the type of anaemia. It is NOT the same as your Hemoglobin level."
        ),
        listOf("mch", "mean corpuscular hemoglobin", "mean corpuscular haemoglobin") to TestInfo(
            "MCH — Mean Corpuscular Hemoglobin",
            "The average amount of hemoglobin inside a single red blood cell. It helps classify anaemia. This is different from your total Hemoglobin level."
        ),
        listOf("mcv", "mean corpuscular volume") to TestInfo(
            "MCV — Mean Corpuscular Volume",
            "The average size of your red blood cells. Cells larger or smaller than normal point to different causes of anaemia."
        ),
        listOf("rdw") to TestInfo(
            "RDW — Red Cell Distribution Width",
            "How much your red blood cells vary in size. Higher values can be an early clue to certain anaemias."
        ),
        listOf("mpv") to TestInfo(
            "MPV — Mean Platelet Volume",
            "The average size of your platelets, which can give clues about how platelets are being produced."
        ),
        listOf("hemoglobin", "haemoglobin", "hgb") to TestInfo(
            "Hemoglobin",
            "The protein in red blood cells that carries oxygen around your body. Low levels can mean anaemia (tiredness, paleness); high levels can occur with dehydration or other conditions."
        ),
        listOf("hematocrit", "haematocrit", "pcv", "packed cell") to TestInfo(
            "Hematocrit (PCV)",
            "The percentage of your blood made up of red blood cells. Low can indicate anaemia; high can indicate dehydration."
        ),
        listOf("wbc", "white blood", "leucocyte", "leukocyte", "total leucocyte", "tlc") to TestInfo(
            "WBC — White Blood Cell Count",
            "Cells that fight infection. High counts often suggest infection or inflammation; low counts can affect your immunity."
        ),
        listOf("platelet") to TestInfo(
            "Platelet Count",
            "Cell fragments that help your blood clot. Low levels raise bleeding risk; very high levels can affect clotting."
        ),
        listOf("rbc", "red blood cell", "red cell count") to TestInfo(
            "RBC — Red Blood Cell Count",
            "The number of red blood cells that carry oxygen. Abnormal counts can relate to anaemia or dehydration."
        ),
        listOf("tsh", "thyroid stimulating") to TestInfo(
            "TSH — Thyroid Stimulating Hormone",
            "A hormone that controls your thyroid gland. HIGH TSH usually means an under-active thyroid (slow metabolism); LOW TSH an over-active thyroid."
        ),
        listOf("free t3", "triiodothyronine") to TestInfo("T3 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism (how your body uses energy). Affects weight, energy, heart rate and mood."),
        listOf("free t4", "thyroxine") to TestInfo("T4 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism. Works together with T3 and TSH to control your energy levels."),
        listOf("t3") to TestInfo("T3 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism (how your body uses energy)."),
        listOf("t4") to TestInfo("T4 (Thyroid Hormone)", "A thyroid hormone that regulates metabolism, working with T3 and TSH."),
        listOf("glucose", "sugar", "fbs", "ppbs", "rbs") to TestInfo(
            "Blood Sugar (Glucose)",
            "The amount of sugar in your blood. Consistently high levels relate to diabetes; very low levels can cause dizziness or weakness."
        ),
        listOf("vldl") to TestInfo("VLDL Cholesterol", "A type of cholesterol that carries triglycerides. High levels can add to heart-disease risk."),
        listOf("ldl") to TestInfo("LDL — 'Bad' Cholesterol", "Cholesterol that can build up in arteries. Lower levels are generally better for your heart."),
        listOf("hdl") to TestInfo("HDL — 'Good' Cholesterol", "Cholesterol that helps remove other cholesterol from your blood. Higher levels are generally protective."),
        listOf("triglyceride") to TestInfo("Triglycerides", "A type of fat in your blood. High levels are linked to heart-disease risk and are influenced by diet and lifestyle."),
        listOf("cholesterol") to TestInfo("Total Cholesterol", "The total amount of cholesterol (a fat) in your blood. High levels can raise heart-disease risk."),
        listOf("creatinine") to TestInfo("Creatinine", "A waste product filtered out by your kidneys. Higher levels can indicate reduced kidney function."),
        listOf("urea", "bun") to TestInfo("Urea (BUN)", "A waste product from protein breakdown, filtered by the kidneys. Used with creatinine to assess kidney function."),
        listOf("uric acid") to TestInfo("Uric Acid", "A waste product; high levels can cause gout (painful joints) or relate to kidney issues."),
        listOf("sgpt", "alt", "alanine") to TestInfo("SGPT / ALT (Liver Enzyme)", "A liver enzyme. Raised levels can indicate that the liver is irritated or under stress."),
        listOf("sgot", "ast", "aspartate") to TestInfo("SGOT / AST (Liver Enzyme)", "An enzyme found in the liver and muscles. Raised levels can point to liver or muscle stress."),
        listOf("bilirubin") to TestInfo("Bilirubin", "A yellow substance from the breakdown of red blood cells, processed by the liver. High levels can cause jaundice (yellow skin/eyes)."),
        listOf("vitamin d", "25-oh", "25 oh") to TestInfo("Vitamin D", "Important for strong bones and immunity. Low levels are common and can cause tiredness or bone aches."),
        listOf("b12", "cobalamin") to TestInfo("Vitamin B12", "Needed for healthy nerves and red blood cells. Low levels can cause tiredness, tingling, or anaemia."),
        listOf("spo2", "oxygen", "saturation") to TestInfo("Oxygen (SpO₂)", "The percentage of oxygen your blood is carrying. Normally around 95–100%; lower can indicate breathing or lung issues."),
        listOf("ejection", "lvef") to TestInfo("Ejection Fraction", "The percentage of blood your heart pumps out with each beat. Lower values can indicate reduced heart pumping strength."),

        // ── Coagulation (PT/INR) ──────────────────────────────────────────────
        listOf("inr", "international normali") to TestInfo(
            "INR — International Normalized Ratio",
            "How long your blood takes to clot, standardized so results are comparable across labs. Used mainly to monitor blood-thinner medicines like warfarin."
        ),
        listOf("prothrombin") to TestInfo(
            "PT — Prothrombin Time",
            "How many seconds your blood takes to clot. Raised values can mean a bleeding risk or reflect blood-thinner medicine."
        ),
        listOf("aptt", "activated partial") to TestInfo(
            "APTT — Activated Partial Thromboplastin Time",
            "Another measure of how long your blood takes to clot, testing a different part of the clotting process than PT/INR."
        ),
        listOf("fibrinogen") to TestInfo(
            "Fibrinogen",
            "A protein your body uses to form blood clots. Low levels can mean a bleeding risk; high levels are linked to inflammation or clotting risk."
        ),
        listOf("d-dimer", "d dimer") to TestInfo(
            "D-Dimer",
            "A fragment left behind after a blood clot breaks down. Raised levels can point to clotting activity but have many possible causes."
        ),

        // ── Electrolytes & minerals ─────────────────────────────────────────────
        listOf("sodium") to TestInfo("Sodium", "A key blood mineral that balances fluid levels. Very high or low levels can affect the brain and muscles."),
        listOf("potassium") to TestInfo("Potassium", "A mineral critical for heart and muscle function. Both high and low levels can be dangerous for heart rhythm."),
        listOf("chloride") to TestInfo("Chloride", "A blood mineral that works with sodium to balance fluids and body pH."),
        listOf("bicarbonate", "hco3") to TestInfo("Bicarbonate", "A blood measure of body pH balance. Abnormal levels can reflect kidney, lung, or metabolic issues."),
        listOf("calcium") to TestInfo("Calcium", "Important for bones, muscles, and nerves. Levels are affected by diet, vitamin D, and parathyroid/kidney function."),
        listOf("magnesium") to TestInfo("Magnesium", "A mineral involved in muscle, nerve, and heart function. Low levels can cause cramps or irregular heartbeat."),
        listOf("phosphor", "phosphate") to TestInfo("Phosphorus", "A mineral that works with calcium for healthy bones. Levels are closely tied to kidney function."),

        // ── Kidney ────────────────────────────────────────────────────────────
        listOf("egfr", "gfr") to TestInfo(
            "eGFR — Estimated Glomerular Filtration Rate",
            "An estimate of how well your kidneys filter waste, calculated from creatinine, age and sex. Lower values indicate reduced kidney function."
        ),

        // ── Liver ─────────────────────────────────────────────────────────────
        listOf("alkaline phosphat", "alp") to TestInfo("ALP — Alkaline Phosphatase", "A liver and bone enzyme. Raised levels can point to liver, bile duct, or bone conditions."),
        listOf("ggt", "gamma gluta", "gamma-gluta") to TestInfo("GGT — Gamma-Glutamyl Transferase", "A liver enzyme, often raised together with alcohol use, bile duct issues, or some medicines."),
        listOf("globulin") to TestInfo("Globulin", "A group of blood proteins made by the liver and immune system, involved in fighting infection."),
        listOf("albumin") to TestInfo("Albumin", "The main protein made by your liver. Low levels can reflect liver or kidney disease, or poor nutrition."),
        listOf("protein") to TestInfo("Total Protein", "The combined amount of albumin and globulin in your blood — a general marker of liver, kidney, and nutritional health."),

        // ── Heart ─────────────────────────────────────────────────────────────
        listOf("troponin") to TestInfo("Troponin", "A protein released when heart muscle is damaged. Used mainly to detect a heart attack."),
        listOf("bnp", "natriuretic") to TestInfo("BNP — B-type Natriuretic Peptide", "A hormone released when the heart is under strain. Raised levels can indicate heart failure."),
        listOf("ck-mb", "ckmb", "creatine kinase", "cpk") to TestInfo("CPK / CK-MB", "An enzyme released by damaged heart or muscle tissue, used to help detect a heart attack or muscle injury."),

        // ── Blood count (CBC) differential ───────────────────────────────────
        listOf("neutrophil") to TestInfo("Neutrophils", "The most common white blood cell, first responders against bacterial infection. High counts often mean an active infection."),
        listOf("lymphocyte") to TestInfo("Lymphocytes", "White blood cells central to fighting viral infections and long-term immunity."),
        listOf("monocyte") to TestInfo("Monocytes", "White blood cells that clean up debris and fight chronic infection or inflammation."),
        listOf("eosinophil") to TestInfo("Eosinophils", "White blood cells that respond to allergies and parasitic infections."),
        listOf("basophil") to TestInfo("Basophils", "The least common white blood cell, involved in allergic reactions and inflammation."),
        listOf("esr", "sedimentation") to TestInfo("ESR — Erythrocyte Sedimentation Rate", "A general marker of inflammation in the body, not specific to any one condition."),

        // ── Vitamins, iron & inflammation ────────────────────────────────────
        listOf("ferritin") to TestInfo("Ferritin", "Your body's stored iron level. Low levels usually mean iron deficiency; high levels can reflect inflammation or iron overload."),
        listOf("tibc", "iron binding") to TestInfo("TIBC — Total Iron Binding Capacity", "How much iron your blood COULD carry. Used alongside iron and ferritin to assess iron deficiency or overload."),
        listOf("iron") to TestInfo("Iron", "The iron currently circulating in your blood, used to make hemoglobin. Interpreted together with ferritin and TIBC."),
        listOf("folate", "folic") to TestInfo("Folate", "A B-vitamin needed for making red blood cells. Low levels can cause a type of anaemia."),
        listOf("c-reactive", "crp") to TestInfo("CRP — C-Reactive Protein", "A general marker of inflammation or infection in the body, not specific to any one condition."),
    )

    /** Returns a plain-language description for a test name, or null if not in the reference. */
    fun describe(name: String): TestInfo? {
        val n = name.lowercase()
        for ((keys, info) in entries) {
            if (keys.any { n.contains(it) }) return info
        }
        return null
    }
}
