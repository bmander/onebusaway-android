package org.onebusaway.android.database.survey

import android.content.Context
import org.onebusaway.android.database.AppDatabase
import org.onebusaway.android.database.DatabaseProvider
import org.onebusaway.android.database.survey.entity.Study
import org.onebusaway.android.database.survey.entity.Survey

/**
 * Repository class for managing Study and Survey data operations.
 * Provides methods for interacting with Study and Survey entities.
 *
 * Uses the shared [AppDatabase] singleton (storage-modernization) instead of a separate
 * `study-survey-db` file; the one-time import of any pre-existing `study-survey-db` data is handled by
 * `LegacyDataImporter.importSurveyDbIfNeeded`.
 */

class SurveyRepository(context: Context) {
    private val db: AppDatabase = DatabaseProvider.getDatabase(context)

    private val studiesDao = db.studiesDao()
    private val surveysDao = db.surveysDao()

    suspend fun addOrUpdateStudy(study: Study) {
        val existingStudy = studiesDao.getStudyById(study.study_id)
        if (existingStudy == null) {
            studiesDao.insertStudy(study)
        } else {
            studiesDao.updateStudy(study)
        }
    }

    suspend fun getAllStudies(): List<Study> {
        return studiesDao.getAllStudies()
    }

    suspend fun addSurvey(survey: Survey) {
        surveysDao.insertSurvey(survey)
    }

    suspend fun getSurveysForStudy(studyId: Int): List<Survey> {
        return surveysDao.getSurveysByStudyId(studyId)
    }

    suspend fun checkSurveyCompleted(surveyId: Int): Boolean {
        return surveysDao.isSurveyIdExists(surveyId)
    }

    suspend fun getAllSurveys(): List<Survey> {
        return surveysDao.getAllSurveys();
    }
}
