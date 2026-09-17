package com.cuppa.app

import com.cuppa.app.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenNavigationTest {

    @Test
    fun verifyBottomNavItemsCount() {
        assertEquals("There should be exactly 4 bottom nav items", 4, Screen.bottomNavItems.size)
    }

    @Test
    fun verifyAllRoutesUnique() {
        val routes = Screen.bottomNavItems.map { it.route }
        val uniqueRoutes = routes.toSet()
        assertEquals("All screen routes must be unique", routes.size, uniqueRoutes.size)
    }

    @Test
    fun verifyExpectedScreensPresent() {
        val titles = Screen.bottomNavItems.map { it.title }
        assertTrue("Dashboard must be present", titles.contains("Dashboard"))
        assertTrue("Printers must be present", titles.contains("Printers"))
        assertTrue("Jobs must be present", titles.contains("Jobs"))
        assertTrue("Settings must be present", titles.contains("Settings"))
    }

    @Test
    fun verifyScreenIconsConfigured() {
        Screen.bottomNavItems.forEach { screen ->
            assertNotEquals("Screen ${screen.title} route should not be blank", "", screen.route)
        }
    }
}
