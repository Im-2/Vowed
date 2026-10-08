package app.vowed.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.vowed.R

/**
 * Illustrated avatars (original artwork, `scripts/gen-avatars.py`). A wallet always gets the same one, picked from its address; the person can
 * choose their own in You, and that choice (kept on this phone) is used wherever they appear.
 */
object Avatars {
    val all: List<Int> = listOf(
        R.drawable.avatar_01, R.drawable.avatar_02, R.drawable.avatar_03, R.drawable.avatar_04, R.drawable.avatar_05, R.drawable.avatar_06, R.drawable.avatar_07,
        R.drawable.avatar_08, R.drawable.avatar_09, R.drawable.avatar_10, R.drawable.avatar_11, R.drawable.avatar_12, R.drawable.avatar_13, R.drawable.avatar_14,
    )
    val neutral: Int = R.drawable.avatar_neutral

    /** the signed-in person and their chosen avatar (-1 = automatic); read by every [Avatar] so a change shows everywhere at once */
    var mineWallet by mutableStateOf<String?>(null)
    var mineIndex by mutableIntStateOf(-1)

    fun forWallet(wallet: String): Int {
        if (wallet.length < 8) return neutral
        if (wallet == mineWallet && mineIndex in all.indices) return all[mineIndex]
        return all[(wallet.hashCode() and 0x7fffffff) % all.size]
    }
}

@Composable
fun Avatar(seed: String, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    Image(painterResource(Avatars.forWallet(seed)), contentDescription = "Avatar", modifier = modifier.size(size).clip(CircleShape))
}
