package com.example.medicalscanner.network

import android.content.Context
import com.example.medicalscanner.local.AppSettings
import com.example.medicalscanner.model.ApiKeyRequest
import com.example.medicalscanner.model.ApiKeyResponse
import com.example.medicalscanner.model.AuthRequest
import com.example.medicalscanner.model.AuthResponse
import com.example.medicalscanner.model.KeyAssignment
import com.example.medicalscanner.model.MedicalReport
import com.example.medicalscanner.model.PhoneLoginRequest
import com.example.medicalscanner.model.ReportUpdateRequest
import com.example.medicalscanner.model.SignupRequest
import com.example.medicalscanner.model.UserAccount
import com.example.medicalscanner.model.ResetPasswordRequest
import com.example.medicalscanner.model.ChangePasswordRequest
import com.example.medicalscanner.model.SimpleResponse
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

    // ── Auth / per-user free tier ────────────────────────────────────────────
    @POST("api/auth/signup")
    suspend fun signup(@Body request: SignupRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body request: AuthRequest): AuthResponse

    /** Phone+OTP login: the phone was already OTP-verified client-side via Firebase. */
    @POST("api/auth/login-phone")
    suspend fun loginPhone(@Body request: PhoneLoginRequest): AuthResponse

    @GET("api/auth/me")
    suspend fun getMe(): UserAccount

    /** Asks the backend which Gemini/Sarvam key this account should use right now, and
     *  accounts for today's free-tier usage. Call once per session, not once per scan —
     *  this consumes one of today's free issuances. For just displaying usage (e.g. the
     *  Account screen), use getUsage() instead, which never consumes one. */
    @GET("api/auth/keys")
    suspend fun getAssignedKeys(): KeyAssignment

    /** Read-only usage/quota snapshot — safe to call every time the Account screen opens. */
    @GET("api/auth/usage")
    suspend fun getUsage(): KeyAssignment

    @POST("api/user/gemini-key")
    suspend fun setGeminiKeyOnAccount(@Body request: ApiKeyRequest): ApiKeyResponse

    @POST("api/user/sarvam-key")
    suspend fun setSarvamKeyOnAccount(@Body request: ApiKeyRequest): ApiKeyResponse

    @POST("api/auth/reset-password-otp")
    suspend fun resetPasswordOtp(@Body request: ResetPasswordRequest): SimpleResponse

    @POST("api/auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): SimpleResponse
}

/** Pulls the backend's {"error": "..."} message out of a failed Retrofit call, if present. */
fun Throwable.apiErrorMessage(): String? {
    val body = (this as? retrofit2.HttpException)?.response()?.errorBody()?.string() ?: return null
    return runCatching {
        com.google.gson.JsonParser.parseString(body).asJsonObject.get("error")?.asString
    }.getOrNull()
}

/** HTTP status code of a failed Retrofit call, or null if it wasn't an HTTP error. */
fun Throwable.httpCode(): Int? = (this as? retrofit2.HttpException)?.code()

object NetworkModule {
    // AWS API Gateway URL for the deployed `medical-scanner` SAM stack (region us-east-1).
    // Leave empty to require manual IP config from the app settings screen.
    private const val DEFAULT_SERVER_URL = "https://k6tdi2uzoh.execute-api.us-east-1.amazonaws.com"

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
                    // Bypass ngrok's browser-warning interstitial page for API calls, and attach
                    // the logged-in user's session (if any) — read fresh per-request so login/
                    // logout takes effect immediately without rebuilding the Retrofit client.
                    val builder = chain.request().newBuilder()
                        .addHeader("ngrok-skip-browser-warning", "true")
                    AppSettings.getAuthToken(context)?.let { token ->
                        builder.addHeader("Authorization", "Bearer $token")
                    }
                    chain.proceed(builder.build())
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
