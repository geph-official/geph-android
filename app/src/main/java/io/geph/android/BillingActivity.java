package io.geph.android;

import android.accounts.AccountManager;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.URLEncoder;

public class BillingActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_billing);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeButtonEnabled(true);

        WebView webView = (WebView) findViewById(R.id.billing_webview);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                view.loadUrl(url);
                return false;
            }
        });
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setUserAgentString("GephBilling");

        // what should we do for the webview?
        Bundle b = getIntent().getExtras();
        String action = b.getString("action");
        try {
            webView.loadUrl("https://geph.io/billing/login?uname="+
                    URLEncoder.encode(AccountUtils.getUsername(this), "utf-8")+"&pwd="+
                    URLEncoder.encode(AccountUtils.getPassword(this), "utf-8")+
                    "&next=" + URLEncoder.encode(action, "utf-8"));
        } catch (java.io.UnsupportedEncodingException e) {

        }
    }

}
