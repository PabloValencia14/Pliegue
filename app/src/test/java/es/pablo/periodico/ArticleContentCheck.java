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
        assertEquals("https://example.com/cover.jpg", content.blocks.get(0).image);
        assertEquals(FIRST, content.blocks.get(1).text);
        assertEquals("https://example.com/photo.jpg", content.blocks.get(2).image);
        assertEquals("Pie de foto público.", content.blocks.get(3).text);
        assertTrue(content.blocks.get(4).heading);
        assertEquals(SECOND, content.blocks.get(5).text);
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
        assertFalse(ArticleContent.parseRss("<p>" + FIRST + "</p><img src='/photo.jpg'><p>" + SECOND + "</p>", URL, metadata().put("body_kind", "content")).limited);
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
        assertEquals(3, photos);
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
    @Test public void identifiesHeaderTitleBeforeCleaningAndJoinsSiblingBodies() throws Exception {
        String current = "<article><header><h1>Noticia guardada</h1></header><div class='article-body'><p>" + FIRST + "</p><img src='/one.jpg'></div><div class='article-body'><p>" + SECOND + "</p><img src='/two.jpg'></div></article>";
        String other = "<article><h2>Ajena</h2><p>" + "NO EXTRAER ".repeat(80) + "</p></article>";
        ArticleContent content = ArticleContent.parse("<main>" + current + other + "</main>", URL, metadata());
        assertFalse(content.limited);
        assertTrue(text(content).contains(FIRST)); assertTrue(text(content).contains(SECOND));
        assertFalse(text(content).contains("NO EXTRAER"));
        assertEquals(3, content.blocks.stream().filter(block -> !block.image.isEmpty()).count());
    }
    @Test public void articleItselfCanBeTheMarkedEditorialBody() throws Exception {
        for (String marker : new String[]{"itemprop='articleBody'", "class='entry-content'"}) {
            ArticleContent content = ArticleContent.parse("<article " + marker + "><h1>Noticia guardada</h1><p>" + FIRST + "</p><p>" + SECOND + "</p></article>", URL, metadata());
            assertFalse(marker, content.limited);
            assertTrue(text(content).contains(FIRST)); assertTrue(text(content).contains(SECOND));
        }
    }
    @Test public void takesLongerStructuredBodyAndMatchesTrackingButNotStoryIds() throws Exception {
        JSONObject schema = new JSONObject().put("@type", "NewsArticle").put("url", URL).put("articleBody", FIRST + "\n" + SECOND + "\nCierre editorial del artículo completo.");
        ArticleContent content = ArticleContent.parse("<script type='application/ld+json'>" + schema + "</script>" + article(), URL + "?utm_source=rss&at_campaign=feed", metadata());
        assertTrue(text(content).contains("Cierre editorial"));
        assertTrue(content.blocks.stream().anyMatch(block -> block.image.endsWith("photo.jpg")));
        assertTrue(ArticleContent.parse("<script type='application/ld+json'>" + schema + "</script>", URL + "?id=other", metadata()).limited);
    }
    @Test public void removesLinkedReadMoreAndBrandFooterButPreservesQuotedProse() throws Exception {
        String quoted = "La entrevistada dijo que el botón Seguir leyendo estaba roto; la entrada citada fue publicada ayer, según relató.";
        String extras = "<p>" + quoted + " <a href='/reference'>Seguir leyendo</a> en su declaración.</p><p><a href='/full'>Leer artículo completo</a></p><p>La entrada <a href='/full'>Titular</a> fue publicada en Medio.</p><p>En Xataka | <a href='/other'>Otra noticia</a></p><p>Y no olvides <a href='/newsletter'>suscribirte aquí a nuestro newsletter</a>.</p>";
        ArticleContent partial = ArticleContent.parse(article().replace("</article>", extras + "</article>"), URL, metadata());
        assertTrue(partial.limited); assertTrue(text(partial).contains(quoted));
        assertFalse(text(partial).contains("Leer artículo completo"));
        assertFalse(text(partial).contains("fue publicada en Medio"));
        assertFalse(text(partial).contains("En Xataka |")); assertFalse(text(partial).contains("nuestro newsletter"));
    }
    @Test public void emptyHiddenAndUnrelatedWallsDoNotRestrictCurrentStory() throws Exception {
        String current = article().replace("<article>", "<article><header><h1>Noticia guardada</h1></header>");
        String offers = "<div class='paywall'></div><div class='paywall' hidden>Suscríbete para leer</div><aside><div class='paywall'>Suscríbete para leer</div></aside><article><h1>Otra</h1><div data-premium='true'>Ajena</div></article>";
        assertFalse(ArticleContent.parse(current + offers, URL, metadata()).limited);
        assertTrue(ArticleContent.parse(current + "<aside class='paywall'>Artículo exclusivo para socias y socios. Suscríbete.</aside>", URL, metadata()).limited);
        assertTrue(ArticleContent.parse(current.replace("</article>", "<div class='paywall'>Suscríbete para seguir leyendo</div></article>"), URL, metadata()).limited);
    }
    @Test public void continuationFooterCanNameItsPublisherInsideOrOutsideTheLink() throws Exception {
        String footers = "<p><a href='/full'>Sigue leyendo</a> en El País</p><p><a href='/full'>Lee la noticia completa en elDiario.es</a></p>";
        ArticleContent content = ArticleContent.parse(article().replace("</article>", footers + "</article>"), URL, metadata());
        assertTrue(content.limited);
        assertTrue(text(content).contains(FIRST)); assertTrue(text(content).contains(SECOND));
        assertFalse(text(content).contains("Sigue leyendo"));
        assertFalse(text(content).contains("en El País"));
        assertFalse(text(content).contains("elDiario.es"));
    }
    @Test public void explicitOfficialFeedContentCanOutliveWebsiteRestriction() throws Exception {
        ArticleContent page = ArticleContent.parse(article(), URL, metadata().put("isAccessibleForFree", false));
        ArticleContent rss = ArticleContent.parseRss(article().replace("<article>", "").replace("</article>", ""), URL, metadata().put("body_kind", "content"));
        assertFalse(rss.limited); assertSame(rss, ArticleContent.preferred(page, rss));
        assertTrue(ArticleContent.parseRss(article(), URL, metadata().put("body_kind", "description")).limited);
        assertFalse(ArticleContent.parseRss(article(), URL, metadata().put("body_kind", "description").put("body_full", true)).limited);
        assertTrue(ArticleContent.parseRss(article(), URL, metadata()).limited);
    }
    @Test public void identifiedEditorialCartoonCanConsistOfOnePhoto() throws Exception {
        String cartoon = "<article class='articulo articulo--vineta'><header><h1>Viñeta</h1></header><figure><img src='/cartoon.jpg'><figcaption>Viñeta editorial.</figcaption></figure><section class='ficha-autor'><p>NO EXTRAER</p><img src='/author.jpg'></section><section class='suscribete'><p>NO EXTRAER</p></section></article>";
        ArticleContent content = ArticleContent.parse(cartoon, "https://ctxt.es/es/cartoon.htm", new JSONObject().put("title", "Viñeta"));
        assertFalse(content.limited); assertTrue(content.blocks.stream().anyMatch(block -> block.image.endsWith("cartoon.jpg")));
        assertFalse(text(content).contains("NO EXTRAER")); assertFalse(content.blocks.stream().anyMatch(block -> block.image.endsWith("author.jpg")));
        assertTrue(ArticleContent.parse("<main><img src='/generic.jpg'></main>", URL, metadata()).limited);
    }
    @Test public void acceptsCanonicalLiveUpdatesButNotArticleCardLists() throws Exception {
        JSONObject live = new JSONObject().put("@type", "LiveBlogPosting").put("url", URL);
        String updates = "<main><ol><li>" + article() + "</li><li>" + article().replace(FIRST, "Actualización " + FIRST) + "</li></ol></main>";
        String schema = "<script type='application/ld+json'>" + live + "</script>";
        ArticleContent content = ArticleContent.parse("<link rel='canonical' href='" + URL + "'>" + schema + updates, URL, metadata());
        assertFalse(content.limited); assertTrue(text(content).contains("Actualización"));
        assertTrue(ArticleContent.parse(schema + updates, URL, metadata()).limited);
    }
    @Test public void relatedReadMoreAndBodiesCannotContaminateTheCurrentStory() throws Exception {
        String current = "<article><header><h1>Noticia guardada</h1></header><div class='entry-content'><p>" + FIRST + "</p><p>" + SECOND + "</p></div><aside><div class='entry-content'><p>NO EXTRAER</p><a href='/other'>Seguir leyendo</a></div></aside><section class='related-stories'><div class='entry-content'><p>NO EXTRAER</p><a href='/other'>Continuar leyendo</a></div></section></article>";
        ArticleContent content = ArticleContent.parse(current, URL, metadata());
        assertFalse(content.limited); assertTrue(text(content).contains(SECOND)); assertFalse(text(content).contains("NO EXTRAER"));
        assertFalse(ArticleContent.parse(article().replace("</article>", "<aside><a href='/other'>Seguir leyendo</a></aside></article>"), URL, metadata()).limited);
    }
    @Test public void preservesLinkedNarrativeAndRemovesSemanticPromoPhotos() throws Exception {
        String narrative = "En España: la reforma cambia los impuestos. En la investigación: aparecen nuevos documentos.";
        String body = article().replace("</article>", "<p>En España: <a href='/law'>la reforma</a> cambia los impuestos. En la investigación: aparecen nuevos documentos.</p><section aria-labelledby='recommendations-heading'><img src='/other-story.jpg'><p>NO EXTRAER</p></section><section aria-labelledby='podcast-promo'><img src='/app.jpg'><p>NO EXTRAER</p></section><p><a href='/app'>Descarga la nueva app del BBC World Service</a>. Elige BBC News Mundo.</p></article>");
        ArticleContent content = ArticleContent.parse(body, URL, metadata());
        assertFalse(content.limited); assertTrue(text(content).contains(narrative)); assertFalse(text(content).contains("NO EXTRAER"));
        assertFalse(text(content).contains("Descarga la nueva app"));
        assertFalse(content.blocks.stream().anyMatch(block -> block.image.endsWith("app.jpg") || block.image.endsWith("other-story.jpg")));
    }
    @Test public void explicitHiddenAccessFlagStillProtectsStructuredArticleBody() throws Exception {
        JSONObject schema = new JSONObject().put("@type", "NewsArticle").put("articleBody", FIRST + "\n" + SECOND);
        ArticleContent content = ArticleContent.parse("<script type='application/ld+json'>" + schema + "</script>" + article() + "<div data-premium='true' hidden></div>", URL, metadata());
        assertTrue(content.limited); assertFalse(text(content).contains(FIRST));
    }
    @Test public void retainsTableRowsAndCellsWithoutDuplicatingNestedParagraphs() throws Exception {
        ArticleContent content = ArticleContent.parse(article().replace("</article>", "<table><tr><th>Sesión</th><th>Hora</th></tr><tr><td><p>Carrera</p></td><td>15:00</td></tr></table></article>"), URL, metadata());
        assertFalse(content.limited);
        assertTrue(text(content).contains("Sesión | Hora")); assertTrue(text(content).contains("Carrera | 15:00"));
        assertEquals(1, content.blocks.stream().filter(block -> block.text.contains("Carrera")).count());
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
            assertSame(original, ArticleContent.preferred(original, restored));
            assertEquals(text(original), text(restored));
            assertTrue(text(restored).contains(literal));
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
