package com.sdw.music.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.sdw.music.player.ui.theme.TextSecondary
import com.sdw.music.player.ui.theme.TextPrimary

/**
 * A-Z vertical index bar pinned to the right edge.
 *
 * @param letters         Letters to display (typically A-Z + #)
 * @param activeLetter    Currently highlighted letter
 * @param onLetterTapped  Called when user taps a letter
 */
@Composable
fun AlphabetIndexBar(
    letters: List<String>,
    activeLetter: String,
    onLetterTapped: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        letters.forEach { letter ->
            val isActive = letter == activeLetter
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent)
                    .pointerInput(letter) {
                        detectTapGestures { onLetterTapped(letter) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    fontSize = 10.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Detect which letter the user is currently over during a vertical drag */
@Composable
fun rememberLetterDetector(
    letters: List<String>,
    onLetterChanged: (String) -> Unit
): Modifier {
    return Modifier.pointerInput(letters) {
        detectTapGestures { /* handled per-item in index bar */ }
    }
}