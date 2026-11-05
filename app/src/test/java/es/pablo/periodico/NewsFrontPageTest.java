package es.pablo.periodico;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.junit.Test;
import static org.junit.Assert.*;

public class NewsFrontPageTest {
    private static String card(String link, String photoLink) {
        return "<article class='m-preview-default'><a class='image' href='" + photoLink + "'><img src='/files/photo.jpg'></a>"
            + "<h2 class='title'><a href='" + link + "'>Titular &amp; contexto</a></h2><p class='author'>Una firma</p></article>";
    }
    @Test public void publicHomepageProducesNewsAndAssociatedPhotosWithoutInventedSummaries() throws Exception {
        JSONArray articles = News.readPublico(Jsoup.parse(card("/politica/story.html", "/politica/story.html"), "https://www.publico.es/"));
        assertEquals(1, articles.length());
        JSONObject article = articles.getJSONObject(0);
        assertEquals("https://www.publico.es/politica/story.html", article.getString("link"));
        assertEquals("https://www.publico.es/files/photo.jpg", article.getString("image"));
        assertEquals("Titular & contexto", article.getString("title"));
        assertEquals("Público", article.getString("source"));
        assertEquals("Actualidad", article.getString("topic"));
        assertEquals("", article.getString("summary"));
        assertEquals("", article.getString("body"));
    }
    @Test public void unrelatedPhotosExternalLinksAndDuplicateStoriesAreExcluded() throws Exception {
        String html = card("/politica/story.html", "/other.html") + card("/politica/story.html", "/other.html")
            + card("https://other.example/story.html", "https://other.example/story.html") + card("/politica/", "/politica/");
        JSONArray articles = News.readPublico(Jsoup.parse(html, "https://www.publico.es/"));
        assertEquals(1, articles.length());
        assertEquals("", articles.getJSONObject(0).getString("image"));
    }
    @Test public void homepageExtractionIsBoundedAndMissingCardsRemainEmpty() throws Exception {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < 80; i++) html.append(card("/politica/" + i + ".html", "/politica/" + i + ".html"));
        assertEquals(60, News.readPublico(Jsoup.parse(html.toString(), "https://www.publico.es/")).length());
        assertEquals(0, News.readPublico(Jsoup.parse("<nav><a href='/about.html'>Información</a></nav>", "https://www.publico.es/")).length());
    }
    @Test public void legacyCtxtUrlsUseHttpsOnlyForThatPublisher() {
        assertEquals("https://ctxt.es/es/story.html", News.sourceUrl("http://ctxt.es/es/story.html", "CTXT"));
        assertEquals("https://ctxt.es//images/photo.jpg", News.sourceUrl("http://ctxt.es//images/photo.jpg", "CTXT"));
        assertEquals("", News.sourceUrl("http://ctxt.es/es/story.html", "Otro medio"));
        assertEquals("", News.sourceUrl("http://ctxt.es.evil.example/story.html", "CTXT"));
        assertEquals("", News.sourceUrl("https://user:password@ctxt.es/es/story.html", "CTXT"));
    }
}
