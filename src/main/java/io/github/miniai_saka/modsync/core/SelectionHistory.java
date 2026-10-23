package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Local preferences, separated by endpoint. Access tokens are never stored here. */
public final class SelectionHistory {
    public Map<String, State> servers = new TreeMap<>();
    public static final class State {
        public Map<String, Boolean> choices = new TreeMap<>();
        public boolean selected(SyncSelection.Action action, String file, ModDetails mod, boolean fallback) {
            String prefix = action.name() + "|";
            Boolean exact = choices.get(prefix + "file:" + file.toLowerCase(Locale.ROOT));
            return exact != null ? exact : choices.getOrDefault(prefix + mod.historyKey(file), fallback);
        }
        public void remember(List<SyncSelection.Item> items) {
            Map<String, Boolean> identities = new HashMap<>();
            for (var item : items) {
                String prefix = item.action().name() + "|";
                choices.put(prefix + "file:" + item.file().toLowerCase(Locale.ROOT), item.selected());
                identities.merge(prefix + item.details().historyKey(item.file()), item.selected(), Boolean::logicalOr);
            }
            choices.putAll(identities);
        }
    }
    public static Path path(Path game) { return game.resolve("config/fabric-mod-sync-selection.json"); }
    public static SelectionHistory load(Path path) throws IOException {
        SafePaths.noLinks(path);
        if (Files.exists(path) && Files.size(path) > 8L * 1024 * 1024) throw new IOException("選択履歴が大きすぎます。設定ファイルを確認してください。");
        SelectionHistory result = Files.exists(path) ? Json.read(path, SelectionHistory.class) : new SelectionHistory();
        if (result.servers == null || result.servers.size() > 64) throw new IOException("Invalid selection history");
        for (var state : result.servers.values()) {
            if (state == null || state.choices == null || state.choices.size() > 32768) throw new IOException("Invalid selection history");
            for (var choice : state.choices.entrySet()) if (choice.getKey() == null || choice.getKey().length() > 512 || choice.getValue() == null)
                throw new IOException("Invalid selection history item");
        }
        return result;
    }
    public State server(ClientConfig config) throws IOException {
        URI uri = config.baseUri();
        int port = uri.getPort() < 0 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
        String key = uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        if (!servers.containsKey(key) && servers.size() >= 64) throw new IOException("同期先の選択履歴が64件を超えました。古い履歴を整理してください。");
        return servers.computeIfAbsent(key, ignored -> new State());
    }
    public void save(Path path) throws IOException { SafePaths.noLinks(path); Json.write(path, this); }
}
