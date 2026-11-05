package es.pablo.periodico;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

public class ReaderActivity extends Activity {
 private WebView web;
 private boolean pageFailed;
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
 private boolean allowed(Uri uri){return uri!=null && "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null;}
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  int paper=Color.rgb(246,245,239), ink=Color.rgb(32,32,29);
  getWindow().setStatusBarColor(paper);getWindow().setNavigationBarColor(paper);
  getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
  String url=getIntent().getStringExtra("url");if(!allowed(Uri.parse(url==null?"":url))){finish();return;}
  LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(paper);
  root.setOnApplyWindowInsetsListener((view,insets)->{view.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
  setContentView(root);
  LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(18),dp(4),dp(18),dp(4));root.addView(bar);
  TextView back=new TextView(this);back.setText("‹  VOLVER");back.setTextColor(ink);back.setTextSize(15);back.setMinHeight(dp(48));back.setGravity(Gravity.CENTER);back.setPadding(0,dp(14),dp(24),dp(14));back.setOnClickListener(v->finish());bar.addView(back);
  TextView source=new TextView(this);source.setText(getIntent().getStringExtra("source"));source.setTextColor(ink);source.setTextSize(14);source.setMaxLines(1);source.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(source,new LinearLayout.LayoutParams(0,-2,1));
  TextView loading=new TextView(this);loading.setTextColor(ink);loading.setText("Cargando…");bar.addView(loading);
  View rule=new View(this);rule.setBackgroundColor(ink);root.addView(rule,new LinearLayout.LayoutParams(-1,dp(1)));
  web=new WebView(this);web.setBackgroundColor(paper);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
  ColorMatrix monochrome=new ColorMatrix();monochrome.setSaturation(0);Paint paint=new Paint();paint.setColorFilter(new ColorMatrixColorFilter(monochrome));web.setLayerType(View.LAYER_TYPE_HARDWARE,paint);
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !allowed(request.getUrl());}
   @Override public void onPageStarted(WebView view,String address,android.graphics.Bitmap favicon){pageFailed=false;loading.setText("Cargando…");source.setText(Uri.parse(address).getHost());}
   @Override public void onPageFinished(WebView view,String address){if(!pageFailed)loading.setText("");}
   @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()){pageFailed=true;loading.setText("No se pudo cargar");}}
  });
  root.addView(web,new LinearLayout.LayoutParams(-1,0,1));if(state==null)web.loadUrl(url);else if(web.restoreState(state)==null)web.loadUrl(url);
 }
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(web!=null)web.saveState(out);}
 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
 @Override protected void onDestroy(){if(web!=null){web.stopLoading();web.destroy();}super.onDestroy();}
}
