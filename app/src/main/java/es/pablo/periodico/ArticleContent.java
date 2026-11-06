package es.pablo.periodico;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import net.dankito.readability4j.extended.Readability4JExtended;
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
    private static final int CACHE_VERSION = 5;
    private static final int MAX_DOWNLOAD = 3 * 1024 * 1024, MAX_CACHE_FILE = 1024 * 1024;
    private static final long CACHE_AGE = 24 * 60 * 60 * 1000L, CACHE_BYTES = 8 * 1024 * 1024;
    private static final String RESTRICTED = "Este medio limita el acceso a la noticia. Se muestran únicamente el resumen y la imagen públicos.";
    private static final String UNAVAILABLE = "No se ha podido recuperar el texto completo. Se muestra la información pública disponible.";
    private static final Pattern CLUTTER = Pattern.compile("(?i)(^|[-_\\s])(ads?|advert(?:isement|ising)?|banner|promo|related|morenews|more-news|recommend(?:ed|ations)?|newsletter|shar(?:e|ing)|social|comments?|outbrain|taboola|paywall|subscription|cookies?|consent)([-_\\s]|$)");
    private static final Pattern HIDDEN = Pattern.compile("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)");

    static final class Block {
        final String text, image;
        final boolean heading;
        private final boolean caption, row;
        private Block(String text, String image, boolean heading) { this(text, image, heading, false); }
        private Block(String text, String image, boolean heading, boolean caption) { this(text, image, heading, caption, false); }
        private Block(String text, String image, boolean heading, boolean caption, boolean row) { this.text = text; this.image = image; this.heading = heading; this.caption = caption; this.row = row; }
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
        ArticleContent rss = parseRss(metadata.optString("body"), url, metadata);
        ArticleContent page;
        try { page = parse(download(url), metadata); }
        catch (Exception ignored) { page = fallback(metadata, url, UNAVAILABLE); }
        ArticleContent result = preferred(page, rss);
        if (!result.limited) writeCache(directory, file, url, result);
        return result;
    }

    // The publisher's explicit RSS content is a separate public source, including when its website has a wall.
    static ArticleContent preferred(ArticleContent page, ArticleContent rss) {
        if (page.limited != rss.limited) return page.limited ? rss : page;
        if (page.message.equals(RESTRICTED)) return page;
        return textLength(rss) > textLength(page) ? rss : page;
    }
    private static int textLength(ArticleContent content) {
        int length = 0; for (Block block : content.blocks) if (!block.caption) length += block.text.length(); return length;
    }
    // These entry points also run on the JVM with Readability4J, Jsoup and org.json.
    static ArticleContent parse(String html, String url, JSONObject metadata) {
        return parse(Jsoup.parse(html, url), metadata);
    }
    static ArticleContent parseRss(String body, String url, JSONObject metadata) {
        Document fragment = Jsoup.parse(body, url);
        ArticleContent content = parse(fragment.select("article").isEmpty() ? Jsoup.parse("<article>" + body + "</article>", url) : fragment, metadata);
        if (content.limited || "content".equals(metadata.optString("body_kind")) || metadata.optBoolean("body_full")) return content;
        // A description (or an old edition without provenance) can be a multi-paragraph teaser.
        return new ArticleContent(content.title, content.source, content.blocks, true, UNAVAILABLE);
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
        Element root = bodyRoot(document, plain(info.optString("title")), article);
        if (restricted(metadata) || restricted(article) || restrictedPage(document, plain(info.optString("title")))) return fallback(info, url, RESTRICTED);
        boolean teaser = root != null && hasTeaser(root);
        Extraction visible = readable(root, url);
        String body = article.optString("articleBody");
        Extraction structured = new Extraction();
        if (!body.isEmpty()) {
            if (host(url) != null && (host(url).equals("nuevatribuna.es") || host(url).endsWith(".nuevatribuna.es")))
                body = body.replaceFirst("(?is)^Necesitamos tu ayuda para seguir informando\\s+Colabora con Nuevatribuna\\s*", "");
            Document fragment = Jsoup.parseBodyFragment(body.contains("<") ? body : paragraphs(body), url);
            clean(fragment);
            structured = extract(fragment.body(), url);
        }
        Extraction content = visible;
        if (substantial(structured) && structured.characters > visible.characters) content = structured;
        boolean imageOnly = root != null && root.hasAttr("data-reader-image-only") && !visible.images.isEmpty();
        if (!substantial(content) && !imageOnly) return fallback(info, url, UNAVAILABLE);
        if (content != visible) restoreStructure(content, visible);
        String image = https(info.optString("image"), url);
        if (!image.isEmpty() && content.images.add(image)) content.blocks.add(0, new Block("", image, false));
        cap(content);
        boolean limited = content.truncated || teaser;
        return new ArticleContent(plain(info.optString("title")), plain(info.optString("source")), content.blocks, limited,
            content.truncated ? "El texto supera el límite del lector. Se muestra una parte del artículo." : teaser ? UNAVAILABLE : "");
    }

    private static Extraction readable(Element root, String url) {
        if (root == null) return new Extraction();
        clean(root);
        Extraction original = extract(root, url);
        try {
            Element readable = new Readability4JExtended(url, "<html><body>" + root.outerHtml() + "</body></html>").parse().getArticleContent();
            if (readable == null) return original;
            clean(readable);
            Extraction result = extract(readable, url);
            // A named editorial body already excludes cards; retain prose the scoring engine prunes from it.
            if (root.hasAttr("data-reader-body") && original.characters > result.characters || !substantial(result) && substantial(original)) return original;
            restoreStructure(result, original);
            return result;
        } catch (Exception ignored) { return original; }
    }
    private static void restoreStructure(Extraction content, Extraction original) {
        content.blocks.removeIf(block -> !block.image.isEmpty() || block.caption || block.row);
        content.images.clear();
        for (int i = 0; i < original.blocks.size(); i++) {
            Block block = original.blocks.get(i);
            if (block.image.isEmpty() && !block.caption && !block.row) continue;
            int position = content.blocks.size();
            for (int j = i + 1; j < original.blocks.size(); j++) {
                Block next = original.blocks.get(j);
                if (next.text.isEmpty() || next.caption || next.row) continue;
                int found = -1;
                for (int k = 0; k < content.blocks.size(); k++) if (content.blocks.get(k).text.equals(next.text)) { found = k; break; }
                if (found >= 0) { position = found; break; }
            }
            if (block.image.isEmpty() || content.images.add(block.image)) content.blocks.add(position, block);
        }
        if (content.blocks.size() > 250) { content.blocks.subList(250, content.blocks.size()).clear(); content.truncated = true; }
        content.characters = 0; content.paragraphs = 0;
        for (Block block : content.blocks) { content.characters += block.text.length(); if (!block.text.isEmpty() && !block.heading && !block.caption) content.paragraphs++; }
    }

    private static void cap(Extraction content) {
        int characters = 0;
        for (int i = 0; i < content.blocks.size(); i++) {
            Block block = content.blocks.get(i);
            if (i >= 250 || characters + block.text.length() > 180000) {
                if (i < 250 && characters < 180000 && !block.text.isEmpty()) {
                    content.blocks.set(i, new Block(block.text.substring(0, 180000 - characters), "", block.heading, block.caption, block.row));
                    i++;
                }
                content.blocks.subList(i, content.blocks.size()).clear(); content.truncated = true; return;
            }
            characters += block.text.length();
        }
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
    private static boolean restrictedPage(Document document, String title) {
        Element currentArticle = null;
        for (Element heading : document.select("article h1")) if (!title.isEmpty() && heading.text().equalsIgnoreCase(title)) currentArticle = heading.closest("article");
        String tier = meta(document, "article:content_tier").toLowerCase(Locale.ROOT);
        if (tier.equals("locked") || tier.equals("premium") || tier.equals("metered")) return true;
        for (Element element : document.select("[itemprop=isAccessibleForFree][content=false], [data-paywall=true], [data-premium=true], [data-is-accessible-for-free=false], [id*=paywall], [class*=paywall], .subscription-wall")) {
            if (element.closest("nav, footer, [class*=comment]") != null) continue;
            Element owner = element.closest("article");
            if (owner != null && currentArticle != null && owner != currentArticle) continue;
            String text = element.text().toLowerCase(Locale.ROOT);
            boolean aside = element.closest("aside") != null;
            if (!aside && (element.hasAttr("data-paywall") || element.hasAttr("data-premium") || element.hasAttr("data-is-accessible-for-free") || element.attr("itemprop").equals("isAccessibleForFree"))) return true;
            if (invisible(element)) continue;
            if (text.matches("(?s).*(?:art[ií]culo|contenido|article|content) exclusivo para (?:socias|socios|suscriptores|miembros|subscribers).*")) return true;
            if (aside) continue;
            if (text.matches("(?s).*(seguir leyendo|continuar leyendo|para leer|para acceder|to read|continue reading|contenido exclusivo|solo para suscriptores|only for subscribers).*")) return true;
        }
        return false;
    }
    private static boolean invisible(Element element) {
        for (Element current = element; current != null; current = current.parent())
            if (current.hasAttr("hidden") || current.hasAttr("inert") || current.attr("aria-hidden").equals("true") || HIDDEN.matcher(current.attr("style")).find()) return true;
        return false;
    }
    private static final Pattern TEASER = Pattern.compile("(?i)^(?:(?:seguir|sigue|continuar|contin[uú]a) leyendo|(?:leer|lee) m[aá]s|(?:leer|lee) (?:la )?(?:noticia|art[ií]culo) complet[oa]|read more|continue reading|read (?:the )?full (?:article|story))(?:\\s+(?:en|on)\\s+[\\p{L}\\p{N}. -]{2,80})?[\\s.…!>»]*$");
    private static boolean teaserLink(Element link) {
        if (!TEASER.matcher(link.text().trim()).matches()) return false;
        Element paragraph = link.closest("p, li");
        return paragraph == null || TEASER.matcher(paragraph.text().trim()).matches();
    }
    private static boolean hasTeaser(Element root) {
        for (Element link : root.select("a")) if (!excluded(link, root) && teaserLink(link)) return true;
        return false;
    }
    private static boolean clutter(Element element) {
        return CLUTTER.matcher(element.id() + " " + element.className() + " " + element.attr("aria-labelledby") + " "
            + element.attr("data-e2e") + " " + element.attr("data-component") + " " + element.attr("data-testid")).find();
    }
    private static boolean excluded(Element element, Element boundary) {
        for (Element current = element; current != null && current != boundary; current = current.parent())
            if (current.normalName().matches("aside|nav|header|footer") || clutter(current) || invisible(current)
                || current.is(".suscribete, .ficha-autor, .desvio, .article-asset-related, .asset-related, .jp-relatedposts, .donaciones, [class*=author-bio]")
                || current.normalName().equals("article") && !boundary.hasAttr("data-reader-live")) return true;
        return false;
    }
    private static void clean(Element root) {
        root.select("script, style, nav, header, footer, aside, form, button, noscript, iframe, svg, canvas, [hidden], [aria-hidden=true], [inert], [role=navigation], [role=banner], [role=contentinfo], .sr-only, .visually-hidden, .suscribete, .ficha-autor, .desvio, .article-asset-related, .asset-related, .zox-post-bot-wrap, .zox-post-tags, .zox-author-thumb, .zox-org-logo, .jp-relatedposts, .donaciones, [class*=author-bio]").remove();
        for (Element element : new ArrayList<>(root.getAllElements())) {
            if (element == root) continue;
            if (clutter(element) || HIDDEN.matcher(element.attr("style")).find()
                    || element.normalName().equals("article") && element.parent() != null && element.parent().closest("article") != null && !root.hasAttr("data-reader-live")) element.remove();
        }
        for (Element box : new ArrayList<>(root.select(".cajita"))) if (!box.select("a[href*=agora.ctxt.es]").isEmpty()) box.remove();
        for (Element link : new ArrayList<>(root.select("a"))) if (teaserLink(link)) {
            Element parent = link.parent();
            if (parent != null && parent != root && parent.normalName().matches("p|li") && TEASER.matcher(parent.text().trim()).matches()) parent.remove();
            else link.remove();
        }
        for (Element element : new ArrayList<>(root.select("p, h2, h3, h4"))) {
            String text = element.text().trim();
            if (text.matches("(?is)^Este art[ií]culo fue originalmente publicado en .*")) element.remove();
            else if (!element.select("a").isEmpty() && text.matches("(?is)^(?:La noticia|El art[ií]culo|La entrada|The post) .*(?:fue publicad[oa]|es original de|se public[oó] primero en|appeared first on).*")) element.remove();
            else if (!element.select("a").isEmpty() && text.matches("(?is)^(?:(?:En Xataka(?: Android)?|En Mundo Xiaomi|En VidaExtra)\\s*[|:]|(?:Y )?no olvides suscribirte .*newsletter|Suscr[ií]bete a (?:nuestro|la) (?:newsletter|bolet[ií]n)|Necesitamos tu ayuda para seguir informando|Colabora con Nuevatribuna|(?:Tambi[eé]n puedes|Recuerda que puedes) seguirnos en |Descarga la nueva app del BBC World Service).*")) element.remove();
        }
    }
    private static Element bodyRoot(Document document, String title, JSONObject schema) {
        Element canonical = document.selectFirst("link[rel=canonical]");
        if (schema.optString("@type").contains("LiveBlogPosting") && canonical != null && samePage(canonical.attr("href"), document.baseUri())) {
            Element main = document.selectFirst("main, [role=main]");
            if (main != null) return main.clone().attr("data-reader-live", "true");
        }
        // These layouts split their current story into header/body containers or include nested cards.
        String domain = first(host(document.baseUri())).toLowerCase(Locale.ROOT);
        String specific = domain.equals("publico.es") || domain.endsWith(".publico.es") ? "article #body-modules"
            : domain.equals("ctxt.es") || domain.endsWith(".ctxt.es") ? "article.articulo .articulo__body, article.articulo--vineta"
            : domain.equals("elsaltodiario.com") || domain.endsWith(".elsaltodiario.com") ? "#contenido-principal-articulo"
            : domain.equals("climatica.coop") || domain.endsWith(".climatica.coop") ? "article.article-content.contenido" : "";
        if (!specific.isEmpty() && document.select(specific).size() == 1) {
            Element selected = document.selectFirst(specific).clone().attr("data-reader-body", "true");
            if (selected.hasClass("articulo--vineta")) selected.attr("data-reader-image-only", "true");
            return selected;
        }
        ArrayList<Element> articles = new ArrayList<>();
        for (Element candidate : document.select("article"))
            if ((candidate.parent() == null || candidate.parent().closest("article") == null) && candidate.closest("aside, nav, footer") == null
                && !CLUTTER.matcher(candidate.id() + " " + candidate.className()).find()) articles.add(candidate);
        Element scope = null;
        for (Element candidate : articles) {
            boolean matches = false;
            for (Element heading : candidate.select("h1")) matches |= !title.isEmpty() && heading.text().equalsIgnoreCase(title);
            if (matches) { if (scope != null) return null; scope = candidate; }
        }
        if (scope == null && articles.size() == 1) scope = articles.get(0);
        if (scope == null && articles.size() > 1) return null;
        Element search = scope == null ? document.body() : scope;
        List<Element> bodies = search.select("[itemprop=articleBody], .article-body, .article__body, .entry-content, .post-content, .zox-post-body");
        if (bodies.contains(search)) return search.clone().attr("data-reader-body", "true");
        if (!bodies.isEmpty()) {
            Element joined = new Element("article").attr("data-reader-body", "true");
            for (Element candidate : bodies) {
                if (excluded(candidate, search)) continue;
                boolean nested = false;
                for (Element parent = candidate.parent(); parent != null && parent != search; parent = parent.parent()) if (bodies.contains(parent)) { nested = true; break; }
                if (!nested && (scope == null || candidate.closest("article") == scope)) joined.appendChild(candidate.clone());
            }
            if (!joined.children().isEmpty()) return joined;
        }
        if (scope != null) return scope.clone();
        List<Element> mains = document.select("main, [role=main]");
        return mains.size() == 1 ? mains.get(0).clone() : null;
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
        if (tag.equals("tr")) {
            ArrayList<String> cells = new ArrayList<>();
            for (Element cell : element.children()) if (cell.normalName().matches("th|td")) cells.add(cell.text());
            String text = String.join(" | ", cells).trim();
            if (!text.isEmpty()) {
                if (text.length() > 180000 - result.characters) { text = text.substring(0, 180000 - result.characters); result.truncated = true; }
                result.blocks.add(new Block(text, "", false, false, true)); result.characters += text.length(); result.paragraphs++;
            }
            for (Element image : element.select("img")) walk(image, url, result);
            return;
        }
        boolean textBlock = tag.matches("p|h[2-4]|li|blockquote|figcaption");
        if (textBlock && element.select("p,li,blockquote").size() <= (tag.matches("p|li|blockquote") ? 1 : 0)) {
            String text = element.text().replace('\u00a0', ' ').trim();
            if (!text.isEmpty() && result.texts.add(text)) {
                if (text.length() > 180000 - result.characters) { text = text.substring(0, 180000 - result.characters); result.truncated = true; }
                result.blocks.add(new Block(text, "", tag.startsWith("h"), tag.equals("figcaption")));
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
        return !normalized.isEmpty() && pageIdentity(normalized).equals(pageIdentity(https(url, url)));
    }
    private static String pageIdentity(String url) {
        String address = url.split("#", 2)[0];
        int query = address.indexOf('?');
        if (query < 0) return address;
        ArrayList<String> parameters = new ArrayList<>();
        for (String parameter : address.substring(query + 1).split("&")) {
            String name = parameter.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (!name.matches("utm_.*|fbclid|gclid|dclid|msclkid|at_.*|mc_cid|mc_eid|cmpid|ocid|ref_src|ref_url")) parameters.add(parameter);
        }
        return address.substring(0, query) + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
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
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0");
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
            if (!url.equals(object.optString("url")) || object.optInt("version") != CACHE_VERSION) return null;
            ArrayList<Block> blocks = new ArrayList<>(); JSONArray items = object.getJSONArray("blocks");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i); String text = item.optString("text").trim(), image = https(item.optString("image"), url);
                if (!text.isEmpty()) blocks.add(new Block(text, "", item.optBoolean("heading"), item.optBoolean("caption"), item.optBoolean("row")));
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
            for (Block block : content.blocks) blocks.put(new JSONObject().put("text", block.text).put("image", block.image).put("heading", block.heading).put("caption", block.caption).put("row", block.row));
            byte[] bytes = new JSONObject().put("version", CACHE_VERSION).put("url", url).put("title", content.title).put("source", content.source).put("blocks", blocks).toString().getBytes(StandardCharsets.UTF_8);
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
