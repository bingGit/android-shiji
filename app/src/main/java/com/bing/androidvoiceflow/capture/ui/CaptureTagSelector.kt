package com.bing.androidvoiceflow.capture.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bing.androidvoiceflow.capture.data.CaptureTagEntity
import com.bing.androidvoiceflow.capture.data.MAX_TAGS_PER_CAPTURE

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CaptureTagSelector(
    availableTags: List<CaptureTagEntity>,
    selectedTagIds: Set<String>,
    enabled: Boolean,
    onSelectionChange: (Set<String>) -> Unit
) {
    if (availableTags.isEmpty()) return
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "标签 ${selectedTagIds.size}/$MAX_TAGS_PER_CAPTURE",
            color = CaptureColors.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableTags.forEach { tag ->
                val selected = tag.tagId in selectedTagIds
                Box(
                    modifier = Modifier
                        .background(
                            if (selected) CaptureColors.Purple else CaptureColors.Card,
                            RoundedCornerShape(16.dp)
                        )
                        .border(
                            1.dp,
                            if (selected) CaptureColors.PurpleSoft else CaptureColors.Border,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable(enabled = enabled) {
                            when {
                                selected -> onSelectionChange(selectedTagIds - tag.tagId)
                                selectedTagIds.size < MAX_TAGS_PER_CAPTURE -> {
                                    onSelectionChange(selectedTagIds + tag.tagId)
                                }
                                else -> Toast.makeText(
                                    context,
                                    "一条内容最多添加 $MAX_TAGS_PER_CAPTURE 个标签",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        if (selected) "${tag.name} ×" else tag.name,
                        color = if (selected) Color.White else CaptureColors.Text,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
