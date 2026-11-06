package es.pablo.periodico;

import android.content.Context;
import android.text.Html;
import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class News {
    static final int MAX_ARTICLES_PER_TOPIC = 60;
    static final int CATALOG_VERSION = 4;
    static final String[] TOPICS = {"Actualidad", "Internacional", "Tecnología", "Ciencia", "Fórmula 1", "Política", "Economía", "Android", "Inteligencia artificial", "Software libre", "Seguridad digital", "Medioambiente", "Cultura", "Videojuegos"};
    // These publishers supply full articles in RSS descriptions as well as content:encoded.
    private static final Set<String> FULL_TEXT_FEEDS = new HashSet<>(Arrays.asList("elDiario.es", "infoLibre", "Xataka", "Xataka Android", "La Marea", "ElPlural", "MuyLinux", "Hispasec", "Climática", "VidaExtra"));
    static final class Feed {
        final String topic, source, url;
        Feed(String topic, String source, String url) { this.topic = topic; this.source = source; this.url = url; }
    }
    static final Feed[] FEEDS = {
        new Feed("Actualidad", "El País", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada"),
        new Feed("Actualidad", "elDiario.es", "https://www.eldiario.es/rss/"),
        new Feed("Actualidad", "infoLibre", "https://www.infolibre.es/rss/"),
        new Feed("Actualidad", "Público", "https://www.publico.es/"),
        new Feed("Actualidad", "CTXT", "https://ctxt.es/es/?tpl=87"),
        new Feed("Actualidad", "El Salto", "https://www.elsaltodiario.com/general/feed"),
        new Feed("Actualidad", "La Marea", "https://www.lamarea.com/feed/"),
        new Feed("Actualidad", "ElPlural", "https://www.elplural.com/uploads/feeds/feed_elplural_es.xml"),
        new Feed("Actualidad", "Nueva Tribuna", "https://www.nuevatribuna.es/rss/"),
        new Feed("Internacional", "BBC Mundo", "https://feeds.bbci.co.uk/mundo/rss.xml"),
        new Feed("Internacional", "El País", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/section/internacional/portada"),
        new Feed("Internacional", "elDiario.es", "https://www.eldiario.es/rss/internacional/"),
        new Feed("Internacional", "infoLibre", "https://www.infolibre.es/rss/internacional/"),
        new Feed("Internacional", "El Salto", "https://www.elsaltodiario.com/global/feed"),
        new Feed("Tecnología", "Xataka", "https://www.xataka.com/index.xml"),
        new Feed("Ciencia", "El País", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/section/ciencia/portada"),
        new Feed("Fórmula 1", "Motorsport.com", "https://es.motorsport.com/rss/f1/news/"),
        new Feed("Política", "elDiario.es", "https://www.eldiario.es/rss/politica/"),
        new Feed("Economía", "El País", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/section/economia/portada"),
        new Feed("Android", "Xataka Android", "https://www.xatakandroid.com/index.xml"),
        new Feed("Inteligencia artificial", "Xataka", "https://www.xataka.com/tag/inteligencia-artificial/rss2.xml"),
        new Feed("Software libre", "MuyLinux", "https://www.muylinux.com/feed/"),
        new Feed("Seguridad digital", "Hispasec", "https://unaaldia.hispasec.com/feed"),
        new Feed("Medioambiente", "Climática", "https://climatica.coop/feed/"),
        new Feed("Cultura", "El País", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/section/cultura/portada"),
        new Feed("Videojuegos", "VidaExtra", "https://www.vidaextra.com/index.xml")
    };
    static boolean enabled(Context context, String topic) {
        android.content.SharedPreferences settings = context.getSharedPreferences("settings", 0);
        return settings.getBoolean(topic, topic.equals("Internacional") ? settings.getBoolean("Mundo", true) : true);
    }
    static JSONArray fetch(Context context) {
        ArrayList<java.util.concurrent.Callable<JSONArray>> tasks = new ArrayList<>();
        for (int i = 0; i < FEEDS.length; i++) {
            final int index = i;
            if (enabled(context, FEEDS[i].topic))
                tasks.add(() -> fetchFeed(index));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        ArrayList<JSONArray> batches = new ArrayList<>();
        try {
            for (java.util.concurrent.Future<JSONArray> future : pool.invokeAll(tasks, 45, java.util.concurrent.TimeUnit.SECONDS)) {
                if (future.isCancelled()) continue;
                try {
                    batches.add(future.get());
                } catch (Exception ignored) { }
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { pool.shutdownNow(); }
        return mergeSaved(interleave(batches), new JSONArray());
    }
    static JSONArray interleave(List<JSONArray> batches) {
        JSONArray result = new JSONArray();
        for (int row = 0; ; row++) {
            boolean added = false;
            for (JSONArray batch : batches) {
                JSONObject article = batch.optJSONObject(row);
                if (article != null) { result.put(article); added = true; }
            }
            if (!added) return result;
        }
    }
    private static JSONArray fetchFeed(int index) {
        HttpURLConnection connection = null;
        try {
            if (FEEDS[index].source.equals("Público")) return readPublico(ArticleContent.download(FEEDS[index].url));
            connection = (HttpURLConnection) new URL(FEEDS[index].url).openConnection();
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0");
            try (java.io.InputStream input = new java.io.FilterInputStream(connection.getInputStream()) {
                int bytes;
                @Override public int read() throws java.io.IOException {
                    int value = super.read();
                    if (value != -1 && ++bytes > 4 * 1024 * 1024) throw new java.io.IOException("RSS demasiado grande");
                    return value;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
                    int count = in.read(buffer, offset, length);
                    if (count > 0 && (bytes += count) > 4 * 1024 * 1024) throw new java.io.IOException("RSS demasiado grande");
                    return count;
                }
            }) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
                parser.setInput(input, null);
                return readFeed(parser, FEEDS[index].topic, FEEDS[index].source);
            }
        } catch (Exception ignored) { return new JSONArray(); }
        finally { if (connection != null) connection.disconnect(); }
    }
    static JSONArray readPublico(org.jsoup.nodes.Document document) throws Exception {
        JSONArray result = new JSONArray();
        HashSet<String> links = new HashSet<>();
        for (org.jsoup.nodes.Element headline : document.select("article[class*=m-preview] h2.title a[href]")) {
            String link = ArticleContent.https(headline.attr("href"), document.baseUri());
            if (link.isEmpty() || headline.text().trim().isEmpty()) continue;
            URL address = new URL(link);
            if ((!address.getHost().equals("www.publico.es") && !address.getHost().equals("publico.es")) || !address.getPath().endsWith(".html") || !links.add(link)) continue;
            String image = "";
            org.jsoup.nodes.Element card = headline.closest("article");
            for (org.jsoup.nodes.Element photo : card.select("a.image[href] img[src]")) {
                org.jsoup.nodes.Element anchor = photo.closest("a");
                if (link.equals(ArticleContent.https(anchor.attr("href"), document.baseUri()))) {
                    image = ArticleContent.https(photo.attr("src"), document.baseUri());
                    if (!image.isEmpty()) break;
                }
            }
            // The homepage has bylines, not reliable summaries; the reader extracts the article.
            result.put(new JSONObject().put("title", headline.text().trim()).put("link", link).put("topic", "Actualidad")
                .put("source", "Público").put("image", image).put("summary", "").put("body", ""));
            if (result.length() >= MAX_ARTICLES_PER_TOPIC) break;
        }
        return result;
    }
    static String sourceUrl(String value, String source) {
        String link = value == null ? "" : value.trim();
        if ("CTXT".equals(source) && (link.startsWith("http://ctxt.es/") || link.startsWith("http://www.ctxt.es/"))) link = "https" + link.substring(4);
        return imageUrl(link);
    }
    static JSONArray readFeed(XmlPullParser p, String topic, String source) throws Exception {
                JSONArray result = new JSONArray();
                HashSet<String> links = new HashSet<>();
                String title="", link="", description="", content="", image="", tag=""; boolean item=false; int count=0, imagePriority=0;
                for (int event=p.getEventType(); event!=XmlPullParser.END_DOCUMENT; event=p.next()) {
                    if (event==XmlPullParser.START_TAG) {
                        tag=p.getName();
                        if (tag.equals("item") || tag.equals("entry")) { item=true; title=""; link=""; description=""; content=""; image=""; imagePriority=0; }
                        else if (item) {
                            if (tag.equals("link") && p.getAttributeValue(null,"href") != null) {
                                String relation = p.getAttributeValue(null,"rel");
                                if (relation == null || relation.equals("alternate")) link = p.getAttributeValue(null,"href");
                            }
                            String candidate = mediaImage(tag, sourceUrl(p.getAttributeValue(null,"url"), source), p.getAttributeValue(null,"type"), p.getAttributeValue(null,"medium"));
                            int priority=tag.equals("media:content")?3:tag.equals("media:thumbnail")?2:1;
                            if (!candidate.isEmpty() && priority>imagePriority) { image=candidate; imagePriority=priority; }
                        }
                    }
                    else if (event==XmlPullParser.TEXT && item) {
                        if (tag.equals("title")) title+=p.getText(); else if (tag.equals("link")) link+=p.getText(); else if (tag.equals("description") || tag.equals("summary")) description+=p.getText(); else if (tag.equals("content:encoded") || tag.equals("content")) content+=p.getText();
                    } else if (event==XmlPullParser.END_TAG) {
                        // Some publishers append site markup after their RSS document.
                        if (p.getName().equals("rss") || p.getName().equals("feed")) break;
                        if (p.getName().equals("item") || p.getName().equals("entry")) {
                            item=false; String clean=Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT).toString().replace('\ufffc', ' ').replaceAll("\\s+", " ").trim();
                            link = sourceUrl(link, source);
                            if (image.isEmpty()) image=htmlImage(description);
                            if (image.isEmpty()) image=htmlImage(content);
                            if (!title.trim().isEmpty() && link.startsWith("https://") && links.add(link.trim())) {
                                JSONObject article=new JSONObject(); article.put("title",Html.fromHtml(title,Html.FROM_HTML_MODE_COMPACT).toString().trim()); article.put("link",link.trim()); article.put("summary",clean.substring(0, Math.min(600, clean.length()))); article.put("topic",topic); article.put("source",source); article.put("image",image);
                                String body = content.trim().isEmpty() ? description : content;
                                article.put("body", count < 20 && body.length() <= 120000 ? body : "");
                                article.put("body_kind", content.trim().isEmpty() ? "description" : "content");
                                article.put("body_full", !article.optString("body").isEmpty() && FULL_TEXT_FEEDS.contains(source));
                                result.put(article); if (++count>=MAX_ARTICLES_PER_TOPIC) break;
                            }
                        } tag="";
                    }
                }
        return result;
    }
    static String imageUrl(String value) {
        if (value==null) return "";
        try {
            URL url=new URL(value.trim());
            return "https".equalsIgnoreCase(url.getProtocol()) && !url.getHost().isEmpty() && url.getUserInfo()==null ? url.toExternalForm() : "";
        } catch (Exception ignored) { return ""; }
    }
    static String mediaImage(String tag, String value, String type, String medium) {
        String url=imageUrl(value);
        if (url.isEmpty()) return "";
        type=type==null?"":type.toLowerCase(Locale.ROOT);
        medium=medium==null?"":medium.toLowerCase(Locale.ROOT);
        if (type.startsWith("video/") || medium.equals("video") || type.startsWith("audio/") || medium.equals("audio")) return "";
        if (tag.equals("media:thumbnail") || ((tag.equals("media:content") || tag.equals("enclosure")) && type.startsWith("image/")) || (tag.equals("media:content") && medium.equals("image"))) return url;
        if (tag.equals("media:content") && type.isEmpty() && medium.isEmpty()) {
            try { if (new URL(url).getPath().toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp|gif|avif)$")) return url; } catch (Exception ignored) {}
        }
        return "";
    }
    private static final Pattern IMAGE_SOURCE=Pattern.compile("<img\\b[^>]*?\\s+src\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", Pattern.CASE_INSENSITIVE);
    static String htmlImage(String html) {
        Matcher match=IMAGE_SOURCE.matcher(html);
        if (!match.find()) return "";
        String source=match.group(1)!=null?match.group(1):match.group(2)!=null?match.group(2):match.group(3);
        return imageUrl(Html.fromHtml(source,Html.FROM_HTML_MODE_COMPACT).toString());
    }
    static String today() { return new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()); }
    static synchronized void save(Context context, JSONArray data) {
        if (data.length()==0) return;
        android.content.SharedPreferences edition=context.getSharedPreferences("edition",0);
        JSONArray previous;
        try { previous=new JSONArray(edition.getString("data","[]")); } catch (Exception ignored) { previous=new JSONArray(); }
        edition.edit().putString("data",mergeSaved(data,previous).toString()).putString("date",today()).putInt("catalog_version",CATALOG_VERSION).apply();
    }
    static JSONArray mergeSaved(JSONArray fresh, JSONArray previous) {
        Catalog catalog=new Catalog(Arrays.asList(TOPICS));
        catalog.merge(fresh);
        catalog.merge(previous);
        JSONArray result=new JSONArray();
        for (ArrayList<JSONObject> articles:catalog.grouped.values())
            for (int i=0;i<Math.min(articles.size(),MAX_ARTICLES_PER_TOPIC);i++) result.put(articles.get(i));
        return result;
    }
    static final class Catalog {
        private final LinkedHashMap<String,ArrayList<JSONObject>> grouped=new LinkedHashMap<>();
        private final HashMap<String,Integer> shown=new HashMap<>();
        private final HashSet<String> links=new HashSet<>();
        Catalog(Collection<String> topics) {
            for (String topic:topics) { grouped.put(topic,new ArrayList<>()); shown.put(topic,0); }
        }
        int merge(JSONArray data) {
            int added=0;
            for (int i=0;i<data.length();i++) {
                JSONObject article=data.optJSONObject(i);
                if (article==null) continue;
                // The retired MeriStation RSS points to removed pages from 2023.
                if (article.optString("source").equals("MeriStation")) continue;
                boolean needsProvenance = !article.optString("body").isEmpty() && !article.has("body_full");
                if (article.optString("topic").equals("Mundo") || article.optString("summary").indexOf('\ufffc') >= 0 || needsProvenance) {
                    try {
                        article = new JSONObject(article.toString());
                        if (article.optString("topic").equals("Mundo")) article.put("topic", "Internacional");
                        article.put("summary", article.optString("summary").replace('\ufffc', ' ').replaceAll("\\s+", " ").trim());
                        if (needsProvenance) article.put("body_full", FULL_TEXT_FEEDS.contains(article.optString("source")));
                    }
                    catch (org.json.JSONException invalid) { continue; }
                }
                ArrayList<JSONObject> articles=grouped.get(article.optString("topic"));
                String link=imageUrl(article.optString("link"));
                if (articles!=null && !link.isEmpty() && !article.optString("title").trim().isEmpty() && links.add(link)) {
                    articles.add(article); added++;
                }
            }
            return added;
        }
        boolean hasMore() {
            for (Map.Entry<String,ArrayList<JSONObject>> section:grouped.entrySet())
                if (shown.get(section.getKey())<section.getValue().size()) return true;
            return false;
        }
        LinkedHashMap<String,ArrayList<JSONObject>> nextPage(int perTopic) {
            LinkedHashMap<String,ArrayList<JSONObject>> page=new LinkedHashMap<>();
            if (perTopic<=0) return page;
            for (Map.Entry<String,ArrayList<JSONObject>> section:grouped.entrySet()) {
                int start=shown.get(section.getKey()), end=Math.min(start+perTopic,section.getValue().size());
                if (start<end) {
                    page.put(section.getKey(),new ArrayList<>(section.getValue().subList(start,end)));
                    shown.put(section.getKey(),end);
                }
            }
            return page;
        }
    }
}
