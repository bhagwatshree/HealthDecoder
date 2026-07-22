import db from './db.js';
import crypto from 'crypto';

// Simulated Indian medical facilities matching doctors/hospitals in the reports
const MOCK_HOSPITALS = [
  {
    id: "hosp-1",
    name: "Deenanath Mangeshkar Hospital & Research Center",
    address: "Erandawane, Pune 411004",
    phone: "020 40151000",
    distance: "0.8 km",
    rating: "4.6",
    hasEmergency: true,
    latitude: 18.5089,
    longitude: 73.8378
  },
  {
    id: "hosp-2",
    name: "Sahyadri Super Speciality Hospital",
    address: "Deccan Gymkhana, Pune 411004",
    phone: "020 67213000",
    distance: "1.5 km",
    rating: "4.3",
    hasEmergency: true,
    latitude: 18.5144,
    longitude: 73.8412
  },
  {
    id: "hosp-3",
    name: "Poona Hospital and Research Centre",
    address: "Sadashiv Peth, Pune 411030",
    phone: "020 24331707",
    distance: "2.1 km",
    rating: "4.1",
    hasEmergency: true,
    latitude: 18.5065,
    longitude: 73.8488
  },
  {
    id: "hosp-4",
    name: "Ruby Hall Clinic",
    address: "Sassoon Road, Near Pune Station, Pune 411001",
    phone: "020 66450500",
    distance: "4.8 km",
    rating: "4.5",
    hasEmergency: true,
    latitude: 18.5284,
    longitude: 73.8741
  }
];

const MOCK_LABS = [
  {
    id: "lab-1",
    name: "Dhande Pathlab Diagnostics Pvt. Ltd.",
    address: "Chinar Apartments, Sheelavihar Colony, Opp. Paud Phata Police Station, Pune 411038",
    phone: "020 25442020",
    distance: "0.4 km",
    rating: "4.7",
    homeCollection: true,
    supportedTests: [
      { code: "CBC", name: "Haemogram (CBC)", price: 320, timeToReport: "6 hours" },
      { code: "LIPID", name: "Lipid Profile", price: 600, timeToReport: "8 hours" },
      { code: "TSH", name: "Thyroid Profile (T3, T4, TSH)", price: 450, timeToReport: "12 hours" },
      { code: "URINE", name: "Urine Routine (Panel)", price: 180, timeToReport: "4 hours" }
    ],
    latitude: 18.5076,
    longitude: 73.8315
  },
  {
    id: "lab-2",
    name: "Dr. Lal PathLabs Collection Center",
    address: "Deccan Gymkhana, Opp. Central Mall, Pune 411004",
    phone: "011 39885050",
    distance: "1.6 km",
    rating: "4.4",
    homeCollection: true,
    supportedTests: [
      { code: "CBC", name: "Complete Blood Count (CBC)", price: 350, timeToReport: "8 hours" },
      { code: "LIPID", name: "Lipid Profile", price: 650, timeToReport: "8 hours" },
      { code: "TSH", name: "Thyroid UltraSensitive TSH", price: 500, timeToReport: "8 hours" },
      { code: "URINE", name: "Urine Analysis", price: 200, timeToReport: "6 hours" }
    ],
    latitude: 18.5135,
    longitude: 73.8425
  },
  {
    id: "lab-3",
    name: "Tata 1mg Diagnostics",
    address: "Kothrud, Near Ideal Colony, Pune 411038",
    phone: "1800 212 2323",
    distance: "1.2 km",
    rating: "4.5",
    homeCollection: true,
    supportedTests: [
      { code: "CBC", name: "Complete Blood Count (CBC)", price: 299, timeToReport: "12 hours" },
      { code: "LIPID", name: "Lipid Profile Standard", price: 550, timeToReport: "12 hours" },
      { code: "TSH", name: "Thyroid Stimulating Hormone", price: 399, timeToReport: "12 hours" },
      { code: "URINE", name: "Urine Routine", price: 150, timeToReport: "8 hours" }
    ],
    latitude: 18.5032,
    longitude: 73.8214
  },
  {
    id: "lab-4",
    name: "Metropolis Healthcare Ltd.",
    address: "Karve Road, Pune 411004",
    phone: "022 33993999",
    distance: "0.9 km",
    rating: "4.2",
    homeCollection: true,
    supportedTests: [
      { code: "CBC", name: "Complete Hemogram (CBC)", price: 380, timeToReport: "6 hours" },
      { code: "LIPID", name: "Lipid Profile Basic", price: 700, timeToReport: "6 hours" },
      { code: "TSH", name: "TSH Ultra Sensitive", price: 480, timeToReport: "6 hours" },
      { code: "URINE", name: "Urine Routine", price: 220, timeToReport: "4 hours" }
    ],
    latitude: 18.5098,
    longitude: 73.8344
  }
];

