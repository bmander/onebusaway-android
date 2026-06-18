/*
 * Copyright (C) 2015-2017 University of South Florida (sjbarbeau@gmail.com)
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

package org.onebusaway.android.util;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ImageSpan;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import androidx.annotation.DrawableRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.res.ResourcesCompat;

import com.github.amlcurran.showcaseview.OnShowcaseEventListener;
import com.github.amlcurran.showcaseview.ShowcaseView;
import com.github.amlcurran.showcaseview.targets.Target;
import com.github.amlcurran.showcaseview.targets.ViewTarget;

import org.onebusaway.android.BuildConfig;
import org.onebusaway.android.R;
import org.onebusaway.android.app.di.PreferencesEntryPoint;
import org.onebusaway.android.io.request.ObaArrivalInfoResponse;
import org.onebusaway.android.preferences.PreferencesRepository;
import org.onebusaway.android.ui.tutorial.ArrivalTutorial;

/**
 * A class containing utility methods related to showing a tutorial to users for how to use various
 * OBA features, using the ShowcaseView library (https://github.com/amlcurran/ShowcaseView).
 */
public class ShowcaseViewUtils {

    public static final String TUTORIAL_WELCOME = ".tutorial_welcome";

    public static final String TUTORIAL_OPT_OUT_DIALOG = ".tutorial_opt_out_dialog";

    public static final String TUTORIAL_ARRIVAL_SORT = ".tutorial_arrival_sort";

    public static final String TUTORIAL_RECENT_STOPS_ROUTES = ".tutorial_recent_stops_routes";

    public static final String TUTORIAL_STARRED_STOPS_SORT = ".tutorial_starred_stops_sort";

    public static final String TUTORIAL_STARRED_STOPS_SHORTCUT = ".tutorial_starred stops_shortcut";

    public static final String TUTORIAL_SEND_FEEDBACK_OPEN311_CATEGORIES
            = ".tutorial_send_feedback_open311_categories";

    private static ShowcaseView mShowcaseView;

    /**
     * Shows the tutorial for the specified tutorialType.  This method handles checking to see if
     * other ShowcaseViews are already being shown, as well as if this tutorial has already been
     * shown - if either of these cases are true, this method is a no-op.
     *
     * @param tutorialType type of tutorial to show, defined by the TUTORIAL_* constants in
     *                     ShowcaseViewUtils
     * @param activity     activity used to show the tutorial
     * @param response     The response that contains arrival info, or null if this is not available.
     *                     Some tutorials require that arrival info is showing - these tutorials
     *                     will only be displayed if arrival info is provided in this parameter.
     * @param alwaysShow   true if the tutorial should be shown to the user even if they chose to
     *                     turn off tutorials, false if we should follow the user preference
     */
    public synchronized static void showTutorial(String tutorialType,
                                                 final AppCompatActivity activity, final ObaArrivalInfoResponse response, boolean alwaysShow) {
        if (activity == null) {
            return;
        }
        if (isShowcaseViewShowing()) {
            return;
        }

        PreferencesRepository prefs = PreferencesEntryPoint.get(activity);

        // If user has opted out of tutorials, do nothing
        boolean showTutorials = prefs.getBoolean(R.string.preference_key_show_tutorial_screens, true);
        if (!showTutorials && !alwaysShow) {
            return;
        }

        // If we've already shown this tutorial to the user, do nothing
        boolean showedThisTutorial = prefs.getBoolean(tutorialType, false);
        if (showedThisTutorial) {
            return;
        }

        // Make sure we're not spamming the user with tutorials
        if (giveUserTutorialBreak(activity, tutorialType)) {
            return;
        }

        Resources r = activity.getResources();
        OnShowcaseEventListener listener = null;
        boolean moveButtonLeft = false;

        String title;
        SpannableString text;
        Target target = Target.NONE;

        String appName = r.getString(R.string.app_name);
        switch (tutorialType) {
            case TUTORIAL_WELCOME:
                title = r.getString(R.string.tutorial_welcome_title, appName);
                text = new SpannableString(r.getString(R.string.tutorial_welcome_text));
                break;
            case TUTORIAL_ARRIVAL_SORT:
                title = r.getString(R.string.tutorial_arrival_sort_title);
                text = new SpannableString(r.getString(R.string.tutorial_arrival_sort_text));
                addIcon(activity, text, R.drawable.ic_action_content_sort);
                break;
            case TUTORIAL_RECENT_STOPS_ROUTES:
                title = r.getString(R.string.tutorial_recent_stops_routes_title);
                text = new SpannableString(
                        r.getString(R.string.tutorial_recent_stops_routes_text));
                addIcon(activity, text, R.drawable.ic_navigation_more_vert);
                break;
            case TUTORIAL_STARRED_STOPS_SORT:
                title = r.getString(R.string.tutorial_starred_stops_sort_title);
                text = new SpannableString(r.getString(R.string.tutorial_starred_stops_sort_text));
                addIcon(activity, text, R.drawable.ic_action_content_sort);
                break;
            case TUTORIAL_STARRED_STOPS_SHORTCUT:
                title = r.getString(R.string.tutorial_starred_stops_shortcut_title);
                text = new SpannableString(
                        r.getString(R.string.tutorial_starred_stops_shortcut_text));
                break;
            default:
                throw new IllegalArgumentException(
                        "tutorialType must be one of the TUTORIAL_* constants in ShowcaseViewUtils");
        }

        mShowcaseView = new ShowcaseView.Builder(activity)
                .setTarget(target)
                .setStyle(R.style.CustomShowcaseTheme)
                .setContentTitle(title)
                .setContentText(text)
                .build();
        // If button should be positioned to the left, then set the parameters
        if (moveButtonLeft) {
            moveButtonLeft(activity, mShowcaseView);
        }
        if (listener != null) {
            mShowcaseView.setOnShowcaseEventListener(listener);
        }

        // Set the preference for this tutorial type so it doesn't show again
        doNotShowTutorial(activity, tutorialType);
    }

