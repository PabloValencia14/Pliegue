package es.pablo.periodico;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;

// Run with app_process, as for NewsImagesCheck; no test framework is required.
public final class NewsPaginationCheck {
    public static void main(String[] args) throws Exception {
        News.Catalog catalog=new News.Catalog(Arrays.asList(News.TOPICS[0],News.TOPICS[1]));
        require(!catalog.hasMore() && catalog.nextPage(6).isEmpty(),"Empty catalog has a page");
        JSONArray input=new JSONArray();
        for (int n=0;n<14;n++) input.put(article("a"+n,News.TOPICS[0]));
        for (int n=0;n<8;n++) input.put(article("b"+n,News.TOPICS[1]));
        input.put(article("a0",News.TOPICS[1]));
        input.put(article("disabled",News.TOPICS[2]));
        input.put(new JSONObject().put("link","http://example.com/insecure").put("title","Invalid").put("topic",News.TOPICS[0]));
        require(catalog.merge(input)==22,"Deduplication or topic filtering failed");
        HashSet<String> shown=new HashSet<>();
        LinkedHashMap<String,ArrayList<JSONObject>> first=catalog.nextPage(6);
        require(first.size()==2 && first.get(News.TOPICS[0]).size()==6 && first.get(News.TOPICS[1]).size()==6,"Initial page lacks topic diversity");
        collect(first,shown);
        require(catalog.hasMore(),"Catalog ended early");
        collect(catalog.nextPage(6),shown);
        require(shown.size()==20 && catalog.hasMore(),"Second page is incorrect");
        collect(catalog.nextPage(6),shown);
        require(shown.size()==22 && !catalog.hasMore(),"Final page is incorrect");
        require(catalog.nextPage(6).isEmpty() && catalog.merge(input)==0,"End or repeat fetch duplicated articles");
        JSONArray newer=new JSONArray().put(article("new",News.TOPICS[0])).put(article("a0",News.TOPICS[0]).put("title","Updated"));
        require(catalog.merge(newer)==1 && catalog.hasMore(),"New publication was lost");
        collect(catalog.nextPage(6),shown);
        require(shown.size()==23 && !catalog.hasMore(),"New publication repeated or end is wrong");
        JSONArray saved=News.mergeSaved(newer,input);
        require(saved.length()==24 && saved.getJSONObject(0).getString("link").endsWith("/new"),"Cache failed to keep new first and retain old/disabled topics");
        require(saved.getJSONObject(1).getString("title").equals("Updated"),"Fresh article metadata was lost");
        JSONArray large=new JSONArray();
        for(int n=0;n<90;n++) large.put(article("large"+n,News.TOPICS[0]));
        require(News.mergeSaved(large,new JSONArray()).length()==60,"Saved catalog is unbounded");
        System.out.println("NewsPaginationCheck: dedup, diverse pages, exhaustion, new publications and bounded persistent merge passed");
    }
    private static JSONObject article(String id,String topic) throws Exception {
        return new JSONObject().put("link","https://example.com/"+id).put("title",id).put("topic",topic);
    }
    private static void collect(LinkedHashMap<String,ArrayList<JSONObject>> page,HashSet<String> shown) {
        for(ArrayList<JSONObject> articles:page.values())
            for(JSONObject article:articles) require(shown.add(article.optString("link")),"Visible duplicate");
    }
    private static void require(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
