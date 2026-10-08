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
    val kind: String? = null,
    val visibility: String = "private",
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
@Serializable data class Participant(val wallet: String, val stake: String, val tzOffsetMinutes: Int, val daysCompleted: Int, val checkinBitmap: String, val frozenBitmap: String = "0", val status: String)
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

@Serializable data class FaucetToken(val symbol: String, val name: String, val mint: String, val amount: String, val decimals: Int)
@Serializable data class FaucetBalances(val tUSDC: String = "0", val tSKR: String = "0")
@Serializable
data class FaucetStatus(
    val enabled: Boolean,
    val network: String,
    val label: String,
    val tokens: List<FaucetToken> = emptyList(),
    val canClaim: Boolean = false,
    val nextClaimAt: Long = 0,
    val claimsLeftToday: Int = 0,
    val balances: FaucetBalances = FaucetBalances(),
)
@Serializable data class FaucetMinted(val tUSDC: String, val tSKR: String)
@Serializable data class FaucetClaim(val signature: String, val minted: FaucetMinted, val nextClaimAt: Long, val label: String)

@Serializable data class ParseRequest(val text: String, val useAi: Boolean = true)
@Serializable data class PlanExtras(val needsPlace: Boolean = false, val needsApp: Boolean = false, val limitations: List<String> = emptyList())
@Serializable data class PlanOption(val label: String, val plan: JsonObject, val demoPlan: JsonObject? = null, val extras: PlanExtras = PlanExtras())
@Serializable data class AiInfo(val used: Boolean = false, val note: String? = null)
@Serializable
data class ParseResult(
    val status: String,
    val source: String = "none",
    val plan: JsonObject? = null,
    val demoPlan: JsonObject? = null,
    val templateId: String? = null,
    val trustTier: String? = null,
    val needsPlace: Boolean = false,
    val needsApp: Boolean = false,
    val limitations: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val confidence: String = "low",
    val reason: String? = null,
    val suggestedAlternative: String? = null,
    val alternatives: List<PlanOption> = emptyList(),
    val clarifyingQuestions: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val ai: AiInfo = AiInfo(),
)
@Serializable data class TemplateInfo(val id: String, val title: String, val example: String, val summary: String, val proofType: String, val category: String, val needsPlace: Boolean = false, val needsApp: Boolean = false)
@Serializable data class TemplateList(val templates: List<TemplateInfo>)
@Serializable data class ValidateRequest(val plan: JsonObject, val demo: Boolean = false)
@Serializable data class ValidateResult(val ok: Boolean, val plan: JsonObject? = null, val demoPlan: JsonObject? = null, val reason: String? = null, val notes: List<String> = emptyList())

@Serializable data class ExampleGoal(val text: String, val templateId: String, val family: String, val category: String, val proofType: String)
@Serializable data class ExampleList(val examples: List<ExampleGoal>)

@Serializable
data class ExploreItem(
    val pool: String,
    val title: String,
    val category: String,
    val proofType: String? = null,
    val trustTier: String? = null,
    val mode: String,
    val mint: String,
    val tokenSymbol: String,
    val tokenIsTest: Boolean = true,
    val durationDays: Int,
    val requiredDays: Int,
    val startTs: Long,
    val joinDeadlineTs: Long,
    val participantCount: Int,
    val maxParticipants: Int,
    val totalDeposits: String = "0",
    val stakeCap: String? = null,
    val isDemo: Boolean = false,
    val daySecs: Int = 86_400,
    val demoLabel: String? = null,
    val sample: Boolean = false,
    val createdByYou: Boolean = false,
    val joined: Boolean = false,
)
@Serializable data class ExplorePage(val items: List<ExploreItem>, val nextCursor: String? = null)
@Serializable data class ReportRequest(val reason: String, val note: String? = null)

