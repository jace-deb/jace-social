package dev.jacedeb.jacesocial;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

/** Jace Social: the live web app (see capacitor.config.json), plus invite links. */
public class MainActivity extends BridgeActivity {
    private static final String BASE = "https://jace-social.vercel.app";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        openLink(intent);
    }

    /** jacesocial://invite/<code> opens that invite in the app. */
    private void openLink(Intent intent) {
        Uri uri = intent == null ? null : intent.getData();
        if (uri == null || !"jacesocial".equals(uri.getScheme()) || getBridge() == null) return;
        String path = (uri.getHost() == null ? "" : uri.getHost()) + (uri.getPath() == null ? "" : uri.getPath());
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        if (parts.length == 2 && parts[0].equals("invite") && parts[1].matches("[A-Za-z0-9_-]{1,32}")) {
            getBridge().getWebView().post(() -> getBridge().getWebView().loadUrl(BASE + "/invite/" + parts[1]));
        }
    }
}
