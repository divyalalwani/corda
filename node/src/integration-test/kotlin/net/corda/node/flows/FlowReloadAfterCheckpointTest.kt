package net.corda.node.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.HospitalizeFlowException
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.Party
import net.corda.core.internal.IdempotentFlow
import net.corda.core.internal.concurrent.transpose
import net.corda.core.messaging.StateMachineTransactionMapping
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import net.corda.core.utilities.seconds
import net.corda.core.utilities.unwrap
import net.corda.finance.DOLLARS
import net.corda.finance.flows.CashIssueAndPaymentFlow
import net.corda.node.services.config.NodeConfiguration
import net.corda.node.services.statemachine.FlowStateMachineImpl
import net.corda.node.services.statemachine.StaffedFlowHospital
import net.corda.testing.core.ALICE_NAME
import net.corda.testing.core.BOB_NAME
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.internal.FINANCE_CORDAPPS
import net.corda.testing.node.internal.enclosedCordapp
import org.junit.Test
import java.util.concurrent.Semaphore
import kotlin.test.assertEquals

class FlowReloadAfterCheckpointTest {

    private companion object {
        val cordapps = listOf(enclosedCordapp())
    }

    @Test(timeout = 300_000)
    fun `flow will reload from its checkpoint after suspending when reloadCheckpointAfterSuspend is true`() {
        val reloadCounts = mutableMapOf<StateMachineRunId, Int>()
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { id ->
            reloadCounts.compute(id) { _, value -> value?.plus(1) ?: 1 }
        }
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList(), cordappsForAllNodes = cordapps)) {

            val (alice, bob) = listOf(ALICE_NAME, BOB_NAME)
                .map {
                    startNode(
                        providedName = it,
                        customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
                    )
                }
                .transpose()
                .getOrThrow()

            val handle = alice.rpc.startFlow(::ReloadFromCheckpointFlow, bob.nodeInfo.singleIdentity(), false, false, false)
            val flowStartedByAlice = handle.id
            handle.returnValue.getOrThrow()
            assertEquals(5, reloadCounts[flowStartedByAlice])
            assertEquals(6, reloadCounts[ReloadFromCheckpointResponder.flowId])
        }
    }

    @Test(timeout = 300_000)
    fun `flow will not reload from its checkpoint after suspending when reloadCheckpointAfterSuspend is false`() {
        val reloadCounts = mutableMapOf<StateMachineRunId, Int>()
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { id ->
            reloadCounts.compute(id) { _, value -> value?.plus(1) ?: 1 }
        }
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList(), cordappsForAllNodes = cordapps)) {

            val (alice, bob) = listOf(ALICE_NAME, BOB_NAME)
                .map {
                    startNode(
                        providedName = it,
                        customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to false)
                    )
                }
                .transpose()
                .getOrThrow()

            val handle = alice.rpc.startFlow(::ReloadFromCheckpointFlow, bob.nodeInfo.singleIdentity(), false, false, false)
            val flowStartedByAlice = handle.id
            handle.returnValue.getOrThrow()
            assertEquals(null, reloadCounts[flowStartedByAlice])
            assertEquals(null, reloadCounts[ReloadFromCheckpointResponder.flowId])
        }
    }

    @Test(timeout = 300_000)
    fun `flow will reload from its checkpoint after suspending when reloadCheckpointAfterSuspend is true and be kept for observation due to failed deserialization`() {
        val reloadCounts = mutableMapOf<StateMachineRunId, Int>()
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { id ->
            reloadCounts.compute(id) { _, value -> value?.plus(1) ?: 1 }
        }
        lateinit var flowKeptForObservation: StateMachineRunId
        val lock = Semaphore(0)
        StaffedFlowHospital.onFlowKeptForOvernightObservation.add { id, _ ->
            flowKeptForObservation = id
            lock.release()
        }
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList(), cordappsForAllNodes = cordapps)) {

            val (alice, bob) = listOf(ALICE_NAME, BOB_NAME)
                .map {
                    startNode(
                        providedName = it,
                        customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
                    )
                }
                .transpose()
                .getOrThrow()

            val handle = alice.rpc.startFlow(::ReloadFromCheckpointFlow, bob.nodeInfo.singleIdentity(), true, false, false)
            val flowStartedByAlice = handle.id
            lock.acquire()
            assertEquals(flowStartedByAlice, flowKeptForObservation)
            assertEquals(4, reloadCounts[flowStartedByAlice])
            assertEquals(4, reloadCounts[ReloadFromCheckpointResponder.flowId])
        }
    }

    @Test(timeout = 300_000)
    fun `flow will reload from a previous checkpoint after calling suspending function and skipping the persisting the current checkpoint when reloadCheckpointAfterSuspend is true`() {
        val reloadCounts = mutableMapOf<StateMachineRunId, Int>()
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { id ->
            reloadCounts.compute(id) { _, value -> value?.plus(1) ?: 1 }
        }
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList(), cordappsForAllNodes = cordapps)) {

            val (alice, bob) = listOf(ALICE_NAME, BOB_NAME)
                .map {
                    startNode(
                        providedName = it,
                        customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
                    )
                }
                .transpose()
                .getOrThrow()

            val handle = alice.rpc.startFlow(::ReloadFromCheckpointFlow, bob.nodeInfo.singleIdentity(), false, false, true)
            val flowStartedByAlice = handle.id
            handle.returnValue.getOrThrow()
            assertEquals(5, reloadCounts[flowStartedByAlice])
            assertEquals(6, reloadCounts[ReloadFromCheckpointResponder.flowId])
        }
    }

    @Test(timeout = 300_000)
    fun `idempotent flow will reload from initial checkpoint after calling a suspending function when reloadCheckpointAfterSuspend is true`() {
        var reloadCount = 0
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { _ -> reloadCount += 1 }
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList(), cordappsForAllNodes = cordapps)) {

            val alice = startNode(
                providedName = ALICE_NAME,
                customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
            ).getOrThrow()

            alice.rpc.startFlow(::MyIdempotentFlow, false).returnValue.getOrThrow()
            assertEquals(5, reloadCount)
        }
    }

    @Test(timeout = 300_000)
    fun `idempotent flow will reload from initial checkpoint after calling a suspending function when reloadCheckpointAfterSuspend is true but can't throw deserialization error from objects in the call function`() {
        var reloadCount = 0
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { _ -> reloadCount += 1 }
        driver(DriverParameters(startNodesInProcess = true, notarySpecs = emptyList(), cordappsForAllNodes = cordapps)) {

            val alice = startNode(
                providedName = ALICE_NAME,
                customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
            ).getOrThrow()

            alice.rpc.startFlow(::MyIdempotentFlow, false).returnValue.getOrThrow()
            assertEquals(5, reloadCount)
        }
    }

    @Test(timeout = 300_000)
    fun `flow continues reloading from checkpoints after node restart when reloadCheckpointAfterSuspend is true`() {
        var reloadCount = 0
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { _ -> reloadCount += 1 }
        driver(
            DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = true,
                notarySpecs = emptyList(),
                cordappsForAllNodes = cordapps
            )
        ) {

            val alice = startNode(
                providedName = ALICE_NAME,
                customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
            ).getOrThrow()

            alice.rpc.startFlow(::MyRestartingFlow)
            Thread.sleep(10.seconds.toMillis())

            alice.stop()

            startNode(
                providedName = ALICE_NAME,
                customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
            ).getOrThrow()

            Thread.sleep(20.seconds.toMillis())

            assertEquals(5, reloadCount)
        }
    }

    @Test(timeout = 300_000)
    fun `idempotent flow continues reloading from checkpoints after node restart when reloadCheckpointAfterSuspend is true`() {
        var reloadCount = 0
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { _ -> reloadCount += 1 }
        driver(
            DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = true,
                notarySpecs = emptyList(),
                cordappsForAllNodes = cordapps
            )
        ) {

            val alice = startNode(
                providedName = ALICE_NAME,
                customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
            ).getOrThrow()

            alice.rpc.startFlow(::IdempotentRestartingFlow)
            Thread.sleep(10.seconds.toMillis())

            alice.stop()

            startNode(
                providedName = ALICE_NAME,
                customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
            ).getOrThrow()

            Thread.sleep(20.seconds.toMillis())

            // restarts completely from the beginning and forgets the in-memory reload count therefore
            // it reloads an extra 2 times for checkpoints it had already reloaded before the node shutdown
            assertEquals(7, reloadCount)
        }
    }

    @Test(timeout = 300_000)
    fun `more complicated flow will reload from its checkpoint after suspending when reloadCheckpointAfterSuspend is true`() {
        val reloadCounts = mutableMapOf<StateMachineRunId, Int>()
        FlowStateMachineImpl.onReloadFlowFromCheckpoint = { id ->
            reloadCounts.compute(id) { _, value -> value?.plus(1) ?: 1 }
        }
        driver(DriverParameters(startNodesInProcess = true, cordappsForAllNodes = FINANCE_CORDAPPS)) {

            val (alice, bob) = listOf(ALICE_NAME, BOB_NAME)
                .map {
                    startNode(
                        providedName = it,
                        customOverrides = mapOf(NodeConfiguration::reloadCheckpointAfterSuspend.name to true)
                    )
                }
                .transpose()
                .getOrThrow()

            val handle = alice.rpc.startFlow(
                ::CashIssueAndPaymentFlow,
                500.DOLLARS,
                OpaqueBytes.of(0x01),
                bob.nodeInfo.singleIdentity(),
                false,
                defaultNotaryIdentity
            )
            val flowStartedByAlice = handle.id
            handle.returnValue.getOrThrow(30.seconds)
            val flowStartedByBob = bob.rpc.stateMachineRecordedTransactionMappingSnapshot()
                .map(StateMachineTransactionMapping::stateMachineRunId)
                .toSet()
                .single()
            Thread.sleep(10.seconds.toMillis())
            assertEquals(7, reloadCounts[flowStartedByAlice])
            assertEquals(6, reloadCounts[flowStartedByBob])
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class ReloadFromCheckpointFlow(
        private val party: Party,
        private val shouldHaveDeserializationError: Boolean,
        private val counterPartyHasDeserializationError: Boolean,
        private val skipCheckpoints: Boolean
    ) : FlowLogic<Unit>() {

        @Suspendable
        override fun call() {
            val session = initiateFlow(party)
            session.send(counterPartyHasDeserializationError, skipCheckpoints)
            session.receive(String::class.java, skipCheckpoints).unwrap { it }
            sleep(1.seconds, skipCheckpoints)
            val map = if (shouldHaveDeserializationError) {
                BrokenMap(mutableMapOf("i dont want" to "this to work"))
            } else {
                mapOf("i dont want" to "this to work")
            }
            logger.info("I need to use my variable to pass the build!: $map")
            session.sendAndReceive<String>("hey I made it this far")
        }
    }

    @InitiatedBy(ReloadFromCheckpointFlow::class)
    class ReloadFromCheckpointResponder(private val session: FlowSession) : FlowLogic<Unit>() {

        companion object {
            var flowId: StateMachineRunId? = null
        }

        @Suspendable
        override fun call() {
            flowId = runId
            val counterPartyHasDeserializationError = session.receive<Boolean>().unwrap { it }
            session.send("hello there 12312311")
            sleep(1.seconds)
            val map = if (counterPartyHasDeserializationError) {
                BrokenMap(mutableMapOf("i dont want" to "this to work"))
            } else {
                mapOf("i dont want" to "this to work")
            }
            logger.info("I need to use my variable to pass the build!: $map")
            session.receive<String>().unwrap { it }
            session.send("sending back a message")
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class MyIdempotentFlow(private val shouldHaveDeserializationError: Boolean) : FlowLogic<Unit>(), IdempotentFlow {

        @Suspendable
        override fun call() {
            sleep(1.seconds)
            sleep(1.seconds)
            val map = if (shouldHaveDeserializationError) {
                BrokenMap(mutableMapOf("i dont want" to "this to work"))
            } else {
                mapOf("i dont want" to "this to work")
            }
            logger.info("I need to use my variable to pass the build!: $map")
            sleep(1.seconds)
            sleep(1.seconds)
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class MyRestartingFlow : FlowLogic<Unit>() {

        companion object {
            var thrown = false
        }

        @Suspendable
        override fun call() {
            sleep(1.seconds)
            sleep(1.seconds)
            if (!thrown) {
                thrown = true
                throw HospitalizeFlowException("i want to try again")
            }
            sleep(1.seconds)
            sleep(1.seconds)
        }
    }

    @StartableByRPC
    @InitiatingFlow
    class IdempotentRestartingFlow : FlowLogic<Unit>(), IdempotentFlow {

        companion object {
            var thrown = false
        }

        @Suspendable
        override fun call() {
            sleep(1.seconds)
            sleep(1.seconds)
            if (!thrown) {
                thrown = true
                throw HospitalizeFlowException("i want to try again")
            }
            sleep(1.seconds)
            sleep(1.seconds)
        }
    }
}