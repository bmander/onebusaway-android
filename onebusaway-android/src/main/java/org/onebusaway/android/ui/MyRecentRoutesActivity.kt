/*
 * Copyright (C) 2010-2017 Paul Watts (paulcwatts@gmail.com),
 * University of South  Florida (sjbarbeau@gmail.com)
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
package org.onebusaway.android.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutManagerCompat
import org.onebusaway.android.R
import org.onebusaway.android.ui.mylists.MyTabs
import org.onebusaway.android.util.UIUtils

/**
 * No-UI launcher-shortcut shell: on `CREATE_SHORTCUT` it pins a shortcut that deep-links into
 * [MyRoutesActivity]'s recent tab (`tab://recent_routes`), then finishes. Kept as its own manifest
 * entry so shortcuts pinned by older versions keep resolving.
 */
class MyRecentRoutesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            val shortcut = UIUtils.makeShortcutInfo(
                this,
                getString(R.string.recent_routes_shortcut),
                Intent(this, MyRoutesActivity::class.java).setData(MyTabs.defaultTabUri(MyTabs.RECENT_ROUTES)),
                R.drawable.ic_history
            )
            ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
            setResult(RESULT_OK, shortcut.intent)
        }
        finish()
    }
}
