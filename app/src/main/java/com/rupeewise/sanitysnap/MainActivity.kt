package com.rupeewise.sanitysnap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rupeewise.sanitysnap.ui.LocalContainer
import com.rupeewise.sanitysnap.ui.screens.AddExpenseScreen
import com.rupeewise.sanitysnap.ui.screens.BackupScreen
import com.rupeewise.sanitysnap.ui.screens.BalancesScreen
import com.rupeewise.sanitysnap.ui.screens.ChallengeScreen
import com.rupeewise.sanitysnap.ui.screens.ConflictResolutionScreen
import com.rupeewise.sanitysnap.ui.screens.FriendsScreen
import com.rupeewise.sanitysnap.ui.screens.GroupDetailScreen
import com.rupeewise.sanitysnap.ui.screens.GroupsScreen
import com.rupeewise.sanitysnap.ui.screens.HomeScreen
import com.rupeewise.sanitysnap.ui.screens.OnboardingScreen
import com.rupeewise.sanitysnap.ui.screens.SyncHistoryDetailScreen
import com.rupeewise.sanitysnap.ui.screens.SyncHistoryScreen
import com.rupeewise.sanitysnap.ui.screens.SyncScreen
import com.rupeewise.sanitysnap.ui.theme.FairShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as FairShareApp).container
        setContent {
            FairShareTheme {
                CompositionLocalProvider(LocalContainer provides container) {
                    Surface(Modifier.fillMaxSize()) { Root(container) }
                }
            }
        }
    }
}

@Composable
private fun Root(container: AppContainer) {
    // null = still loading; then true/false for "has profile".
    val hasProfile by produceState<Boolean?>(null) { value = container.repo.getProfile() != null }
    when (val hp = hasProfile) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> AppNav(rememberNavController(), if (hp) Routes.HOME else Routes.ONBOARDING)
    }
}

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
}

@Composable
private fun AppNav(nav: NavHostController, start: String) {
    fun goHomeClearingOnboarding() = nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(navController = nav, startDestination = start) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onCreated = ::goHomeClearingOnboarding,
                onImportBackup = { nav.navigate("backup?onboarding=true") },
                onSyncWithDevice = { nav.navigate("sync?adopt=true") },
            )
        }
        composable(Routes.HOME) { HomeScreen(navigate = { nav.navigate(it) }) }
        composable("friends") { FriendsScreen(onBack = back) }
        composable("groups") { GroupsScreen(onBack = back, openGroup = { nav.navigate("group/$it") }) }
        composable("group/{groupId}", arguments = listOf(navArgument("groupId") { type = NavType.StringType })) { entry ->
            val gid = entry.arguments?.getString("groupId")!!
            GroupDetailScreen(
                groupId = gid, onBack = back,
                addExpense = { nav.navigate("addExpense?groupId=$gid") },
                editExpense = { nav.navigate("addExpense?groupId=$gid&expenseId=$it") },
                openSync = { nav.navigate("sync") },
            )
        }
        composable(
            "addExpense?groupId={groupId}&expenseId={expenseId}",
            arguments = listOf(
                navArgument("groupId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("expenseId") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            AddExpenseScreen(
                groupId = entry.arguments?.getString("groupId"),
                expenseId = entry.arguments?.getString("expenseId"),
                onDone = back,
            )
        }
        composable("balances") { BalancesScreen(onBack = back, openSync = { nav.navigate("sync") }) }
        composable(
            "sync?adopt={adopt}",
            arguments = listOf(navArgument("adopt") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            SyncScreen(
                adoptMode = entry.arguments?.getBoolean("adopt") ?: false,
                onBack = back,
                onFinishedOnboarding = ::goHomeClearingOnboarding,
                openConflicts = { nav.navigate("conflicts") },
                openHistory = { nav.navigate("history") },
            )
        }
        composable("history") { SyncHistoryScreen(onBack = back, open = { nav.navigate("history/$it") }) }
        composable("history/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            SyncHistoryDetailScreen(entry.arguments?.getString("id")!!, onBack = back, openConflicts = { nav.navigate("conflicts") })
        }
        composable("conflicts") { ConflictResolutionScreen(onBack = back) }
        composable("challenges") { ChallengeScreen(onBack = back) }
        composable(
            "backup?onboarding={onboarding}",
            arguments = listOf(navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            BackupScreen(
                onboarding = entry.arguments?.getBoolean("onboarding") ?: false,
                onBack = back,
                onRestored = ::goHomeClearingOnboarding,
            )
        }
    }
}
