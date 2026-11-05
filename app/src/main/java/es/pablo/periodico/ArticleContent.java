package es.pablo.periodico;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

final class ArticleContent {
    final String title, source, message;
    final List<Block> blocks;
    final boolean limited;
    private static final int MAX_DOWNLOAD = 3 * 1024 * 1024, MAX_CACHE_FILE = 1024 * 1024;
    private static final long CACHE_AGE = 24 * 60 * 60 * 1000L, CACHE_BYTES = 8 * 1024 * 1024;
    private static final String RESTRICTED = "Este medio limita el acceso a la noticia. Se muestran únicamente el resumen y la imagen públicos.";
    private static final String UNAVAILABLE = "No se ha podido recuperar el texto completo. Se muestra la información pública disponible.";
    private static final Pattern CLUTTER = Pattern.compile("(?i)(^|[-_\\s])(ads?|advert(?:isement|ising)?|banner|promo|related|morenews|more-news|recommend(?:ed|ations)?|newsletter|shar(?:e|ing)|social|comments?|outbrain|taboola|paywall|subscription|cookies?|consent)([-_\\s]|$)");
    private static final Pattern HIDDEN = Pattern.compile("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)");

    static final class Block {
        final String text, image;
        final boolean heading;
        private Block(String text, String image, boolean heading) { this.text = text; this.image = image; this.heading = heading; }
    }
    private ArticleContent(String title, String source, List<Block> blocks, boolean limited, String message) {
        this.title = title; this.source = source;
        this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
        this.limited = limited; this.message = message;
    }

    static ArticleContent load(Context context, String address) throws Exception {
        String url = https(address, address);
        JSONObject metadata = new JSONObject();
        try {
            JSONArray edition = new JSONArray(context.getSharedPreferences("edition", 0).getString("data", "[]"));
            for (int i = 0; i < edition.length(); i++) {
                JSONObject article = edition.optJSONObject(i);
                if (article != null && url.equals(https(article.optString("link"), url))) { metadata = article; break; }
            }
        } catch (Exception ignored) {}
        if (url.isEmpty()) return fallback(metadata, "", UNAVAILABLE);
        if (restricted(metadata)) return fallback(metadata, url, RESTRICTED);
        File directory = new File(context.getCacheDir(), "articles");
        File file = new File(directory, key(url) + ".json");
        ArticleContent cached = readCache(file, url);
        if (cached != null) return cached;
        String body = metadata.optString("body");
        if (!body.isEmpty()) {
            ArticleContent rss = parseRss(body, url, metadata);
            if (!rss.limited) { writeCache(directory, file, url, rss); return rss; }
            if (rss.message.equals(RESTRICTED)) return rss;
        }
        try {
            ArticleContent result = parse(download(url), metadata);
            if (!result.limited) writeCache(directory, file, url, result);
            return result;
        } catch (Exception ignored) { return fallback(metadata, url, UNAVAILABLE); }
    }

