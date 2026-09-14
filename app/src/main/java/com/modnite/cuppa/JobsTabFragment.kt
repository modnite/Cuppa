package com.modnite.cuppa

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton

class JobsTabFragment : Fragment() {

    private var jobQueueManager: JobQueueManager? = null

    fun setJobQueueManager(manager: JobQueueManager) {
        this.jobQueueManager = manager
        manager.onQueueJobsChanged = { jobs ->
            activity?.runOnUiThread {
                refreshAdapter(jobs)
            }
        }
        if (view != null) {
            refreshAdapter(manager.getQueuedJobs())
        }
    }

    private var isOldestFirst = true
    private lateinit var rvQueueJobs: RecyclerView
    private lateinit var tvEmptyQueuePlaceholder: TextView
    private lateinit var btnToggleSortOrder: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_jobs_tab, container, false)

        rvQueueJobs = view.findViewById(R.id.rvQueueJobs)
        tvEmptyQueuePlaceholder = view.findViewById(R.id.tvEmptyQueuePlaceholder)
        btnToggleSortOrder = view.findViewById(R.id.btnToggleSortOrder)

        rvQueueJobs.layoutManager = LinearLayoutManager(requireContext())

        jobQueueManager?.onQueueJobsChanged = { jobs ->
            activity?.runOnUiThread {
                refreshAdapter(jobs)
            }
        }

        btnToggleSortOrder.setOnClickListener {
            isOldestFirst = !isOldestFirst
            if (isOldestFirst) {
                btnToggleSortOrder.setImageResource(R.drawable.ic_arrow_upward)
                btnToggleSortOrder.contentDescription = getString(R.string.sort_oldest_first)
            } else {
                btnToggleSortOrder.setImageResource(R.drawable.ic_arrow_downward)
                btnToggleSortOrder.contentDescription = getString(R.string.sort_newest_first)
            }
            jobQueueManager?.let { refreshAdapter(it.getQueuedJobs()) }
        }

        jobQueueManager?.let { refreshAdapter(it.getQueuedJobs()) }

        return view
    }

    private fun refreshAdapter(jobs: List<JobQueueManager.PrintJob>) {
        val displayJobs = if (isOldestFirst) jobs else jobs.reversed()
        if (displayJobs.isEmpty()) {
            rvQueueJobs.visibility = View.GONE
            tvEmptyQueuePlaceholder.visibility = View.VISIBLE
        } else {
            rvQueueJobs.visibility = View.VISIBLE
            tvEmptyQueuePlaceholder.visibility = View.GONE
            rvQueueJobs.adapter = QueueAdapter(
                displayJobs,
                onHold = { job ->
                    jobQueueManager?.holdJob(job.id)
                },
                onRelease = { job ->
                    jobQueueManager?.releaseJob(job.id)
                },
                onCancel = { job ->
                    jobQueueManager?.cancelJob(job.id)
                }
            )
        }
    }

    override fun onDestroyView() {
        jobQueueManager?.onQueueJobsChanged = null
        super.onDestroyView()
    }

    private class QueueAdapter(
        private val jobs: List<JobQueueManager.PrintJob>,
        private val onHold: (JobQueueManager.PrintJob) -> Unit,
        private val onRelease: (JobQueueManager.PrintJob) -> Unit,
        private val onCancel: (JobQueueManager.PrintJob) -> Unit
    ) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

        class QueueViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvJobName: TextView = view.findViewById(R.id.tvJobName)
            val tvJobStatus: TextView = view.findViewById(R.id.tvJobStatus)
            val btnItemHold: MaterialButton = view.findViewById(R.id.btnItemPreview) // using existing ID
            val btnItemRelease: MaterialButton = view.findViewById(R.id.btnItemPrint)
            val btnItemCancel: MaterialButton = view.findViewById(R.id.btnItemDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue_job, parent, false)
            return QueueViewHolder(view)
        }

        override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
            val job = jobs[position]
            holder.tvJobName.text = "Job #${job.id} - ${job.name}"
            holder.tvJobStatus.text = "Status: ${job.status.name}"

            holder.btnItemHold.setOnClickListener { onHold(job) }
            holder.btnItemRelease.setOnClickListener { onRelease(job) }
            holder.btnItemCancel.setOnClickListener { onCancel(job) }
        }

        override fun getItemCount(): Int = jobs.size
    }
}
