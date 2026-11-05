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
        System.out.println("NewsImagesCheck: 12 checks passed");
    }
    private static void check(String markup, String expected) throws Exception {
        JSONObject article=parse(item(markup)).getJSONObject(0);
        if (!article.getString("image").equals(expected)) throw new AssertionError("Expected "+expected+", got "+article);
        if (!article.getString("title").equals("Título & texto") || !article.getString("link").equals("https://example.com/article") || !article.getString("topic").equals("Tema") || !article.getString("source").equals("Fuente") || !article.has("summary")) throw new AssertionError("Existing fields changed: "+article);
    }
    private static String item(String markup) { return "<item><title>Título &amp; texto</title><link>https://example.com/article</link>"+markup+"</item>"; }
    private static JSONArray parse(String items) throws Exception {
        XmlPullParser parser=Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);
        parser.setInput(new StringReader("<rss><channel>"+items+"</channel></rss>"));
        return News.readFeed(parser,"Tema","Fuente");
    }
}