    // This entry point needs only Jsoup and org.json, so extraction can be checked on a regular JVM.
    static ArticleContent parse(String html, String url, JSONObject metadata) {
        return parse(Jsoup.parse(html, url), metadata);
    }
    static ArticleContent parseRss(String body, String url, JSONObject metadata) {
        ArticleContent content = parse("<article>" + body + "</article>", url, metadata);
        if (content.limited) return content;
        Document fragment = Jsoup.parseBodyFragment(body, url);
        for (Element link : fragment.select("a"))
            if (link.text().trim().toLowerCase(Locale.ROOT).matches("(?s)^(seguir leyendo|continuar leyendo|leer m[aá]s|leer noticia completa|read more|continue reading).*")) return fallback(metadata, url, UNAVAILABLE);
        clean(fragment);
        int paragraphs = 0, characters = 0;
        for (Element paragraph : fragment.select("p"))
            if (paragraph.closest("figcaption") == null && paragraph.text().length() >= 80) { paragraphs++; characters += paragraph.text().length(); }
        // RSS descriptions may be long teasers, so their length alone does not establish a full article.
        return paragraphs >= 2 && characters > plain(metadata.optString("summary")).length() + 120 ? content : fallback(metadata, url, UNAVAILABLE);
    }
    private static ArticleContent parse(Document document, JSONObject metadata) {
        String url = document.baseUri();
        JSONObject article = structuredArticle(document, url);
        JSONObject info = new JSONObject();
        try {
            info.put("title", first(metadata.optString("title"), article.optString("headline"), selectedText(document, "h1"), meta(document, "og:title"), document.title()));
            JSONObject publisher = article.optJSONObject("publisher");
            info.put("source", first(metadata.optString("source"), meta(document, "og:site_name"), publisher == null ? "" : publisher.optString("name"), host(url)));
            info.put("summary", first(metadata.optString("summary"), meta(document, "og:description"), meta(document, "description"), article.optString("description")));
            info.put("image", first(https(metadata.optString("image"), url), https(meta(document, "og:image"), url), structuredImage(article.opt("image"), url)));
        } catch (Exception ignored) {}
        if (restricted(metadata) || restricted(article) || restrictedPage(document)) return fallback(info, url, RESTRICTED);
        clean(document);
        Element root = bodyRoot(document, plain(info.optString("title")));
        Extraction visible = extract(root, url);
        String body = article.optString("articleBody");
        Extraction structured = new Extraction();
        if (!body.isEmpty()) {
            Document fragment = Jsoup.parseBodyFragment(body.contains("<") ? body : paragraphs(body), url);
            clean(fragment);
            structured = extract(fragment.body(), url);
        }
        Extraction content = visible;
        if (!substantial(visible) && substantial(structured)) content = structured;
        if (!substantial(content)) return fallback(info, url, UNAVAILABLE);
        boolean hasPhoto = false;
        for (Block block : content.blocks) hasPhoto |= !block.image.isEmpty();
        String image = https(info.optString("image"), url);
        if (!hasPhoto && !image.isEmpty()) content.blocks.add(0, new Block("", image, false));
        return new ArticleContent(plain(info.optString("title")), plain(info.optString("source")), content.blocks, content.truncated,
            content.truncated ? "El texto supera el límite del lector. Se muestra una parte del artículo." : "");
    }

