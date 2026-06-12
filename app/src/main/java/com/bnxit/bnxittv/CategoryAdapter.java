package com.bnxit.bnxittv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the category sidebar.
 * TV-optimized: focus-based highlighting, no animations.
 */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.CategoryViewHolder> {

    private List<String> categories = new ArrayList<>();
    private OnCategoryClickListener listener;
    private int selectedPosition = 0;

    public interface OnCategoryClickListener {
        void onCategoryClick(String category, int position);
        void onCategorySelected(String category, int position);
    }

    public void setOnCategoryClickListener(OnCategoryClickListener listener) {
        this.listener = listener;
    }

    public void setCategories(List<String> categories) {
        this.categories = categories != null ? categories : new ArrayList<>();
        this.selectedPosition = 0;
        notifyDataSetChanged();
    }

    public void setSelectedPosition(int position) {
        int oldPos = this.selectedPosition;
        this.selectedPosition = position;
        if (oldPos >= 0 && oldPos < categories.size()) {
            notifyItemChanged(oldPos, "selection_changed");
        }
        if (position >= 0 && position < categories.size()) {
            notifyItemChanged(position, "selection_changed");
        }
    }

    public int getSelectedPosition() {
        return selectedPosition;
    }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_category, parent, false);
        return new CategoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            if (holder.tvName.hasFocus() || position == selectedPosition) {
                holder.tvName.setTextColor(0xFFFFFFFF); // White when focused or selected
            } else {
                holder.tvName.setTextColor(0xFFC4C4D0); // Dim when normal
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        String category = categories.get(position);

        // Add emoji prefix for visual distinction
        String displayText = getCategoryEmoji(category) + " " + category;
        holder.tvName.setText(displayText);

        // Selection state manually handled via text color to prevent focus conflicts
        boolean isSelected = (position == selectedPosition);

        // Text color based on selection and focus
        if (holder.tvName.hasFocus() || position == selectedPosition) {
            holder.tvName.setTextColor(0xFFFFFFFF); // White when focused or selected
        } else {
            holder.tvName.setTextColor(0xFFC4C4D0); // Dim when normal
        }

        // Focus animation (scale & elevation)
        holder.tvName.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.04f).scaleY(1.04f).translationZ(6f).setDuration(150).start();
                holder.tvName.setTextColor(0xFFFFFFFF);
                
                int adapterPos = holder.getAdapterPosition();
                if (adapterPos != RecyclerView.NO_POSITION && adapterPos != selectedPosition) {
                    setSelectedPosition(adapterPos);
                    if (listener != null) {
                        listener.onCategorySelected(categories.get(adapterPos), adapterPos);
                    }
                }
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start();
                int adapterPos = holder.getAdapterPosition();
                if (adapterPos == selectedPosition) {
                    holder.tvName.setTextColor(0xFFFFFFFF);
                } else {
                    holder.tvName.setTextColor(0xFFC4C4D0);
                }
            }
        });

        // Click handler
        holder.tvName.setOnClickListener(v -> {
            int adapterPos = holder.getAdapterPosition();
            if (adapterPos != RecyclerView.NO_POSITION && listener != null) {
                setSelectedPosition(adapterPos);
                listener.onCategoryClick(categories.get(adapterPos), adapterPos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return categories.size();
    }

    /**
     * Get emoji prefix for category name for visual distinction.
     */
    private String getCategoryEmoji(String category) {
        if (category == null) return "📺";

        String lower = category.toLowerCase();
        if ("all".equals(lower)) return "📺";
        if (lower.contains("sport") || lower.contains("cricket")) return "⚽";
        if (lower.contains("news")) return "📰";
        if (lower.contains("bangla")) return "🇧🇩";
        if (lower.contains("movie") || lower.contains("cinema")) return "🎬";
        if (lower.contains("english")) return "🇬🇧";
        if (lower.contains("fifa")) return "🏆";
        if (lower.contains("travel")) return "✈️";
        if (lower.contains("india")) return "🇮🇳";
        if (lower.contains("music")) return "🎵";
        if (lower.contains("kids") || lower.contains("cartoon")) return "🧸";
        if (lower.contains("entertainment")) return "🎭";
        return "📺";
    }

    /**
     * Find position of a category by name.
     */
    public int findPosition(String category) {
        if (category == null) return 0;
        for (int i = 0; i < categories.size(); i++) {
            if (category.equals(categories.get(i))) {
                return i;
            }
        }
        return 0;
    }

    static class CategoryViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;

        CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = (TextView) itemView; // item_category.xml is just a TextView
        }
    }
}
