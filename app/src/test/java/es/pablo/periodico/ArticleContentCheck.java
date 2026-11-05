package es.pablo.periodico;

import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.stream.Collectors;
import static org.junit.Assert.*;

public class ArticleContentCheck {
    private static final String URL = "https://example.com/news/story";
    private static final String FIRST = "Primer párrafo: este es el contenido público de una noticia, con detalles suficientes para poder reconocer la información que pertenece al artículo y mantenerla separada de la navegación, los anuncios y el resto de elementos de la página.";
    private static final String SECOND = "Segundo párrafo: la información continúa con contexto adicional y explicaciones que deben conservarse en su posición original, sin mezclar titulares recomendados ni mostrar contenido ejecutable del documento del medio.";
    private static final String SUMMARY = "Resumen público de la noticia.";
    private static JSONObject metadata() throws Exception { return new JSONObject().put("title", "Noticia guardada").put("source", "Medio").put("summary", SUMMARY).put("image", "/cover.jpg"); }
    private static String text(ArticleContent content) { return content.blocks.stream().map(block -> block.text).collect(Collectors.joining("\n")); }
    private static String article() { return "<article><p>" + FIRST + "</p><figure><img src='../photo.jpg'><figcaption>Pie de foto público.</figcaption></figure><h2>Contexto</h2><p>" + SECOND + "</p></article>"; }

