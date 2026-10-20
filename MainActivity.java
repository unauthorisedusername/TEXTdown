package com.codeeditor.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1001;
    private static final int REQ_SAVE_AS = 1002;
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private WebView webView;
    private boolean pageReady = false;
    private String pendingName, pendingText;
    private Uri currentUri;
    private String pendingSaveText;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true); // needed for file:///android_asset

        webView.setWebChromeClient(new WebChromeClient()); // enables alert/confirm dialogs
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                deliver();
            }
        });
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ---------- Opening files ----------

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Uri uri = intent.getData();
        if (uri != null && (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action))) {
            loadUri(uri);
        }
    }

    private void loadUri(final Uri uri) {
        new Thread(() -> {
            try {
                final String text = readText(uri);
                final String name = displayName(uri);
                runOnUiThread(() -> {
                    currentUri = uri;
                    pendingName = name;
                    pendingText = text;
                    deliver();
                });
            } catch (Exception e) {
                toast("Could not open file: " + e.getMessage());
            }
        }).start();
    }

    /** Sends the pending file to the web page (UI thread only). */
    private void deliver() {
        if (!pageReady || pendingText == null) return;
        String js = "if(window.openFileInEditor)window.openFileInEditor("
                + q(pendingName) + "," + q(pendingText) + ");";
        pendingName = null;
        pendingText = null;
        webView.evaluateJavascript(js, null);
    }

    // ---------- File helpers ----------

    private String readText(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open file stream");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("File is larger than 8 MB");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void writeText(Uri uri, String text) throws IOException {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot open file for writing");
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return last != null ? last : "untitled";
    }

    private static String q(String s) {
        return JSONObject.quote(s == null ? "" : s)
                .replace(String.valueOf((char) 0x2028), "\\u2028")
                .replace(String.valueOf((char) 0x2029), "\\u2029");
    }

    private void callJs(final String js) {
        runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    // ---------- Bridge used by index.html (window.Android) ----------

    private class Bridge {

        /** Open the system file picker. */
        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_OPEN);
            });
        }

        /** Save back to the file that is currently open. Returns "OK", "NOURI" or "ERR:...". */
        @JavascriptInterface
        public String saveFile(String text) {
            if (currentUri == null) return "NOURI";
            try {
                writeText(currentUri, text);
                return "OK";
            } catch (Exception e) {
                return "ERR:" + e.getMessage();
            }
        }

        /** Ask the user where to save a new copy. */
        @JavascriptInterface
        public void saveAs(String name, String text) {
            pendingSaveText = text;
            final String fileName = (name == null || name.isEmpty()) ? "untitled.txt" : name;
            int dot = fileName.lastIndexOf('.');
            String ext = dot >= 0 ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            final String type = mime != null ? mime : "text/plain";
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(type);
                i.putExtra(Intent.EXTRA_TITLE, fileName);
                startActivityForResult(i, REQ_SAVE_AS);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();

        if (requestCode == REQ_OPEN) {
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
            loadUri(uri);

        } else if (requestCode == REQ_SAVE_AS) {
            final String text = pendingSaveText;
            pendingSaveText = null;
            if (text == null) return;
            new Thread(() -> {
                try {
                    writeText(uri, text);
                    final String name = displayName(uri);
                    runOnUiThread(() -> {
                        currentUri = uri;
                        callJs("window.onSaveResult(true," + q(name) + ",'');");
                    });
                } catch (Exception e) {
                    callJs("window.onSaveResult(false,''," + q("Save failed: " + e.getMessage()) + ");");
                }
            }).start();
        }
    }
}
