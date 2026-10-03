package com.mamre.billing.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.mamre.billing.R

/** The Mamre Foods "M" mark (drawable ic_logo_mark, 1024 x 1024 viewport). Decorative, so no description. */
@Composable
fun MamreLogo(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_logo_mark),
        contentDescription = null,
        modifier = modifier.size(size),
    )
}
