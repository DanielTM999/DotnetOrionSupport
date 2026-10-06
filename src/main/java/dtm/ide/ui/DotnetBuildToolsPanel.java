package dtm.ide.ui;

import dtm.ide.build.BuildRequest;
import dtm.ide.run.TargetFramework;
import dtm.stools.i18n.I18n;
import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class DotnetBuildToolsPanel extends JPanel implements AutoCloseable {
    private final Supplier<List<Path>> projects;
    private final Consumer<BuildRequest> run;
    private final ExecutorService worker = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong revision = new AtomicLong();
    private final AtomicLong frameworkRevision = new AtomicLong();
    private final JComboBox<Path> project = new JComboBox<>();
    private final JComboBox<String> framework = new JComboBox<>();
    private final JComboBox<String> configuration = new JComboBox<>(new String[]{"Debug", "Release"});
    private final JComboBox<String> action = new JComboBox<>(new String[]{"build", "rebuild", "restore", "clean", "test", "pack", "publish", "msbuild"});
    private final JTextField target = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final JButton execute = new JButton(text("run", "Run"));

    public DotnetBuildToolsPanel(Supplier<List<Path>> projects, Consumer<BuildRequest> run) {
        super(new BorderLayout(8, 8));
        this.projects = projects;
        this.run = run;
        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        addField(form, "project", "Solution / project", project);
        addField(form, "configuration", "Configuration", configuration);
        addField(form, "framework", "Framework (empty = all)", framework);
        addField(form, "action", "Action", action);
        addField(form, "target", "MSBuild target", target);
        target.setEnabled(false);
        action.addActionListener(event -> target.setEnabled("msbuild".equals(action.getSelectedItem())));
        project.addActionListener(event -> refreshFrameworks());
        JButton refresh = new JButton(text("refresh", "Refresh"));
        refresh.addActionListener(event -> refresh());
        execute.addActionListener(event -> execute());
        JPanel buttons = new JPanel();
        buttons.add(refresh);
        buttons.add(execute);
        add(form, BorderLayout.NORTH);
        add(status, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    }

    private static String text(String key, String fallback) { return I18n.getText(DotnetBuildToolsPanel.class, key, fallback); }

    private static void addField(JPanel form, String key, String fallback, JComponent component) {
        form.add(new JLabel(text(key, fallback)));
        form.add(component);
    }

    public void refresh() {
        long ticket = revision.incrementAndGet();
        frameworkRevision.incrementAndGet();
        project.removeAllItems();
        execute.setEnabled(false);
        if (worker.isShutdown()) return;
        status.setText(text("loading", "Loading projects..."));
        worker.submit(() -> {
            try {
                List<Path> found = projects.get();
                SwingUtilities.invokeLater(() -> {
                    if (ticket != revision.get()) return;
                    found.forEach(project::addItem);
                    execute.setEnabled(!found.isEmpty());
                    status.setText(found.isEmpty() ? text("empty", "No projects found.") : " ");
                });
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    if (ticket == revision.get()) status.setText(failure.getMessage());
                });
            }
        });
    }

    private void refreshFrameworks() {
        long ticket = frameworkRevision.incrementAndGet();
        framework.removeAllItems();
        framework.addItem("");
        Path selected = (Path) project.getSelectedItem();
        if (selected == null || worker.isShutdown()) return;
        worker.submit(() -> {
            var frameworks = TargetFramework.resolveTfms(selected);
            SwingUtilities.invokeLater(() -> {
                if (ticket == frameworkRevision.get()) frameworks.forEach(framework::addItem);
            });
        });
    }

    private void execute() {
        try {
            run.accept(new BuildRequest((Path) project.getSelectedItem(), (String) action.getSelectedItem(),
                    (String) configuration.getSelectedItem(), (String) framework.getSelectedItem(), target.getText()));
            status.setText(" ");
        } catch (IllegalArgumentException failure) {
            status.setText(text("invalid", "Select a project and a valid MSBuild target."));
        }
    }

    @Override
    public void close() {
        revision.incrementAndGet();
        frameworkRevision.incrementAndGet();
        worker.shutdownNow();
    }
}
