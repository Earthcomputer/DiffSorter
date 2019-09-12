package net.earthcomputer.diffsorter;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class ProgressDialog extends JDialog {

    private JLabel title;
    private JLabel text;
    private JProgressBar progress;

    private ProgressDialog(Frame parent) {
        super(parent);

        setTitle("Operation in Progress");
        Container pane = getContentPane();
        FlowLayout layout = new FlowLayout();
        layout.setAlignment(FlowLayout.LEFT);
        pane.setLayout(layout);

        title = new JLabel();
        pane.add(title);

        JPanel panel = new JPanel();
        pane.add(panel);
        panel.setLayout(new BorderLayout());
        text = new JLabel();
        text.setFont(text.getFont().deriveFont(text.getFont().getStyle() & ~Font.BOLD));
        progress = new JProgressBar();
        text.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        panel.add(text, BorderLayout.NORTH);
        panel.add(progress, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(360, 50));

        pane.doLayout();
        setSize(400, 120);
        setResizable(false);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setVisible(true);
    }

    public void init(int totalWork, String title) {
        this.title.setText(title);
        progress.setMaximum(0);
        progress.setMaximum(totalWork);
        progress.setValue(0);
    }

    public void step(int numDone, String message) {
        text.setText(message);
        progress.setValue(numDone);
        validate();
        repaint();
    }

    public static CompletableFuture<Void> startLongTask(Frame parent, Consumer<ProgressDialog> task) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        new Thread(() -> {
            ProgressDialog dialog = new ProgressDialog(parent);
            try {
                task.accept(dialog);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                throw t;
            } finally {
                dialog.dispose();
            }
        }).start();
        return future;
    }

}
