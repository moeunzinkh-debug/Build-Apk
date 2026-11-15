package com.xtr3.downloader;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/** In-app playback for videos saved by Xtr3, powered by Media3. */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView view;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        Uri uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        TextView title = findViewById(R.id.player_title);
        String name = getIntent().getStringExtra("title");
        title.setText(name == null || name.isEmpty() ? getString(R.string.app_name) : LinkParser.cleanTitle(name));
        findViewById(R.id.player_close).setOnClickListener(v -> finish());
        view = findViewById(R.id.player_view);
        player = new ExoPlayer.Builder(this).build();
        view.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlayerError(PlaybackException error) {
                Diagnostics.log("PLAYER", "Playback error " + error.getErrorCodeName());
                Ui.toast(PlayerActivity.this, getString(R.string.player_error, error.getErrorCodeName()));
            }
        });
        player.setMediaItem(MediaItem.fromUri(uri));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    @Override protected void onStart() { super.onStart(); if (player != null) player.setPlayWhenReady(true); }
    @Override protected void onStop() { if (player != null) player.setPlayWhenReady(false); super.onStop(); }
    @Override protected void onDestroy() {
        if (view != null) view.setPlayer(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
    @Override public void onBackPressed() { finish(); }
}
