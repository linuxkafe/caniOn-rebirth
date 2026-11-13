package org.caniorebirth.android

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun activityLaunches() {
        // If the activity crashes during launch, this test fails.
        activityRule.scenario.onActivity { /* no-op */ }
    }
}
