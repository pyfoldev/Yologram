import pathlib, shutil, subprocess, tempfile, unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

HARNESS = """package mi.tiktokmi;
import java.net.*;
import java.util.List;
public class BypassHarness {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) throws Exception {
        BypassRules.Spec a = BypassRules.parse("proxy.example.com:8080");
        check(a != null && !a.socks && a.host.equals("proxy.example.com") && a.port == 8080, "host:port is an http proxy");
        BypassRules.Spec b = BypassRules.parse("  socks5://10.0.0.2:1080/ ");
        check(b != null && b.socks && b.host.equals("10.0.0.2") && b.port == 1080, "socks5 scheme, spaces and a trailing slash");
        BypassRules.Spec c = BypassRules.parse("http://[2001:db8::1]:3128");
        check(c != null && c.host.equals("2001:db8::1") && c.port == 3128, "ipv6 in brackets");
        check(c.text().equals("http://[2001:db8::1]:3128"), "ipv6 is written back in brackets");
        check(a.text().equals("http://proxy.example.com:8080"), "text is scheme, host, port");
        String[] bad = {null, "", "host", "host:0", "host:70000", "host:abc", "ftp://h:1", "h:1/path",
                "user@h:1", "a b:1", "http://:80", "[::1:80", "h:1:2", "ho_st:80"};
        for (String s : bad) check(BypassRules.parse(s) == null, "refused: " + s);

        check(BypassRules.local("localhost") && BypassRules.local("127.0.0.1") && BypassRules.local("::1"), "loopback is local");
        check(!BypassRules.local("tiktok.com") && !BypassRules.local(null), "the rest is not");

        ProxySelector before = ProxySelector.getDefault();
        String hostBefore = System.getProperty("http.proxyHost");
        BypassRules.install(a);
        check(BypassRules.installed(), "installed");
        check("proxy.example.com".equals(System.getProperty("http.proxyHost"))
                && "8080".equals(System.getProperty("https.proxyPort")), "properties set for the native stack");
        List<Proxy> sel = ProxySelector.getDefault().select(new URI("https://www.tiktok.com/"));
        check(sel.size() == 1 && sel.get(0).type() == Proxy.Type.HTTP, "tiktok goes through the proxy");
        check(ProxySelector.getDefault().select(new URI("http://127.0.0.1:9/")).get(0) == Proxy.NO_PROXY, "loopback stays direct");

        BypassRules.install(b);
        check(System.getProperty("http.proxyHost") == null && "10.0.0.2".equals(System.getProperty("socksProxyHost")),
                "switching to socks clears the http properties");
        check(ProxySelector.getDefault().select(new URI("https://x.org/")).get(0).type() == Proxy.Type.SOCKS, "socks selector");

        BypassRules.uninstall();
        check(!BypassRules.installed(), "uninstalled");
        check(ProxySelector.getDefault() == before, "the original selector is back");
        check(System.getProperty("socksProxyHost") == null, "socks property gone");
        check((hostBefore == null) == (System.getProperty("http.proxyHost") == null), "http property as it was");
        BypassRules.uninstall(); // twice is harmless
    }
}
"""


class BypassTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac"), "JDK required")
    def test_proxy_addresses_and_install(self):
        with tempfile.TemporaryDirectory() as folder:
            room = pathlib.Path(folder)
            (room / "BypassHarness.java").write_text(HARNESS, encoding="utf-8")
            built = subprocess.run(
                ["javac", "-encoding", "UTF-8", "-d", folder,
                 str(ROOT / "inject/java/mi/tiktokmi/BypassRules.java"),
                 str(room / "BypassHarness.java")], capture_output=True, text=True)
            self.assertEqual(0, built.returncode, built.stderr)
            ran = subprocess.run(["java", "-cp", folder, "mi.tiktokmi.BypassHarness"],
                                 capture_output=True, text=True)
            self.assertEqual(0, ran.returncode, ran.stderr)


if __name__ == "__main__":
    unittest.main()
