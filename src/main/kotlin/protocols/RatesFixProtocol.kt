package protocols

import co.paralleluniverse.fibers.Suspendable
import core.*
import core.crypto.DigitalSignature
import core.messaging.SingleMessageRecipient
import core.node.services.NodeInfo
import core.protocols.ProtocolLogic
import core.utilities.ProgressTracker
import java.math.BigDecimal
import java.util.*

// This code is unit tested in NodeInterestRates.kt

/**
 * This protocol queries the given oracle for an interest rate fix, and if it is within the given tolerance embeds the
 * fix in the transaction and then proceeds to get the oracle to sign it. Although the [call] method combines the query
 * and signing step, you can run the steps individually by constructing this object and then using the public methods
 * for each step.
 *
 * @throws FixOutOfRange if the returned fix was further away from the expected rate by the given amount.
 */
open class RatesFixProtocol(protected val tx: TransactionBuilder,
                            private val oracle: NodeInfo,
                            private val fixOf: FixOf,
                            private val expectedRate: BigDecimal,
                            private val rateTolerance: BigDecimal) : ProtocolLogic<Unit>() {
    companion object {
        val TOPIC = "platform.rates.interest.fix"

        class QUERYING(val name: String) : ProgressTracker.Step("Querying oracle for $name interest rate")
        object WORKING : ProgressTracker.Step("Working with data returned by oracle")
        object SIGNING : ProgressTracker.Step("Requesting transaction signature from interest rate oracle")
    }

    override val progressTracker = ProgressTracker(QUERYING(fixOf.name), WORKING, SIGNING)

    class FixOutOfRange(val byAmount: BigDecimal) : Exception()

    data class QueryRequest(val queries: List<FixOf>, val replyTo: SingleMessageRecipient, val sessionID: Long)
    data class SignRequest(val tx: WireTransaction, val replyTo: SingleMessageRecipient, val sessionID: Long)

    @Suspendable
    override fun call() {
        progressTracker.currentStep = progressTracker.steps[1]
        val fix = query()
        progressTracker.currentStep = WORKING
        checkFixIsNearExpected(fix)
        tx.addCommand(fix, oracle.identity.owningKey)
        beforeSigning(fix)
        progressTracker.currentStep = SIGNING
        tx.addSignatureUnchecked(sign())
    }

    /**
     * You can override this to perform any additional work needed after the fix is added to the transaction but
     * before it's sent back to the oracle for signing (for example, adding output states that depend on the fix).
     */
    @Suspendable
    protected open fun beforeSigning(fix: Fix) {
    }

    private fun checkFixIsNearExpected(fix: Fix) {
        val delta = (fix.value - expectedRate).abs()
        if (delta > rateTolerance) {
            // TODO: Kick to a user confirmation / ui flow if it's out of bounds instead of raising an exception.
            throw FixOutOfRange(delta)
        }
    }

    @Suspendable
    fun sign(): DigitalSignature.LegallyIdentifiable {
        val sessionID = random63BitValue()
        val wtx = tx.toWireTransaction()
        val req = SignRequest(wtx, serviceHub.networkService.myAddress, sessionID)
        val resp = sendAndReceive<DigitalSignature.LegallyIdentifiable>(TOPIC + ".sign", oracle.address, 0, sessionID, req)

        return resp.validate { sig ->
            check(sig.signer == oracle.identity)
            tx.checkSignature(sig)
            sig
        }
    }

    @Suspendable
    fun query(): Fix {
        val sessionID = random63BitValue()
        val req = QueryRequest(listOf(fixOf), serviceHub.networkService.myAddress, sessionID)
        val resp = sendAndReceive<ArrayList<Fix>>(TOPIC + ".query", oracle.address, 0, sessionID, req)

        return resp.validate {
            val fix = it.first()
            // Check the returned fix is for what we asked for.
            check(fix.of == fixOf)
            fix
        }
    }
}
