package com.bnxit.bnxittv;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.media3.ui.PlayerView;

import java.util.List;

/**
 * Main activity for BNXIT TV IPTV player.
 *
 * Layout: [Categories] | [Channel List] | [Video Player]
 *
 * TV-first design:
 * - D-pad navigation between panels
 * - Focus-based highlighting
 * - Instant channel switching
 * - Auto-play last channel on startup
 * - Loading/error/placeholder overlays
 * - Remote JSON loading from GitHub with cache + asset fallback
 */
public class MainActivity extends AppCompatActivity implements
        PlayerManager.PlayerCallback,
        ChannelAdapter.OnChannelClickListener,
        CategoryAdapter.OnCategoryClickListener {

    private static final String TAG = "MainActivity";
    private static final long NOW_PLAYING_HIDE_DELAY = 4000;
    private static final long AUTO_HIDE_DELAY_MS = 10000;

    // UI Components
    private RecyclerView rvCategories;
    private RecyclerView rvChannels;
    private PlayerView playerView;
    private TextView tvCategoryTitle;
    private TextView tvChannelCount;
    private TextView tvNowPlayingName;
    private LinearLayout nowPlayingBar;
    private View loadingOverlay;
    private View errorOverlay;
    private View placeholderOverlay;
    private View panelsContainer;
    private View startupLoader;

    // Controls HUD Components
    private View controlsHud;
    private TextView btnQuality;
    private TextView btnAspect;
    private TextView btnMenu;
    private TextView btnDeveloper;
    private View developerInfoOverlay;
    private TextView btnCloseDeveloper;
    private TextView btnDevFacebook;
    private ScrollView bioScrollView;
    private boolean isDeveloperOverlayVisible = false;

    // Adapters
    private CategoryAdapter categoryAdapter;
    private ChannelAdapter channelAdapter;

    // Managers
    private PlayerManager playerManager;
    private PreferenceManager prefManager;
    private JsonLoader jsonLoader;

    // State
    private String currentCategory = "All";
    private boolean isPanelVisible = true;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoHideRunnable = () -> {
        togglePanels(false);
        hideControlsHud();
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on during playback
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        initViews();
        initAdapters();
        initPlayer();
        initControlListeners();

        // Screen tap toggles controls HUD or hides side panels
        playerView.setOnClickListener(v -> {
            if (isPanelVisible) {
                togglePanels(false);
            } else {
                if (isControlsHudVisible()) {
                    hideControlsHud();
                } else {
                    showControlsHud();
                }
            }
        });

        loadChannelData();
    }

    private void initViews() {
        rvCategories = findViewById(R.id.rv_categories);
        rvChannels = findViewById(R.id.rv_channels);
        playerView = findViewById(R.id.player_view);
        tvCategoryTitle = findViewById(R.id.tv_category_title);
        tvChannelCount = findViewById(R.id.tv_channel_count);
        tvNowPlayingName = findViewById(R.id.tv_now_playing_name);
        nowPlayingBar = findViewById(R.id.now_playing_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        errorOverlay = findViewById(R.id.error_overlay);
        panelsContainer = findViewById(R.id.panels_container);
        startupLoader = findViewById(R.id.startup_loader);

        controlsHud = findViewById(R.id.player_controls_hud);
        btnQuality = findViewById(R.id.btn_quality);
        btnAspect = findViewById(R.id.btn_aspect);
        btnMenu = findViewById(R.id.btn_menu);
        btnDeveloper = findViewById(R.id.btn_developer);
        developerInfoOverlay = findViewById(R.id.developer_info_overlay);
        btnDevFacebook = findViewById(R.id.btn_dev_facebook);
        btnCloseDeveloper = findViewById(R.id.btn_close_developer);
        bioScrollView = findViewById(R.id.bio_scrollview);
    }

    private void initAdapters() {
        // Category adapter
        categoryAdapter = new CategoryAdapter();
        categoryAdapter.setOnCategoryClickListener(this);

        rvCategories.setAdapter(categoryAdapter);
        rvCategories.setHasFixedSize(true);
        rvCategories.setItemAnimator(null);
        rvCategories.setItemViewCacheSize(15);

        // Channel adapter
        channelAdapter = new ChannelAdapter();
        channelAdapter.setOnChannelClickListener(this);

        rvChannels.setAdapter(channelAdapter);
        rvChannels.setHasFixedSize(true);
        rvChannels.setItemAnimator(null);
        rvChannels.setItemViewCacheSize(30);
    }

    private void initPlayer() {
        prefManager = new PreferenceManager(this);
        playerManager = new PlayerManager(this);
        playerManager.setCallback(this);
        playerManager.init(playerView);
    }

    /**
     * Load channel data asynchronously.
     * First loads from cache/assets (instant), then refreshes from GitHub.
     */
    private void loadChannelData() {
        jsonLoader = new JsonLoader();

        // Show loading state on channel panel
        tvChannelCount.setText("Loading channels…");

        jsonLoader.loadAsync(this, new JsonLoader.LoadCallback() {
            @Override
            public void onLoaded(int channelCount, int categoryCount) {
                if (startupLoader != null) {
                    startupLoader.setVisibility(View.GONE);
                }
                // This is called on main thread
                Log.d(TAG, "Channels loaded: " + channelCount + ", categories: " + categoryCount);

                // Set categories
                List<String> cats = jsonLoader.getCategories();
                categoryAdapter.setCategories(cats);

                // Restore last category
                String lastCategory = prefManager.getLastCategory();
                int catPos = categoryAdapter.findPosition(lastCategory);
                if (catPos >= 0) {
                    categoryAdapter.setSelectedPosition(catPos);
                    currentCategory = lastCategory;
                    rvCategories.scrollToPosition(catPos);
                }

                // Load channels for current category
                updateChannelList(currentCategory);

                // Auto-play last channel (only on first load) or first channel fallback
                if (!playerManager.isPlaying()) {
                    String lastUrl = prefManager.getLastChannelUrl();
                    ChannelModel targetChannel = null;
                    if (lastUrl != null) {
                        targetChannel = jsonLoader.findByUrl(lastUrl);
                    }
                    
                    // Fallback to the first channel in the current category if no last channel is found
                    if (targetChannel == null) {
                        List<ChannelModel> currentChannels = jsonLoader.getChannelsForCategory(currentCategory);
                        if (currentChannels != null && !currentChannels.isEmpty()) {
                            targetChannel = currentChannels.get(0);
                        }
                    }
                    
                    if (targetChannel != null) {
                        playChannel(targetChannel);

                        int chPos = channelAdapter.findPositionByUrl(targetChannel.url);
                        if (chPos >= 0) {
                            channelAdapter.setSelectedPosition(chPos);
                            rvChannels.scrollToPosition(chPos);
                        }
                    }
                }

                // Focus on channel list
                rvChannels.requestFocus();
            }

            @Override
            public void onError(String message) {
                if (startupLoader != null) {
                    startupLoader.setVisibility(View.GONE);
                }
                Log.e(TAG, "Channel load error: " + message);
                tvChannelCount.setText("Failed to load channels");
                Toast.makeText(MainActivity.this, "Failed to load channels", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateChannelList(String category) {
        currentCategory = category;
        List<ChannelModel> channels = jsonLoader.getChannelsForCategory(category);
        channelAdapter.setChannels(channels);

        tvCategoryTitle.setText(category);
        tvChannelCount.setText(channels.size() + " channels");

        prefManager.saveLastCategory(category);
    }

    // ---- Channel/Category click handlers ----

    @Override
    public void onChannelClick(ChannelModel channel, int position) {
        playChannel(channel);
    }

    @Override
    public void onCategoryClick(String category, int position) {
        updateChannelList(category);
        
        // Drill-down: hide categories, show channels
        findViewById(R.id.category_panel).setVisibility(View.GONE);
        findViewById(R.id.channel_panel).setVisibility(View.VISIBLE);

        uiHandler.postDelayed(() -> {
            if (rvChannels.getChildCount() > 0) {
                View firstChild = rvChannels.getChildAt(0);
                if (firstChild != null) {
                    firstChild.requestFocus();
                }
            } else {
                rvChannels.requestFocus();
            }
        }, 100);
    }

    @Override
    public void onCategorySelected(String category, int position) {
        updateChannelList(category);
    }

    private void playChannel(ChannelModel channel) {
        if (channel == null) return;

        showNowPlaying(channel.name);
        channelAdapter.setCurrentPlayingUrl(channel.url);
        prefManager.saveLastChannel(channel.url);
        playerManager.playChannel(channel);
    }

    private void showNowPlaying(String channelName) {
        tvNowPlayingName.setText(channelName);
        nowPlayingBar.setVisibility(View.VISIBLE);

        uiHandler.removeCallbacks(hideNowPlayingRunnable);
        uiHandler.postDelayed(hideNowPlayingRunnable, NOW_PLAYING_HIDE_DELAY);
    }

    private final Runnable hideNowPlayingRunnable = () -> {
        nowPlayingBar.setVisibility(View.GONE);
    };

    // ---- Player callbacks ----

    @Override
    public void onBuffering() {
        runOnUiThread(() -> {
            loadingOverlay.setVisibility(View.VISIBLE);
            errorOverlay.setVisibility(View.GONE);
        });
    }

    @Override
    public void onPlaying() {
        runOnUiThread(() -> {
            loadingOverlay.setVisibility(View.GONE);
            errorOverlay.setVisibility(View.GONE);
            resetAutoHideTimer();
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            loadingOverlay.setVisibility(View.GONE);
            errorOverlay.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onIdle() {
        // No action needed
    }

    // ---- D-pad key handling ----
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetAutoHideTimer();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        resetAutoHideTimer();

        // If developer overlay is visible, let it consume BACK to close
        if (isDeveloperOverlayVisible) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                onBackPressed();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        // 1. If panels are hidden (fullscreen)
        if (!isPanelVisible) {
            if (isControlsHudVisible()) {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    onBackPressed();
                    return true;
                }
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        hideControlsHud();
                        return true;
                }
                // Let other keys pass through to controls HUD buttons
                return super.onKeyDown(keyCode, event);
            } else {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    onBackPressed();
                    return true;
                }
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_CHANNEL_UP:
                    case KeyEvent.KEYCODE_PAGE_UP:
                        navigateChannel(-1);
                        return true;

                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                        navigateChannel(1);
                        return true;

                    case KeyEvent.KEYCODE_DPAD_LEFT:
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_MENU:
                    case KeyEvent.KEYCODE_INFO:
                        showControlsHud();
                        return true;
                }
                return super.onKeyDown(keyCode, event);
            }
        }

        // 2. If panels are visible
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (errorOverlay.getVisibility() == View.VISIBLE) {
                    playerManager.retry();
                    return true;
                }
                break;

            case KeyEvent.KEYCODE_BACK:
                onBackPressed();
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // No more side-by-side focus change needed, drill-down handles it
                break;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                // If on channels, back button logic covers returning to categories.
                break;

            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                togglePanels(false);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                navigateChannel(-1);
                return true;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                navigateChannel(1);
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // 1. If Developer overlay is visible -> hide it (no popup)
        if (isDeveloperOverlayVisible) {
            if (developerInfoOverlay != null) {
                developerInfoOverlay.setVisibility(View.GONE);
                isDeveloperOverlayVisible = false;
                showControlsHud();
                if (btnDeveloper != null) {
                    btnDeveloper.requestFocus();
                }
            }
            return;
        }

        // 2. If controls HUD menu is visible -> hide it (no popup)
        if (isControlsHudVisible()) {
            hideControlsHud();
            return;
        }

        // 3. If side panels (categories/channels) are visible -> drill-up or hide
        if (isPanelVisible) {
            View channelPanel = findViewById(R.id.channel_panel);
            View categoryPanel = findViewById(R.id.category_panel);
            
            if (channelPanel.getVisibility() == View.VISIBLE) {
                // Drill-up: hide channels, show categories
                channelPanel.setVisibility(View.GONE);
                categoryPanel.setVisibility(View.VISIBLE);
                
                int selectedCat = categoryAdapter.getSelectedPosition();
                RecyclerView.ViewHolder holder = rvCategories.findViewHolderForAdapterPosition(selectedCat);
                if (holder != null && holder.itemView != null) {
                    holder.itemView.requestFocus();
                } else {
                    rvCategories.requestFocus();
                }
            } else {
                // Hide panels and go to fullscreen player
                togglePanels(false);
            }
            return;
        }

        // 4. If nothing else is visible (pure fullscreen video player) -> show exit popup
        showExitDialog();
    }

    private void showExitDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this, R.style.TvDialogTheme)
                .setTitle("Exit App")
                .setMessage("Do you want to exit BNXIT TV?")
                .setPositiveButton("Yes", (dialog, which) -> finish())
                .setNegativeButton("No", null)
                .show();
    }

    private void navigateChannel(int direction) {
        ChannelModel current = playerManager.getCurrentChannel();
        if (current == null) return;

        List<ChannelModel> channels = jsonLoader.getChannelsForCategory(currentCategory);
        if (channels.isEmpty()) return;

        int currentPos = -1;
        for (int i = 0; i < channels.size(); i++) {
            if (current.url.equals(channels.get(i).url)) {
                currentPos = i;
                break;
            }
        }

        if (currentPos < 0) return;

        int newPos = currentPos + direction;
        if (newPos < 0) newPos = channels.size() - 1;
        if (newPos >= channels.size()) newPos = 0;

        ChannelModel nextChannel = channels.get(newPos);
        channelAdapter.setSelectedPosition(newPos);
        rvChannels.scrollToPosition(newPos);
        playChannel(nextChannel);
    }

    private void togglePanels(boolean visible) {
        isPanelVisible = visible;

        if (panelsContainer != null) {
            panelsContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        if (visible) {
            cancelAutoHideTimer();
            if (controlsHud != null) {
                controlsHud.setVisibility(View.GONE);
            }
            
            // Initial state for drill-down: Category is visible, Channels hidden
            findViewById(R.id.category_panel).setVisibility(View.VISIBLE);
            findViewById(R.id.channel_panel).setVisibility(View.GONE);
            
            int selectedCat = categoryAdapter.getSelectedPosition();
            if (selectedCat >= 0) {
                rvCategories.scrollToPosition(selectedCat);
            }
            rvCategories.requestFocus();
        } else {
            cancelAutoHideTimer();
            playerView.requestFocus();

            ChannelModel current = playerManager.getCurrentChannel();
            if (current != null) {
                showNowPlaying(current.name);
            }
        }
    }

    private void resetAutoHideTimer() {
        cancelAutoHideTimer();
        if (isPanelVisible || isControlsHudVisible()) {
            uiHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS);
        }
    }

    private void cancelAutoHideTimer() {
        uiHandler.removeCallbacks(autoHideRunnable);
    }

    // ---- Lifecycle ----

    @Override
    protected void onPause() {
        super.onPause();
        cancelAutoHideTimer();
        if (playerManager != null) {
            playerManager.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playerManager != null) {
            playerManager.resume();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (playerManager != null) {
            playerManager.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (playerManager != null) {
            playerManager.release();
            playerManager = null;
        }
        if (jsonLoader != null) {
            jsonLoader.shutdown();
            jsonLoader = null;
        }
    }

    // ---- Fullscreen Controls HUD & Quality dialog ----

    private void initControlListeners() {
        View.OnFocusChangeListener focusListener = (v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).translationZ(6f).setDuration(150).start();
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFFFFFFF);
                }
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).translationZ(0f).setDuration(150).start();
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFC4C4D0);
                }
            }
            resetAutoHideTimer();
        };

        btnQuality.setOnFocusChangeListener(focusListener);
        btnAspect.setOnFocusChangeListener(focusListener);
        btnMenu.setOnFocusChangeListener(focusListener);
        if (btnDeveloper != null) {
            btnDeveloper.setOnFocusChangeListener(focusListener);
        }
        if (btnCloseDeveloper != null) {
            btnCloseDeveloper.setOnFocusChangeListener(focusListener);
        }
        if (btnDevFacebook != null) {
            btnDevFacebook.setOnFocusChangeListener(focusListener);
        }
        if (bioScrollView != null) {
            bioScrollView.setOnFocusChangeListener((v, hasFocus) -> resetAutoHideTimer());
        }

        btnQuality.setOnClickListener(v -> {
            showQualitySelectionDialog();
            resetAutoHideTimer();
        });
        btnAspect.setOnClickListener(v -> {
            String modeName = playerManager.toggleResizeMode();
            btnAspect.setText("📺 ASPECT (" + modeName + ")");
            resetAutoHideTimer();
        });

        btnMenu.setOnClickListener(v -> {
            hideControlsHud();
            togglePanels(true);
            resetAutoHideTimer();
        });

        if (btnDeveloper != null) {
            btnDeveloper.setOnClickListener(v -> {
                hideControlsHud();
                if (developerInfoOverlay != null) {
                    developerInfoOverlay.setVisibility(View.VISIBLE);
                    isDeveloperOverlayVisible = true;
                    cancelAutoHideTimer(); // Prevent auto-hide when reading developer bio
                    if (btnCloseDeveloper != null) {
                        btnCloseDeveloper.requestFocus();
                    }
                }
            });
        }

        if (btnCloseDeveloper != null) {
            btnCloseDeveloper.setOnClickListener(v -> {
                if (developerInfoOverlay != null) {
                    developerInfoOverlay.setVisibility(View.GONE);
                    isDeveloperOverlayVisible = false;
                    showControlsHud();
                    if (btnDeveloper != null) {
                        btnDeveloper.requestFocus();
                    }
                }
            });
        }

        if (btnDevFacebook != null) {
            btnDevFacebook.setOnClickListener(v -> {
                openFacebookProfile();
                resetAutoHideTimer();
            });
        }

        // Clicking the dimmed backdrop closes the developer overlay
        if (developerInfoOverlay != null) {
            developerInfoOverlay.setOnClickListener(v -> {
                if (developerInfoOverlay != null) {
                    developerInfoOverlay.setVisibility(View.GONE);
                    isDeveloperOverlayVisible = false;
                    showControlsHud();
                    if (btnDeveloper != null) {
                        btnDeveloper.requestFocus();
                    }
                }
            });
        }
    }

    /**
     * Opens the developer's Facebook profile in a browser or the Facebook app.
     */
    private void openFacebookProfile() {
        String url = getString(R.string.dev_facebook_url);
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Facebook URL: " + url, e);
            Toast.makeText(this, "Unable to open Facebook", Toast.LENGTH_SHORT).show();
        }
    }

    private void showQualitySelectionDialog() {
        List<PlayerManager.TrackInfo> qualities = playerManager.getVideoQualities();
        if (qualities.isEmpty()) {
            Toast.makeText(this, "No quality options available", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[qualities.size()];
        int selectedIndex = 0;
        for (int i = 0; i < qualities.size(); i++) {
            names[i] = qualities.get(i).name;
            if (qualities.get(i).isSelected) {
                selectedIndex = i;
            }
        }

        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this, R.style.TvDialogTheme);
        builder.setTitle("Select Quality");
        builder.setSingleChoiceItems(names, selectedIndex, (dialog, which) -> {
            PlayerManager.TrackInfo selected = qualities.get(which);
            playerManager.setVideoQuality(selected);
            btnQuality.setText("⚙ QUALITY (" + selected.name + ")");
            dialog.dismiss();
            resetAutoHideTimer();
        });
        builder.show();
    }

    private void showControlsHud() {
        if (controlsHud == null) return;
        controlsHud.setVisibility(View.VISIBLE);

        btnAspect.setText("📺 ASPECT (" + playerManager.getResizeModeName() + ")");

        List<PlayerManager.TrackInfo> qualities = playerManager.getVideoQualities();
        String activeQuality = "Auto";
        for (PlayerManager.TrackInfo t : qualities) {
            if (t.isSelected) {
                activeQuality = t.name;
                break;
            }
        }
        btnQuality.setText("⚙ QUALITY (" + activeQuality + ")");

        btnMenu.requestFocus();
        resetAutoHideTimer();
    }

    private void hideControlsHud() {
        if (controlsHud == null) return;
        controlsHud.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    private boolean isControlsHudVisible() {
        return controlsHud != null && controlsHud.getVisibility() == View.VISIBLE;
    }
}
