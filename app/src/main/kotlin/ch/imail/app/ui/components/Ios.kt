package ch.imail.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ch.imail.app.ui.theme.IosType
import ch.imail.app.ui.theme.LocalIos

/** Large collapsing-style navigation title used on top of lists. */
@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = IosType.largeTitle, color = LocalIos.current.label, modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
fun NavText(text: String, modifier: Modifier = Modifier, bold: Boolean = false, enabled: Boolean = true, destructive: Boolean = false, onClick: () -> Unit) {
    val c = LocalIos.current
    Text(
        text, style = if (bold) IosType.headline else IosType.body,
        color = if (!enabled) c.tertiaryLabel else if (destructive) c.red else c.blue,
        modifier = modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** Rounded inset-grouped section like Settings / the account form. */
@Composable
fun GroupedSection(header: String? = null, footer: String? = null, content: @Composable () -> Unit) {
    val c = LocalIos.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        header?.let { Text(it.uppercase(), style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.cell)) { content() }
        footer?.let { Text(it, style = IosType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp)) }
    }
}

@Composable
fun InsetDivider(start: Int = 16) {
    Box(Modifier.fillMaxWidth().padding(start = start.dp).height(0.5.dp).background(LocalIos.current.separator))
}

@Composable
fun FieldRow(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", secret: Boolean = false, singleLine: Boolean = true, modifier: Modifier = Modifier) {
    val c = LocalIos.current
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = IosType.body, color = c.label, modifier = Modifier.padding(end = 12.dp).size(width = 96.dp, height = 22.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine, textStyle = IosType.body.copy(color = c.label),
            cursorBrush = SolidColor(c.blue),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = IosType.body, color = c.tertiaryLabel); inner() } },
        )
    }
}

@Composable
fun PillButton(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalIos.current
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(14.dp)).background(if (enabled) c.blue else c.fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = IosType.headline, color = if (enabled) Color.White else c.tertiaryLabel) }
}

@Composable
fun UnreadDot(modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(LocalIos.current.blue))
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = LocalIos.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, null, tint = c.secondaryLabel, modifier = Modifier.size(18.dp))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, textStyle = IosType.body.copy(color = c.label), cursorBrush = SolidColor(c.blue),
            modifier = Modifier.weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, style = IosType.body, color = c.secondaryLabel); inner() } },
        )
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.height(8.dp))
