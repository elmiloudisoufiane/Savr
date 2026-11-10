package com.zarnth.savr.presentation.root.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zarnth.savr.domain.model.BottomBarStyle
import com.zarnth.savr.presentation.root.bottomAppBarItems

@Composable
fun RootBottomBar(
    currentTab: Int,
    onTabChange: (Int) -> Unit,
    style: BottomBarStyle = BottomBarStyle.MATERIAL
) {
    when (style) {
        BottomBarStyle.MATERIAL -> MaterialBottomBar(currentTab, onTabChange)
        BottomBarStyle.IOS -> IosBottomBar(currentTab, onTabChange)
    }
}

@Composable
private fun MaterialBottomBar(
    currentTab: Int,
    onTabChange: (Int) -> Unit
) {
    BottomAppBar {
        bottomAppBarItems.forEachIndexed { index, item ->
            val isClicked = currentTab == index
            NavigationBarItem(
                label = { Text(item.title) },
                selected = isClicked,
                onClick = { onTabChange(index) },
                icon = {
                    Icon(
                        painter = painterResource(if (isClicked) item.iconFilled else item.icon),
                        contentDescription = item.title,
                        modifier = Modifier.size(24.dp)
                    )
                }
            )
        }
    }
}

/**
 * iOS-style tab bar: flat bar with a hairline top border, no selection pill,
 * icon stacked over a small label, filled icon + accent colour when selected.
 */
@Composable
private fun IosBottomBar(
    currentTab: Int,
    onTabChange: (Int) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .height(52.dp)
            ) {
                bottomAppBarItems.forEachIndexed { index, item ->
                    val selected = currentTab == index
                    val tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = { onTabChange(index) }
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            painter = painterResource(if (selected) item.iconFilled else item.icon),
                            contentDescription = item.title,
                            tint = tint,
                            modifier = Modifier.size(25.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.title,
                            color = tint,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
