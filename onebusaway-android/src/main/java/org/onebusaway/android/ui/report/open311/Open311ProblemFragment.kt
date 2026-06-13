/*
 * Copyright (C) 2014 University of South Florida,
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
package org.onebusaway.android.ui.report.open311

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.analytics.FirebaseAnalytics
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import edu.usf.cutr.open311client.Open311
import edu.usf.cutr.open311client.models.Service
import org.onebusaway.android.R
import org.onebusaway.android.app.Application
import org.onebusaway.android.io.ObaAnalytics
import org.onebusaway.android.io.PlausibleAnalytics
import org.onebusaway.android.io.elements.ObaArrivalInfo
import org.onebusaway.android.report.ui.InfrastructureIssueActivity
import org.onebusaway.android.report.ui.ReportProblemFragmentCallback
import org.onebusaway.android.ui.compose.composeFragmentView
import org.onebusaway.android.util.UIUtils

/**
 * Compose host for the Open311 dynamic issue form, replacing the legacy AsyncTask-driven
 * Open311ProblemFragment. The form state and submit live in [Open311ProblemViewModel] (surviving
 * rotation); this fragment owns the modern image pickers (FileProvider + PickVisualMedia /
 * TakePicture), the send menu, analytics, and the host [ReportProblemFragmentCallback].
 */
class Open311ProblemFragment : Fragment(), MenuProvider {

    private var open311: Open311? = null
    private var service: Service? = null
    private var arrivalInfo: ObaArrivalInfo? = null
    private var agencyName: String? = null
    private var blockId: String? = null

    private var callback: ReportProblemFragmentCallback? = null
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    /** Absolute path of the file passed to the camera, promoted to the form only on success. */
    private var pendingCameraPath: String? = null

    private val viewModel: Open311ProblemViewModel by viewModels {
        viewModelFactory { initializer { createViewModel() } }
    }

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) viewModel.setImagePath(pendingCameraPath)
        }

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) copyPickedImage(uri)
        }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callback = context as? ReportProblemFragmentCallback
            ?: throw ClassCastException(
                "ReportProblemFragmentCallback should be implemented in parent activity"
            )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        firebaseAnalytics = FirebaseAnalytics.getInstance(requireContext())
        return composeFragmentView(inflater) {
            Open311Route(
                viewModel = viewModel,
                onTakePhoto = ::onTakePhoto,
                onPickFromGallery = ::onPickFromGallery
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.submitState.collect(::onSubmitState)
            }
        }
    }

    private fun onSubmitState(state: Open311SubmitState) {
        val activity = activity as? InfrastructureIssueActivity
        activity?.showProgress(state == Open311SubmitState.Submitting)
        when (state) {
            Open311SubmitState.Sent -> {
                callback?.onReportSent()
                viewModel.onSubmitStateHandled()
            }

            is Open311SubmitState.ValidationError -> {
                toast(state.message)
                viewModel.onSubmitStateHandled()
            }

            is Open311SubmitState.ServerError -> {
                toast(state.message)
                viewModel.onSubmitStateHandled()
            }

            else -> Unit
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.report_problem_options, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.report_problem_send) {
            onSend()
            return true
        }
        return false
    }

    private fun onSend() {
        hideKeyboard()
        viewModel.submit()
        service?.let {
            ObaAnalytics.reportUiEvent(
                firebaseAnalytics,
                Application.get().plausibleInstance,
                PlausibleAnalytics.REPORT_OPEN311_SERVER_EVENT_URL,
                getString(R.string.analytics_problem),
                it.service_name
            )
        }
    }

    private fun onTakePhoto() {
        val file = try {
            UIUtils.createImageFile(requireContext(), null)
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't open camera", e)
            toast(getString(R.string.ri_open_camera_problem))
            return
        }
        pendingCameraPath = file.absolutePath
        val uri = FileProvider.getUriForFile(requireContext(), fileProviderAuthority(), file)
        takePicture.launch(uri)
    }

    private fun onPickFromGallery() {
        pickMedia.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    /** Copies a picked content image into the cache so the repository can downsample from a file. */
    private fun copyPickedImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { copyUriToCache(uri) }
            if (path != null) {
                viewModel.setImagePath(path)
            } else {
                toast(getString(R.string.ri_resize_image_problem))
            }
        }
    }

    private fun copyUriToCache(uri: Uri): String? = try {
        val file = File.createTempFile("gallery_", ".jpg", requireContext().cacheDir)
        requireContext().contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(file).use { output -> input.copyTo(output) }
        }
        file.absolutePath
    } catch (e: IOException) {
        Log.e(TAG, "Couldn't copy picked image", e)
        null
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)
    }

    private fun toast(message: String) =
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()

    private fun fileProviderAuthority() = requireContext().packageName + ".fileprovider"

    private fun createViewModel(): Open311ProblemViewModel {
        val open311 = checkNotNull(open311) { "Open311 must be set before showing the fragment" }
        val service = checkNotNull(service) { "Service must be set before showing the fragment" }
        val activity = requireActivity() as InfrastructureIssueActivity

        // Snapshot the location/address/stop once; this screen is modal per chosen category.
        val issueContext = activity.currentIssueContext()
        val tripContext = arrivalInfo?.let { Open311TripContext(it, agencyName, blockId) }

        val repository = DefaultOpen311Repository(
            context = requireContext().applicationContext,
            service = service,
            open311 = open311,
            tripContext = tripContext,
            issueProvider = { issueContext }
        )
        return Open311ProblemViewModel(repository)
    }

    companion object {

        const val TAG = "Open311ProblemFragment"

        @JvmStatic
        @JvmOverloads
        fun show(
            activity: AppCompatActivity,
            containerViewId: Int,
            open311: Open311,
            service: Service,
            arrivalInfo: ObaArrivalInfo? = null,
            agencyName: String? = null,
            blockId: String? = null
        ) {
            val fragment = Open311ProblemFragment().apply {
                this.open311 = open311
                this.service = service
                this.arrivalInfo = arrivalInfo
                this.agencyName = agencyName
                this.blockId = blockId
            }
            try {
                activity.supportFragmentManager.beginTransaction()
                    .replace(containerViewId, fragment, TAG)
                    .addToBackStack(null)
                    .commit()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Cannot show Open311ProblemFragment after onSaveInstanceState", e)
            }
        }
    }
}
