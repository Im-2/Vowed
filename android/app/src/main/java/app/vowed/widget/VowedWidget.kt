package app.vowed.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.vowed.MainActivity
import java.text.DateFormat
import java.util.Date

/** Home-screen widget (Jetpack Glance): today's check-ins and the best streak, from [WidgetSnapshot]. */
class VowedWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = WidgetSnapshot.read(context)
        provideContent { Content(context, snap) }
    }

    @Composable
    private fun Content(context: Context, s: WidgetSnapshot) {
        val ink = ColorProvider(androidx.compose.ui.graphics.Color(0xFF1C1B1F))
        Column(
            GlanceModifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFE8F5EC)).padding(12.dp)
                .clickable(actionStartActivity(android.content.Intent(context, MainActivity::class.java))),
        ) {
            Text("Vowed", style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            Text(s.headline(), style = TextStyle(color = ink, fontSize = 16.sp, fontWeight = FontWeight.Bold))
            Text(s.streakLine(), style = TextStyle(color = ink, fontSize = 13.sp))
            if (s.updatedAtMillis > 0) Text("Updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.updatedAtMillis)), style = TextStyle(color = ink, fontSize = 10.sp))
        }
    }

    companion object {
        /** Writes the snapshot and redraws every placed widget. Safe to call when none is placed. */
        suspend fun refresh(context: Context, s: WidgetSnapshot) {
            WidgetSnapshot.write(context, s)
            runCatching { VowedWidget().updateAll(context) }
        }
    }
}

class VowedWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VowedWidget()
}