    private static JSONObject structuredArticle(Document document, String url) {
        ArrayList<JSONObject> candidates = new ArrayList<>();
        for (Element script : document.select("script[type=application/ld+json]")) {
            try { collectArticles(new JSONTokener(script.data()).nextValue(), candidates, 0); } catch (Exception ignored) {}
        }
        ArrayList<JSONObject> matching = new ArrayList<>();
        for (JSONObject candidate : candidates) {
            Object mainPage = candidate.opt("mainEntityOfPage");
            String page = mainPage instanceof JSONObject ? first(((JSONObject)mainPage).optString("@id"), ((JSONObject)mainPage).optString("url")) : mainPage instanceof String ? (String)mainPage : "";
            String articleUrl = candidate.optString("url"), id = candidate.optString("@id");
            if (!articleUrl.isEmpty() && !samePage(articleUrl, url) || !page.isEmpty() && !samePage(page, url)) continue;
            if (articleUrl.isEmpty() && page.isEmpty() && !id.isEmpty() && !samePage(id, url)) continue;
            if (first(articleUrl, page, id).isEmpty() && candidates.size() != 1) continue;
            matching.add(candidate);
        }
        JSONObject selected = new JSONObject(); int best = -1;
        for (JSONObject candidate : matching) {
            if (restricted(candidate)) return candidate;
            int score = Math.min(candidate.optString("articleBody").length(), 100000);
            if (score > best) { best = score; selected = candidate; }
        }
        return selected;
    }
    private static void collectArticles(Object node, List<JSONObject> articles, int depth) {
        if (depth > 12) return;
        if (node instanceof JSONArray) {
            JSONArray array = (JSONArray)node;
            for (int i = 0; i < array.length(); i++) collectArticles(array.opt(i), articles, depth + 1);
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject)node;
            String type = object.optString("@type");
            if (type.contains("Article") || type.contains("BlogPosting")) articles.add(object);
            collectArticles(object.opt("@graph"), articles, depth + 1);
            collectArticles(object.opt("mainEntity"), articles, depth + 1);
        }
    }
    private static boolean restricted(JSONObject object) {
        Object free = object.opt("isAccessibleForFree");
        if (Boolean.FALSE.equals(free) || "false".equalsIgnoreCase(String.valueOf(free).trim())) return true;
        Object parts = object.opt("hasPart");
        if (parts instanceof JSONObject) return restricted((JSONObject)parts);
        if (parts instanceof JSONArray) {
            JSONArray array = (JSONArray)parts;
            for (int i = 0; i < array.length(); i++) if (array.optJSONObject(i) != null && restricted(array.optJSONObject(i))) return true;
        }
        return false;
    }
    private static boolean restrictedPage(Document document) {
        if (!document.select("[itemprop=isAccessibleForFree][content=false], [data-paywall=true], [data-premium=true], [data-is-accessible-for-free=false]").isEmpty()) return true;
        String tier = meta(document, "article:content_tier").toLowerCase(Locale.ROOT);
        if (tier.equals("locked") || tier.equals("premium") || tier.equals("metered")) return true;
        for (Element element : document.select("[id*=paywall], [class*=paywall], .subscription-wall"))
            if (element.text().toLowerCase(Locale.ROOT).matches("(?s).*(suscr[ií]b|suscriptor|subscribe|subscription|sign in|inicia sesi[oó]n|premium).*$")) return true;
        return false;
    }
    private static void clean(Element root) {
        root.select("script, style, nav, header, footer, aside, form, button, noscript, iframe, svg, canvas, [hidden], [aria-hidden=true], [inert], [role=navigation], [role=banner], [role=contentinfo], .sr-only, .visually-hidden").remove();
        for (Element element : new ArrayList<>(root.getAllElements())) {
            if (element == root) continue;
            if (CLUTTER.matcher(element.id() + " " + element.className()).find() || HIDDEN.matcher(element.attr("style")).find()) element.remove();
        }
    }
    private static Element bodyRoot(Document document, String title) {
        for (String selector : new String[]{"[itemprop=articleBody], .article-body, .article__body, .entry-content, .post-content", "article", "main, [role=main]"}) {
            List<Element> candidates = document.select(selector);
            if (selector.equals("article") && candidates.size() > 1) {
                Element identified = null;
                for (Element candidate : candidates) {
                    if (!selectedText(candidate, "h1").equalsIgnoreCase(title) || title.isEmpty()) continue;
                    if (identified != null) return null;
                    identified = candidate;
                }
                return identified;
            }
            Element selected = null; int best = 0;
            for (Element candidate : candidates) {
                if ((candidate.normalName().equals("main") || candidate.attr("role").equals("main")) && candidate.select("article").size() > 1) continue;
                int score = 0;
                for (Element paragraph : candidate.select("p")) score += paragraph.text().length();
                for (Element link : candidate.select("a")) score -= link.text().length();
                if (score > best) { best = score; selected = candidate; }
            }
            if (selected != null) return selected;
        }
        return null;
    }
    private static final class Extraction {
        final ArrayList<Block> blocks = new ArrayList<>();
        final HashSet<String> texts = new HashSet<>(), images = new HashSet<>();
        int characters, paragraphs;
        boolean truncated;
    }
    private static Extraction extract(Element root, String url) {
        Extraction result = new Extraction();
        if (root != null) walk(root, url, result);
        return result;
    }
    private static void walk(Element element, String url, Extraction result) {
        if (result.blocks.size() >= 250 || result.characters >= 180000) { result.truncated = true; return; }
        String tag = element.normalName();
        if (tag.equals("img")) {
            int width = number(element.attr("width")), height = number(element.attr("height"));
            if (width > 0 && height > 0 && width < 80 && height < 80) return;
            String image = "";
            for (String attribute : new String[]{"data-src", "data-original", "data-lazy-src", "src"}) {
                image = https(element.attr(attribute), url);
                if (!image.isEmpty()) break;
            }
            if (image.isEmpty() && element.hasAttr("srcset")) image = https(element.attr("srcset").split(",")[0].trim().split("\\s+")[0], url);
            if (!image.isEmpty() && result.images.add(image)) result.blocks.add(new Block("", image, false));
            return;
        }
        boolean textBlock = tag.matches("p|h[2-4]|li|blockquote|figcaption");
        if (textBlock && element.select("p,li,blockquote").size() <= (tag.matches("p|li|blockquote") ? 1 : 0)) {
            String text = element.text().replace('\u00a0', ' ').trim();
            if (!text.isEmpty() && result.texts.add(text)) {
                if (text.length() > 180000 - result.characters) { text = text.substring(0, 180000 - result.characters); result.truncated = true; }
                result.blocks.add(new Block(text, "", tag.startsWith("h")));
                result.characters += text.length();
                if (!tag.startsWith("h") && !tag.equals("figcaption")) result.paragraphs++;
            }
            for (Element image : element.select("img")) walk(image, url, result);
            return;
        }
        for (Element child : element.children()) walk(child, url, result);
    }
    private static boolean substantial(Extraction extraction) {
        return extraction.characters >= 240 && extraction.paragraphs >= 2 || extraction.characters >= 500 && extraction.paragraphs >= 1;
    }
    private static ArticleContent fallback(JSONObject metadata, String url, String message) {
        ArrayList<Block> blocks = new ArrayList<>();
        String image = https(metadata.optString("image"), url), summary = plain(metadata.optString("summary"));
        if (!image.isEmpty()) blocks.add(new Block("", image, false));
        if (!summary.isEmpty()) blocks.add(new Block(summary.substring(0, Math.min(summary.length(), 4000)), "", false));
        return new ArticleContent(plain(metadata.optString("title")), first(plain(metadata.optString("source")), host(url)), blocks, true, message);
    }
    private static String plain(String html) { return Jsoup.parseBodyFragment(html).text().trim(); }
    private static String first(String... values) { for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim(); return ""; }
    private static String selectedText(Element element, String selector) { Element selected = element.selectFirst(selector); return selected == null ? "" : selected.text(); }
    private static String meta(Document document, String key) {
        for (Element element : document.select("meta")) if (key.equalsIgnoreCase(element.attr("property")) || key.equalsIgnoreCase(element.attr("name"))) return element.attr("content");
        return "";
    }
    private static int number(String value) { try { return Integer.parseInt(value); } catch (Exception ignored) { return 0; } }
    private static String host(String url) { try { return new URI(url).getHost(); } catch (Exception ignored) { return ""; } }
    static String https(String value, String base) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            URI uri = new URI(base).resolve(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return "";
            String path = first(uri.getPath()).toLowerCase(Locale.ROOT);
            if (path.matches(".*\\.(svgz?|mp4|webm|m3u8|mpd)$")) return "";
            return uri.toASCIIString();
        } catch (Exception ignored) { return ""; }
    }
    private static boolean samePage(String candidate, String url) {
        String normalized = https(candidate, url);
        return !normalized.isEmpty() && normalized.split("#", 2)[0].equals(url.split("#", 2)[0]);
    }
    private static String structuredImage(Object value, String url) {
        if (value instanceof JSONObject) return https(first(((JSONObject)value).optString("url"), ((JSONObject)value).optString("contentUrl")), url);
        if (value instanceof JSONArray) { JSONArray array = (JSONArray)value; for (int i = 0; i < array.length(); i++) { String image = structuredImage(array.opt(i), url); if (!image.isEmpty()) return image; } return ""; }
        return value instanceof String ? https((String)value, url) : "";
    }
    private static String paragraphs(String text) {
        StringBuilder html = new StringBuilder();
        for (String paragraph : text.split("\\r?\\n+")) html.append("<p>").append(org.jsoup.nodes.Entities.escape(paragraph)).append("</p>");
        return html.toString();
    }

    static Document download(String address) throws Exception {
        String current = https(address, address);
        for (int redirects = 0; redirects <= 3 && !current.isEmpty(); redirects++) {
            HttpURLConnection connection = (HttpURLConnection)new URL(current).openConnection();
            connection.setConnectTimeout(12000); connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 Pliegue/0.5");
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    current = https(connection.getHeaderField("Location"), current);
                    continue;
                }
                String type = connection.getContentType();
                if (status != 200 || connection.getContentLengthLong() > MAX_DOWNLOAD || type != null && !type.toLowerCase(Locale.ROOT).matches("(?:text/html|application/xhtml\\+xml).*")) throw new java.io.IOException("Page unavailable");
                byte[] bytes;
                try (InputStream raw = connection.getInputStream(); InputStream input = "gzip".equalsIgnoreCase(connection.getContentEncoding()) ? new GZIPInputStream(raw) : raw) { bytes = read(input, MAX_DOWNLOAD); }
                String charset = null;
                if (type != null) {
                    Matcher match = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^\\s;\"']+)").matcher(type);
                    if (match.find() && Charset.isSupported(match.group(1))) charset = match.group(1);
                }
                return Jsoup.parse(new ByteArrayInputStream(bytes), charset, current);
            } finally { connection.disconnect(); }
        }
        throw new java.io.IOException("HTTPS redirect unavailable");
    }
    private static byte[] read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) { if (output.size() + count > limit) throw new java.io.IOException("Content too large"); output.write(buffer, 0, count); }
        return output.toByteArray();
    }
    private static String key(String url) throws Exception {
        StringBuilder key = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))) key.append(String.format(Locale.ROOT, "%02x", value & 255));
        return key.toString();
    }
    private static ArticleContent readCache(File file, String url) {
        if (!file.isFile() || System.currentTimeMillis() - file.lastModified() > CACHE_AGE || file.length() > MAX_CACHE_FILE) return null;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            JSONObject object = new JSONObject(new String(read(input, MAX_CACHE_FILE), StandardCharsets.UTF_8));
            if (!url.equals(object.optString("url")) || object.optInt("version") != 3) return null;
            ArrayList<Block> blocks = new ArrayList<>(); JSONArray items = object.getJSONArray("blocks");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i); String text = item.optString("text").trim(), image = https(item.optString("image"), url);
                if (!text.isEmpty()) blocks.add(new Block(text, "", item.optBoolean("heading")));
                else if (!image.isEmpty()) blocks.add(new Block("", image, false));
            }
            return blocks.isEmpty() ? null : new ArticleContent(object.optString("title").trim(), object.optString("source").trim(), blocks, false, "");
        } catch (Exception ignored) { return null; }
    }
    private static void writeCache(File directory, File file, String url, ArticleContent content) {
        if (content.limited || content.blocks.isEmpty()) return;
        File temporary = null;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            JSONArray blocks = new JSONArray();
            for (Block block : content.blocks) blocks.put(new JSONObject().put("text", block.text).put("image", block.image).put("heading", block.heading));
            byte[] bytes = new JSONObject().put("version", 3).put("url", url).put("title", content.title).put("source", content.source).put("blocks", blocks).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_CACHE_FILE) return;
            temporary = File.createTempFile("article-", ".tmp", directory);
            Files.write(temporary.toPath(), bytes);
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            File[] files = directory.listFiles((dir, name) -> name.matches("[0-9a-f]{64}\\.json"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long total = 0; for (File cached : files) total += cached.length();
            for (File cached : files) if (System.currentTimeMillis() - cached.lastModified() > CACHE_AGE || total > CACHE_BYTES) { long size = cached.length(); if (cached.delete()) total -= size; }
        } catch (Exception ignored) {} finally { if (temporary != null && temporary.exists()) temporary.delete(); }
    }
}
