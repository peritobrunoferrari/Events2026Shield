package com.shieldpericias.sentinel.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shieldpericias.sentinel.core.FeatureState
import com.shieldpericias.sentinel.core.PolicyCheck
import com.shieldpericias.sentinel.core.PolicyResult
import com.shieldpericias.sentinel.ui.theme.StatusColors

fun FeatureState.label(): String = when (this) {
    is FeatureState.Active -> "ATIVO"
    is FeatureState.Inactive -> "INATIVO"
    is FeatureState.Unavailable -> "INDISPONÍVEL"
    is FeatureState.Error -> "ERRO"
}

fun FeatureState.detailText(): String? = when (this) {
    is FeatureState.Active -> detail
    is FeatureState.Inactive -> detail
    is FeatureState.Unavailable -> reason
    is FeatureState.Error -> message
}

fun FeatureState.color(): Color = when (this) {
    is FeatureState.Active -> StatusColors.active
    is FeatureState.Inactive -> StatusColors.inactive
    is FeatureState.Unavailable -> StatusColors.unavailable
    is FeatureState.Error -> StatusColors.error
}

fun PolicyResult.describe(): String = when (this) {
    PolicyResult.Applied -> "aplicado"
    is PolicyResult.Unsupported -> "não suportado: $reason"
    is PolicyResult.Denied -> "negado: $reason"
    is PolicyResult.Failed -> "falhou: $message"
}

@Composable
fun StateBadge(state: FeatureState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).background(state.color(), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(state.label(), style = MaterialTheme.typography.labelMedium, color = state.color(), fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
fun InfoCard(title: String? = null, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
            }
            content()
        }
    }
}

@Composable
fun CheckRow(check: PolicyCheck) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(check.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            StateBadge(check.state)
        }
        val d = check.state.detailText() ?: check.detail
        if (!d.isNullOrBlank()) Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ChecksCard(title: String, checks: List<PolicyCheck>) {
    InfoCard(title) { checks.forEach { CheckRow(it) } }
}

@Composable
fun KeyValue(key: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.45f))
        Text(value ?: "—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.55f))
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, description: String? = null, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
fun <T> RadioGroup(options: List<Pair<T, String>>, selected: T, enabled: Boolean = true, onSelect: (T) -> Unit) {
    Column {
        options.forEach { (value, label) ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = value == selected, onClick = { onSelect(value) }, enabled = enabled)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
fun WarningText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 2.dp))
}

/**
 * Diálogo de confirmação forte: exige digitar [requiredText] (ex.: "APAGAR").
 * Usado para operações irreversíveis ou de alto impacto.
 */
@Composable
fun TypedConfirmDialog(
    title: String,
    message: String,
    requiredText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Digite $requiredText para confirmar") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = typed.trim() == requiredText) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun SimpleConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text("Confirmar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
