package ru.parts.ledger.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Copper=Color(0xFF9A481F)
val Ink=Color(0xFF171717)
val Muted=Color(0xFF737373)
val Positive=Color(0xFF247344)
val Negative=Color(0xFFB3261E)
private val palette=lightColorScheme(primary=Copper,onPrimary=Color.White,primaryContainer=Color(0xFFF4E7DF),onPrimaryContainer=Ink,
    secondary=Color(0xFF525252),onSecondary=Color.White,secondaryContainer=Color(0xFFEAEAEA),onSecondaryContainer=Ink,
    tertiary=Color(0xFF525252),onTertiary=Color.White,tertiaryContainer=Color(0xFFF4F4F5),onTertiaryContainer=Ink,
    background=Color(0xFFFAFAFA),onBackground=Ink,surface=Color.White,onSurface=Ink,
    surfaceTint=Color.Transparent,surfaceDim=Color(0xFFDEDEDE),surfaceBright=Color(0xFFFAFAFA),
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFF8F8F8),surfaceContainer=Color(0xFFF4F4F5),surfaceContainerHigh=Color(0xFFEFEFEF),surfaceContainerHighest=Color(0xFFE8E8E8),
    surfaceVariant=Color(0xFFF4F4F5),onSurfaceVariant=Color(0xFF525252),outline=Color(0xFFD4D4D4),outlineVariant=Color(0xFFE8E8E8),error=Negative)
private fun type(size:Int,line:Int,weight:FontWeight=FontWeight.Normal)=TextStyle(fontFamily=FontFamily.SansSerif,fontSize=size.sp,lineHeight=line.sp,fontWeight=weight,fontFeatureSettings="tnum",letterSpacing=0.sp)
@Composable fun LedgerTheme(content:@Composable ()->Unit) {
    MaterialTheme(colorScheme=palette,typography=Typography(
        headlineLarge=type(32,38,FontWeight.Medium),headlineMedium=type(28,34,FontWeight.Medium),
        titleLarge=type(22,28,FontWeight.Medium),titleMedium=type(16,22,FontWeight.Medium),titleSmall=type(14,20,FontWeight.Medium),
        bodyLarge=type(16,24),bodyMedium=type(14,20),bodySmall=type(12,16),
        labelLarge=type(14,20,FontWeight.Medium),labelMedium=type(12,16,FontWeight.Medium),labelSmall=type(12,16)
    ),content=content)
}
