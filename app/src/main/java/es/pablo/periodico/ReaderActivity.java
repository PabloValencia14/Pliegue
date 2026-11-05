package es.pablo.periodico;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;

public class ReaderActivity extends Activity {
    private final int paper = Color.rgb(246, 245, 239), ink = Color.rgb(32, 32, 29);
    private static final java.util.concurrent.ExecutorService CONTENT = java.util.concurrent.Executors.newFixedThreadPool(2);
    private java.util.concurrent.Future<?> contentTask;
    private final ArrayList<Photo> photos = new ArrayList<>();
    private LinearLayout page;
    private ScrollView scroll;
    private TextView source, status;
    private String url;
    private int request, restoreY;

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ink);
        view.setTypeface(Typeface.create("serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        url = News.imageUrl(getIntent().getStringExtra("url"));
        if (url.isEmpty()) { finish(); return; }
        restoreY = state == null ? 0 : state.getInt("scrollY", 0);
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(paper);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(20), 0, dp(20), 0);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(bar);
        TextView back = text("‹  VOLVER", 15, false);
        back.setMinHeight(dp(52));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setPadding(0, 0, dp(24), 0);
        back.setOnClickListener(view -> finish());
        bar.addView(back);
        source = text("Pliegue", 14, false);
        source.setMaxLines(1);
        source.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(source, new LinearLayout.LayoutParams(0, -2, 1));
        status = text("Cargando…", 13, false);
        bar.addView(status);
        View rule = new View(this);
        rule.setBackgroundColor(ink);
        root.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        scroll = new ScrollView(this);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(shell);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(28), 0, dp(40));
        shell.addView(page, new LinearLayout.LayoutParams(-1, -2));
        scroll.post(() -> { page.getLayoutParams().width = Math.min(dp(760), scroll.getWidth() - dp(48)); page.requestLayout(); });
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> updatePhotos());
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(this::updatePhotos);
        load();
    }
    private void load() {
        status.setText("Cargando…");
        int current = ++request;
        if (contentTask != null) contentTask.cancel(true);
        contentTask = CONTENT.submit(() -> {
            try {
                ArticleContent article = ArticleContent.load(getApplicationContext(), url);
                runOnUiThread(() -> {
                    if (current != request || isFinishing() || isDestroyed()) return;
                    show(article);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (current != request || isFinishing() || isDestroyed()) return;
                    status.setText("");
                    page.removeAllViews();
                    page.addView(text("No se ha podido cargar el contenido. Comprueba la conexión y vuelve a intentarlo.", 19, false));
                    TextView retry = text("Reintentar", 17, true);
                    retry.setMinHeight(dp(52));
                    retry.setGravity(Gravity.CENTER_VERTICAL);
                    retry.setOnClickListener(view -> load());
                    page.addView(retry);
                });
            }
        });
    }
    private void show(ArticleContent article) {
        for (Photo photo : photos) ArticleImages.clear(photo.view);
        photos.clear();
        page.removeAllViews();
        source.setText(article.source);
        status.setText(article.limited ? "Texto parcial" : "");
        TextView title = text(article.title, 33, true);
        title.setAccessibilityHeading(true);
        title.setPadding(0, 0, 0, dp(22));
        page.addView(title);
        if (!article.message.isEmpty()) {
            TextView message = text(article.message, 15, false);
            message.setPadding(0, 0, 0, dp(20));
            page.addView(message);
        }
        for (ArticleContent.Block block : article.blocks) {
            if (!block.image.isEmpty()) {
                ReadingImage image = new ReadingImage();
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setContentDescription("Fotografía del artículo");
                image.setVisibility(View.INVISIBLE);
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
                layout.topMargin = dp(8);
                layout.bottomMargin = dp(20);
                page.addView(image, layout);
                photos.add(new Photo(image, block.image));
            } else if (!block.text.isEmpty()) {
                TextView paragraph = text(block.text, block.heading ? 24 : 19, block.heading);
                paragraph.setTextIsSelectable(true);
                paragraph.setAccessibilityHeading(block.heading);
                paragraph.setLineSpacing(dp(5), 1.1f);
                paragraph.setPadding(0, block.heading ? dp(12) : 0, 0, dp(18));
                page.addView(paragraph);
            }
        }
        scroll.post(() -> { scroll.scrollTo(0, restoreY); updatePhotos(); });
    }
    private void updatePhotos() {
        if (scroll == null || scroll.getHeight() == 0) return;
        Rect bounds = new Rect();
        int top = scroll.getScrollY(), height = scroll.getHeight();
        for (Photo photo : photos) {
            if (photo.view.getWidth() == 0) continue;
            bounds.set(0, 0, photo.view.getWidth(), photo.view.getHeight());
            scroll.offsetDescendantRectToMyCoords(photo.view, bounds);
            boolean near = bounds.bottom >= top - height && bounds.top <= top + 2 * height;
            if (near && !photo.requested) { photo.requested = true; ArticleImages.load(photo.view, photo.url); }
            else if (!near && photo.requested) { ArticleImages.clear(photo.view); photo.requested = false; }
        }
    }
    private final class ReadingImage extends ImageView {
        private float ratio = 2f / 3f;
        ReadingImage() { super(ReaderActivity.this); }
        @Override public void setImageDrawable(Drawable drawable) {
            if (drawable != null && drawable.getIntrinsicWidth() > 0) ratio = (float) drawable.getIntrinsicHeight() / drawable.getIntrinsicWidth();
            super.setImageDrawable(drawable);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Math.round(width * ratio), View.MeasureSpec.EXACTLY));
        }
    }
    private static final class Photo {
        final ImageView view;
        final String url;
        boolean requested;
        Photo(ImageView view, String url) { this.view = view; this.url = url; }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("scrollY", scroll == null ? 0 : scroll.getScrollY());
    }
    @Override protected void onDestroy() {
        request++;
        if (contentTask != null) contentTask.cancel(true);
        for (Photo photo : photos) ArticleImages.clear(photo.view);
        super.onDestroy();
    }
}
