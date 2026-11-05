package es.pablo.periodico;

import android.util.Xml;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import java.io.StringReader;

// Run on Android with app_process so Html and Xml use their real platform implementations.
public final class NewsImagesCheck {
    public static void main(String[] args) throws Exception {
        check("<media:content url='https://images.example/photo.jpg' type='image/jpeg'/>", "https://images.example/photo.jpg");
        check("<media:content url='https://images.example/video.mp4' type='video/mp4'><media:thumbnail url='https://images.example/thumb.jpg'/></media:content>", "https://images.example/thumb.jpg");
        check("<media:thumbnail url='https://images.example/small.jpg'/><media:content url='https://images.example/large.jpg' medium='image'/>", "https://images.example/large.jpg");
        check("<enclosure url='https://images.example/attached.webp' type='image/webp'/>", "https://images.example/attached.webp");
        check("<media:content url='https://images.example/video.mp4' medium='video'/><enclosure url='https://images.example/audio.mp3' type='audio/mpeg'/>", "");
        check("<media:content url='http://images.example/photo.jpg' type='image/jpeg'/><content:encoded><![CDATA[<img src=\"https://images.example/content.jpg?a=1&amp;b=2\">]]></content:encoded>", "https://images.example/content.jpg?a=1&b=2");
        check("<description><![CDATA[<p>Resumen</p><img alt='Foto' src='https://images.example/html.jpg'>]]></description><content:encoded><![CDATA[<img src='https://images.example/other.jpg'>]]></content:encoded>", "https://images.example/html.jpg");
        check("<description><![CDATA[<img data-src='https://images.example/lazy.jpg' src='http://images.example/plain.jpg'>]]></description>", "");
        check("<media:content url='https://images.example/no-type.png'/>", "https://images.example/no-type.png");
        if (!News.imageUrl("https://user:pass@images.example/a.jpg").isEmpty()) throw new AssertionError("Credentials accepted");
        if (parse("<item><title>  </title><link>https://example.com/article</link></item>").length()!=0) throw new AssertionError("Whitespace title accepted");
        StringBuilder items=new StringBuilder();
        for (int n=0;n<65;n++) items.append(item("").replace("/article", "/article"+n));
        if (parse(items.toString()).length()!=60) throw new AssertionError("Edition limit changed");
        if (parseDocument("<rss><channel>"+item("")+"</channel></rss><html><script>ignored()</script></html>", "Fuente").length()!=1) throw new AssertionError("Trailing publisher HTML discarded a valid RSS feed");
        String legacy="<rss><channel><item><title>Noticia CTXT</title><link>http://ctxt.es/es/story.html</link><enclosure url='http://ctxt.es//images/photo.jpg' type='image/jpg'/></item></channel></rss><html><script>ignored()</script></html>";
        JSONObject ctxt=parseDocument(legacy, "CTXT").getJSONObject(0);
        if (!ctxt.getString("link").equals("https://ctxt.es/es/story.html") || !ctxt.getString("image").equals("https://ctxt.es//images/photo.jpg")) throw new AssertionError("CTXT article or photograph lost during HTTPS normalization");
        String atom="<feed xmlns='http://www.w3.org/2005/Atom'><entry><title>Noticia Atom</title><summary type='html'>&lt;img src='https://images.example/atom.jpg'&gt;Resumen público</summary><content type='html'>&lt;p&gt;Texto público&lt;/p&gt;</content><link rel='self' href='https://example.com/feed-entry'/><link rel='alternate' href='https://example.com/atom'/></entry></feed>";
        JSONObject entry=parseDocument(atom, "El Salto").getJSONObject(0);
        if (!entry.getString("link").equals("https://example.com/atom") || !entry.getString("image").equals("https://images.example/atom.jpg") || !entry.getString("body").contains("Texto público")) throw new AssertionError("Atom headline, alternate link, photo or content lost");
        String defaultLink=atom.replace("rel='alternate' ", "");
        if (!parseDocument(defaultLink,"El Salto").getJSONObject(0).getString("link").equals("https://example.com/atom")) throw new AssertionError("Default Atom alternate link ignored");
        System.out.println("NewsImagesCheck: 16 checks passed");
    }
    private static void check(String markup, String expected) throws Exception {
        JSONObject article=parse(item(markup)).getJSONObject(0);
        if (!article.getString("image").equals(expected)) throw new AssertionError("Expected "+expected+", got "+article);
        if (article.getString("summary").indexOf('\ufffc') >= 0) throw new AssertionError("An image placeholder leaked into the summary");
        if (!article.getString("title").equals("Título & texto") || !article.getString("link").equals("https://example.com/article") || !article.getString("topic").equals("Tema") || !article.getString("source").equals("Fuente") || !article.has("summary")) throw new AssertionError("Existing fields changed: "+article);
    }
    private static String item(String markup) { return "<item><title>Título &amp; texto</title><link>https://example.com/article</link>"+markup+"</item>"; }
    private static JSONArray parse(String items) throws Exception {
        return parseDocument("<rss><channel>"+items+"</channel></rss>", "Fuente");
    }
    private static JSONArray parseDocument(String document, String source) throws Exception {
        XmlPullParser parser=Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);
        parser.setInput(new StringReader(document));
        return News.readFeed(parser,"Tema",source);
    }
}
