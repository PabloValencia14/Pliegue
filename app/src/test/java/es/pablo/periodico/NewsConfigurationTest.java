package es.pablo.periodico;

import org.junit.Test;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class NewsConfigurationTest {
    @Test public void feedsReferenceDistinctKnownTopicsAndHttpsUrls() throws Exception {
        assertEquals(14, News.TOPICS.length);
        HashSet<String> topics = new HashSet<>(Arrays.asList(News.TOPICS));
        assertEquals(News.TOPICS.length, topics.size());
        HashSet<String> combinations = new HashSet<>(), covered = new HashSet<>(), current = new HashSet<>(), international = new HashSet<>();
        for (News.Feed feed : News.FEEDS) {
            assertTrue(topics.contains(feed.topic));
            assertFalse(feed.source.trim().isEmpty());
            assertTrue(combinations.add(feed.topic + " " + feed.url));
            covered.add(feed.topic);
            if (feed.topic.equals("Actualidad")) current.add(feed.source);
            if (feed.topic.equals("Internacional")) international.add(feed.source);
            URI uri = new URI(feed.url);
            assertEquals("https", uri.getScheme());
            assertNotNull(uri.getHost());
        }
        assertEquals(topics, covered);
        assertTrue(current.size() >= 6);
        assertTrue(international.size() >= 3);
        assertEquals("Actualidad", News.TOPICS[0]);
        assertEquals("Internacional", News.TOPICS[1]);
        assertEquals("Fórmula 1", News.TOPICS[4]);
    }
    private static JSONObject article(String id, String source, String topic) throws Exception {
        return new JSONObject().put("title", id).put("link", "https://example.com/" + id).put("source", source).put("topic", topic);
    }
    @Test public void sourcesAlternateBeforeTheTopicLimitIsApplied() throws Exception {
        ArrayList<JSONArray> batches = new ArrayList<>();
        batches.add(new JSONArray());
        for (int source = 0; source < 8; source++) {
            JSONArray batch = new JSONArray();
            for (int row = 0; row < 20; row++) batch.put(article(source + "-" + row, "Medio " + source, "Actualidad"));
            batches.add(batch);
        }
        JSONArray saved = News.mergeSaved(News.interleave(batches), new JSONArray());
        assertEquals(News.MAX_ARTICLES_PER_TOPIC, saved.length());
        HashSet<String> firstPage = new HashSet<>();
        HashMap<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < saved.length(); i++) {
            String source = saved.getJSONObject(i).getString("source");
            counts.put(source, counts.getOrDefault(source, 0) + 1);
            if (i < 6) assertTrue(firstPage.add(source));
        }
        assertEquals(8, counts.size());
        for (int count : counts.values()) assertTrue(count >= 7 && count <= 8);
    }
    @Test public void unevenOrFailedFeedsRetainTheirAvailableArticlesWithoutDuplicates() throws Exception {
        JSONArray first = new JSONArray().put(article("shared", "A", "Actualidad")).put(article("second", "A", "Actualidad"));
        JSONArray second = new JSONArray().put(article("shared", "B", "Actualidad"));
        JSONArray result = News.mergeSaved(News.interleave(Arrays.asList(first, new JSONArray(), second)), new JSONArray());
        assertEquals(2, result.length());
        assertEquals("A", result.getJSONObject(0).getString("source"));
        assertEquals("second", result.getJSONObject(1).getString("title"));
    }
    @Test public void savedWorldArticlesMigrateToInternationalWithoutMutatingTheInput() throws Exception {
        JSONObject legacy = article("world", "BBC Mundo", "Mundo").put("summary", "\ufffc Texto guardado");
        JSONArray migrated = News.mergeSaved(new JSONArray(), new JSONArray().put(legacy));
        assertEquals(1, migrated.length());
        assertEquals("Internacional", migrated.getJSONObject(0).getString("topic"));
        assertEquals("Mundo", legacy.getString("topic"));
        assertEquals("Texto guardado", migrated.getJSONObject(0).getString("summary"));
        assertTrue(legacy.getString("summary").contains("\ufffc"));
        JSONObject current = article("current", "elDiario.es", "Actualidad").put("summary", "\ufffc Texto actual");
        assertEquals("Texto actual", News.mergeSaved(new JSONArray().put(current), new JSONArray()).getJSONObject(0).getString("summary"));
        News.Catalog disabled = new News.Catalog(Arrays.asList("Actualidad"));
        assertEquals(0, disabled.merge(new JSONArray().put(legacy)));
    }
}
