package com.example.medicalscanner

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.medicalscanner.ui.ChatScreen
import com.example.medicalscanner.ui.CompareScreen
import com.example.medicalscanner.ui.DetailedAnalysisScreen
import com.example.medicalscanner.ui.IPConfigScreen
import com.example.medicalscanner.ui.ReportDetailScreen
import com.example.medicalscanner.ui.ReportListScreen
import com.example.medicalscanner.ui.ScanScreen
import com.example.medicalscanner.ui.TrendsScreen

@Composable
fun MainNavigation() {
  val backStack = rememberNavBackStack(Main)
  var ipReturnKey by remember { mutableIntStateOf(0) }

  NavDisplay(
    backStack = backStack,
    onBack = { backStack.removeLastOrNull() },
    entryProvider =
      entryProvider {
        entry<Main> {
          ReportListScreen(
            onNavigateToScan = { backStack.add(Scan) },
            onNavigateToDetail = { reportId -> backStack.add(ReportDetail(reportId)) },
            onNavigateToSettings = { backStack.add(IPConfig) },
            onNavigateToCompare = { backStack.add(Compare) },
            onNavigateToChat = { backStack.add(Chat) },
            onNavigateToTrends = { backStack.add(Trends) },
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
