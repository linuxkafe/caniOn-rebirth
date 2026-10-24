package org.caniorebirth.android

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabNavigationTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun switchTabs() {
        onView(withText("Cells")).perform(click())
        onView(withText("Heater")).perform(click())
        onView(withText("Vehicle")).perform(click())
        onView(withText("Map")).perform(click())
        onView(withText("Dashboard")).perform(click())
    }
}