    @Test public void keepsParagraphsFiguresAndHeadingsInOrder() throws Exception {
        ArticleContent content = ArticleContent.parse(article(), URL, metadata());
        assertFalse(content.limited);
        assertEquals(FIRST, content.blocks.get(0).text);
        assertEquals("https://example.com/photo.jpg", content.blocks.get(1).image);
        assertEquals("Pie de foto público.", content.blocks.get(2).text);
        assertTrue(content.blocks.get(3).heading);
        assertEquals(SECOND, content.blocks.get(4).text);
        for (ArticleContent.Block block : content.blocks) assertTrue(block.text.isEmpty() ^ block.image.isEmpty());
    }
    @Test public void stripsNavigationAdsRelatedAndHiddenText() throws Exception {
        String noise = "NO EXTRAER";
        ArticleContent content = ArticleContent.parse("<main><nav><p>" + noise + "</p></nav><header><p>" + noise + "</p></header>" + article().replace("</article>", "<div class='article-advertisement'><p>" + noise + "</p></div><aside><p>" + noise + "</p></aside><div class='related-stories'><p>" + noise + "</p></div><p style='display: none'>" + noise + "</p></article>") + "<footer><p>" + noise + "</p></footer></main>", URL, metadata());
        assertFalse(content.limited);
        assertFalse(text(content).contains(noise));
    }
    @Test public void specificArticleWinsOverLongerMainAndMoreNews() throws Exception {
        String related = "NO EXTRAER. ".repeat(100);
        ArticleContent content = ArticleContent.parse("<main>" + article() + "<section class='moreNews'><p>" + related + "</p><p>" + related + "</p></section><noscript><p>" + related + "</p></noscript><p hidden>" + related + "</p></main>", URL, metadata());
        assertFalse(content.limited);
        assertFalse(text(content).contains("NO EXTRAER"));
        assertEquals(2, content.blocks.stream().filter(block -> block.text.equals(FIRST) || block.text.equals(SECOND)).count());
        String marked = article().replace("<article>", "<article><div itemprop='articleBody'>").replace("</article>", "</div><section><p>" + related + "</p></section></article>");
        assertFalse(text(ArticleContent.parse(marked, URL, metadata())).contains("NO EXTRAER"));
    }
    @Test public void listOfArticleCardsDoesNotBecomeOneFullArticle() throws Exception {
        String card = "<article><a href='/other'><p>" + FIRST + "</p></a></article>";
        assertTrue(ArticleContent.parse("<main>" + card + card + card + "</main>", URL, metadata()).limited);
    }
    @Test public void relatedArticleWithLongerProseCannotReplaceCurrentStory() throws Exception {
        String other = "<article><h2><a href='/other'>Otra noticia</a></h2><p>" + FIRST + "</p><p>" + SECOND + "</p></article>";
        String current = "<article><h1>Noticia guardada</h1><p>Entradilla corta</p></article>";
        ArticleContent partial = ArticleContent.parse("<main>" + current + other + "</main>", URL, metadata());
        assertTrue(partial.limited);
        assertFalse(text(partial).contains(FIRST));
        assertTrue(ArticleContent.parse("<main>" + other + other + "</main>", URL, metadata()).limited);
        ArticleContent identified = ArticleContent.parse("<main>" + article().replace("<article>", "<article><h1>Noticia guardada</h1>") + other + "</main>", URL, metadata());
        assertFalse(identified.limited);
        assertFalse(text(identified).contains("Otra noticia"));
    }
    @Test public void neverTreatsScriptsAndMetadataAsVisibleProse() throws Exception {
        String script = "<script>alert('NO EXTRAER')</script><script type='application/ld+json'>{\"@type\":\"NewsArticle\",\"headline\":\"Titular\",\"description\":\"Descripción pública\",\"potentialAction\":\"NO EXTRAER\"}</script>";
        ArticleContent content = ArticleContent.parse(script + article(), URL, new JSONObject());
        assertFalse(content.limited);
        assertFalse(text(content).contains("NO EXTRAER"));
        assertEquals("Titular", content.title);
    }
    @Test public void readsPublicJsonLdArticleBodyWithoutInventingDomText() throws Exception {
        JSONObject schema = new JSONObject().put("@type", "NewsArticle").put("url", URL).put("isAccessibleForFree", true).put("headline", "Artículo público").put("articleBody", FIRST + "\n\n" + SECOND);
        ArticleContent content = ArticleContent.parse("<script type='application/ld+json'>" + schema + "</script>", URL, new JSONObject());
        assertFalse(content.limited);
        assertEquals(FIRST + "\n" + SECOND, text(content));
    }
    @Test public void ignoresOtherArticlesAndAmbiguousJsonLdBodies() throws Exception {
        JSONObject other = new JSONObject().put("@type", "NewsArticle").put("url", "https://example.com/other").put("articleBody", "NO EXTRAER ".repeat(150));
        ArticleContent unrelated = ArticleContent.parse("<script type='application/ld+json'>" + other + "</script>", URL, metadata());
        assertTrue(unrelated.limited);
        assertFalse(text(unrelated).contains("NO EXTRAER"));
        JSONObject current = new JSONObject().put("@type", "NewsArticle").put("mainEntityOfPage", new JSONObject().put("@id", URL)).put("articleBody", FIRST + "\n" + SECOND);
        ArticleContent identified = ArticleContent.parse("<script type='application/ld+json'>[" + other + "," + current + "]</script>", URL, metadata());
        assertFalse(identified.limited);
        assertFalse(text(identified).contains("NO EXTRAER"));
        current.remove("mainEntityOfPage"); other.remove("url");
        assertTrue(ArticleContent.parse("<script type='application/ld+json'>[" + other + "," + current + "]</script>", URL, metadata()).limited);
    }
    @Test public void capsOneHugeParagraphAndMarksItAsPartial() throws Exception {
        ArticleContent content = ArticleContent.parse("<article><p>" + "Texto. ".repeat(30000) + "</p></article>", URL, metadata());
        assertTrue(content.limited);
        assertEquals(180000, text(content).trim().length());
        assertTrue(content.message.contains("parte"));
    }
    @Test public void rssLongTeaserIsNotClaimedAsFullArticle() throws Exception {
        JSONObject data = metadata().put("summary", FIRST + " " + SECOND);
        assertTrue(ArticleContent.parseRss("<p>" + FIRST + " " + SECOND + "</p>", URL, data).limited);
        assertTrue(ArticleContent.parseRss("<p>" + FIRST + "</p><p>" + SECOND + "</p>", URL, data).limited);
        assertFalse(ArticleContent.parseRss("<p>" + FIRST + "</p><img src='/photo.jpg'><p>" + SECOND + "</p>", URL, metadata()).limited);
        assertTrue(ArticleContent.parseRss("<p>" + FIRST + "</p><p>" + SECOND + "</p><p><a href='/story'>Seguir leyendo</a></p>", URL, metadata()).limited);
        assertTrue(ArticleContent.parseRss("<p>" + FIRST + "</p><figure><figcaption>" + SECOND + "</figcaption></figure>", URL, metadata()).limited);
    }
    @Test public void rejectsPaywalledJsonEvenWhenHiddenFullTextIsDelivered() throws Exception {
        for (Object flag : new Object[]{false, "false", " FALSE "}) {
            JSONObject schema = new JSONObject().put("@type", "NewsArticle").put("isAccessibleForFree", flag).put("articleBody", FIRST + "\n" + SECOND);
            ArticleContent content = ArticleContent.parse("<script type='application/ld+json'>" + schema + "</script>" + article(), URL, metadata());
            assertTrue(content.limited);
            assertEquals(SUMMARY, text(content).trim());
            assertTrue(content.message.contains("limita el acceso"));
            assertFalse(text(content).contains(FIRST));
        }
    }
    @Test public void respectsPaywalledPartsAndVisibleSubscriptionWalls() throws Exception {
        JSONObject schema = new JSONObject().put("@type", "Article").put("isAccessibleForFree", true).put("hasPart", new JSONObject().put("isAccessibleForFree", false));
        assertTrue(ArticleContent.parse("<script type='application/ld+json'>" + schema + "</script>" + article(), URL, metadata()).limited);
        assertTrue(ArticleContent.parse(article() + "<div class='paywall'>Suscríbete para seguir leyendo</div>", URL, metadata()).limited);
        assertTrue(ArticleContent.parse(article(), URL, metadata().put("isAccessibleForFree", false)).limited);
    }
    @Test public void acceptsOnlyHttpsRasterImagesAndResolvesLazySources() throws Exception {
        String images = "<img src='data:image/png;base64,x'><img src='http://example.com/insecure.jpg'><img src='/icon.svg'><img src='/movie.mp4'><img width='1' height='1' src='/pixel.png'><img src='/placeholder.svg' data-src='//cdn.example.com/photo.webp'>";
        ArticleContent content = ArticleContent.parse(article().replace("<figure>", images + "<figure>"), URL, metadata());
        long photos = content.blocks.stream().filter(block -> !block.image.isEmpty()).count();
        assertEquals(2, photos);
        assertTrue(content.blocks.stream().anyMatch(block -> block.image.equals("https://cdn.example.com/photo.webp")));
        assertEquals("", ArticleContent.https("https://user:password@example.com/photo.jpg", URL));
    }
    @Test public void emptyOrUnstructuredPagesRemainClearlyLimited() throws Exception {
        ArticleContent empty = ArticleContent.parse("<main><h1>Título</h1><nav><p>Noticias y navegación</p></nav></main>", URL, metadata());
        assertTrue(empty.limited);
        assertEquals(SUMMARY, text(empty).trim());
        assertFalse(empty.message.isEmpty());
        assertTrue(ArticleContent.parse("<p>" + FIRST + "</p><p>" + SECOND + "</p>", URL, metadata()).limited);
    }
    @Test public void savesOnlySuccessfulTextAndExpiresThePrivateCache() throws Exception {
        File directory = Files.createTempDirectory("pliegue-content-check").toFile();
        File file = new File(directory, "a".repeat(64) + ".json");
        Method write = ArticleContent.class.getDeclaredMethod("writeCache", File.class, File.class, String.class, ArticleContent.class);
        Method read = ArticleContent.class.getDeclaredMethod("readCache", File.class, String.class);
        write.setAccessible(true); read.setAccessible(true);
        try {
            String literal = "<script>ejemplo</script>";
            String markup = article().replace(FIRST, FIRST + " <code>&lt;script&gt;ejemplo&lt;/script&gt;</code>");
            ArticleContent original = ArticleContent.parse(markup, URL, metadata());
            write.invoke(null, directory, file, URL, original);
            ArticleContent restored = (ArticleContent)read.invoke(null, file, URL);
            assertNotNull(restored);
            assertEquals(original.blocks.get(0).text, restored.blocks.get(0).text);
            assertTrue(restored.blocks.get(0).text.contains(literal));
            assertNull(read.invoke(null, file, URL + "/other"));
            assertTrue(file.setLastModified(System.currentTimeMillis() - 25 * 60 * 60 * 1000L));
            assertNull(read.invoke(null, file, URL));
            assertTrue(file.delete());
            write.invoke(null, directory, file, URL, ArticleContent.parse("<main></main>", URL, metadata()));
            assertFalse(file.exists());
        } finally {
            File[] files = directory.listFiles();
            if (files != null) for (File cached : files) cached.delete();
            directory.delete();
        }
    }
}
