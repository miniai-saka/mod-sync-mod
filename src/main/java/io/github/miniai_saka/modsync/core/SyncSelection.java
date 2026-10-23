package io.github.miniai_saka.modsync.core;

import java.io.IOException;
import java.util.*;

/** Shared, testable checkbox behavior for the Minecraft screen and optional prelaunch dialog. */
public final class SyncSelection {
    public enum Action { INSTALL, RETIRE }
    public static final class Item {
        private final Action action;
        private final String file;
        private final ModDetails details;
        private boolean selected;
        private Item(Action action, String file, ModDetails details, boolean selected) {
            this.action = action; this.file = file; this.details = details; this.selected = selected;
        }
        public Action action() { return action; }
        public String file() { return file; }
        public ModDetails details() { return details; }
        public boolean selected() { return selected; }
    }
    private final SyncPlan base;
    private final List<Item> items = new ArrayList<>(), installs = new ArrayList<>(), retires = new ArrayList<>();
    private final Map<String, ModDetails> local;
    private boolean advanced;
    private String notice = "";

    public SyncSelection(SyncPlan base, Map<String, ModDetails> local, SelectionHistory.State history) {
        this.base = base;
        this.local = new TreeMap<>(local);
        for (Manifest.Entry entry : base.downloads()) {
            Item item = new Item(Action.INSTALL, entry.name(), entry.details(),
                    history.selected(Action.INSTALL, entry.name(), entry.details(), entry.selectedByDefault()));
            installs.add(item); items.add(item);
        }
        Set<String> possible = new TreeSet<>(base.removals());
        for (String name : base.before().keySet()) for (var install : installs)
            if (sameFile(name, install.file) || details(name).conflicts(install.details)) possible.add(name);
        for (String name : possible) {
            ModDetails details = details(name);
            boolean serverOverride = base.downloads().stream().anyMatch(e -> e.selectedByDefault()
                    && (sameFile(name, e.name()) || details.conflicts(e.details())));
            Item item = new Item(Action.RETIRE, name, details,
                    history.selected(Action.RETIRE, name, details, !details.serverOnly() || serverOverride));
            retires.add(item); items.add(item);
        }
        // A remembered skipped update must not independently discard the old version.
        for (Item retire : retires) if (installs.stream().anyMatch(i -> associated(retire, i))
                && installs.stream().noneMatch(i -> i.selected && associated(retire, i))) retire.selected = false;
        normalize();
    }
    private ModDetails details(String name) { return local.getOrDefault(name, ModDetails.UNKNOWN); }
    private static boolean sameFile(String a, String b) { return a.equalsIgnoreCase(b); }
    private boolean associated(Item retire, Item install) {
        return sameFile(retire.file, install.file) || retire.details.conflicts(install.details)
                // Old servers lack metadata: conservatively couple unknown updates to retirements.
                || (install.details.ids().isEmpty() && base.removals().contains(retire.file));
    }
    public List<Item> items() { return Collections.unmodifiableList(items); }
    public boolean advanced() { return advanced; }
    public String notice() { return notice; }
    public void setAdvanced(boolean value) {
        advanced = value;
        notice = value ? "Mod IDの競合チェックを解除しています。選択した構成の互換性を確認してください。" : "";
        normalize();
    }
    public void setSelected(Item item, boolean value) {
        if (!items.contains(item)) throw new IllegalArgumentException("Unknown selection item");
        item.selected = value; notice = "";
        if (item.action == Action.INSTALL && value) {
            for (Item retire : retires) if (sameFile(retire.file, item.file) || (!advanced && associated(retire, item))) retire.selected = true;
            if (!advanced) for (Item other : installs) if (other != item && item.details.conflicts(other.details)) other.selected = false;
        } else if (item.action == Action.RETIRE && !value) {
            for (Item install : installs) if (sameFile(item.file, install.file) || (!advanced && associated(item, install))) install.selected = false;
        } else if (item.action == Action.INSTALL && !value && !advanced) {
            for (Item retire : retires) if (associated(retire, item)
                    && installs.stream().noneMatch(i -> i.selected && associated(retire, i))) retire.selected = false;
        }
        normalize();
    }
    public void selectAll(boolean value) {
        items.forEach(item -> item.selected = value); notice = "";
        normalize();
    }
    private void normalize() {
        for (Item install : installs) if (install.selected) {
            for (Item retire : retires) if (!retire.selected
                    && (sameFile(retire.file, install.file) || (!advanced && associated(retire, install)))) {
                install.selected = false;
                notice = "旧版を残す選択に合わせて、競合するModの導入を外しました。";
            }
        }
        if (!advanced) {
            List<Item> accepted = new ArrayList<>();
            for (Item item : installs) if (item.selected) {
                if (accepted.stream().anyMatch(other -> item.details.conflicts(other.details))) {
                    item.selected = false; notice = "同じMod IDの候補が複数あるため、導入する候補を1つに絞りました。";
                } else accepted.add(item);
            }
        }
    }
    public SyncPlan plan() throws IOException {
        Set<String> install = new HashSet<>(), retire = new HashSet<>();
        for (Item item : installs) if (item.selected) install.add(item.file);
        for (Item item : retires) if (item.selected) retire.add(item.file);
        return base.select(install, retire);
    }
    public void remember(SelectionHistory.State history) { history.remember(items); }
}
