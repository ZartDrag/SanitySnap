# SanitySnap

A server-less, Splitwise-style expense splitter for Android. Data lives **only on the phone**
(Room/SQLite) and moves between phones through **manual, proximate peer-to-peer sync** of an
append-only, signed event log. V1 has no internet permission at all.

- Application id: `com.rupeewise.sanitysnap`
- Min SDK 26 · target/compile SDK 35 · Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.11.1 (wrapper)
- Jetpack Compose (BOM 2024.12.01, Material 3) · Navigation Compose 2.8.5 · Room 2.6.1 (KSP)
- kotlinx.serialization for payloads, the wire protocol and backups

## Build & run

```bash
# Prerequisites: JDK 17+ (21 works), Android SDK with platforms;android-35 + build-tools;35.0.0
echo "sdk.dir=/path/to/Android/Sdk" > local.properties   # Android Studio writes this for you
./gradlew :app:assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM + Robolectric tests (split math, full sync flow)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Android Studio:** *File → Open…* → pick the `fairshare/` folder (the one holding
`settings.gradle.kts`). Let Gradle sync, then run the `app` configuration on a device or emulator
(API 26+). Any recent Studio (Ladybug or newer) handles AGP 8.7.

## Architecture

```
com.rupeewise.sanitysnap
├── FairShareApp.kt          Application + AppContainer (manual DI)
├── MainActivity.kt          Compose NavHost (all routes)
├── crypto/
│   ├── DeviceSigner.kt      DeviceSigner interface; KeystoreSigner (EC P-256 in Android Keystore);
│   │                        SoftwareSigner (simulated peers/tests only); CryptoUtil (verify, ids)
│   └── BackupCrypto.kt      PBKDF2-HMAC-SHA256 (210k) → AES-256-GCM envelope
├── data/
│   ├── db/                  Room entities, DAOs, FairShareDatabase
│   └── repo/                FairShareRepository — every write = signed event + projection, atomically
├── domain/                  Money, SplitCalculator (equal/exact/percent/shares), BalanceCalculator,
│                            event types, ops and serializable payloads
├── sync/
│   ├── EventLog.kt          append-only log: per-device seq, Lamport clock, signing, verification
│   ├── Projector.kt         replays the log into Room tables + deterministic conflict detection
│   ├── SyncEngine.kt        symmetric gossip-merge protocol (HELLO/CONFIRM/EVENTS/DONE)
│   ├── Transport.kt         SyncChannel + NearbyTransport interfaces, NoOpNearbyTransport (stub),
│   │                        InMemoryChannel (fake transport)
│   ├── LiveSession.kt       stays connected after a sync: in-session re-sync + two-phone settle-up
│   ├── Settlement.kt        co-signed settlement terms + PAYMENT validity rules
│   ├── SyncController.kt    UI orchestration + in-app simulated peer devices (B, C)
│   ├── ChangeSummary.kt     post-sync "what changed" summary (serializable, stored in sync history)
│   └── BackupManager.kt     encrypted export/import of the whole log
└── ui/                      Compose screens: Onboarding, Home, Friends, Groups, GroupDetail,
                             AddExpense (add/edit), Balances, Sync, Conflicts, Challenges, Backup
