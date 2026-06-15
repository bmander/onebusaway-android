/*
 * Copyright (C) 2026 Open Transit Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.onebusaway.android.testing.FakePreferencesRepository

/**
 * Unit tests for [HelpViewModel]'s dialog-state transitions (migrated from HomeViewModelTest when help
 * became its own feature module). `maybeAutoShowWhatsNew` still reads package info from Application, so
 * it's verified by equivalence rather than here.
 */
class HelpViewModelTest {

    @Test
    fun `showing the menu sets the dialog and the contact-us flag`() {
        val vm = HelpViewModel(FakePreferencesRepository())
        vm.showMenu(showContactUs = false)
        assertEquals(HelpDialog.Menu, vm.state.value.dialog)
        assertFalse(vm.state.value.showContactUs)
    }

    @Test
    fun `legend and what's-new transition the dialog, dismiss clears it`() {
        val vm = HelpViewModel(FakePreferencesRepository())
        vm.showLegend()
        assertEquals(HelpDialog.Legend, vm.state.value.dialog)
        vm.showWhatsNew()
        assertEquals(HelpDialog.WhatsNew, vm.state.value.dialog)
        vm.dismiss()
        assertEquals(HelpDialog.None, vm.state.value.dialog)
    }
}
