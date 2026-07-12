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
