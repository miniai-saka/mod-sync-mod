package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;

public final class ClientConfig {
    public boolean enabled = false;
    public String server = "localhost:25566";
    public String accessToken = "";
    public static ClientConfig load(Path path) throws IOException {
        ClientConfig c = Files.exists(path) ? Json.read(path, ClientConfig.class) : new ClientConfig();
        if (c.server == null || c.accessToken == null) throw new IOException("Invalid client config");
        return c;
    }
    public URI baseUri() throws IOException { return endpoint(server); }
    public static URI endpoint(String input) throws IOException {
        try {
            String s = input.strip();
            URI uri = URI.create(s.contains("://") ? s : "http://" + s);
            if ((!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath()))
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/", null, null);
        } catch (Exception e) { throw new IOException("ドメイン:ポートを確認してください (例: localhost:25566)"); }
    }
}
