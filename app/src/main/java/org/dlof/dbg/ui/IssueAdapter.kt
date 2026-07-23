package org.dlof.dbg.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.dlof.dbg.R
import org.dlof.dbg.databinding.ItemIssueBinding
import org.dlof.dbg.validator.DlofIssue
import org.dlof.dbg.validator.Severity

class IssueAdapter(private var items: List<DlofIssue> = emptyList()) :
    RecyclerView.Adapter<IssueAdapter.IssueViewHolder>() {

    fun submitList(newItems: List<DlofIssue>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IssueViewHolder {
        val binding = ItemIssueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return IssueViewHolder(binding)
    }

    override fun onBindViewHolder(holder: IssueViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class IssueViewHolder(private val binding: ItemIssueBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(issue: DlofIssue) {
            val ctx = binding.root.context
            val (barColor, textColor, label) = when (issue.severity) {
                Severity.ERROR -> Triple(R.color.dlof_error, R.color.dlof_error, ctx.getString(R.string.severity_error))
                Severity.WARNING -> Triple(R.color.dlof_warning, R.color.dlof_warning, ctx.getString(R.string.severity_warning))
                Severity.INFO -> Triple(R.color.dlof_info, R.color.dlof_info, ctx.getString(R.string.severity_info))
            }
            binding.severityBar.setBackgroundColor(ctx.getColor(barColor))
            binding.tvSeverity.setTextColor(ctx.getColor(textColor))
            binding.tvSeverity.text = label
            binding.tvMessage.text = issue.message

            val lineInfo = if (issue.line > 0) " • ${ctx.getString(R.string.label_line)} ${issue.line}" else ""
            binding.tvMeta.text = "${ctx.getString(R.string.label_file)} ${issue.fileName}$lineInfo"

            binding.tvFixable.visibility = if (issue.autoFixable) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
}