const MOCK_DOCTORS = [
  {
    id: "doc-1",
    name: "Dr. Karne Swapneel Keshav",
    specialty: "Pediatrician / General Physician",
    experience: "12 years",
    clinic: "Karne Children's Clinic, Deccan, Pune",
    phone: "020 25435000",
    distance: "1.4 km",
    rating: "4.8",
    fee: 500,
    slots: ["10:30 AM", "11:15 AM", "05:30 PM", "06:15 PM"],
    latitude: 18.5122,
    longitude: 73.8405
  },
  {
    id: "doc-2",
    name: "Dr. Kulkarni Bipin",
    specialty: "Radiologist & Vascular Specialist",
    experience: "18 years",
    clinic: "Kulkarni Diagnostics, Erandawane, Pune",
    phone: "020 40151037",
    distance: "0.7 km",
    rating: "4.9",
    fee: 800,
    slots: ["09:30 AM", "11:00 AM", "03:00 PM", "04:30 PM"],
    latitude: 18.5091,
    longitude: 73.8365
  },
  {
    id: "doc-3",
    name: "Dr. Vijaya Gadage",
    specialty: "Consultant Haematopathologist",
    experience: "15 years",
    clinic: "Dhande Diagnostics, Paud Road, Pune",
    phone: "020 25452020",
    distance: "0.4 km",
    rating: "4.7",
    fee: 400,
    slots: ["08:30 AM", "10:00 AM", "02:30 PM", "04:00 PM"],
    latitude: 18.5076,
    longitude: 73.8315
  },
  {
    id: "doc-4",
    name: "Dr. Satish Sakhalkar",
    specialty: "Gynaecologist & Obstetrician",
    experience: "20 years",
    clinic: "Sakhalkar Maternity Home, Sadashiv Peth, Pune",
    phone: "020 24332211",
    distance: "2.3 km",
    rating: "4.6",
    fee: 600,
    slots: ["11:00 AM", "12:30 PM", "06:00 PM", "07:30 PM"],
    latitude: 18.5055,
    longitude: 73.8475
  }
];

/**
 * Searches medical facilities using Commercial APIs (or Fallback Mock Data)
 */
