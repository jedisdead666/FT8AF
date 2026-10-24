package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8af.theme.TextMuted

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    // Overrides the default Text(title) rendering below, for a screen whose
    // title is itself an interactive control (e.g. Decode's tappable
    // band/mode label) rather than plain static text. [title] is still
    // required in that case, for accessibility/semantics fallbacks that may
    // read it, even though it isn't rendered directly.
    titleContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.01).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (subtitle != null) {
                subtitle()
            }
        }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
            }
        }
    }
}

/**
 * A [TopBar] title that IS the band/mode control, not a page label that sits
 * above one -- for a screen the operator reaches for constantly (Decode)
 * where an extra tap into Settings/the controls drawer just to retune was the
 * actual complaint this exists to fix. Tapping it should open the same
 * Band & Mode sheet the always-visible [radio.ks3ckc.ft8af.ui.components.BandModeRow]
 * strip opens elsewhere, so there's exactly one picker for this in the app,
 * not two.
 */
@Composable
fun ClickableTopBarTitle(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.headlineLarge.copy(
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
        )
        FT8AFIcons.ChevronDown(size = 16.dp, color = TextMuted, strokeWidth = 2f)
    }
}

@Composable
fun TopBarSubtitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(top = 2.dp),
        color = TextMuted,
        fontSize = 12.sp,
    )
}
