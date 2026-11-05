package es.pablo.periodico;

import org.junit.Test;
import java.net.URI;
import java.util.HashSet;
import static org.junit.Assert.*;

public class NewsConfigurationTest {
    @Test public void topicsHaveDistinctNamesAndVerifiedHttpsFeeds() throws Exception {
        assertEquals(14, News.TOPICS.length);
        assertEquals(News.TOPICS.length, News.SOURCES.length);
        assertEquals(News.TOPICS.length, News.FEEDS.length);
        HashSet<String> names = new HashSet<>();
        for (int i = 0; i < News.TOPICS.length; i++) {
            assertTrue(names.add(News.TOPICS[i]));
            assertFalse(News.SOURCES[i].trim().isEmpty());
            URI uri = new URI(News.FEEDS[i]);
            assertEquals("https", uri.getScheme());
            assertNotNull(uri.getHost());
        }
        assertEquals("Actualidad", News.TOPICS[0]);
        assertEquals("Fórmula 1", News.TOPICS[4]);
    }
}
