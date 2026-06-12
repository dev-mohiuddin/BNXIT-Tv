package com.bnxit.bnxittv;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.common.Tracks;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.util.List;
import java.util.ArrayList;

import java.util.UUID;

/**
 * Manages ExoPlayer lifecycle and playback for IPTV streams.
 *
 * Features:
 * - Low buffer configuration for 1GB RAM device
 * - Hardware decoding with software fallback
 * - Auto retry on stream failure
 * - HLS, MP4, DASH support
 * - ClearKey DRM support
 * - Proper resource cleanup
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerManager {

    private static final String TAG = "PlayerManager";

    // Low buffer config for 1GB RAM device
    private static final int MIN_BUFFER_MS = 1000;
    private static final int MAX_BUFFER_MS = 3000;
    private static final int BUFFER_FOR_PLAYBACK_MS = 500;
    private static final int BUFFER_FOR_REBUFFER_MS = 1000;

    // Auto retry
    private static final int MAX_RETRY_COUNT = 5;
    private static final long RETRY_DELAY_MS = 3000;

    private ExoPlayer player;
    private PlayerView playerView;
    private final Context context;
    private final Handler mainHandler;

    private PlayerCallback callback;
    private ChannelModel currentChannel;
    private int retryCount = 0;
    private boolean isReleased = false;

    // Reusable data source factories
    private DefaultDataSource.Factory dataSourceFactory;

    public interface PlayerCallback {
        void onBuffering();
        void onPlaying();
        void onError(String message);
        void onIdle();
    }

    public PlayerManager(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        createDataSourceFactory();
    }

    private void createDataSourceFactory() {
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(8000)
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent("BNXIT-TV/1.0");

        dataSourceFactory = new DefaultDataSource.Factory(context, httpFactory);
    }

    /**
     * Initialize player and attach to PlayerView.
     */
    public void init(PlayerView view) {
        this.playerView = view;
        createPlayer();
    }

    private void createPlayer() {
        if (player != null) {
            releasePlayer();
        }

        isReleased = false;

        // Renderers: prefer hardware decoding, fallback to software
        DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                .setEnableDecoderFallback(true);

        // Low buffer load control
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, BUFFER_FOR_PLAYBACK_MS, BUFFER_FOR_REBUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        // Media source factory
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);

        player = new ExoPlayer.Builder(context)
                .setRenderersFactory(renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(mediaSourceFactory)
                .build();

        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        // Attach to view
        if (playerView != null) {
            playerView.setPlayer(player);
        }

        // Listener
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (isReleased) return;

                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        if (callback != null) callback.onBuffering();
                        break;
                    case Player.STATE_READY:
                        retryCount = 0;
                        if (callback != null) callback.onPlaying();
                        break;
                    case Player.STATE_ENDED:
                        // For live streams, retry
                        if (currentChannel != null) {
                            scheduleRetry();
                        }
                        break;
                    case Player.STATE_IDLE:
                        if (callback != null) callback.onIdle();
                        break;
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                if (isReleased) return;
                Log.e(TAG, "Playback error: " + error.getMessage());
                scheduleRetry();
            }
        });
    }

    /**
     * Play a channel. Handles HLS, DASH, and progressive streams.
     */
    public void playChannel(ChannelModel channel) {
        if (channel == null || channel.url == null || channel.url.isEmpty()) {
            if (callback != null) callback.onError("Invalid channel");
            return;
        }

        this.currentChannel = channel;
        this.retryCount = 0;

        if (player == null || isReleased) {
            createPlayer();
        }

        if (callback != null) callback.onBuffering();

        try {
            MediaSource mediaSource = buildMediaSource(channel);
            player.setMediaSource(mediaSource, true);
            player.prepare();
            player.setPlayWhenReady(true);

        } catch (Exception e) {
            Log.e(TAG, "Error starting playback", e);
            if (callback != null) callback.onError("Playback error");
        }
    }

    /**
     * Build the appropriate MediaSource based on stream type.
     */
    private MediaSource buildMediaSource(ChannelModel channel) {
        Uri uri = Uri.parse(channel.url);
        String url = channel.url.toLowerCase();

        // DASH streams
        if (channel.isDash()) {
            MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(uri);

            // ClearKey DRM
            if (channel.hasClearKey()) {
                String licenseUrl = buildClearKeyLicense(channel.kid, channel.key);
                MediaItem.DrmConfiguration drmConfig = new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                        .setLicenseUri(licenseUrl)
                        .build();
                itemBuilder.setDrmConfiguration(drmConfig);
            }

            return new DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(itemBuilder.build());
        }

        // HLS streams
        if (url.contains(".m3u8") || url.contains("hls")) {
            MediaItem item = MediaItem.fromUri(uri);
            return new HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }

        // Progressive (MP4, etc.) — use default factory
        MediaItem item = MediaItem.fromUri(uri);
        return new DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(item);
    }

    /**
     * Build ClearKey license data URI.
     */
    private String buildClearKeyLicense(String kid, String key) {
        // ClearKey uses a JSON license format
        String kidB64 = hexToBase64Url(kid);
        String keyB64 = hexToBase64Url(key);
        String json = "{\"keys\":[{\"kty\":\"oct\",\"k\":\"" + keyB64 + "\",\"kid\":\"" + kidB64 + "\"}],\"type\":\"temporary\"}";
        return "data:application/json;base64," + android.util.Base64.encodeToString(json.getBytes(), android.util.Base64.NO_WRAP);
    }

    /**
     * Convert hex string to base64url (no padding).
     */
    private String hexToBase64Url(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP);
        return b64;
    }

    /**
     * Auto retry on stream failure with exponential backoff.
     */
    private void scheduleRetry() {
        if (isReleased || currentChannel == null) return;

        retryCount++;
        if (retryCount > MAX_RETRY_COUNT) {
            if (callback != null) callback.onError("Stream unavailable");
            return;
        }

        Log.d(TAG, "Retry " + retryCount + "/" + MAX_RETRY_COUNT);
        if (callback != null) callback.onBuffering();

        mainHandler.postDelayed(() -> {
            if (!isReleased && currentChannel != null) {
                playChannel(currentChannel);
            }
        }, RETRY_DELAY_MS);
    }

    /**
     * Retry the current channel (for user-initiated retry).
     */
    public void retry() {
        retryCount = 0;
        if (currentChannel != null) {
            playChannel(currentChannel);
        }
    }

    public void setCallback(PlayerCallback callback) {
        this.callback = callback;
    }

    public void pause() {
        if (player != null && !isReleased) {
            player.setPlayWhenReady(false);
        }
    }

    public void resume() {
        if (player != null && !isReleased) {
            player.setPlayWhenReady(true);
        }
    }

    public void stop() {
        if (player != null && !isReleased) {
            player.stop();
        }
    }

    /**
     * Release player resources. Must be called in onDestroy.
     */
    public void release() {
        isReleased = true;
        mainHandler.removeCallbacksAndMessages(null);

        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }

        currentChannel = null;
        callback = null;
    }

    private void releasePlayer() {
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
    }

    // ---- HLS Quality Selection & Aspect Ratio Controls ----

    public static class TrackInfo {
        public final String name;
        public final int groupIndex;
        public final int trackIndex;
        public final boolean isSelected;

        public TrackInfo(String name, int groupIndex, int trackIndex, boolean isSelected) {
            this.name = name;
            this.groupIndex = groupIndex;
            this.trackIndex = trackIndex;
            this.isSelected = isSelected;
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    public List<TrackInfo> getVideoQualities() {
        List<TrackInfo> list = new ArrayList<>();
        if (player == null) return list;

        Tracks tracks = player.getCurrentTracks();
        List<Tracks.Group> groups = tracks.getGroups();
        boolean hasVideoOverride = false;

        // Check overrides in active selection parameters
        for (TrackSelectionOverride override : player.getTrackSelectionParameters().overrides.values()) {
            if (override.getType() == C.TRACK_TYPE_VIDEO) {
                hasVideoOverride = true;
                break;
            }
        }

        // Use a LinkedHashMap to deduplicate by resolution name while maintaining tracks order
        java.util.Map<String, TrackInfo> uniqueTracks = new java.util.LinkedHashMap<>();

        for (int g = 0; g < groups.size(); g++) {
            Tracks.Group group = groups.get(g);
            if (group.getType() == C.TRACK_TYPE_VIDEO) {
                for (int t = 0; t < group.length; t++) {
                    if (group.isTrackSupported(t)) {
                        Format format = group.getTrackFormat(t);
                        String name = format.height > 0 ? format.height + "p" : "Quality " + (t + 1);
                        boolean isSelected = group.isTrackSelected(t);

                        TrackInfo existing = uniqueTracks.get(name);
                        // If no track for this name exists yet, or the new one is the actively selected one, update the map
                        if (existing == null || isSelected) {
                            uniqueTracks.put(name, new TrackInfo(name, g, t, isSelected));
                        }
                    }
                }
            }
        }

        list.addAll(uniqueTracks.values());

        // Add Auto to the beginning of the list
        list.add(0, new TrackInfo("Auto", -1, -1, !hasVideoOverride));
        return list;
    }

    @OptIn(markerClass = UnstableApi.class)
    public void setVideoQuality(TrackInfo trackInfo) {
        if (player == null) return;

        TrackSelectionParameters params = player.getTrackSelectionParameters();
        if (trackInfo.groupIndex == -1) {
            // Clear overrides to fallback to Auto selection
            player.setTrackSelectionParameters(
                    params.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .build()
            );
        } else {
            List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
            if (trackInfo.groupIndex >= 0 && trackInfo.groupIndex < groups.size()) {
                Tracks.Group group = groups.get(trackInfo.groupIndex);
                if (trackInfo.trackIndex >= 0 && trackInfo.trackIndex < group.length) {
                    player.setTrackSelectionParameters(
                            params.buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    .addOverride(new TrackSelectionOverride(
                                            group.getMediaTrackGroup(),
                                            trackInfo.trackIndex
                                    ))
                                    .build()
                    );
                }
            }
        }
    }

    public String toggleResizeMode() {
        if (playerView == null) return "Fit";
        int current = playerView.getResizeMode();
        int next;
        String name;
        if (current == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            next = AspectRatioFrameLayout.RESIZE_MODE_FILL;
            name = "Stretch";
        } else if (current == AspectRatioFrameLayout.RESIZE_MODE_FILL) {
            next = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            name = "Zoom";
        } else {
            next = AspectRatioFrameLayout.RESIZE_MODE_FIT;
            name = "Fit";
        }
        playerView.setResizeMode(next);
        return name;
    }

    public String getResizeModeName() {
        if (playerView == null) return "Fit";
        int current = playerView.getResizeMode();
        if (current == AspectRatioFrameLayout.RESIZE_MODE_FILL) return "Stretch";
        if (current == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) return "Zoom";
        return "Fit";
    }

    public boolean isPlaying() {
        return player != null && !isReleased && player.isPlaying();
    }

    public ChannelModel getCurrentChannel() {
        return currentChannel;
    }
}
