package com.bnxit.bnxittv;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the channel list.
 * Optimized for TV: focus-based highlighting, no animations, ViewHolder pattern.
 */
public class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder> {

    private List<ChannelModel> channels = new ArrayList<>();
    private OnChannelClickListener listener;
    private int selectedPosition = -1;
    private String currentPlayingUrl = null;

    // Pre-computed category colors to avoid lookups in onBindViewHolder
    private static final int COLOR_SPORTS = 0xFF4CAF50;
    private static final int COLOR_NEWS = 0xFFE53935;
    private static final int COLOR_BANGLA = 0xFF29B6F6;
    private static final int COLOR_MOVIES = 0xFFFF9800;
    private static final int COLOR_ENTERTAINMENT = 0xFFAB47BC;
    private static final int COLOR_DEFAULT = 0xFF78909C;

    public interface OnChannelClickListener {
        void onChannelClick(ChannelModel channel, int position);
    }

    public void setOnChannelClickListener(OnChannelClickListener listener) {
        this.listener = listener;
    }

    public void setChannels(List<ChannelModel> channels) {
        this.channels = channels != null ? channels : new ArrayList<>();
        this.selectedPosition = -1;
        notifyDataSetChanged();
    }

    public void setCurrentPlayingUrl(String url) {
        String oldUrl = this.currentPlayingUrl;
        this.currentPlayingUrl = url;

        if (oldUrl != null) {
            int oldPos = findPositionByUrl(oldUrl);
            if (oldPos >= 0) notifyItemChanged(oldPos, "playing_changed");
        }

        if (url != null) {
            int newPos = findPositionByUrl(url);
            if (newPos >= 0) notifyItemChanged(newPos, "playing_changed");
        }
    }

    public void setSelectedPosition(int position) {
        int oldPos = this.selectedPosition;
        this.selectedPosition = position;
        if (oldPos >= 0 && oldPos < channels.size()) {
            notifyItemChanged(oldPos, "selection_changed");
        }
        if (position >= 0 && position < channels.size()) {
            notifyItemChanged(position, "selection_changed");
        }
    }

    @NonNull
    @Override
    public ChannelViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel, parent, false);
        return new ChannelViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position);
        } else {
            ChannelModel channel = channels.get(position);
            boolean isCurrentlyPlaying = currentPlayingUrl != null && currentPlayingUrl.equals(channel.url);
            holder.tvLiveDot.setVisibility(isCurrentlyPlaying ? View.VISIBLE : View.GONE);

            boolean isSelected = (position == selectedPosition);
            if (holder.itemView.hasFocus() || isSelected || isCurrentlyPlaying) {
                holder.tvName.setTextColor(0xFFFFFFFF);
            } else {
                holder.tvName.setTextColor(0xFFC4C4D0);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull ChannelViewHolder holder, int position) {
        ChannelModel channel = channels.get(position);

        // Channel name
        holder.tvName.setText(channel.name);

        // Initial circle with category color
        char initial = channel.getInitial();
        holder.tvInitial.setText(String.valueOf(initial));

        // Set circle color based on category
        int color = getCategoryColor(channel.group);
        GradientDrawable bg = (GradientDrawable) holder.tvInitial.getBackground().mutate();
        bg.setColor(color);

        // Live indicator
        boolean isCurrentlyPlaying = currentPlayingUrl != null && currentPlayingUrl.equals(channel.url);
        holder.tvLiveDot.setVisibility(isCurrentlyPlaying ? View.VISIBLE : View.GONE);

        // Selection state logic manually handled via text color to avoid VerticalGridView focus conflicts
        boolean isSelected = (position == selectedPosition);

        // Text color based on selection/focus/playing states
        if (holder.itemView.hasFocus() || position == selectedPosition || isCurrentlyPlaying) {
            holder.tvName.setTextColor(0xFFFFFFFF);
        } else {
            holder.tvName.setTextColor(0xFFC4C4D0);
        }

        // Focus animation (scale & elevation)
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.04f).scaleY(1.04f).translationZ(6f).setDuration(150).start();
                holder.tvName.setTextColor(0xFFFFFFFF);
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start();
                int adapterPos = holder.getAdapterPosition();
                if (adapterPos != RecyclerView.NO_POSITION) {
                    ChannelModel ch = channels.get(adapterPos);
                    boolean isPlaying = currentPlayingUrl != null && currentPlayingUrl.equals(ch.url);
                    if (adapterPos == selectedPosition || isPlaying) {
                        holder.tvName.setTextColor(0xFFFFFFFF);
                    } else {
                        holder.tvName.setTextColor(0xFFC4C4D0);
                    }
                }
            }
        });

        // Click handler
        holder.itemView.setOnClickListener(v -> {
            int adapterPos = holder.getAdapterPosition();
            if (adapterPos != RecyclerView.NO_POSITION && listener != null) {
                setSelectedPosition(adapterPos);
                listener.onChannelClick(channels.get(adapterPos), adapterPos);
            }
        });
    }

    @Override
    public int getItemCount() {
        return channels.size();
    }

    /**
     * Find position of a channel by URL.
     */
    public int findPositionByUrl(String url) {
        if (url == null) return -1;
        for (int i = 0; i < channels.size(); i++) {
            if (url.equals(channels.get(i).url)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get category color for channel initial circle.
     * Uses string contains checks for performance (no regex).
     */
    private int getCategoryColor(String group) {
        if (group == null) return COLOR_DEFAULT;

        String lower = group.toLowerCase();
        if (lower.contains("sport") || lower.contains("cricket") || lower.contains("football")) {
            return COLOR_SPORTS;
        }
        if (lower.contains("news")) {
            return COLOR_NEWS;
        }
        if (lower.contains("bangla")) {
            return COLOR_BANGLA;
        }
        if (lower.contains("movie") || lower.contains("cinema")) {
            return COLOR_MOVIES;
        }
        if (lower.contains("entertain") || lower.contains("english") || lower.contains("channel")) {
            return COLOR_ENTERTAINMENT;
        }
        return COLOR_DEFAULT;
    }

    static class ChannelViewHolder extends RecyclerView.ViewHolder {
        final TextView tvInitial;
        final TextView tvName;
        final TextView tvLiveDot;

        ChannelViewHolder(@NonNull View itemView) {
            super(itemView);
            tvInitial = itemView.findViewById(R.id.tv_channel_initial);
            tvName = itemView.findViewById(R.id.tv_channel_name);
            tvLiveDot = itemView.findViewById(R.id.tv_live_dot);
        }
    }
}
