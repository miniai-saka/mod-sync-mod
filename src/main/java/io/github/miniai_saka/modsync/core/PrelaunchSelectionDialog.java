package io.github.miniai_saka.modsync.core;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.*;
import javax.swing.table.*;

/** The optional agent uses the same selection rules and history as the in-game screen. */
final class PrelaunchSelectionDialog {
    private PrelaunchSelectionDialog() {}
    static SyncPlan choose(SyncSelection selection, ClientConfig config, SelectionHistory history,
                           SelectionHistory.State remembered, Path game) throws IOException {
        JLabel status = new JLabel("チェックを外したModは残します。");
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JLabel heading = new JLabel("サーバー側のModとこのクライアントのModに不整合があります。同期しますか？");
        panel.add(heading, BorderLayout.NORTH);
        AbstractTableModel model = new AbstractTableModel() {
            private final String[] columns = {"選択", "処理", "Modファイル", "用途", "バージョン"};
            @Override public int getRowCount() { return selection.items().size(); }
            @Override public int getColumnCount() { return columns.length; }
            @Override public String getColumnName(int column) { return columns[column]; }
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int column) { return column == 0; }
            @Override public Object getValueAt(int row, int column) {
                var item = selection.items().get(row);
                return switch (column) {
                    case 0 -> item.selected();
                    case 1 -> item.action() == SyncSelection.Action.INSTALL ? "導入" : "退避";
                    case 2 -> item.file();
                    case 3 -> switch (item.details().environment()) {
                        case SERVER -> "サーバー専用";
                        case CLIENT -> "クライアント専用";
                        case BOTH -> "双方用";
                        case UNKNOWN -> "用途未判定";
                    };
                    default -> item.details().version();
                };
            }
            @Override public void setValueAt(Object value, int row, int column) {
                if (column != 0) return;
                selection.setSelected(selection.items().get(row), Boolean.TRUE.equals(value));
                fireTableDataChanged();
                status.setText(selection.notice().isEmpty() ? "チェックを外したModは残します。" : selection.notice());
            }
        };
        JTable table = new JTable(model);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(1).setMaxWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(120);
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
                Component component = super.getTableCellRendererComponent(t, value, selected, focus, row, col);
                putClientProperty("html.disable", true);
                if (!selected) component.setForeground(switch (selection.items().get(row).details().environment()) {
                    case SERVER -> new Color(150, 75, 0);
                    case CLIENT -> new Color(0, 100, 65);
                    case BOTH -> new Color(30, 75, 160);
                    case UNKNOWN -> Color.GRAY;
                });
                return component;
            }
        };
        table.setDefaultRenderer(String.class, renderer);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(760, 300));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(5, 5)), controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton all = new JButton("すべてチェックする"), none = new JButton("すべてのチェックを外す");
        JCheckBox advanced = new JCheckBox("上級者向けモード");
        all.addActionListener(event -> { selection.selectAll(true); model.fireTableDataChanged(); status.setText(selection.notice()); });
        none.addActionListener(event -> { selection.selectAll(false); model.fireTableDataChanged(); status.setText("選択した項目に変更はありません。"); });
        advanced.addActionListener(event -> { selection.setAdvanced(advanced.isSelected()); model.fireTableDataChanged(); status.setText(selection.notice()); });
        controls.add(all); controls.add(none); controls.add(advanced);
        bottom.add(controls, BorderLayout.NORTH); bottom.add(status, BorderLayout.SOUTH);
        panel.add(bottom, BorderLayout.SOUTH);
        int answer = JOptionPane.showOptionDialog(null, panel, "Fabric Mod Sync — 起動前の確認",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"選択したModを同期して起動する", "しないでそのまま起動する"}, "選択したModを同期して起動する");
        selection.remember(remembered); history.save(SelectionHistory.path(game));
        return answer == 0 && selection.plan().changed() ? selection.plan() : null;
    }
}
