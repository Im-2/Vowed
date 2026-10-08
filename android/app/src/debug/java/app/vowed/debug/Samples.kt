package app.vowed.debug

import app.vowed.UiState
import app.vowed.data.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/**
 * DEBUG BUILDS ONLY. Made-up screens data for the preview activity, used to take screenshots without a wallet or a server.
 * Everything here is invented; the preview screen says so on top.
 */
object Samples {
    const val ME = "2YePEWRp8aTfqQnJHK2EBt4YkXRXWetzdmL8dDG7UFZf"
    private const val BOB = "8gXPzzTcPqB9nM7vWzJ3kqRLbq3rXQ6mYaZ5c1Rr9YiYQ"
    private const val ADA = "AdaLo7vCwK2gHt9nRyU4bXpFe8sDmQ1zJ6kTqW3hNv5E"
    private const val KAI = "KaiR9xTt2mPq5LwZ8cVb3nYhJd6sUe4gFa7oNk1MxB2C"
    private const val MIA = "MiaQ4vNz8rHs1TpLx6cWb9yKd3eUg5jFa2oBn7MxC1Zt"
    private const val MINT = "C6pXRRmoHsf7Mqa1ZrW3JfspyknhSR1cRqHMUqao63Hv"

    private fun plan(title: String, category: String, type: String, trust: String): JsonObject =
        Json.parseToJsonElement(
            """{"title":"$title","category":"$category","proofMethods":[{"type":"$type","trustTier":"$trust"}],"target":{"metric":"m","value":30,"unit":"minutes","direction":"atLeast"}}""",
        ).jsonObject

    fun challenge(pool: String, title: String, category: String, type: String, trust: String, demo: Boolean = false, startedDaysAgo: Int = 2, days: Int = 7, deposits: String = "6000000", count: Int = 4): Challenge {
        val now = System.currentTimeMillis() / 1000
        val daySecs = if (demo) 600 else 86_400
        val start = now - startedDaysAgo * daySecs.toLong() - 600
        return Challenge(
            pool = pool, creator = BOB, mint = MINT, vault = "v$pool", kind = "Open", mode = "Soft", penaltyBps = 3000, startTs = start, endTs = start + days * daySecs, joinDeadlineTs = now + 40_000,
            settleAfterTs = start + days * daySecs + 7200, durationDays = days, requiredDays = days - 1, goalHash = "0".repeat(64), participantCount = count, maxParticipants = 50, totalDeposits = deposits,
            status = "Open", isDemo = demo, daySecs = daySecs, demoLabel = if (demo) "DEMO POOL: each \"day\" lasts 10 minutes. For demonstration only, with test money." else null,
            plan = plan(title, category, type, trust),
        )
    }

    private fun detail(c: Challenge, bitmap: String, days: Int) = ChallengeDetail(
        c,
        listOf(Participant(ME, "1000000", 0, days, bitmap, "0", "Active"), Participant(BOB, "1000000", 0, 2, "3", "0", "Active"), Participant(ADA, "2000000", 0, 3, "7", "0", "Active")),
        MeInfo(true, "0"),
    )

    fun explore(): List<ExploreItem> {
        val now = System.currentTimeMillis() / 1000
        fun item(pool: String, title: String, cat: String, type: String, trust: String, sample: Boolean, demo: Boolean = false, n: Int = 3, ends: Long = 70_000) = ExploreItem(
            pool = pool, title = title, category = cat, proofType = type, trustTier = trust, mode = "Soft", mint = MINT, tokenSymbol = "tUSDC", durationDays = 7, requiredDays = 6, startTs = now,
            joinDeadlineTs = now + ends, participantCount = n, maxParticipants = 50, totalDeposits = "${n * 1_000_000}", isDemo = demo, daySecs = if (demo) 600 else 86_400,
            demoLabel = if (demo) "DEMO POOL: each \"day\" lasts 10 minutes. For demonstration only, with test money." else null, sample = sample,
        )
        return listOf(
            item("e1", "Walk outside 3,000 steps a day", "steps", "STEPS", "medium", true),
            item("e2", "No TikTok after 22:00", "detox", "NO_USE_WINDOW", "high", true, n = 5, ends = 3 * 3600),
            item("e3", "Focus for 2 hours a day", "study", "FOCUS_TIMER", "medium", true, n = 2),
            item("e4", "Drink 8 glasses of water a day", "custom", "SELF_ATTEST", "low", true, n = 6),
            item("e5", "Read for 20 minutes every evening", "study", "FOCUS_TIMER", "medium", false, demo = true, n = 2, ends = 1200),
            item("e6", "Sleep 7 hours a night", "sleep", "STEPS", "medium", false, n = 4, ends = 20_000),
        )
    }

