package com.example.calldelegate.feature.main.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.calldelegate.domain.model.ModuleStatus
import com.example.calldelegate.domain.model.SceneType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    content: @Composable (Modifier) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = { actions() },
            )
        },
    ) { padding -> content(Modifier.padding(padding)) }
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun LabelValue(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(2f).padding(end = 12.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            modifier = Modifier.weight(3f),
            textAlign = TextAlign.End,
        )
    }
    Spacer(Modifier.height(6.dp))
}

fun ModuleStatus.label(): String = when (this) {
    ModuleStatus.MockReady -> "Mock 可用"
    ModuleStatus.Initializing -> "初始化中"
    is ModuleStatus.RealReady -> "真实模型 $version"
    is ModuleStatus.Deferred -> "按需加载：$reason"
    is ModuleStatus.Missing -> "缺失：$reason"
    is ModuleStatus.Error -> "错误：$reason"
}

fun SceneType.shortName(): String = when (this) {
    SceneType.DELIVERY -> "配送"
    SceneType.RIDE_HAILING -> "打车"
    SceneType.CUSTOMER_SERVICE -> "客服"
    SceneType.REAL_ESTATE -> "房产"
    SceneType.INSURANCE_FINANCE -> "保险金融"
    SceneType.SPAM_RISK -> "风险来电"
    SceneType.WORK -> "工作"
    SceneType.UNKNOWN_IDENTITY -> "陌生来电"
    SceneType.SALES -> "旧版推销"
    SceneType.UNCLASSIFIED -> "待判断"
}

fun formatTime(epochMillis: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))
