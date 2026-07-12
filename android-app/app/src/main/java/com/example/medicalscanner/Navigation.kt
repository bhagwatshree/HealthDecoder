package com.example.medicalscanner

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.example.medicalscanner.network.NetworkModule
import com.example.medicalscanner.network.httpCode
import com.example.medicalscanner.ui.AccountScreen
import com.example.medicalscanner.ui.ChatScreen
import com.example.medicalscanner.ui.CompareScreen
import com.example.medicalscanner.ui.DetailedAnalysisScreen
import com.example.medicalscanner.ui.IPConfigScreen
import com.example.medicalscanner.ui.LoginScreen
import com.example.medicalscanner.ui.RegisterScreen
import com.example.medicalscanner.ui.ReportDetailScreen
import com.example.medicalscanner.ui.ReportListScreen
import com.example.medicalscanner.ui.ScanScreen
import com.example.medicalscanner.ui.TrendsScreen

@Composable
fun MainNavigation() {
  val context = LocalContext.current
  val startKey = remember { if (AppSettings.isLoggedIn(context)) Main else Login }
  val backStack = rememberNavBackStack(startKey)
  var ipReturnKey by remember { mutableIntStateOf(0) }

  // A stored token can be stale (e.g. the account was deleted server-side). Validate it once
  // per launch; on 401 wipe the session and force a fresh login. Network failures are ignored
  // so the app still opens offline.
  var showDisclaimer by remember { mutableStateOf(!AppSettings.isDisclaimerAccepted(context)) }

  if (showDisclaimer) {
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
                  Text("Medical Disclaimer", fontWeight = FontWeight.Bold)
              }
          },
          text = {
              Column(
                  modifier = Modifier
                      .fillMaxWidth()
                      .verticalScroll(rememberScrollState()),
                  verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                  Text(
                      text = "Please read and accept this disclaimer before using Medical Assist:",
                      fontWeight = FontWeight.SemiBold,
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Text(
                      text = "1. Not Medical Advice: This application provides automated analysis and summaries of medical records using artificial intelligence. It does NOT provide medical advice, diagnosis, treatment, or clinical recommendations.",
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Text(
                      text = "2. Not a Medical Device: Medical Assist is not a certified medical device. The information shown is for informational and educational purposes only.",
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Text(
                      text = "3. Always Consult a Professional: You must always consult a qualified physician or healthcare provider before making any healthcare decisions or changing your medications. Never ignore professional medical advice because of something you read in this app.",
                      style = MaterialTheme.typography.bodyMedium
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                      text = "By clicking 'Accept and Continue', you acknowledge that you have read, understood, and agree to these terms.",
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
                  Text("Accept and Continue")
              }
          }
      )
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
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Main> {
          ReportListScreen(
            onNavigateToScan = { backStack.add(Scan) },
            onNavigateToDetail = { reportId -> backStack.add(ReportDetail(reportId)) },
            onNavigateToSettings = { backStack.add(IPConfig) },
            onNavigateToCompare = { backStack.add(Compare) },
            onNavigateToChat = { backStack.add(Chat) },
            onNavigateToTrends = { backStack.add(Trends) },
            onNavigateToAccount = { backStack.add(Account) },
            modifier = Modifier.safeDrawingPadding(),
            reloadKey = ipReturnKey
          )
        }
        entry<Trends> {
          TrendsScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            onNavigateToReport = { reportId -> backStack.add(ReportDetail(reportId)) },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<IPConfig> {
          IPConfigScreen(
            onNavigateBack = {
              ipReturnKey++
              backStack.removeLastOrNull()
            },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Compare> {
          CompareScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
          )
        }
        entry<Chat> {
          ChatScreen(
            onNavigateBack = { backStack.removeLastOrNull() },
            modifier = Modifier.safeDrawingPadding()
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
