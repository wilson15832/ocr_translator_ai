package com.example.ocr_translation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.ocr_translation.R
import com.example.ocr_translation.TranslationArea
import android.util.Log

class TranslationAreasAdapter(
    private var areas: List<TranslationArea>,
    private val onAreaSelected: (TranslationArea) -> Unit,
    private val onAreaDelete: (TranslationArea) -> Unit
) : RecyclerView.Adapter<TranslationAreasAdapter.AreaViewHolder>() {

    class AreaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameTextView: TextView = view.findViewById(R.id.area_name)
        val sizeTextView: TextView = view.findViewById(R.id.area_size)
        val deleteButton: View = view.findViewById(R.id.delete_area_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AreaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_translation_area, parent, false)
        return AreaViewHolder(view)
    }

    override fun onBindViewHolder(holder: AreaViewHolder, position: Int) {
        val area = areas[position]
        holder.nameTextView.text = area.name
        holder.sizeTextView.text = "${area.right - area.left}x${area.bottom - area.top}"

        holder.itemView.setOnClickListener {
            Log.d("TranslationAreasAdapter", "Item view clicked at position $position") // <-- 添加此日志 (临时)
            onAreaSelected(area)
        }

        holder.deleteButton.setOnClickListener {
            onAreaDelete(area)
        }
    }

    override fun getItemCount() = areas.size

    fun updateAreas(newAreas: List<TranslationArea>) {
        areas = newAreas
        notifyDataSetChanged()
    }
}