package es.pablo.periodico;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private final int ink = Color.rgb(32, 32, 29), paper = Color.rgb(246, 245, 239);
    private LinearLayout content, sections;
    private TextView status, loadStatus, retry;
    private ScrollView scroll;
    private boolean refreshing, extending, endChecked, pagePending;
    private int generation, shownCount, restoreCount, restoreY = -1;
    private News.Catalog catalog;
    private final LinkedHashMap<String, TextView> firstHeadings = new LinkedHashMap<>();
    private final ArrayList<ArticlePhoto> photos = new ArrayList<>();
    private final Locale spanish = new Locale("es", "ES");

    private static final class ArticlePhoto {
        final ImageView view;
        final String url;
        boolean requested;
        ArticlePhoto(ImageView view, String url) { this.view = view; this.url = url; }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(ink);
        view.setTypeface(Typeface.create("serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return view;
    }
    private TextView label(String value) {
        TextView view = text(value.toUpperCase(spanish), 11, true);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setLetterSpacing(.10f);
        InkAppearance.accent(view);
        return view;
    }
    private void rule(LinearLayout host, int height) {
        View view = new View(this);
        view.setBackgroundColor(ink);
        host.addView(view, new LinearLayout.LayoutParams(-1, dp(height)));
    }
    private LinearLayout column(LinearLayout row, int width, boolean divider, boolean last) {
        if (divider) {
            View rule = new View(this);
            rule.setBackgroundColor(Color.rgb(183, 181, 174));
            row.addView(rule, new LinearLayout.LayoutParams(dp(1), -1));
        }
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(divider ? dp(14) : 0, 0, last ? 0 : dp(14), 0);
        row.addView(column, new LinearLayout.LayoutParams(width, -2));
        return column;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(paper);
        getWindow().setNavigationBarColor(paper);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        DailyReceiver.schedule(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        render();
        if (state != null) {
            endChecked = state.getBoolean("endChecked", false);
            restoreCount = state.getInt("shownCount", shownCount);
            restoreY = state.getInt("scrollY", 0);
            if (!pagePending) appendPage();
            if (!catalog.hasMore() && endChecked) setFeedStatus("Has llegado al final de las noticias disponibles.", true);
        }
        boolean needsImages = false;
        try {
            JSONArray cached = new JSONArray(getSharedPreferences("edition", 0).getString("data", "[]"));
            needsImages = cached.length() > 0 && !cached.getJSONObject(0).has("image");
        } catch (JSONException ignored) { needsImages = true; }
        if (state == null && (getSharedPreferences("edition", 0).getInt("catalog_version", 0) != News.CATALOG_VERSION || needsImages || !News.today().equals(getSharedPreferences("edition", 0).getString("date", "")))) refresh();
    }
    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(paper);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll);
        setContentView(root);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int gutter = getResources().getConfiguration().screenWidthDp < 600 ? 20 : 34;
        page.setPadding(dp(gutter), dp(6), dp(gutter), dp(28));
        scroll.addView(page);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(top);
        TextView date = label(DateFormat.getDateInstance(DateFormat.FULL, spanish).format(new Date()));
        top.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        TextView appearance = label("Aspecto");
        appearance.setMinHeight(dp(48));
        appearance.setGravity(Gravity.CENTER);
        appearance.setPadding(dp(16), 0, 0, 0);
        appearance.setOnClickListener(view -> InkAppearance.choose(this));
        top.addView(appearance);
        TextView settings = label("Temas");
        settings.setMinHeight(dp(48));
        settings.setGravity(Gravity.CENTER);
        settings.setPadding(dp(20), 0, 0, 0);
        settings.setOnClickListener(view -> topics());
        top.addView(settings);
        rule(page, 2);
        TextView masthead = text(getString(R.string.app_name), getResources().getConfiguration().screenWidthDp >= 600 ? 62 : 42, true);
        masthead.setGravity(Gravity.CENTER);
        masthead.setPadding(0, dp(2), 0, dp(4));
        page.addView(masthead);
        rule(page, 2);

        LinearLayout edition = new LinearLayout(this);
        edition.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(edition);
        status = label("Edición diaria");
        edition.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        TextView update = label("Actualizar");
        update.setMinHeight(dp(48));
        update.setGravity(Gravity.CENTER);
        update.setPadding(dp(16), 0, 0, 0);
        update.setOnClickListener(view -> refresh());
        edition.addView(update);
        rule(page, 1);
        HorizontalScrollView navigation = new HorizontalScrollView(this);
        navigation.setHorizontalScrollBarEnabled(false);
        sections = new LinearLayout(this);
        navigation.addView(sections);
        page.addView(navigation);
        rule(page, 1);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        page.addView(content);
        loadStatus = text("", 15, false);
        loadStatus.setPadding(0, dp(24), 0, dp(8));
        page.addView(loadStatus);
        retry = label("Reintentar");
        retry.setMinHeight(dp(48));
        retry.setGravity(Gravity.CENTER_VERTICAL);
        retry.setOnClickListener(view -> findMore());
        page.addView(retry);
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            updatePhotos();
            if (y > oldY) maybeLoadMore();
        });
        scroll.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (pagePending && content.getWidth() > 0) content.post(() -> { if (pagePending && !isDestroyed()) appendPage(); });
            updatePhotos();
            if (scroll.getHeight() > 0 && scroll.getChildAt(0).getHeight() <= scroll.getHeight() + dp(240)) maybeLoadMore();
        });
        showSaved();
    }
    private void showSaved() {
        for (ArticlePhoto photo : photos) ArticleImages.clear(photo.view);
        photos.clear();
        content.removeAllViews();
        sections.removeAllViews();
        firstHeadings.clear();
        shownCount = 0;
        restoreCount = 0;
        restoreY = -1;
        ArrayList<String> selected = new ArrayList<>();
        for (String topic : News.TOPICS)
            if (News.enabled(this, topic)) selected.add(topic);
        catalog = new News.Catalog(selected);
        try {
            JSONArray saved = new JSONArray(getSharedPreferences("edition", 0).getString("data", "[]"));
            catalog.merge(saved);
            if (!catalog.hasMore()) {
                TextView empty = text("Aún no hay noticias. Pulsa Actualizar para preparar la edición.", 18, false);
                empty.setPadding(0, dp(24), 0, 0);
                content.addView(empty);
            }
            appendPage();
            status.setText("EDICIÓN · " + getSharedPreferences("edition", 0).getString("date", ""));
        } catch (JSONException error) {
            content.addView(text("No se puede leer la edición guardada.", 18, false));
            setFeedStatus("Puedes reintentar la carga.", true);
        }
        scroll.scrollTo(0, 0);
    }
    private void appendPage() {
        int width = content.getWidth();
        if (width == 0) { pagePending = true; return; }
        pagePending = false;
        do {
            LinkedHashMap<String, ArrayList<JSONObject>> grouped = catalog.nextPage(6);
            if (shownCount == 0 && !grouped.isEmpty()) content.removeAllViews();
            boolean opening = shownCount == 0;
            for (Map.Entry<String, ArrayList<JSONObject>> section : grouped.entrySet()) {
                TextView heading = label(section.getKey());
                heading.setTextSize(15);
                heading.setPadding(0, dp(18), 0, dp(8));
                content.addView(heading);
                if (!firstHeadings.containsKey(section.getKey())) {
                    firstHeadings.put(section.getKey(), heading);
                    TextView tab = label(section.getKey());
                    tab.setPadding(dp(14), 0, dp(14), 0);
                    tab.setGravity(Gravity.CENTER);
                    tab.setMinHeight(dp(48));
                    tab.setOnClickListener(view -> scroll.smoothScrollTo(0, content.getTop() + heading.getTop()));
                    sections.addView(tab);
                }
                rule(content, 2);
                ArrayList<JSONObject> articles = section.getValue();
                composeSection(articles, width, opening, Math.max(0, Arrays.asList(News.TOPICS).indexOf(section.getKey())));
                shownCount += articles.size();
                opening = false;
            }
        } while (shownCount < restoreCount && catalog.hasMore());
        restoreCount = 0;
        boolean finished = !catalog.hasMore() && endChecked;
        setFeedStatus(finished ? "Has llegado al final de las noticias disponibles." : "", finished);
        if (restoreY >= 0) {
            int position = restoreY;
            restoreY = -1;
            scroll.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    scroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    scroll.scrollTo(0, position);
                    updatePhotos();
                }
            });
        }
    }
    private void composeSection(ArrayList<JSONObject> articles, int width, boolean opening, int variant) {
        int columns = Math.min(width >= dp(1000) ? 3 : width >= dp(550) ? 2 : 1, (articles.size() + 1) / 2);
        int feature = opening ? 0 : variant % columns;
        float[] weights = new float[columns];
        Arrays.fill(weights, 1);
        weights[feature] = opening ? 1.75f : variant % 2 == 0 ? 1.45f : 1.25f;
        int[] widths = ArticleColumns.widths(width, dp(1), weights);
        int[] heights = new int[columns];
        LinearLayout row = new LinearLayout(this);
        content.addView(row);
        ArrayList<LinearLayout> rails = new ArrayList<>();
        for (int i = 0; i < columns; i++) rails.add(column(row, widths[i], i > 0, i == columns - 1));
        int featureWidth = widths[feature] - rails.get(feature).getPaddingLeft() - rails.get(feature).getPaddingRight();
        LinearLayout lead = articleBlock(articles.get(0), opening ? 0 : 1, featureWidth);
        heights[feature] = placeArticle(rails.get(feature), lead, featureWidth, opening ? 0 : 1);
        int narrowWidth = width;
        for (int i = 0; i < columns; i++) narrowWidth = Math.min(narrowWidth, widths[i] - rails.get(i).getPaddingLeft() - rails.get(i).getPaddingRight());
        ArrayList<LinearLayout> remaining = new ArrayList<>();
        for (int i = 1; i < articles.size(); i++) remaining.add(articleBlock(articles.get(i), 2, narrowWidth));
        // Place tall stories first so a final long headline cannot leave an empty neighbouring column.
        remaining.sort((a, b) -> Integer.compare(b.getMeasuredHeight(), a.getMeasuredHeight()));
        for (LinearLayout block : remaining) {
            int target = ArticleColumns.shortest(heights);
            LinearLayout rail = rails.get(target);
            int articleWidth = widths[target] - rail.getPaddingLeft() - rail.getPaddingRight();
            heights[target] += placeArticle(rail, block, articleWidth, 2);
        }
    }
    private void setFeedStatus(String message, boolean canRetry) {
        loadStatus.setText(shownCount + " noticias mostradas" + (message.isEmpty() ? "" : " · " + message));
        retry.setVisibility(canRetry ? View.VISIBLE : View.GONE);
    }
    private void maybeLoadMore() {
        if (refreshing || extending || pagePending || catalog == null || scroll.getChildCount() == 0) return;
        int remaining = scroll.getChildAt(0).getHeight() - scroll.getHeight() - scroll.getScrollY();
        if (remaining > dp(600)) return;
        if (catalog.hasMore()) appendPage();
        else if (!endChecked) findMore();
    }
    private void findMore() {
        if (refreshing || extending || catalog == null) return;
        extending = true;
        endChecked = true;
        int request = generation;
        setFeedStatus("Buscando nuevas noticias…", false);
        new Thread(() -> {
            JSONArray data = News.fetch(getApplicationContext());
            runOnUiThread(() -> {
                if (request != generation || isFinishing() || isDestroyed()) return;
                extending = false;
                if (data.length() > 0) News.save(this, data);
                if (catalog.merge(data) > 0) appendPage();
                else setFeedStatus(data.length() == 0 ? "No se pudieron buscar novedades. Comprueba la conexión." : "No hay más noticias nuevas en las fuentes.", true);
            });
        }).start();
    }
    private void updatePhotos() {
        int height = scroll.getHeight();
        if (height == 0) return;
        Rect bounds = new Rect();
        for (ArticlePhoto photo : photos) {
            if (photo.view.getWidth() == 0) continue;
            bounds.set(0, 0, photo.view.getWidth(), photo.view.getHeight());
            scroll.offsetDescendantRectToMyCoords(photo.view, bounds);
            int top = scroll.getScrollY();
            boolean near = bounds.bottom >= top - height && bounds.top <= top + height * 2;
            if (near && !photo.requested) {
                photo.requested = true;
                ArticleImages.load(photo.view, photo.url);
            } else if (!near && photo.requested) {
                ArticleImages.clear(photo.view);
                photo.requested = false;
            }
        }
    }
    private int photoHeight(int width, int prominence) {
        return Math.max(dp(prominence == 0 ? 210 : prominence == 1 ? 150 : 105),
            Math.min(dp(prominence == 0 ? 310 : prominence == 1 ? 250 : 190), Math.round(width / (prominence == 0 ? 1.9f : prominence == 1 ? 1.65f : 1.85f))));
    }
    private int placeArticle(LinearLayout host, LinearLayout block, int width, int prominence) {
        for (int i = 0; i < block.getChildCount(); i++)
            if (block.getChildAt(i) instanceof ImageView) block.getChildAt(i).getLayoutParams().height = photoHeight(width, prominence);
        host.addView(block);
        rule(host, 1);
        block.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return block.getMeasuredHeight() + dp(1);
    }
    private LinearLayout articleBlock(JSONObject article, int prominence, int width) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(prominence == 0 ? 18 : 14), 0, dp(18));
        block.setOnClickListener(view -> openArticle(article));
        TextView title = text(article.optString("title"), prominence == 0 ? 36 : prominence == 1 ? 25 : 21, true);
        title.setLineSpacing(dp(2), 1f);
        block.addView(title);
        String imageUrl = article.optString("image");
        if (!imageUrl.isEmpty()) {
            ImageView image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription("Fotografía de la noticia: " + article.optString("title"));
            LinearLayout.LayoutParams imageLayout = new LinearLayout.LayoutParams(-1, photoHeight(width, prominence));
            imageLayout.topMargin = dp(12);
            block.addView(image, imageLayout);
            image.setVisibility(View.INVISIBLE);
            photos.add(new ArticlePhoto(image, imageUrl));
        }
        String summary = article.optString("summary").trim();
        if (!summary.isEmpty()) {
            TextView excerpt = text(summary, prominence == 0 ? 18 : 15, false);
            excerpt.setLineSpacing(dp(2), 1f);
            excerpt.setMaxLines(prominence == 0 ? 6 : prominence == 1 ? 4 : 3);
            excerpt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            excerpt.setPadding(0, dp(10), 0, 0);
            block.addView(excerpt);
        }
        TextView source = label(article.optString("source"));
        source.setTextSize(10);
        source.setPadding(0, dp(12), 0, 0);
        block.addView(source);
        block.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return block;
    }
    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        extending = false;
        endChecked = false;
        int request = ++generation;
        showSaved();
        status.setText("ACTUALIZANDO…");
        new Thread(() -> {
            JSONArray data = News.fetch(getApplicationContext());
            runOnUiThread(() -> {
                if (request != generation || isFinishing() || isDestroyed()) return;
                refreshing = false;
                if (data.length() > 0) { News.save(this, data); showSaved(); }
                else {
                    endChecked = true;
                    status.setText("EDICIÓN · " + getSharedPreferences("edition", 0).getString("date", ""));
                    setFeedStatus("No se ha podido actualizar. Puedes seguir leyendo la edición guardada.", true);
                    Toast.makeText(this, "No se han podido cargar noticias. Comprueba la conexión.", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
    private void topics() {
        boolean[] selected = new boolean[News.TOPICS.length];
        for (int i = 0; i < selected.length; i++) selected[i] = News.enabled(this, News.TOPICS[i]);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Temas del periódico")
            .setMultiChoiceItems(News.TOPICS, selected, (d, which, checked) -> selected[which] = checked)
            .setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            boolean any = false;
            for (boolean value : selected) any |= value;
            if (!any) { Toast.makeText(this, "Selecciona al menos un tema", Toast.LENGTH_SHORT).show(); return; }
            SharedPreferences.Editor editor = getSharedPreferences("settings", 0).edit();
            for (int i = 0; i < selected.length; i++) editor.putBoolean(News.TOPICS[i], selected[i]);
            editor.apply();
            dialog.dismiss();
            refreshing = false;
            refresh();
        }));
        dialog.show();
    }
    private void openArticle(JSONObject article) {
        String url = article.optString("link");
        Uri uri = Uri.parse(url);
        if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null) {
            Intent intent = new Intent(this, ReaderActivity.class);
            intent.putExtra("url", url);
            startActivity(intent);
        } else Toast.makeText(this, "Enlace no disponible", Toast.LENGTH_SHORT).show();
    }
    @Override protected void onResume() {
        super.onResume();
        InkAppearance.apply(getWindow().getDecorView());
    }
    @Override protected void onDestroy() {
        generation++;
        for (ArticlePhoto photo : photos) ArticleImages.clear(photo.view);
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("shownCount", shownCount);
        out.putInt("scrollY", scroll.getScrollY());
        out.putBoolean("endChecked", endChecked);
    }
}
