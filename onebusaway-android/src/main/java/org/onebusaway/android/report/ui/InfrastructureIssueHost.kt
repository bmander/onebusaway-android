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
package org.onebusaway.android.report.ui

import org.onebusaway.android.ui.report.open311.Open311IssueContext

/**
 * The host-activity surface the infrastructure-issue report fragments reach through `getActivity()`.
 * Formerly these were methods on `InfrastructureIssueActivity`; now that the screen is a NavHost
 * destination the host is [org.onebusaway.android.ui.HomeActivity], which implements this and forwards
 * to the destination's [org.onebusaway.android.ui.report.infrastructure.InfrastructureIssueViewModel]
 * (set on enter / cleared on dispose by `InfrastructureIssueDestination`).
 */
interface InfrastructureIssueHost {

    /** The current issue location/address/stop snapshot, for the hosted Open311 form. */
    fun currentIssueContext(): Open311IssueContext

    /** Shows or hides the indeterminate progress affordance while a report submits. */
    fun showProgress(visible: Boolean)

    /** Leaves the infrastructure-issue screen after a successful submission (pops the destination). */
    fun finishInfrastructureIssue()
}