    /**
     * Returns true if the ShowcaseView is currently showing, false if it is not
     *
     * @return true if the ShowcaseView is currently showing, false if it is not
     */
    public static boolean isShowcaseViewShowing() {
        return mShowcaseView != null && mShowcaseView.isShowing();
    }

    /**
     * Hides a currently showing ShowcaseView
     */
    public static void hideShowcaseView() {
        if (mShowcaseView != null) {
            mShowcaseView.hide();
        }
    }

    /**
     * Give the user a break from tutorials - only show every 10th time, unless its the beginning
     * three important screens or the intro to the new trip planning geocoder
     *
     * @param tutorialType type of tutorial to show, defined by the TUTORIAL_* constants in
     *                     ShowcaseViewUtils
     * @return true if we should give the user a break and not show a tutorial, false if it's ok
     * to show them one
     */
    private static boolean giveUserTutorialBreak(Context context, String tutorialType) {
        final String TUTORIAL_COUNTER = context.getString(R.string.preference_key_tutorial_counter);
        if (!tutorialType.equals(TUTORIAL_WELCOME)) {

            PreferencesRepository prefs = PreferencesEntryPoint.get(context);
            int counter = prefs.getInt(TUTORIAL_COUNTER, 0);
            counter++;
            prefs.setInt(TUTORIAL_COUNTER, counter);

            if (!(counter % 10 == 0)) {
                // Wait longer to show the next tutorial
                return true;
            }
        }
        return false;
    }

    /**
     * Adds the provided icon to the right side of the provided SpannableString
     *
     * @param text             SpannableString to add sort icon to
     * @param drawableResource ID of the drawable resource to add to the right side of the
     *                         SpannableString
     */
    private static void addIcon(Context context, SpannableString text,
            @DrawableRes int drawableResource) {
        Drawable d = ResourcesCompat.getDrawable(context.getResources(),
                drawableResource, context.getTheme());
        d.setBounds(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        d.setColorFilter(context.getResources().getColor(R.color.header_text_color),
                PorterDuff.Mode.SRC_IN);
        ImageSpan imageSpan = new ImageSpan(d, ImageSpan.ALIGN_BOTTOM);
        text.setSpan(imageSpan, text.length() - 1, text.length(),
                Spannable.SPAN_INCLUSIVE_INCLUSIVE);
    }

    /**
     * Moves the button to acknowledge the ShowcaseView to be left aligned
     *
     * @param v ShowcaseView for which the button should be left aligned
     */
    private static void moveButtonLeft(Context context, ShowcaseView v) {
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        lp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        int p = ViewUtils.dpToPixels(context, 12);
        lp.setMargins(p, p, p, p);
        v.setButtonPosition(lp);
    }

    /**
     * Sets a particular tutorial to the "viewed" state, so a user won't ever see it again.
     *
     * This is also useful in the case where a user has already found a particular feature but we
     * haven't yet shown them the tutorial for that feature.  In this case, they don't need to see
     * the tutorial for that feature, so we can avoid annoying them with another popup for something
     * they already know.
     *
     * @param tutorialType type of tutorial to not show, defined by the TUTORIAL_* constants in
     *                     ShowcaseViewUtils
     */
    public static void doNotShowTutorial(Context context, String tutorialType) {
        PreferencesEntryPoint.get(context).setBoolean(tutorialType, true);
    }

    /**
     * Resets all tutorials so they are shown to the user again.  If any ShowcaseView is showing,
     * it will be hidden.
     */
    public static void resetAllTutorials(Context context) {
        if (mShowcaseView != null) {
            mShowcaseView.hide();
        }
        PreferencesRepository prefs = PreferencesEntryPoint.get(context);
        prefs.setBoolean(R.string.preference_key_show_tutorial_screens, true);

        prefs.setBoolean(TUTORIAL_WELCOME, false);
        prefs.setBoolean(TUTORIAL_ARRIVAL_SORT, false);
        prefs.setBoolean(TUTORIAL_RECENT_STOPS_ROUTES, false);
        prefs.setBoolean(TUTORIAL_STARRED_STOPS_SORT, false);
        prefs.setBoolean(TUTORIAL_STARRED_STOPS_SHORTCUT, false);
        prefs.setBoolean(TUTORIAL_SEND_FEEDBACK_OPEN311_CATEGORIES, false);

        // Re-arm the Compose arrivals-panel onboarding spotlight (its keys live with the sequence).
        for (String key : ArrivalTutorial.resetKeys()) {
            prefs.setBoolean(key, false);
        }
    }
}
