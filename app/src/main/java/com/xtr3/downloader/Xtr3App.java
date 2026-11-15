package com.xtr3.downloader;

import android.app.Application;

/** Initializes the bundled media network stack once per process. */
public final class Xtr3App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Diagnostics.init(this);
        NetworkStack.init(this);
    }
}
