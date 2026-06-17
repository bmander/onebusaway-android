package org.onebusaway.android.ui.survey

/**
 * Interface for handling actions related to dismissing survey dialogs.
 */
interface SurveyActionsListener {
    fun onSkipSurvey()
    fun onRemindMeLater()
    fun onCancelSurvey()
}