export async function searchCommercial(latitude, longitude, category, query) {
  // If actual API keys are configured, implement call logic:
  // e.g. if (process.env.MAPMYINDIA_API_KEY) { ... }
  // For the B2B lab tests, Practo, and geocoding.
  
  // Return realistic mock responses mimicking MapmyIndia, Dr. Lal PathLabs, 1mg, and Practo
  // relative to coordinates. In a real deployment, coordinates are used to calculate real distance.
  
  const normQuery = (query || "").trim().toLowerCase();
  
  if (category === "hospitals") {
    // Simulating MapmyIndia Nearby API filtering
    if (normQuery) {
      return MOCK_HOSPITALS.filter(h => h.name.toLowerCase().includes(normQuery) || h.address.toLowerCase().includes(normQuery));
    }
    return MOCK_HOSPITALS;
  } 
  
  else if (category === "lab_tests") {
    // Simulating Dr. Lal PathLabs / 1mg test search and collection center discovery
    const results = [];
    for (const lab of MOCK_LABS) {
      // Find matching tests in this lab
      const matchedTests = lab.supportedTests.filter(t => {
        if (!normQuery) return true;
        // e.g. query "CBC" or "lipid" or "thyroid"
        return t.code.toLowerCase().includes(normQuery) || 
               t.name.toLowerCase().includes(normQuery);
      });
      
      if (matchedTests.length > 0) {
        results.push({
          id: lab.id,
          name: lab.name,
          address: lab.address,
          phone: lab.phone,
          distance: lab.distance,
          rating: lab.rating,
          homeCollection: lab.homeCollection,
          matchedTests: matchedTests,
          latitude: lab.latitude,
          longitude: lab.longitude
        });
      }
    }
    return results;
  } 
  
  else if (category === "doctors") {
    // Simulating Practo Ray / MapmyIndia Clinics search
    if (normQuery) {
      return MOCK_DOCTORS.filter(d => 
        d.name.toLowerCase().includes(normQuery) || 
        d.specialty.toLowerCase().includes(normQuery) ||
        d.clinic.toLowerCase().includes(normQuery)
      );
    }
    return MOCK_DOCTORS;
  }
  
  return [];
}

/**
 * Initiates an Asynchronous UHI Search Session (Beckn Protocol)
 * Sends a request to the UHI Gateway and sets up simulated asynchronous callbacks on the webhook.
 */
export async function startUhiSearch(userId, { latitude, longitude, category, query }) {
  const searchId = crypto.randomUUID();
  const intent = `${category}:${query || ""}`;

  // Insert session record
  await db.query(
    `INSERT INTO uhi_search_sessions (search_id, user_id, latitude, longitude, intent, results)
     VALUES ($1, $2, $3, $4, $5, $6)`,
    [searchId, userId, latitude, longitude, intent, JSON.stringify([])]
  );

  // Async simulation: In the Beckn Protocol, the gateway immediately returns HTTP 200 ACK,
  // and clinic/hospital providers push their data to the /on_search webhook over the next few seconds.
  // We trigger a background process that simulates this asynchronous webhook traffic.
  simulateUhiWebhookCallbacks(searchId, category, query);

  return {
    search_id: searchId,
    gateway_status: "ACK",
    message: "Search broadcasted to the Unified Health Interface (UHI) network. Webhook responses will arrive shortly."
  };
}

/**
 * Simulates asynchronous callbacks pushing clinic/doctor profiles to the webhook
 */
function simulateUhiWebhookCallbacks(searchId, category, query) {
  // Simulate network latency: callbacks arrive over 1.5 to 4 seconds
  setTimeout(async () => {
    try {
      // Simulate Webhook Push 1: A nearby clinic
      const pushData = generateUhiPushPayload(searchId, category, query, 0);
      if (pushData) {
        await processUhiWebhook(pushData);
      }
    } catch (e) {
      console.error("Error in UHI Webhook simulation 1:", e);
    }
  }, 1500);

  setTimeout(async () => {
    try {
      // Simulate Webhook Push 2: A major hospital
      const pushData = generateUhiPushPayload(searchId, category, query, 1);
      if (pushData) {
        await processUhiWebhook(pushData);
      }
    } catch (e) {
      console.error("Error in UHI Webhook simulation 2:", e);
    }
  }, 3000);
}

/**
 * Webhook Processor (/api/discovery/uhi/on_search)
 * Combines new incoming provider push items with the existing session results.
 */
