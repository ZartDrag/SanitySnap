package com.rupeewise.sanitysnap

import android.app.Application
import android.content.Context
import com.rupeewise.sanitysnap.crypto.KeystoreSigner
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.repo.FairShareRepository
import com.rupeewise.sanitysnap.sync.BackupManager
import com.rupeewise.sanitysnap.sync.NearbyTransport
import com.rupeewise.sanitysnap.sync.NoOpNearbyTransport
import com.rupeewise.sanitysnap.sync.SyncController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class FairShareApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Tiny manual DI container (no Hilt in V1 to keep the scaffold lean). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: FairShareDatabase = FairShareDatabase.build(appContext, "fairshare.db")
    val signer = KeystoreSigner()
    val repo = FairShareRepository(db, signer)
    val backup = BackupManager(repo)

    /** Real proximity transport — stubbed in V1. TODO(nearby): swap for NearbyConnectionsTransport. */
    val nearby: NearbyTransport = NoOpNearbyTransport()

    val sync = SyncController(appContext, repo, appScope)
}
