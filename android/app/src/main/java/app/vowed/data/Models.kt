package app.vowed.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Shared JSON settings: the backend may add fields without breaking old app versions. */
val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable data class NonceRequest(val wallet: String)
@Serializable data class NonceResponse(val nonce: String, val domain: String, val statement: String, val uri: String, val issuedAt: String, val expirationTime: String)
@Serializable data class VerifyRequest(val wallet: String, val message: String, val signature: String)
@Serializable data class VerifyResponse(val token: String, val expiresAt: Long, val wallet: String)

@Serializable data class DeviceChallengeResponse(val challenge: String, val expiresAt: Long)
@Serializable data class DeviceRegisterRequest(val devicePublicKey: String, val attestationChain: List<String>? = null, val challenge: String, val walletSignature: String)
@Serializable data class AttestationInfo(val level: String, val verifiedBoot: String, val note: String)
@Serializable data class DeviceRegisterResponse(val deviceId: String, val trustCap: String, val attestation: AttestationInfo)

@Serializable data class DemoRequest(val daySecs: Int)
@Serializable
data class CreateTxRequest(
    val mint: String,
    val kind: String = "Squad",
    val mode: String,
    val penaltyBps: Int? = null,
    val startTs: Long,
    val joinWindowSecs: Long? = null,
    val maxParticipants: Int? = null,
    val plan: JsonObject,
    val demo: DemoRequest? = null,
)
@Serializable data class JoinTxRequest(val pool: String, val stake: String, val tzOffsetMinutes: Int, val deviceId: String)
@Serializable data class ClaimTxRequest(val pool: String)
@Serializable data class SyncRequest(val signature: String)
@Serializable data class TxResponse(val transaction: String, val blockhash: String, val lastValidBlockHeight: Long, val pool: String, val summary: JsonObject)

@Serializable
data class Challenge(
    val pool: String,
    val creator: String,
    val mint: String,
    val vault: String,
    val kind: String,
    val mode: String,
    val penaltyBps: Int,
    val feeBps: Int = 0,
    val startTs: Long,
    val endTs: Long,
    val joinDeadlineTs: Long,
    val settleAfterTs: Long,
    val durationDays: Int,
    val requiredDays: Int,
    val goalHash: String,
    val participantCount: Int,
    val maxParticipants: Int,
    val settledCount: Int = 0,
    val pendingClaims: Int = 0,
    val totalDeposits: String,
    val totalForfeit: String = "0",
    val totalSuccessStake: String = "0",
    val distributable: String = "0",
    val status: String,
    val squadId: String? = null,
    /** true for a DEMO POOL: minutes-long days, test money only. The UI must show [demoLabel] prominently. */
    val isDemo: Boolean = false,
    val daySecs: Int = 86_400,
    val demoLabel: String? = null,
    val trustTier: String? = null,
    val plan: JsonObject? = null,
)
@Serializable data class ChallengeList(val challenges: List<Challenge>)
@Serializable data class Participant(val wallet: String, val stake: String, val tzOffsetMinutes: Int, val daysCompleted: Int, val checkinBitmap: String, val status: String)
@Serializable data class MeInfo(val joined: Boolean, val claimable: String)
@Serializable data class ChallengeDetail(val challenge: Challenge, val participants: List<Participant>, val me: MeInfo)

@Serializable
data class MetaConfig(
    val oracle: String,
    val treasury: String,
    val feeBps: Int,
    val paused: Boolean,
    val maxStake: String,
    val settleGraceSecs: String,
    val allowedMints: List<String>,
    val demoEnabled: Boolean = false,
    val demoMaxStake: String = "0",
    val demoMints: List<String> = emptyList(),
)
@Serializable data class Meta(val programId: String, val network: String, val authDomain: String, val initialised: Boolean, val config: MetaConfig? = null)

@Serializable data class ProofSessionRequest(val pool: String, val dayIndex: Int, val proofType: String)
@Serializable data class ProofTarget(val metric: String, val value: Double, val unit: String, val direction: String)
@Serializable data class ProofWindow(val opensAt: Long, val closesAt: Long)
@Serializable
data class ProofSession(
    val sessionId: String,
    val nonce: String,
    val expiresAt: Long,
    val proofType: String,
    val dayIndex: Int,
    val target: ProofTarget,
    val window: ProofWindow,
    val isDemo: Boolean = false,
    val daySecs: Int = 86_400,
)
@Serializable data class CheckinOutcome(val status: String, val signature: String? = null, val error: String? = null)
@Serializable data class ProofResult(val accepted: Boolean, val trustTier: String, val daysCompleted: Int, val streak: Int, val checkin: CheckinOutcome)

@Serializable data class ApiErrorBody(val error: ApiErrorInfo)
@Serializable data class ApiErrorInfo(val code: String, val message: String)

/** A failed API call with the backend's stable error code (see docs/openapi.json). */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)
