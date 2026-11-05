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
    static final String[] TOPICS = {"Actualidad", "Mundo", "Tecnología", "Ciencia", "Fórmula 1"};
    static final String[] SOURCES = {"El País", "BBC Mundo", "Xataka", "El País", "Motorsport.com"};
    static final String[] FEEDS = {"https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/portada", "https://feeds.bbci.co.uk/mundo/rss.xml", "https://www.xataka.com/index.xml", "https://feeds.elpais.com/mrss-s/pages/ep/site/elpais.com/section/ciencia/portada", "https://es.motorsport.com/rss/f1/news/"};
    static JSONArray fetch(Context context) {
        JSONArray result = new JSONArray();
        boolean[] selected = new boolean[TOPICS.length];
        for (int i=0; i<TOPICS.length; i++) selected[i]=context.getSharedPreferences("settings", 0).getBoolean(TOPICS[i], true);
        for (int i=0; i<FEEDS.length; i++) {
            if (!selected[i]) continue;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(FEEDS[i]).openConnection();
                connection.setConnectTimeout(12000); connection.setReadTimeout(12000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 Periodico/1.0");
                try (java.io.InputStream input = connection.getInputStream()) {
                    XmlPullParser p = Xml.newPullParser(); p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false); p.setInput(input, null);
                    JSONArray articles = readFeed(p, TOPICS[i], SOURCES[i]);
                    for (int n=0; n<articles.length(); n++) result.put(articles.getJSONObject(n));
                }
            } catch (Exception ignored) {} finally { if (connection!=null) connection.disconnect(); }
        }
        return mergeSaved(result, new JSONArray());
    }
    static JSONArray readFeed(XmlPullParser p, String topic, String source) throws Exception {
                JSONArray result = new JSONArray();
                HashSet<String> links = new HashSet<>();
                String title="", link="", description="", content="", image="", tag=""; boolean item=false; int count=0, imagePriority=0;
                for (int event=p.getEventType(); event!=XmlPullParser.END_DOCUMENT; event=p.next()) {
                    if (event==XmlPullParser.START_TAG) {
                        tag=p.getName();
                        if (tag.equals("item")) { item=true; title=""; link=""; description=""; content=""; image=""; imagePriority=0; }
                        else if (item) {
                            String candidate = mediaImage(tag, p.getAttributeValue(null,"url"), p.getAttributeValue(null,"type"), p.getAttributeValue(null,"medium"));
                            int priority=tag.equals("media:content")?3:tag.equals("media:thumbnail")?2:1;
                            if (!candidate.isEmpty() && priority>imagePriority) { image=candidate; imagePriority=priority; }
                        }
                    }
                    else if (event==XmlPullParser.TEXT && item) {
                        if (tag.equals("title")) title+=p.getText(); else if (tag.equals("link")) link+=p.getText(); else if (tag.equals("description")) description+=p.getText(); else if (tag.equals("content:encoded")) content+=p.getText();
                    } else if (event==XmlPullParser.END_TAG) {
                        if (p.getName().equals("item")) {
                            item=false; String clean=Html.fromHtml(description, Html.FROM_HTML_MODE_COMPACT).toString().replaceAll("\\s+", " ").trim();
                            if (image.isEmpty()) image=htmlImage(description);
                            if (image.isEmpty()) image=htmlImage(content);
                            if (!title.trim().isEmpty() && link.startsWith("https://") && links.add(link.trim())) {
                                JSONObject article=new JSONObject(); article.put("title",Html.fromHtml(title,Html.FROM_HTML_MODE_COMPACT).toString().trim()); article.put("link",link.trim()); article.put("summary",clean); article.put("topic",topic); article.put("source",source); article.put("image",image); result.put(article); if (++count>=MAX_ARTICLES_PER_TOPIC) break;
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
        edition.edit().putString("data",mergeSaved(data,previous).toString()).putString("date",today()).apply();
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
