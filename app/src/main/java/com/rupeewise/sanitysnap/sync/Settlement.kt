package com.rupeewise.sanitysnap.sync

import com.rupeewise.sanitysnap.crypto.CryptoUtil
import com.rupeewise.sanitysnap.crypto.DeviceSigner
import com.rupeewise.sanitysnap.data.db.EventEntity
import com.rupeewise.sanitysnap.data.db.FairShareDatabase
import com.rupeewise.sanitysnap.domain.EntityType
import com.rupeewise.sanitysnap.domain.Op
import com.rupeewise.sanitysnap.domain.PaymentPayload
import com.rupeewise.sanitysnap.domain.SettlementProof
import com.rupeewise.sanitysnap.domain.SettlementTerms

/**
 * Crypto + validity rules for synchronous, two-device settlements.
 *
 * Design: ONE `PAYMENT/CREATE` event per settlement. The event envelope is signed by the
 * proposer's device (like every event), and its payload embeds a [SettlementProof]: the agreed
 * [SettlementTerms] co-signed by BOTH users' device keys. Because both signatures travel inside a
 * single, immutable event, any device that has the event (including third parties that receive it
 * later by gossip) has the complete settlement. There is never a half-settled state in any log.
 * A PAYMENT without a valid proof is ignored by the [Projector].
 */
object Settlement {

    /** Length-prefixed canonical encoding (same scheme as [EventLog.canonicalBytes]). */
    fun canonicalBytes(t: SettlementTerms): ByteArray {
        val fields = listOf(
            "settle-v1", t.settlementId, t.groupId ?: "", t.fromUserId, t.toUserId, t.amountMinor.toString(),
            t.currency, t.balanceHash, t.proposerUserId, t.accepterUserId, t.proposedAt.toString(),
        )
        return fields.joinToString("") { "${it.length}:$it;" }.toByteArray(Charsets.UTF_8)
    }

    fun sign(signer: DeviceSigner, t: SettlementTerms): String = CryptoUtil.b64(signer.sign(canonicalBytes(t)))

    fun verifyTermsSignature(publicKeyB64: String, t: SettlementTerms, signatureB64: String): Boolean =
        CryptoUtil.verify(publicKeyB64, canonicalBytes(t), signatureB64)

    /** Structural + cryptographic checks that don't need the database. */
    fun proofProblem(e: EventEntity, p: PaymentPayload): String? {
        if (e.entityType != EntityType.PAYMENT || e.op != Op.CREATE) return "not a payment create"
        val proof = p.settlement ?: return "missing two-party settlement proof"
        val t = proof.terms
        if (t.settlementId != p.id || e.entityId != p.id) return "id mismatch"
        if (t.groupId != p.groupId || t.fromUserId != p.fromUserId || t.toUserId != p.toUserId ||
            t.amountMinor != p.amountMinor || t.currency != p.currency
        ) return "terms do not match payment"
        if (t.amountMinor <= 0 || t.fromUserId == t.toUserId) return "invalid amount or parties"
        if (setOf(t.proposerUserId, t.accepterUserId) != setOf(t.fromUserId, t.toUserId)) return "signers are not payer and payee"
        if (e.authorUserId != t.proposerUserId || e.publicKey != proof.proposerPublicKey) return "event not authored by proposer device"
        if (proof.proposerPublicKey == proof.accepterPublicKey) return "both signatures from one device"
        if (!verifyTermsSignature(proof.proposerPublicKey, t, proof.proposerSignature)) return "bad proposer signature"
        if (!verifyTermsSignature(proof.accepterPublicKey, t, proof.accepterSignature)) return "bad accepter signature"
        return null
    }

    /**
     * Full validity: [proofProblem] plus "the accepter key is a known device of the accepter user"
     * (it has authored at least one event under that userId in the log, e.g. their USER/CREATE).
     * TODO(identity): replace with DEVICE_LINK verification once device-link events exist.
     */
    suspend fun isValid(db: FairShareDatabase, e: EventEntity, p: PaymentPayload): Boolean {
        if (proofProblem(e, p) != null) return false
        val proof: SettlementProof = p.settlement!!
        return db.eventDao().countByKeyAndAuthor(proof.accepterPublicKey, proof.terms.accepterUserId) > 0
    }
}