    fun state(empty: Boolean = false): UiState {
        val a = challenge("p1", "Read for 30 minutes a day", "study", "FOCUS_TIMER", "medium", startedDaysAgo = 2, deposits = "6000000")
        val b = challenge("p2", "Walk 6000 steps a day", "steps", "STEPS", "medium", startedDaysAgo = 3, days = 10, deposits = "9000000", count = 6)
        val c = challenge("p3", "Focus for 25 minutes a day", "study", "FOCUS_TIMER", "medium", demo = true, startedDaysAgo = 1, days = 3, deposits = "2000000", count = 2)
        val list = if (empty) emptyList() else listOf(a, b, c)
        return UiState(
            account = app.vowed.data.Account(ME, "dev", "low"), signedIn = true,
            challenges = list,
            details = if (empty) emptyMap() else mapOf("p1" to detail(a, "3", 2), "p2" to detail(b, "7", 3), "p3" to detail(c, "0", 0)),
            faucet = app.vowed.FaucetUi(
                status = FaucetStatus(true, "devnet", "TEST TOKENS: tUSDC and tSKR exist only on Solana devnet and have no real value.", canClaim = false, nextClaimAt = System.currentTimeMillis() / 1000 + 20_000, balances = FaucetBalances("127900000", "20000000")),
            ),
            explore = app.vowed.ExploreUi(items = explore().filter { !it.isDemo }, demoPools = explore().filter { it.isDemo }, loaded = true),
            rewardsUi = app.vowed.RewardsUi(
                status = RewardsStatus(
                    true, "TEST SKR: weekly rewards are paid in a test token on devnet. It has no value.", false, 3, listOf("10000000", "5000000", "3000000"), 2960, System.currentTimeMillis() / 1000 + 200_000, null,
                    listOf(RewardStanding(1, ADA, 9, false, true), RewardStanding(2, ME, 6, true, true), RewardStanding(3, KAI, 5, false, true), RewardStanding(4, MIA, 3, false, false), RewardStanding(5, BOB, 2, false, false)),
                    listOf(MyReward(2959, 2, 5, "5000000", "sent", null)),
                ),
                week = app.vowed.data.Board(
                    "week", "TEST SKR", true, System.currentTimeMillis() / 1000 + 200_000, 3,
                    listOf(
                        app.vowed.data.BoardEntry(1, "SAMPLEMira111111111111111111111111111111", "Mira", 6, null, false, true),
                        app.vowed.data.BoardEntry(2, "SAMPLEJonas11111111111111111111111111111", "Jonas", 5, null, false, true),
                        app.vowed.data.BoardEntry(3, "SAMPLEAiko111111111111111111111111111111", "Aiko", 4, null, false, true),
                        app.vowed.data.BoardEntry(4, "SAMPLETomas11111111111111111111111111111", "Tomas", 3, null, false, true),
                        app.vowed.data.BoardEntry(5, "SAMPLELena111111111111111111111111111111", "Lena", 3, null, false, true),
                        app.vowed.data.BoardEntry(6, "SAMPLERui1111111111111111111111111111111", "Rui", 2, null, false, true),
                        app.vowed.data.BoardEntry(7, ME, "You", 2, null, true, false),
                        app.vowed.data.BoardEntry(8, "SAMPLEOmar111111111111111111111111111111", "Omar", 1, null, false, true),
                    ),
                    app.vowed.data.BoardMe(7, 2, null, false), true, "SAMPLE: made-up players, never paid.",
                ),
                all = app.vowed.data.Board(
                    "all", "TEST SKR", true, System.currentTimeMillis() / 1000 + 200_000, 3,
                    listOf(
                        app.vowed.data.BoardEntry(1, "SAMPLEMira111111111111111111111111111111", "Mira", 21, null, false, true),
                        app.vowed.data.BoardEntry(2, "SAMPLEAiko111111111111111111111111111111", "Aiko", 18, null, false, true),
                        app.vowed.data.BoardEntry(3, "SAMPLEJonas11111111111111111111111111111", "Jonas", 14, null, false, true),
                    ),
                    app.vowed.data.BoardMe(null, 0, null, false), true, "SAMPLE: made-up players, never paid.",
                ),
            ),
        )
    }
}
