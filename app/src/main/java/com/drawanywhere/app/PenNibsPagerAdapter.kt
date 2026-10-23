package com.drawanywhere.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class PenNibsPagerAdapter(
    private val onPage1Ready: (View) -> Unit,
    private val onPage2Ready: (View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private class SimpleHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = if (viewType == 0) R.layout.panel_pen_nibs_page1 else R.layout.panel_pen_nibs_page2
        return SimpleHolder(LayoutInflater.from(parent.context).inflate(layoutId, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) onPage1Ready(holder.itemView) else onPage2Ready(holder.itemView)
    }

    override fun getItemViewType(position: Int): Int = position
    override fun getItemCount(): Int = 2
}
