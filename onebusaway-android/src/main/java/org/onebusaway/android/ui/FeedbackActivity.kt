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
package org.onebusaway.android.ui

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.firebase.analytics.FirebaseAnalytics
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.apache.commons.io.FileUtils
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.nav.NavigationService
import org.onebusaway.android.nav.NavigationService.LOG_DIRECTORY
import org.onebusaway.android.nav.NavigationUploadWorker
import org.onebusaway.android.ui.compose.components.ObaTopAppBar
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.util.PreferenceUtils

/**
 * Collects thumbs-up/down feedback (plus optional comments) after a destination-reminder trip,
 * launched from the post-trip notification's Yes/No actions. On send, the trip's navigation log
 * either gets the feedback appended and is queued for upload, or is deleted and the feedback goes
 * to analytics only — matching the user's "share logs" choice.
 */
class FeedbackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialLiked = intent?.getIntExtra(RESPONSE, 0) == FEEDBACK_YES
        setContent {
            ObaTheme {
                FeedbackScreen(
                    initialLiked = initialLiked,
                    initialSendLogs = shareLogsPref(),
                    onBack = { finish() },
                    onSendLogsChanged = { share ->
                        PreferenceUtils.saveBoolean(
                            getString(R.string.preferences_key_user_share_destination_logs), share
                        )
                    },
                    onSend = { liked, text ->
                        submitFeedback(liked, text)
                        finish()
                    }
                )
            }
        }
    }

    private fun shareLogsPref(): Boolean = Application.getPrefs()
        .getBoolean(getString(R.string.preferences_key_user_share_destination_logs), true)

    private fun submitFeedback(liked: Boolean, feedback: String) {
        PreferenceUtils.saveBoolean(NavigationService.FIRST_FEEDBACK, false)
        if (shareLogsPref()) {
            moveLog(liked, feedback)
        } else {
            deleteLog()
            logFeedback(liked, feedback)
        }
        Toast.makeText(this, getString(R.string.feedback_notify_confirmation), Toast.LENGTH_SHORT)
            .show()
    }

    /** Appends the feedback to the trip log and moves it to the upload folder for its response. */
    private fun moveLog(liked: Boolean, feedback: String) {
        val logFilePath = intent?.getStringExtra(LOG_FILE) ?: return
        val response = getString(
            if (liked) R.string.analytics_label_destination_reminder_yes
            else R.string.analytics_label_destination_reminder_no
        )
        try {
            val logFile = File(logFilePath)
            FileUtils.write(logFile, System.lineSeparator() + feedback, true)
            val destFolder = File(
                applicationContext.filesDir.absolutePath
                        + File.separator + LOG_DIRECTORY + File.separator + response
            )
            try {
                FileUtils.moveFileToDirectory(logFile, destFolder, true)
            } catch (e: Exception) {
                Log.e(TAG, "File move failed")
            }
            setupLogUploadTask()
        } catch (e: IOException) {
            Log.e(TAG, "File write failed: $e")
        }
    }

    private fun deleteLog() {
        val logFilePath = intent?.getStringExtra(LOG_FILE) ?: return
        val deleted = File(logFilePath).delete()
        Log.d(TAG, "Log deleted $deleted")
    }

    private fun setupLogUploadTask() {
        val uploadCheckWork = PeriodicWorkRequest
            .Builder(NavigationUploadWorker::class.java, 24, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance().enqueue(uploadCheckWork)
    }

    private fun logFeedback(liked: Boolean, feedbackText: String) {
        ObaAnalytics.reportDestinationReminderFeedback(
            FirebaseAnalytics.getInstance(this), liked, feedbackText.ifEmpty { null }, null
        )
    }

    companion object {

        const val TAG = "FeedbackActivity"

        const val TRIP_ID = ".TRIP_ID"
        const val NOTIFICATION_ID = ".NOTIFICATION_ID"
        const val RESPONSE = ".RESPONSE"
        const val LOG_FILE = ".LOG_FILE"

        const val FEEDBACK_NO = 1
        const val FEEDBACK_YES = 2
    }
}

@Composable
private fun FeedbackScreen(
    initialLiked: Boolean,
    initialSendLogs: Boolean,
    onBack: () -> Unit,
    onSendLogsChanged: (Boolean) -> Unit,
    onSend: (liked: Boolean, text: String) -> Unit
) {
    var liked by rememberSaveable { mutableStateOf(initialLiked) }
    var text by rememberSaveable { mutableStateOf("") }
    var sendLogs by rememberSaveable { mutableStateOf(initialSendLogs) }
    Scaffold(
        topBar = {
            ObaTopAppBar(stringResource(R.string.feedback_label), onBack) {
                IconButton(onClick = { onSend(liked, text) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_social_send_now),
                        contentDescription = stringResource(R.string.report_problem_send),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.feedback_msg), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                ThumbButton(
                    selected = liked,
                    upvote = true,
                    onClick = { liked = true },
                    modifier = Modifier.weight(1f)
                )
                ThumbButton(
                    selected = !liked,
                    upvote = false,
                    onClick = { liked = false },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.feedback_freeText)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = sendLogs,
                    onCheckedChange = {
                        sendLogs = it
                        onSendLogsChanged(it)
                    }
                )
                Text(
                    stringResource(R.string.feedback_checkbox_text),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.feedback_log_guide, stringResource(R.string.app_name)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One half of the thumbs up / thumbs down pair; the selected side shows its filled icon. */
@Composable
private fun ThumbButton(
    selected: Boolean,
    upvote: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when {
        upvote && selected -> R.drawable.ic_thumb_up_selected
        upvote -> R.drawable.ic_thumb_up
        selected -> R.drawable.ic_thumb_down_selected
        else -> R.drawable.ic_thumb_down
    }
    val description = stringResource(
        if (upvote) R.string.feedback_like_button_description
        else R.string.feedback_dislike_button_description
    )
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = Color.Unspecified,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FeedbackPreview() {
    ObaTheme {
        FeedbackScreen(
            initialLiked = true,
            initialSendLogs = true,
            onBack = {},
            onSendLogsChanged = {},
            onSend = { _, _ -> }
        )
    }
}