export async function processUhiWebhook(payload) {
  const { search_id, providers } = payload;
  if (!search_id || !providers) return;

  const result = await db.query(
    `SELECT results FROM uhi_search_sessions WHERE search_id = $1`,
    [search_id]
  );
  if (result.rows.length === 0) return;

  const currentResults = result.rows[0].results || [];
  const updatedResults = [...currentResults, ...providers];

  await db.query(
    `UPDATE uhi_search_sessions SET results = $1 WHERE search_id = $2`,
    [JSON.stringify(updatedResults), search_id]
  );
}

/**
 * Helper to generate UHI push payloads (matching Beckn Schema style)
 */
function generateUhiPushPayload(searchId, category, query, step) {
  const normQuery = (query || "").trim().toLowerCase();
  
  if (category === "doctors") {
    // Provider list mapping
    const docs = step === 0 ? [MOCK_DOCTORS[0], MOCK_DOCTORS[2]] : [MOCK_DOCTORS[1], MOCK_DOCTORS[3]];
    const filteredDocs = docs.filter(d => 
      !normQuery || 
      d.name.toLowerCase().includes(normQuery) || 
      d.specialty.toLowerCase().includes(normQuery)
    );

    if (filteredDocs.length === 0) return null;

    return {
      search_id: searchId,
      providers: filteredDocs.map(d => ({
        provider_id: d.id,
        provider_name: d.clinic,
        type: "clinic",
        distance: d.distance,
        rating: d.rating,
        items: [
          {
            id: `item-${d.id}`,
            name: d.name,
            descriptor: { specialty: d.specialty, experience: d.experience },
            price: { value: d.fee, currency: "INR" },
            slots: d.slots
          }
        ]
      }))
    };
  } 
  
  else if (category === "hospitals") {
    const hosps = step === 0 ? [MOCK_HOSPITALS[0], MOCK_HOSPITALS[2]] : [MOCK_HOSPITALS[1], MOCK_HOCK_HOSPITALS ? MOCK_HOSPITALS[3] : MOCK_HOSPITALS[3]];
    const filteredHosps = hosps.filter(h => 
      !normQuery || 
      h.name.toLowerCase().includes(normQuery) || 
      h.address.toLowerCase().includes(normQuery)
    );

    if (filteredHosps.length === 0) return null;

    return {
      search_id: searchId,
      providers: filteredHosps.map(h => ({
        provider_id: h.id,
        provider_name: h.name,
        type: "hospital",
        distance: h.distance,
        rating: h.rating,
        address: h.address,
        phone: h.phone,
        items: [
          {
            id: `item-${h.id}-emergency`,
            name: "Emergency/OPD Consult",
            descriptor: { specialty: "General Triage" },
            price: { value: 300, currency: "INR" },
            slots: ["Immediate", "Next 30 mins"]
          }
        ]
      }))
    };
  } 
  
  else if (category === "lab_tests") {
    // While lab discovery is "Coming Soon", we provide mock support to demonstrate the schema
    const labs = step === 0 ? [MOCK_LABS[0], MOCK_LABS[2]] : [MOCK_LABS[1], MOCK_LABS[3]];
    const filteredLabs = [];
    for (const lab of labs) {
      const tests = lab.supportedTests.filter(t => 
        !normQuery || 
        t.code.toLowerCase().includes(normQuery) || 
        t.name.toLowerCase().includes(normQuery)
      );

      if (tests.length > 0) {
        filteredLabs.push({
          provider_id: lab.id,
          provider_name: lab.name,
          type: "laboratory",
          distance: lab.distance,
          rating: lab.rating,
          homeCollection: lab.homeCollection,
          items: tests.map(t => ({
            id: `item-${lab.id}-${t.code}`,
            name: t.name,
            descriptor: { timeToReport: t.timeToReport },
            price: { value: t.price, currency: "INR" },
            slots: ["07:30 AM", "09:00 AM", "11:00 AM"]
          }))
        });
      }
    }

    if (filteredLabs.length === 0) return null;

    return {
      search_id: searchId,
      providers: filteredLabs
    };
  }

  return null;
}
