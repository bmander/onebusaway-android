/*
 * Copyright (C) 2015-2026 University of South Florida (sjbarbeau@gmail.com),
 * Open Transit Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onebusaway.android.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.onebusaway.android.R
import org.onebusaway.android.ui.compose.components.ObaTopAppBar
import org.onebusaway.android.ui.compose.theme.ObaTheme
import org.onebusaway.android.ui.nav.NavRoutes

/**
 * Launches the about screen (version, license, and contributor information).
 *
 * Campaign C: the about screen is a NavHost destination hosted by [HomeActivity]; this is no longer
 * an Activity but a launcher facade. `start` builds an explicit [HomeActivity] intent carrying the
 * [NavRoutes.ABOUT] route, which HomeActivity's translator navigates to. The screen prose lives in
 * per-locale string resources, the contributor/translator/credit names in string-arrays, and URLs
 * in the prose render as tappable links. (Non-exported, launched only in-app, so no alias needed.)
 */
object AboutActivity {

    @JvmStatic
    fun start(context: Context) {
        context.startActivity(HomeActivity.navIntent(context, NavRoutes.ABOUT))
    }
}

/** The displayed "Version: name (code)" line, or empty if the package info can't be read. */
internal fun buildVersionText(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    @Suppress("DEPRECATION")
    "Version: ${info.versionName} (${info.versionCode})"
} catch (e: PackageManager.NameNotFoundException) {
    ""
}

@Composable
internal fun AboutScreen(versionText: String, onBack: () -> Unit) {
    Scaffold(
        topBar = { ObaTopAppBar(stringResource(R.string.title_activity_about), onBack) }
    ) { padding ->
        val appName = stringResource(R.string.app_name)
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(versionText, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_welcome_title, appName),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colorResource(R.color.theme_primary)
            )
            Paragraph(stringResource(R.string.about_intro, appName))

            SectionHeader(stringResource(R.string.about_contributors_header))
            Paragraph(stringResource(R.string.about_contributors_intro))
            NameList(stringArrayResource(R.array.about_code_contributors), bold = true)

            SectionHeader(stringResource(R.string.about_translations_header))
            Paragraph(stringResource(R.string.about_translations_intro, appName))
            NameList(stringArrayResource(R.array.about_translators), bold = true)

            SectionHeader(stringResource(R.string.about_image_credits_header))
            Paragraph(stringResource(R.string.about_image_credits_intro))
            NameList(stringArrayResource(R.array.about_image_credits), bold = false)

            SectionHeader(stringResource(R.string.about_join_header))
            Paragraph(stringResource(R.string.about_join_github))
            Paragraph(stringResource(R.string.about_join_oba, appName))
            Paragraph(stringResource(R.string.about_thanks_material_icons))
            Paragraph(stringResource(R.string.about_thanks_amcharts))
            Paragraph(stringResource(R.string.about_outro, appName))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

/** A body paragraph with any bare URLs rendered as tappable links. */
@Composable
private fun Paragraph(text: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val linked = remember(text, linkColor) { linkifyUrls(text, linkColor) }
    Text(
        text = linked,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun NameList(names: Array<String>, bold: Boolean) {
    Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
        names.forEach { name ->
            Row {
                Text("•  ", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

private val URL_REGEX = Regex("""https?://[^\s)]+""")

/** Wraps every bare URL in [text] with a tappable, underlined [LinkAnnotation.Url]. */
private fun linkifyUrls(text: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString {
        var last = 0
        for (match in URL_REGEX.findAll(text)) {
            val url = match.value.trimEnd('.', ',')
            append(text.substring(last, match.range.first))
            withLink(
                LinkAnnotation.Url(
                    url,
                    TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                    )
                )
            ) {
                append(url)
            }
            last = match.range.first + url.length
        }
        append(text.substring(last))
    }

@Preview(showBackground = true)
@Composable
private fun AboutPreview() {
    ObaTheme {
        AboutScreen(versionText = "Version: 26.1.0 (153)", onBack = {})
    }
}
