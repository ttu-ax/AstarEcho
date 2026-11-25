package com.astraecho.rhythm.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astraecho.rhythm.R
import com.astraecho.rhythm.model.GameResult
import com.astraecho.rhythm.ui.theme.AstraColors

@Composable
fun ResultScreen(
    result: GameResult,
    onReplay: () -> Unit,
    onBackToSongs: () -> Unit
) {
    val rankColor = when (result.rank) {
        "S+", "S" -> AstraColors.StarlightGold
        "A" -> AstraColors.NeonCyan
        "B" -> AstraColors.CosmicPurple
        else -> AstraColors.ElectricMagenta
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isTablet = maxWidth >= 840.dp

        Image(
            painter = painterResource(id = R.drawable.bg_home),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(listOf(Color(0xB8070A16), Color(0xEE070A16)))
                )
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (isTablet) 56.dp else 28.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isTablet) 40.dp else 24.dp)
        ) {
            // Left: Rank Showcase
            Column(
                modifier = Modifier.weight(0.9f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("共鸣结算 · RESONANCE COMPLETE", color = AstraColors.NeonCyan, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
                Text(result.songTitle, color = AstraColors.TextPrimary, fontSize = if (isTablet) 24.sp else 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${result.laneCount} 轨 · ${result.difficulty.displayName} · ${if (result.onlyTapAndHold) "仅按键和长条" else "全部键型"}",
                    color = AstraColors.TextSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = result.rank,
                    color = rankColor,
                    fontSize = if (isTablet) 112.sp else 84.sp,
                    fontWeight = FontWeight.Black
                )
                if (result.isFullCombo) {
                    Surface(
                        color = AstraColors.StarlightGold.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, AstraColors.StarlightGold)
                    ) {
                        Text(
                            "FULL COMBO · 完美星轨",
                            color = AstraColors.StarlightGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            // Right: Stats Matrix & Actions
            Column(
                modifier = Modifier.weight(1.1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    color = AstraColors.GlassCard,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, AstraColors.GlassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            StatBlock("总分 SCORE", result.score.toString(), AstraColors.TextPrimary)
                            StatBlock("准确度 ACC", String.format("%.2f%%", result.accuracy), AstraColors.NeonCyan)
                            StatBlock("最大连击", result.maxCombo.toString(), AstraColors.StarlightGold)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            JudgeChip("PERFECT", result.perfectCount, AstraColors.StarlightGold)
                            JudgeChip("GREAT", result.greatCount, AstraColors.NeonCyan)
                            JudgeChip("GOOD", result.goodCount, AstraColors.CosmicPurple)
                            JudgeChip("MISS", result.missCount, AstraColors.ElectricMagenta)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBackToSongs,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AstraColors.GlassBorder)
                    ) {
                        Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = AstraColors.TextPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("返回曲库", color = AstraColors.TextPrimary)
                    }

                    Button(
                        onClick = onReplay,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AstraColors.NeonCyan,
                            contentColor = Color(0xFF041320)
                        )
                    ) {
                        Icon(Icons.Rounded.Replay, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("再奏一次", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBlock(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AstraColors.TextSecondary, fontSize = 10.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
private fun JudgeChip(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(count.toString(), color = AstraColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
