package app.verdant.search

import org.junit.Assert.*
import org.junit.Test

class ThemeTest {
    @Test fun bothLooksKeepTextReadable() {
        for(option in listOf(DarkLook,LightLook)) {
            val scheme=colorScheme(option)
            assertTrue(option.name+" titles on page",contrastRatio(scheme.onBackground,scheme.background)>=7f)
            assertTrue(option.name+" text on cards",contrastRatio(scheme.onSurface,scheme.surface)>=4.5f)
            assertTrue(option.name+" muted text on page",contrastRatio(scheme.onSurfaceVariant,scheme.background)>=4.5f)
            assertTrue(option.name+" muted text on cards",contrastRatio(scheme.onSurfaceVariant,scheme.surface)>=4.5f)
            assertTrue(option.name+" text on buttons",contrastRatio(scheme.onPrimary,scheme.primary)>=4.5f)
            assertTrue(option.name+" selected chips",contrastRatio(scheme.onSecondaryContainer,scheme.secondaryContainer)>=4.5f)
            assertTrue(option.name+" snackbar action",contrastRatio(scheme.inversePrimary,scheme.inverseSurface)>=4.5f)
            assertTrue(option.name+" accent on page",contrastRatio(scheme.primary,scheme.background)>=4.5f)
        }
        assertTrue(DarkLook.dark);assertFalse(LightLook.dark)
    }
}