```

### Room schema (`app/schemas/.../1.json`)
`local_profile` (this device's identity, never synced) · `users` · `friendships` ·
`expense_groups` · `group_members` · `expenses` · `expense_payers` · `expense_shares` ·
`payments` · `challenges` · `events` · `entity_heads` · `conflicts`.
Money is `Long` minor units (paise) and every money row carries `currency` (default `INR`).
The table is `expense_groups` rather than `groups` because `GROUPS` is an SQL keyword.

### Event sourcing, gossip and conflicts
- **The event log is the source of truth.** All other tables are projections that
  `Projector.rebuild()` can recreate exactly by replaying events in `(lamport, deviceId, seq)` order.
- Every event is signed (SHA256withECDSA) by the device key. `deviceId = sha256(publicKey)[0..16]`,
  so nobody can post under another device's id with a different key. Tampered or forged events are
  dropped at import.
- **Gossip / transitive sync:** peers swap version vectors (`deviceId → max seq`) and each sends
  every event the other lacks, *including events written by third devices*. So A→B→C works without
  A and C ever meeting (covered by `SyncIntegrationTest`).
- **Git-style conflicts:** each UPDATE/DELETE/RESOLVE records `baseEventId` (the entity head it
  was based on). Two events on the same entity with the same base but different states mean the
  histories diverged. Every device derives the same conflict (the id is a hash of both event ids),
  shows a provisional last-writer-wins state, and lists it under **Conflicts** with a field-level
  diff. Choosing *Keep mine / Keep theirs* writes a signed `RESOLVE` merge event that syncs everywhere
  and closes the conflict on every device.
- **Challenges:** anyone can challenge an expense (from the group screen or the post-sync summary).
  While a challenge is OPEN, settling in that group is blocked. For non-group (overall) settlements,
  settling between the people involved is blocked. A challenge closes when it is marked resolved or withdrawn.
- **Conflicts keep coming back until agreed:** if both people pick *Keep mine*, the two RESOLVE
  events conflict with each other on the next sync (the card says "You and @x resolved this
  differently"). A modal reminder on Home (on app open and after every sync) and on the Sync screen
  (after every sync) shows the number of open conflicts with **Resolve** / **Later**.

### Settling up (synchronous, two phones)
You can't record a payment offline. Settling up only works **while both phones are connected in a
live sync session** (Sync screen → connect & sync → **Settle up with @x**). Everywhere else the
button is disabled with "Connect with @x to settle up".
1. Both phones run a full sync inside the session, so their logs and balances match.
2. The proposer sends `settle_propose`: payer, payee, amount, group (or overall) and a SHA-256
   **balance hash** (pairwise net + every contributing expense id@head and payment), signed by its device.
3. The other phone recomputes the hash and checks the rules. If they pass, its user sees a
   confirmation dialog. Accepting co-signs the same terms (`settle_accept`).
4. The proposer builds **one PAYMENT event** whose payload carries both co-signatures
   (`settle_final`). The peer verifies it (`settle_ack`), the proposer commits it and sends `settle_commit`,
   and then the peer commits the same event. Nothing is written before this step, so if the
   connection drops earlier, nothing is recorded on either phone.
- **Blocked when:** there's an OPEN conflict on an expense or payment between the two people (or in
  that group), there's an OPEN challenge, or the balance hashes differ.
- **Why one event:** a third phone that gets it later by gossip always gets both signatures at once.
  The projector counts a PAYMENT only if it has a valid two-party proof, and the accepter key must
  have authored events for that user. One-sided or legacy payments are ignored.
- Messages are `SyncMessage` subtypes (`session_sync…`, `settle_*`, `bye`) carried over any
  `SyncChannel`, so the future Nearby transport carries them unchanged.

### Sync summary and history
- After every sync (including an in-session re-sync or the re-sync before a settle-up, when it brings
  anything in), the Sync screen opens a **"What changed in this sync"** sheet, grouped by group or
  friend. It covers new/edited/deleted expenses (edits as a field-level `old → new` diff with the edit
  highlighted), settlements, new people/friends/groups/members, challenges raised or closed, new
  conflicts (with a link) and your net balance change per friend. Each item says who made it and on
  which device, and is tagged **Relayed** when it came through the peer from another device. Expense items
  have **Challenge**, or show the open challenge instead. If nothing came in, it says "Already up to date".
- **Sync history** (Home → *Sync history*, or Sync → *Sync history*) lists every run, newest first.
  Each row shows the peer, device, transport, outcome, counts and any settlement. Tap a row to reopen
  its summary. Use the trash icon to delete one record or *Clear all* for everything; both ask to confirm. This only removes the
  record. It never touches the event log or balances.
- Stored in the local `sync_history` table (DB v2, migration `1→2`) as the **rendered summary JSON**
  plus the received event ids. That way a summary reopens exactly as it was shown, even after later
  events change projections or delete the expense.
- **Backups skip sync history** (`BackupManager.SKIPPED_TABLES`). It is per-device metadata, and the
  event log, which is backed up, is the account's state.

### Identity
- *New user:* username + display name. A keypair is generated in the Android Keystore and the
  user gets a random `userId` (UUID).
- *Existing user:* (a) **import an encrypted backup**, or (b) **sync with my other device**
  (`SyncMode.ADOPT_PEER_IDENTITY`). Both people confirm, and the whole log is merged. The new phone
  keeps its **own** device key (Keystore keys can't be exported) but adopts the account's `userId`
  and username.

## Trying it without a second phone
Open **Sync now**. The app hosts two **simulated peer phones (Device B, Device C)**, each with its
own Room DB and its own key. They run the exact same `SyncEngine` over an in-memory channel.
1. Create a profile on Device B (e.g. `riya`) → **Sync now with Device B** → accept the
   fingerprint dialog → Riya appears in Friends.
2. **Peer adds expense** → Sync → see the change summary, then challenge from there if you like.
3. **Divergent edit** → Sync → open **Conflicts** → keep mine/theirs.
4. Create a profile on Device C, then **Gossip B ⇄ C** to watch events pass along transitively.
4b. While connected to B: **Settle up with @riya** → a dialog labelled "[Simulated Device B]" asks
   on B's behalf (Accept/Decline as peer). **Simulate: @riya proposes** makes B the proposer, and
   this phone shows the confirmation. The **Simulate connection drop** switch cuts the link right before
   the final step, and nothing is recorded.
5. Fresh install → *I already use FairShare* → *Sync with my other device* → "Make this my old
   phone" → **Sync & restore**. This exercises the full-merge onboarding.

## Choices made (decided without asking)
- **Stack:** AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.11.1, which are stable and avoid AGP 9. JVM target 17.
- **No Hilt or ViewModels yet:** a small `AppContainer` passed through a `CompositionLocal`, and
  screens collect Room `Flow`s directly. Long-lived sync state is held in `SyncController`.
- **Full snapshots in payloads:** every CREATE/UPDATE/DELETE/RESOLVE carries the whole entity.
  That makes conflicts and merges simple, at the cost of slightly larger events.
- **Rebuild projections after each sync:** this is simple and deterministic. It is fine for V1 data
  sizes; switch to incremental apply plus snapshots if logs get large.
- **Pairwise balances:** each participant's share is owed to the payers in proportion to what they
  paid. There is no debt simplification, by design.
- **Manual friend add** makes an *unverified placeholder* user. A friend met through sync is a
  verified user with a public key.
- **Backup format:** a JSON envelope (`format: fairshare-backup`) containing the AES-GCM-encrypted
  signed event log plus the account id. Import **merges** and never overwrites.

## Known V1 limits / TODOs
- **Real proximity transport is stubbed** (`NoOpNearbyTransport`, look for `TODO(nearby)`). Plan:
  Nearby Connections `P2P_POINT_TO_POINT` with the auth token shown on both phones as the peer
  confirmation. A no-Play-Services fallback would use Wi-Fi Direct or a hotspot socket with a QR
  carrying ip:port + public key. Permissions are still to be added to the manifest.
- No QR friend-add yet (`TODO(qr)`). Placeholder users are **not** merged automatically with
  the real user after a sync (a username match will need a merge/alias event).
- No device-link proof: peers trust an event's `authorUserId` (`TODO(identity)`). Multi-device
  accounts should add DEVICE_LINK events signed by an existing device.
- Conflict resolution picks a whole version. Per-field merge is a TODO. ADD_MEMBER and
  FRIENDSHIP are additive and never conflict.
- The UI exposes one payer per expense; the data model, sync and balances already support several.
- No expense comments, categories, receipts or multi-currency conversion. The currency is stored
  (default INR), but the UI does not let you change it.
- Settle-up commit is two-step: if the link drops after the proposer committed but before the peer
  got `settle_commit`, the peer catches up on the next sync (the event is complete and valid).
  Leaving the Sync screen ends the live session.
- Room uses explicit migrations only (`FairShareDatabase.MIGRATIONS`, no destructive fallback). Every schema
  bump needs a migration and a test, like `SyncHistoryTest.migration1to2_…`.
- Out of scope for V1 (by decision): simplify debts, recurring expenses, OCR, internet/cloud sync,
  payment rails, charts, Google Drive backup.
