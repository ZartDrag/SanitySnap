package com.rupeewise.sanitysnap

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.rupeewise.sanitysnap.TestDevices.device
import com.rupeewise.sanitysnap.TestDevices.sync
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.data.repo.SyncHistoryStore
import com.rupeewise.sanitysnap.data.repo.SyncOutcome
import com.rupeewise.sanitysnap.data.repo.SyncTransport
import com.rupeewise.sanitysnap.domain.ShareInput
import com.rupeewise.sanitysnap.domain.SplitType
import com.rupeewise.sanitysnap.sync.BackupManager
import com.rupeewise.sanitysnap.sync.ChangeSummary
import com.rupeewise.sanitysnap.sync.SettlementRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncHistoryTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun persistAndReloadEntry_thenDeletingHistoryLeavesLogAndBalancesUntouched() = runBlocking {
        val a = device(ctx); val b = device(ctx)
        val ua = a.createProfile("kartik", "").userId
        val ub = b.createProfile("riya", "").userId
        sync(a, b)
        val g = b.createGroup("Goa", listOf(ua))
        b.addExpense(g, "Dinner", 90_000, SplitType.EQUAL, mapOf(ub to 90_000L), listOf(ShareInput(ua), ShareInput(ub)))
        val before = ChangeSummary.capture(a)
        val (r, _) = sync(a, b)
        val summary = ChangeSummary.build(a, before, r.newEvents, r.peer!!.deviceId, "@riya")

        val store = SyncHistoryStore(a.db)
        val id = store.record("s1", ub, "riya", r.peer!!.deviceId, SyncTransport.SIMULATED, SyncOutcome.OK, null, r.sent, r.newEvents.map { it.id }, summary)
        store.addSettlement("s1", SettlementRecord(SyncOutcome.OK, "You paid @riya ₹450.00 overall (co-signed)", "p1"))
        store.record("s2", ub, "riya", r.peer!!.deviceId, SyncTransport.SIMULATED, SyncOutcome.FAILED, "boom", 0, emptyList(), summary.copy(sections = emptyList()), timestamp = System.currentTimeMillis() + 1000)

        // reload: newest first, summary identical, ids + settlement kept
        val all = store.allNow()
        assertEquals(listOf("s2", "s1"), all.map { it.sessionId })
        val e = store.byId(id)!!
        assertEquals(summary, SyncHistoryStore.summary(e))
        assertEquals(r.newEvents.map { it.id }, SyncHistoryStore.receivedIds(e))
        assertEquals("p1", SyncHistoryStore.settlements(e).single().paymentId)
        assertEquals(r.newEvents.size, e.receivedCount)

        // deleting never touches the event log or balances
        val events = a.db.eventDao().allOrdered()
        val owed = a.currentLedger().netOwed(ua, ub)
        assertEquals(45_000L, owed)
        store.delete(id)
        assertNull(store.byId(id))
        assertEquals(1, store.allNow().size)
        store.clear()
        assertTrue(store.allNow().isEmpty())
        assertEquals(events, a.db.eventDao().allOrdered())
        assertEquals(owed, a.currentLedger().netOwed(ua, ub))
        a.projector.rebuild(a.signer.deviceId)
        assertEquals(owed, a.currentLedger().netOwed(ua, ub))

        // backups explicitly skip sync history
        assertEquals(listOf("sync_history"), BackupManager.SKIPPED_TABLES)
    }

    @Test
    fun migration1to2_keepsDataAndAddsSyncHistory() = runBlocking {
        val name = "migration-test.db"
        ctx.deleteDatabase(name)
        // Build a real v1 database from the exported Room schema (app/schemas/.../1.json)
        val schema = Json.parseToJsonElement(File("schemas/com.rupeewise.sanitysnap.data.db.FairShareDatabase/1.json").readText()).jsonObject["database"]!!.jsonObject
        val v1 = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(name).apply { parentFile?.mkdirs() }, null)
        schema["entities"]!!.jsonArray.forEach { ent ->
            val t = ent.jsonObject["tableName"]!!.jsonPrimitive.content
            v1.execSQL(ent.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t))
            ent.jsonObject["indices"]?.jsonArray?.forEach { v1.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", t)) }
        }
        schema["setupQueries"]!!.jsonArray.forEach { v1.execSQL(it.jsonPrimitive.content) }
        v1.execSQL(
            "INSERT INTO events (id, deviceId, seq, lamport, authorUserId, entityType, entityId, op, payload, baseEventId, createdAt, publicKey, signature) " +
                "VALUES ('e1','d1',1,1,'u1','USER','u1','CREATE','{}',NULL,1,'pk','sig')",
        )
        v1.version = 1
        v1.close()

        // Open with the app's builder (real migrations, no destructive fallback). Room validates the schema.
        val db = FairShareDatabase.build(ctx, name)
        assertEquals("e1", db.eventDao().allOrdered().single().id)
        val store = SyncHistoryStore(db)
        store.record("s", null, null, null, SyncTransport.SIMULATED, SyncOutcome.OK, null, 0, emptyList(), com.rupeewise.sanitysnap.sync.SyncSummary("@x", ""))
        assertEquals(1, store.allNow().size)
        assertEquals(2, db.openHelper.readableDatabase.version)
        db.close()
        ctx.deleteDatabase(name)
        Unit
    }
}
