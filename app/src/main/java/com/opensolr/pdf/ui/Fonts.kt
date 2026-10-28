package com.opensolr.pdf.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.opensolr.pdf.R

/** Space Grotesk, one file per weight: HyperOS does not apply the weight axis of a variable font. */
object Fonts {
    val grotesk = FontFamily(
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )
}