@Serializable data class Squad(val id: String, val name: String, val owner: String, val inviteCode: String, val deepLink: String, val memberCount: Int)
@Serializable data class SquadList(val squads: List<Squad>)
@Serializable data class SquadMember(val wallet: String, val joinedAt: Long)
@Serializable data class SquadChallenge(val pool: String, val status: String, val startTs: Long, val endTs: Long, val durationDays: Int)
@Serializable data class SquadDetail(val squad: Squad, val members: List<SquadMember>, val challenges: List<SquadChallenge>)
@Serializable data class SquadCreate(val name: String)
@Serializable data class SquadJoin(val code: String)
@Serializable data class LinkPool(val pool: String)
@Serializable data class LinkResult(val pool: String, val squadId: String, val visibility: String)
@Serializable data class FeedEvent(val id: Long, val kind: String, val wallet: String, val pool: String? = null, val data: JsonObject = JsonObject(emptyMap()), val createdAt: Long)
@Serializable data class FeedPage(val events: List<FeedEvent>)
@Serializable data class LeaderRow(val rank: Int, val wallet: String, val daysCompleted: Int, val bestStreak: Int, val checkedInToday: Boolean)
@Serializable data class Leaderboard(val rows: List<LeaderRow>)
@Serializable data class NudgeRequest(val recipient: String)
@Serializable data class NudgeResult(val delivered: Int = 0)
@Serializable data class NoteItem(val id: Long, val kind: String, val squadId: String, val squadName: String, val wallet: String, val pool: String? = null, val data: JsonObject = JsonObject(emptyMap()), val createdAt: Long)
@Serializable data class Notes(val now: Long, val items: List<NoteItem>)

@Serializable data class ApiErrorBody(val error: ApiErrorInfo)
@Serializable data class ApiErrorInfo(val code: String, val message: String)

/** A failed API call with the backend's stable error code (see docs/openapi.json). */
class ApiException(val status: Int, val code: String, override val message: String) : Exception(message)

@Serializable
data class CoachSuggestion(
    val category: String,
    val action: String,
    val reason: String,
    val successRate: Double? = null,
    val suggestedDifficulty: Int? = null,
    val targetScale: Double = 1.0,
    val hints: List<String> = emptyList(),
    val appliesTo: String = "next_challenge",
    val message: String,
)
@Serializable data class CoachResponse(val suggestions: List<CoachSuggestion>)

@Serializable data class FreezeTxRequest(val pool: String, val dayIndex: Int)
@Serializable data class FreezeRedeemRequest(val pool: String, val dayIndex: Int, val signature: String)
@Serializable data class FreezeRedeemed(val pool: String, val dayIndex: Int)
@Serializable data class FreezeQuote(val price: String, val mint: String, val payee: String, val payeeToken: String, val decimals: Int, val label: String, val transaction: String)
@Serializable data class RewardStanding(val rank: Int, val wallet: String, val streak: Int, val you: Boolean, val qualifies: Boolean)
@Serializable data class MyReward(val week: Int, val rank: Int, val streak: Int, val amount: String, val status: String, val signature: String? = null)
@Serializable
data class RewardsStatus(
    val enabled: Boolean,
    val label: String,
    val includesDemoPools: Boolean,
    val minStreak: Int,
    val ladder: List<String>,
    val currentWeek: Int,
    val weekEndsAt: Long,
    /** the public address of the server rewards wallet (never a key), or null when rewards are off */
    val rewardsWallet: String? = null,
    val standings: List<RewardStanding>,
    val mine: List<MyReward>,
)

/** One row of a leaderboard: only an avatar seed (wallet), a short name and a streak number. [sample] rows are made up and never paid. */
@Serializable data class BoardEntry(val rank: Int, val wallet: String, val name: String, val streak: Int, val reward: String? = null, val you: Boolean = false, val sample: Boolean = false)
@Serializable data class BoardMe(val rank: Int? = null, val streak: Int = 0, val rewardIfNow: String? = null, val hidden: Boolean = false)
@Serializable
data class Board(
    val scope: String,
    val label: String,
    val enabled: Boolean,
    val weekEndsAt: Long,
    val minStreak: Int,
    val entries: List<BoardEntry>,
    val me: BoardMe,
    val hasSamples: Boolean = false,
    val sampleNote: String? = null,
)
@Serializable data class HiddenRequest(val hidden: Boolean)
@Serializable data class HiddenResponse(val hidden: Boolean)
