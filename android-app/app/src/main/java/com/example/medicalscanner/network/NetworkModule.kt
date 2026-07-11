package com.example.medicalscanner.network

import android.content.Context
import com.example.medicalscanner.model.MedicalReport
import com.example.medicalscanner.model.ReportUpdateRequest
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.google.gson.GsonBuilder
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

data class HealthResponse(
    val status: String,
    val timestamp: String
)

interface MedicalScannerApi {
    @GET("api/health")
    suspend fun checkHealth(): HealthResponse

    @GET("api/reports")
    suspend fun getReports(): List<MedicalReport>

    @DELETE("api/data/all")
    suspend fun deleteAllData(): Map<String, String>

    @GET("api/reports/{id}")
    suspend fun getReportDetails(@Path("id") id: String): MedicalReport

    @GET("api/reports/{id}/detailed-analysis")
    suspend fun getDetailedAnalysis(
        @Path("id") id: String,
        @Query("refresh") refresh: Boolean = false
    ): com.example.medicalscanner.model.DetailedAnalysis

    @Multipart
    @POST("api/reports")
    suspend fun uploadReport(
        @Part image: MultipartBody.Part,
        @Part("use_sarvam") useSarvam: okhttp3.RequestBody,
        @Part("local_ocr_text") localOcrText: okhttp3.RequestBody?,
        @Part("scan_type") scanType: okhttp3.RequestBody?,
        @Part("report_category") reportCategory: okhttp3.RequestBody?
    ): MedicalReport

    @PUT("api/reports/{id}")
    suspend fun updateReport(
        @Path("id") id: String,
        @Body request: ReportUpdateRequest
    ): MedicalReport

    @DELETE("api/reports/{id}")
    suspend fun deleteReport(@Path("id") id: String): Map<String, String>

    @GET("api/dashboard")
    suspend fun getDashboard(
        @Query("period") period: String? = null
    ): com.example.medicalscanner.model.DashboardData

    @GET("api/health-summary")
    suspend fun getHealthSummary(
        @Query("patient_name") patientName: String,
        @Query("period") period: String? = null
    ): com.example.medicalscanner.model.HealthSummary

    @POST("api/pending-tests")
    suspend fun createPendingTest(
        @Body request: Map<String, String>
    ): com.example.medicalscanner.model.PendingTest

    @DELETE("api/pending-tests/{id}")
    suspend fun deletePendingTest(
        @Path("id") id: String
    ): Map<String, String>

    @POST("api/medications/log")
    suspend fun logMedicationIntake(
        @Body request: Map<String, String>
    ): Map<String, Any>

    @GET("api/medications/log")
    suspend fun getMedicationLogs(
        @Query("patientName") patientName: String,
        @Query("medicineName") medicineName: String
    ): List<Map<String, Any>>

    @POST("api/medications/update")
    suspend fun updateMedicationDetails(
        @Body request: Map<String, Any>
    ): Map<String, Any>

    @POST("api/medications/bulk-delete")
    suspend fun bulkDeleteMedications(
        @Body request: com.example.medicalscanner.model.MedicationBulkRequest
    ): Map<String, Any>

    @POST("api/medications/bulk-update")
    suspend fun bulkUpdateMedications(
        @Body request: com.example.medicalscanner.model.MedicationBulkRequest
    ): Map<String, Any>

    @POST("api/chat")
    suspend fun chat(
        @Body request: com.example.medicalscanner.model.ChatRequest
    ): com.example.medicalscanner.model.ChatResponse

    @POST("api/tts")
    suspend fun getSpeech(
        @Body request: com.example.medicalscanner.model.TtsRequest
    ): com.example.medicalscanner.model.TtsResponse

    @Multipart
    @POST("api/compare")
    suspend fun compareReports(
        @Part report1: MultipartBody.Part,
        @Part report2: MultipartBody.Part,
        @Part("scan_type_1") scanType1: okhttp3.RequestBody,
        @Part("scan_type_2") scanType2: okhttp3.RequestBody,
        @Part("report_category_1") category1: okhttp3.RequestBody,
        @Part("report_category_2") category2: okhttp3.RequestBody
    ): com.example.medicalscanner.model.CompareResponse
}

object NetworkModule {
    // Replace with your permanent ngrok/cloudflare URL once set up.
    // Leave empty to require manual IP config from the app settings screen.
    private const val DEFAULT_SERVER_URL = "https://oppressed-matter-gusto.ngrok-free.dev"

    private var currentRetrofit: Retrofit? = null
    private var currentIp: String? = null

    /**
     * Retrieves the server IP from SharedPreferences.
     */
    fun getServerIp(context: Context): String {
        val sharedPref = context.getSharedPreferences("medical_scanner_prefs", Context.MODE_PRIVATE)
        return sharedPref.getString("server_ip", "") ?: ""
    }

    /**
     * Saves the server IP to SharedPreferences.
     */
    fun saveServerIp(context: Context, ip: String) {
        val sharedPref = context.getSharedPreferences("medical_scanner_prefs", Context.MODE_PRIVATE)
        sharedPref.edit().putString("server_ip", ip.trim()).apply()
    }

    /**
     * Builds and caches the Retrofit API service. If the server IP changes,
     * the client is automatically rebuilt.
     */
    fun getApi(context: Context): MedicalScannerApi {
        val savedIp = getServerIp(context)
        // DEFAULT_SERVER_URL (hardcoded domain) wins unless the user has saved a different override
        val ip = when {
            DEFAULT_SERVER_URL.isNotEmpty() && (savedIp.isEmpty() || savedIp == DEFAULT_SERVER_URL) -> DEFAULT_SERVER_URL
            savedIp.isNotEmpty() -> savedIp
            else -> "10.0.2.2"
        }
        
        if (currentRetrofit == null || currentIp != ip) {
            currentIp = ip
            
            // Format base URL
            val baseUrl = when {
                ip.startsWith("http://") || ip.startsWith("https://") -> {
                    if (ip.endsWith("/")) ip else "$ip/"
                }
                ip.contains(":") -> {
                    "http://$ip/"
                }
                else -> {
                    "http://$ip:3000/" // Default to port 3000 if no port specified
                }
            }

            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }

            val client = OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS) // OCR can take time
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    // Bypass ngrok's browser-warning interstitial page for API calls
                    val request = chain.request().newBuilder()
                        .addHeader("ngrok-skip-browser-warning", "true")
                        .build()
                    chain.proceed(request)
                }
                .addInterceptor(logging)
                .build()

            currentRetrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
                .client(client)
                .build()
        }
        
        return currentRetrofit!!.create(MedicalScannerApi::class.java)
    }

    /**
     * Formats the image path returned by the server to be fully qualified,
     * so it can be loaded by Coil.
     */
    fun getFullImageUrl(context: Context, relativePath: String): String {
        val savedIp = getServerIp(context)
        val ip = when {
            DEFAULT_SERVER_URL.isNotEmpty() && (savedIp.isEmpty() || savedIp == DEFAULT_SERVER_URL) -> DEFAULT_SERVER_URL
            savedIp.isNotEmpty() -> savedIp
            else -> "10.0.2.2"
        }
        
        val baseUrl = when {
            ip.startsWith("http://") || ip.startsWith("https://") -> {
                if (ip.endsWith("/")) ip.substring(0, ip.length - 1) else ip
            }
            ip.contains(":") -> {
                "http://$ip"
            }
            else -> {
                "http://$ip:3000"
            }
        }
        
        // Ensure relative path starts with /
        val path = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
        return "$baseUrl$path"
    }
}
