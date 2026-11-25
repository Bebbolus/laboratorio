package it.reno.lab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Headlines for the "Scopri" sheet, fetched through the Tor tunnel. */
final class Discover {
    static final class Item {
        final String title;
        final String url;
        final String meta;

        Item(String title, String url, String meta) {
            this.title = title;
            this.url = url;
            this.meta = meta;
        }
    }

    private static final Pattern ITEM = Pattern.compile("<item\\b.*?</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern CDATA = Pattern.compile("^<!\\[CDATA\\[(.*?)]]>$", Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private Discover() { }

    static List<Item> hackerNews(WebSearch.Fetcher fetcher, int limit) throws Exception {
        String top = fetcher.get("hacker-news.firebaseio.com", "/v0/topstories.json");
        JSONArray ids = new JSONArray(top);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < ids.length() && items.size() < limit; i++) {
            String body;
            try {
                body = fetcher.get("hacker-news.firebaseio.com",
                        "/v0/item/" + ids.getLong(i) + ".json");
            } catch (Exception skipped) {
                continue;
            }
            JSONObject story = new JSONObject(body);
            if (story.optBoolean("dead") || story.optBoolean("deleted")) continue;
            String title = story.optString("title", "").trim();
            if (title.isEmpty()) continue;
            String url = story.optString("url", "");
            if (url.isEmpty()) {
                url = "https://news.ycombinator.com/item?id=" + story.optLong("id");
            }
            items.add(new Item(title, url, story.optInt("score") + " punti · "
                    + story.optInt("descendants") + " commenti"));
        }
        return items;
    }

    static List<Item> rss(String host, String path, String source,
                          WebSearch.Fetcher fetcher, int limit) throws Exception {
        String body = fetcher.get(host, path);
        List<Item> items = new ArrayList<>();
        Matcher entry = ITEM.matcher(body);
        while (entry.find() && items.size() < limit) {
            String block = entry.group();
            String title = first(TITLE, block);
            String link = first(LINK, block);
            if (title.isEmpty() || link.isEmpty()) continue;
            items.add(new Item(title, link, source));
        }
        return items;
    }

    private static String first(Pattern pattern, String block) {
        Matcher matcher = pattern.matcher(block);
        if (!matcher.find()) return "";
        String value = matcher.group(1).trim();
        Matcher cdata = CDATA.matcher(value);
        if (cdata.matches()) value = cdata.group(1).trim();
        value = TAG.matcher(value).replaceAll(" ");
        return value.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
    }
}
