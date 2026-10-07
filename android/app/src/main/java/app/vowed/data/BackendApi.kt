package app.vowed.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Thin client for docs/openapi.json. The bearer token is held in memory only. */
class BackendApi(private val baseUrl: () -> String, private val client: OkHttpClient = defaultClient()) {
    @Volatile var token: String? = null

    private val jsonType = "application/json".toMediaType()

    private suspend fun <T> call(method: String, path: String, body: String?, idempotent: Boolean = false, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(baseUrl().trimEnd('/') + path)
        token?.let { builder.header("Authorization", "Bearer $it") }
        if (idempotent) builder.header("Idempotency-Key", UUID.randomUUID().toString())
        when (method) {
            "GET" -> builder.get()
            else -> builder.post((body ?: "{}").toRequestBody(jsonType))
        }
        try {
            client.newCall(builder.build()).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val err = runCatching { AppJson.decodeFromString<ApiErrorBody>(text).error }.getOrNull()
                    throw ApiException(res.code, err?.code ?: "http_${res.code}", err?.message ?: "The server answered ${res.code}.")
                }
                parse(text)
            }
        } catch (e: IOException) {
            throw ApiException(0, "network", "Could not reach the Vowed server (${e.message ?: "network error"}).")
        }
    }

    private suspend inline fun <reified R, reified T> post(path: String, req: R, idempotent: Boolean = false): T =
        call("POST", path, AppJson.encodeToString(req), idempotent) { AppJson.decodeFromString<T>(it) }

    private suspend inline fun <reified T> get(path: String): T = call("GET", path, null) { AppJson.decodeFromString<T>(it) }

    suspend fun meta(): Meta = get("/v1/meta")
    suspend fun nonce(wallet: String): NonceResponse = post("/v1/auth/nonce", NonceRequest(wallet))
    suspend fun verify(req: VerifyRequest): VerifyResponse = post("/v1/auth/verify", req)
    suspend fun deviceChallenge(): DeviceChallengeResponse = post("/v1/devices/challenge", JsonObject(emptyMap()))
    suspend fun registerDevice(req: DeviceRegisterRequest): DeviceRegisterResponse = post("/v1/devices/register", req)
    suspend fun challenges(mine: Boolean = true): ChallengeList = get("/v1/challenges?mine=$mine&limit=50")
    suspend fun challenge(pool: String): ChallengeDetail = get("/v1/challenges/$pool")
    suspend fun createTx(req: CreateTxRequest): TxResponse = post("/v1/challenges/tx/create", req, idempotent = true)
    suspend fun joinTx(req: JoinTxRequest): TxResponse = post("/v1/challenges/tx/join", req, idempotent = true)
    suspend fun claimTx(req: ClaimTxRequest): TxResponse = post("/v1/challenges/tx/claim", req, idempotent = true)
    suspend fun proofSession(req: ProofSessionRequest): ProofSession = post("/v1/proofs/session", req)
    suspend fun submitProof(pkg: JsonObject): ProofResult = post("/v1/proofs/submit", pkg)
    suspend fun coach(): CoachResponse = get("/v1/coach/suggestions")
    suspend fun goalTemplates(): TemplateList = get("/v1/goals/templates")
    suspend fun parseGoal(text: String, useAi: Boolean): ParseResult = post("/v1/goals/parse", ParseRequest(text, useAi))
    suspend fun validateGoal(plan: JsonObject, demo: Boolean): ValidateResult = post("/v1/goals/validate", ValidateRequest(plan, demo))
    suspend fun goalExamples(count: Int = 8): ExampleList = get("/v1/goals/examples?count=$count&seed=${(Math.random() * 1_000_000_000).toLong()}")
    suspend fun explore(category: String?, mint: String?, demo: String, endingSoon: Boolean, cursor: String?): ExplorePage {
        val q = buildList {
            category?.let { add("category=$it") }
            mint?.let { add("mint=$it") }
            add("demo=$demo")
            if (endingSoon) add("endingSoon=true")
            cursor?.let { add("cursor=$it") }
            add("limit=20")
        }.joinToString("&")
        return get("/v1/explore?$q")
    }
    suspend fun report(pool: String, reason: String, note: String? = null): Unit = call("POST", "/v1/explore/$pool/report", AppJson.encodeToString(ReportRequest(reason, note))) { }
    suspend fun squads(): SquadList = get("/v1/squads")
    suspend fun createSquad(name: String): Squad = post("/v1/squads", SquadCreate(name))
    suspend fun joinSquad(code: String): Squad = post("/v1/squads/join", SquadJoin(code))
    suspend fun squad(id: String): SquadDetail = get("/v1/squads/$id")
    suspend fun linkPool(squadId: String, pool: String): LinkResult = post("/v1/squads/$squadId/challenges", LinkPool(pool))
    suspend fun feed(squadId: String): FeedPage = get("/v1/squads/$squadId/feed?limit=40")
    suspend fun leaderboard(squadId: String): Leaderboard = get("/v1/squads/$squadId/leaderboard")
    suspend fun nudge(squadId: String, recipient: String): NudgeResult = post("/v1/squads/$squadId/nudge", NudgeRequest(recipient))
    suspend fun notifications(since: Long): Notes = get("/v1/notifications?since=$since&limit=30")
    suspend fun faucet(): FaucetStatus = get("/v1/faucet")
    suspend fun faucetClaim(): FaucetClaim = post("/v1/faucet/claim", JsonObject(emptyMap()))
    suspend fun sync(signature: String): Unit = call("POST", "/v1/challenges/sync", AppJson.encodeToString(SyncRequest(signature))) { }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
