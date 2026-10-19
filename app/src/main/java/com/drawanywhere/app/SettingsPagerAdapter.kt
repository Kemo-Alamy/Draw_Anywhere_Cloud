package com.drawanywhere.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter بسيط لصفحتين: Settings و About
 * شغال من غير Fragments لأننا جوا Service مش Activity
 */
class SettingsPagerAdapter(
    private val onSettingsPageReady: (View) -> Unit,
    private val onAboutPageReady: (View) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private class SimpleHolder(view: View) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutId = if (viewType == 0) R.layout.panel_settings_page1
        else R.layout.panel_settings_page2
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return SimpleHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position == 0) onSettingsPageReady(holder.itemView)
        else onAboutPageReady(holder.itemView)
    }

    override fun getItemViewType(position: Int): Int = position
    override fun getItemCount(): Int = 2
}
