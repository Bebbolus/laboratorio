package it.reno.lab;

import java.io.IOException;
import java.net.URI;

public final class OnionLookupTest {
    private static final String ONION = "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion/index.html";

    public static void main(String[] args) throws Exception {
        OnionLookup.Result result = OnionLookup.find("example.com", url -> {
            if (url.toString().equals("https://example.com/")) return new OnionLookup.Response(302, "https://www.example.com/path?q=1", null);
            if (url.toString().equals("https://www.example.com/path?q=1")) return new OnionLookup.Response(200, null, ONION);
            throw new AssertionError(url);
        });
        expect(result.onion.equals(ONION));
        expect(result.source.toString().equals("https://www.example.com/path?q=1"));
        expect(result.chain.size() == 2);
        OnionLookup.Result missing = OnionLookup.find("example.com", url -> new OnionLookup.Response(200, null, null));
        expect(missing.onion.isEmpty());
        fails("example.com", url -> new OnionLookup.Response(302, "http://example.com/", null));
        fails("example.com", url -> new OnionLookup.Response(302, "https://user@example.com/", null));
        fails("example.com", url -> new OnionLookup.Response(302, "http://127.0.0.1/", null));
        fails("example.com", url -> new OnionLookup.Response(302, "/", null));
        fails("example.com", url -> new OnionLookup.Response(404, null, ONION));
        fails("example.com", url -> new OnionLookup.Response(200, null, "http://" + "a".repeat(56) + ".onion/"));
        fails("example.com@evil.example", url -> { throw new AssertionError("userinfo must be rejected"); });
        System.out.println("OnionLookupTest: OK");
    }

    private static void fails(String domain, OnionLookup.Transport transport) throws Exception {
        try {
            OnionLookup.find(domain, transport);
            throw new AssertionError("expected failure");
        } catch (IOException expected) {
            // Expected failure, with no unsafe fallback.
        }
    }

    private static void expect(boolean ok) {
        if (!ok) throw new AssertionError();
    }
}
