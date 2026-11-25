package it.reno.lab;

public final class OnionAddressTest {
    public static void main(String[] args) {
        String host = "2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion";
        expect(true, "http://" + host + "/index.html");
        expect(true, "https://" + host.toUpperCase(java.util.Locale.ROOT) + "/?q=1");
        expect(false, "http://3" + host.substring(1) + "/");
        expect(false, "http://" + host.substring(0, 55) + "a.onion/");
        expect(false, "http://user@" + host + "/");
        expect(false, "http://" + host + ":8080/");
        expect(false, "http://" + host + ".example.com/");
        expect(false, "http://" + host + "@example.com/");
        expect(false, "ftp://" + host + "/");
        expect(false, " http://" + host + "/");
        expect(false, "http://" + host + "/bad path");
        expect(false, null);
        System.out.println("OnionAddressTest: OK");
    }

    private static void expect(boolean expected, String input) {
        boolean actual = OnionAddress.isValidUrl(input);
        if (actual != expected) throw new AssertionError(String.valueOf(input) + ": expected " + expected + ", got " + actual);
    }
}
