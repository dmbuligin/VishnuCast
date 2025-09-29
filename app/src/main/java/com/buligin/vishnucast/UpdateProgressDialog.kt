package com.buligin.vishnucast.update

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.buligin.vishnucast.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UpdateProgressDialog(
    private val onCancel: (() -> Unit)? = null,
    private val onInstall: ((filePath: String) -> Unit)? = null,
) : DialogFragment() {

    private var job: Job? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_update_progress, null, false)

        val progress = view.findViewById<ProgressBar>(R.id.progressBar)
        val text = view.findViewById<TextView>(R.id.progressText)
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnInstall = view.findViewById<Button>(R.id.btnInstall)

        progress.isIndeterminate = true
        btnInstall.isEnabled = false

        btnCancel.setOnClickListener {
            onCancel?.invoke()
            dismissAllowingStateLoss()
        }
        btnInstall.setOnClickListener {
            val st = (UpdateManager.state.value as? UpdateManager.State.Completed)
            if (st != null) onInstall?.invoke(st.file.absolutePath)
        }

        // ВАЖНО: используем lifecycleScope фрагмента (а не viewLifecycleOwner),
        // т.к. у DialogFragment нет view lifecycle, когда мы в onCreateDialog().
        job = lifecycleScope.launch {
            UpdateManager.state.collectLatest { st ->
                when (st) {
                    is UpdateManager.State.Idle -> {
                        progress.isIndeterminate = true
                        progress.progress = 0
                        text.text = getString(R.string.update_downloading)
                        btnInstall.isEnabled = false
                    }
                    is UpdateManager.State.Running -> {
                        progress.isIndeterminate = false
                        progress.max = 100
                        progress.progress = st.progress
                        text.text = getString(R.string.update_progress_fmt, st.progress)
                        btnInstall.isEnabled = false
                    }
                    is UpdateManager.State.Completed -> {
                        progress.isIndeterminate = false
                        progress.progress = 100
                        text.text = getString(R.string.update_ready_install)
                        btnInstall.isEnabled = true
                    }
                    is UpdateManager.State.Failed -> {
                        progress.isIndeterminate = false
                        text.text = getString(R.string.update_failed_fmt, st.reason)
                        btnInstall.isEnabled = false
                    }
                }
            }
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .setCancelable(false)
            .create()
    }

    override fun onDestroy() {
        job?.cancel()
        job = null
        super.onDestroy()
    }
}
