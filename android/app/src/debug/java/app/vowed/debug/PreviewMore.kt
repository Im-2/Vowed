package app.vowed.debug

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import app.vowed.CoachUi
import app.vowed.GoalUi
import app.vowed.LettersUi
import app.vowed.PendingTx
import app.vowed.SquadsUi
import app.vowed.core.TxReview
import app.vowed.data.AiInfo
import app.vowed.data.CoachSuggestion
import app.vowed.data.ExampleGoal
import app.vowed.data.FeedEvent
import app.vowed.data.LeaderRow
import app.vowed.data.ParseResult
import app.vowed.data.Squad
import app.vowed.data.SquadChallenge
import app.vowed.data.SquadDetail
import app.vowed.data.SquadMember
import app.vowed.letters.Letter
import app.vowed.letters.Trigger
import app.vowed.ui.CategoriesScreen
import app.vowed.ui.CoachScreen
import app.vowed.ui.ConnectScreen
import app.vowed.ui.DetailScreen
import app.vowed.ui.ExploreScreen
import app.vowed.ui.LettersScreen
import app.vowed.ui.NewGoalScreen
import app.vowed.ui.RewardsScreen
import app.vowed.ui.ReviewScreen
import app.vowed.ui.SettingsScreen
import app.vowed.ui.SquadDetailScreen
import app.vowed.ui.SquadsScreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** The remaining preview screens. All data is made up (see Samples). */
@Composable
fun PreviewMore(screen: String) {
    val none = {}
    val st = Samples.state()
    val now = System.currentTimeMillis() / 1000
    val plan = Json.parseToJsonElement(
        """{"title":"Practice guitar for 45 minutes","category":"study","cadence":{"periodDays":1,"totalDays":7,"requiredDays":6},"target":{"metric":"focus","value":45,"unit":"minutes","direction":"atLeast"},
           "proofMethods":[{"type":"FOCUS_TIMER","params":{},"trustTier":"medium"}],"window":{"startLocalTime":"17:00","endLocalTime":"23:59"},"difficulty":3,"verifiable":true}""",
    ).jsonObject
    val parse = ParseResult(
        status = "plan", source = "ai", plan = plan, trustTier = "medium", limitations = listOf("The timer counts only while Vowed is open on screen.", "The time of day is shown but not checked yet."),
        ai = AiInfo(true, null),
    )
    val examples = listOf(
        ExampleGoal("read for 30 minutes every day for a week", "reading", "study", "study", "FOCUS_TIMER"),
        ExampleGoal("walk 8000 steps a day for a week", "steps", "steps", "steps", "STEPS"),
        ExampleGoal("keep Instagram under 30 minutes a day", "usage", "screen", "detox", "USAGE_LIMIT"),
        ExampleGoal("sleep 7 hours a night for a week", "sleep", "sleep", "sleep", "STEPS"),
    )
    val squad = Squad("s1", "Gym Crew", Samples.ME, "674KBM2R", "https://vowed.app/join/674KBM2R", 3)
    val members = listOf(SquadMember(Samples.ME, now), SquadMember("AdaLo7vCwK2gHt9nRyU4bXpFe8sDmQ1zJ6kTqW3hNv5E", now), SquadMember("KaiR9xTt2mPq5LwZ8cVb3nYhJd6sUe4gFa7oNk1MxB2C", now))
    val squadState = st.copy(
        squads = SquadsUi(
            list = listOf(squad, Squad("s2", "Morning readers", Samples.ME, "QW3RTY8U", "https://vowed.app/join/QW3RTY8U", 5)), loaded = true,
            detail = SquadDetail(squad, members, listOf(SquadChallenge("p1", "Open", now, now + 700_000, 7), SquadChallenge("p3", "Open", now, now + 3000, 3))),
            board = listOf(
                LeaderRow(1, "AdaLo7vCwK2gHt9nRyU4bXpFe8sDmQ1zJ6kTqW3hNv5E", 6, 6, true), LeaderRow(2, Samples.ME, 4, 4, false), LeaderRow(3, "KaiR9xTt2mPq5LwZ8cVb3nYhJd6sUe4gFa7oNk1MxB2C", 2, 1, false),
            ),
            feed = listOf(
                FeedEvent(4, "checked_in", "AdaLo7vCwK2gHt9nRyU4bXpFe8sDmQ1zJ6kTqW3hNv5E", "p1", buildJsonObject { put("day", 5) }, now),
                FeedEvent(3, "nudge", "AdaLo7vCwK2gHt9nRyU4bXpFe8sDmQ1zJ6kTqW3hNv5E", null, buildJsonObject { put("recipient", Samples.ME) }, now - 60),
                FeedEvent(2, "joined", Samples.ME, "p1", buildJsonObject {}, now - 3600),
                FeedEvent(1, "joined", "KaiR9xTt2mPq5LwZ8cVb3nYhJd6sUe4gFa7oNk1MxB2C", "p1", buildJsonObject {}, now - 7200),
            ),
        ),
    )
    when (screen) {
        "create-start" -> NewGoalScreen(st.copy(goal = GoalUi(examples = examples)), none, none, none, {}, {}, {}, none, none) { _, _, _, _, _, _, _, _ -> }
        "create-plan" -> NewGoalScreen(st.copy(goal = GoalUi(result = parse, examples = examples)), none, none, none, {}, {}, {}, none, none) { _, _, _, _, _, _, _, _ -> }
        "review" -> ReviewScreen(
            PendingTx(
                "create",
                TxReview("Create a DEMO pool", listOf("Goal" to "Practice guitar for 45 minutes", "Mode" to "Soft: a missed day costs part of the stake", "Days" to "7 days, 6 needed", "Pool" to "8pGNB…mNxB4", "Token" to "C6pXR…o63Hv", "What you pay" to "network fee and account rent in SOL; no tokens move until you join")),
                ByteArray(0), "8pGNB",
            ),
            none, none,
        )
        "detail" -> DetailScreen(st.copy(detail = st.details["p1"]), "p1", Samples.ME, none, none, {}, none, none)
        "detail-demo" -> DetailScreen(st.copy(detail = st.details["p3"]), "p3", Samples.ME, none, none, {}, none, none)
        "explore" -> ExploreScreen(st, none, none, { _, _, _ -> }, {}, { _, _ -> }, {}, none)
        "categories" -> CategoriesScreen(none, {})
        "squads" -> SquadsScreen(squadState, none, {}, {}, {}, null)
        "squad-detail" -> SquadDetailScreen(squadState, Samples.ME, none, none, {}, {}, none)
        "you" -> SettingsScreen(st, "https://vowed-backend.onrender.com", none, none, none, none, none, none)
        "rewards" -> RewardsScreen(st, Samples.ME, none, none, { _, _ -> }, none)
        "coach" -> CoachScreen(
            st.copy(
                coachUi = CoachUi(
                    listOf(
                        CoachSuggestion("study", "harder", "HIGH_SUCCESS_STREAK", 0.93, 4, 1.25, listOf("Most of your check-ins are in the evening."), message = "You have finished the last two reading challenges. Try a slightly bigger target next time."),
                        CoachSuggestion("steps", "easier", "LOW_SUCCESS", 0.5, 2, 0.75, emptyList(), message = "Half of your walking days were missed. A smaller daily target may keep you going."),
                    ),
                    loaded = true,
                ),
            ),
            none, none, {},
        )
        "letters" -> LettersScreen(
            st.copy(
                lettersUi = LettersUi(
                    listOf(
                        Letter("l1", now, "Dear me, you can do this.", Trigger.Milestone(7)),
                        Letter("l2", now, "Remember why you started.", Trigger.StreakBroken, deliveredAt = now, deliveredBecause = "Your streak was broken."),
                    ),
                ),
            ),
            none, none, { _, _, _ -> }, {}, {}, none, {},
        )
        "token-sheet" -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(top = 360.dp)) {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.padding(24.dp)) { app.vowed.ui.FaucetPanel(st.faucet, Samples.ME, none, none) }
            }
        }
        "info-sheet" -> androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(top = 520.dp)) {
            androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.surface, shadowElevation = 12.dp, modifier = androidx.compose.ui.Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Text("Demo mode", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    androidx.compose.material3.Text("DEMO MODE: days last a few minutes and the money is test money, so you can see a whole challenge in minutes.", style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                    app.vowed.ui.components.SoftButton("Got it", none)
                }
            }
        }
        "splash" -> app.vowed.ui.SplashScreen { }
        "connect-error" -> ConnectScreen(st.copy(signedIn = false, connectError = "Your wallet app closed before it connected. Open the wallet, make sure it is unlocked, then tap the button again. Nothing was signed."), none, none, none, none)
        "connect-success" -> ConnectScreen(st.copy(signedIn = true), none, none, none, none)
        else -> androidx.compose.material3.Text("No preview for $screen")
    }
}
