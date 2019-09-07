package net.earthcomputer.diffsorter;

import com.github.difflib.algorithm.DiffException;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.text.DiffRow;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import de.sciss.syntaxpane.DefaultSyntaxKit;
import de.sciss.syntaxpane.SyntaxDocument;
import de.sciss.syntaxpane.syntaxkits.JavaSyntaxKit;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

public class DiffSorter {
    private JPanel panel1;
    private JEditorPane leftEditorPane;
    private JEditorPane rightEditorPane;
    private JScrollPane leftScrollBar;
    private JScrollPane rightScrollBar;
    private JPanel categoryPanel;
    private JPanel addToPanel;
    private JComboBox<String> categoriesComboBox;

    public static void main(String[] args) {
        JFrame frame = new JFrame("DiffSorter");
        DiffSorter sorter = new DiffSorter();
        ProgramState.ui = sorter;
        ProgramState.frame = frame;
        frame.setContentPane(sorter.panel1);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setJMenuBar(createMenu(frame));
        frame.pack();
        frame.setVisible(true);
    }

    private static JMenuBar createMenu(JFrame frame) {
        JMenuBar menuBar = new JMenuBar();
        {
            JMenu fileMenu = new JMenu("File");
            fileMenu.setMnemonic(KeyEvent.VK_F);

            {
                JMenuItem openItem = new JMenuItem("Open");
                openItem.setMnemonic(KeyEvent.VK_O);
                openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK));
                openItem.addActionListener(e -> open(frame));
                fileMenu.add(openItem);
            }

            {
                JMenuItem saveItem = new JMenuItem("Save");
                saveItem.setMnemonic(KeyEvent.VK_S);
                saveItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK));
                saveItem.addActionListener(e -> save(frame));
                fileMenu.add(saveItem);
            }

            menuBar.add(fileMenu);
        }

        return menuBar;
    }

    private static void open(JFrame frame) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File("."));
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fileChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION)
            return;
        ProgramState.saveDir = fileChooser.getSelectedFile();
        try {
            ProgramState.load(frame, fileChooser.getSelectedFile());
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "An I/O error occurred", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void save(JFrame frame) {
        if (ProgramState.saveDir == null) {
            JOptionPane.showMessageDialog(frame, "No project open to save", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            ProgramState.save(ProgramState.saveDir);
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "An I/O error occurred", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    static class Highlight {
        int begin;
        int end;
        Color color;

        public Highlight(int begin, int end, Color color) {
            this.begin = begin;
            this.end = end;
            this.color = color;
        }
    }

    public void refresh(JFrame frame) {
        // Category panel
        categoryPanel.removeAll();
        categoryPanel.add(new JLabel("Selected category: "));
        categoriesComboBox = new JComboBox<>(ProgramState.categories.keySet().stream()
                .sorted()
                .toArray(String[]::new));
        categoriesComboBox.setSelectedItem(ProgramState.currentCategory);
        categoriesComboBox.addActionListener(e -> {
            ProgramState.currentCategory = (String) categoriesComboBox.getSelectedItem();
            ProgramState.selectedFile = 0;
            ProgramState.selectedHunk = -1;
            refresh(frame);
        });
        categoryPanel.add(categoriesComboBox);
        {
            JButton button = new JButton("New category");
            button.addActionListener(e -> {
                String name = JOptionPane.showInputDialog(frame, "Enter category name");
                if (name != null && !name.isEmpty()) {
                    if (ProgramState.categories.keySet().stream().anyMatch(n -> n.equalsIgnoreCase(name))) {
                        JOptionPane.showMessageDialog(frame, "There is already a category with that name");
                    } else {
                        ProgramState.categories.put(name, UnifiedDiff.from("", ""));
                        refresh(frame);
                    }
                }
            });
            categoryPanel.add(button);
        }
        {
            JButton button = new JButton("Delete category");
            button.setEnabled(!"unsorted".equalsIgnoreCase(ProgramState.currentCategory));
            button.addActionListener(e -> {
                if (JOptionPane.showConfirmDialog(frame,
                        "Are you sure you want to remove this category and everything inside it?\nThis will be permanent and cannot be undone.",
                        "Are you sure?",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION)
                    return;
                ProgramState.categories.remove(ProgramState.currentCategory);
                ProgramState.selectedFile = 0;
                ProgramState.selectedHunk = -1;
                ProgramState.currentCategory = "unsorted";
                refresh(frame);
            });
            categoryPanel.add(button);
        }
        categoryPanel.revalidate();

        // Editor panes
        leftEditorPane.getHighlighter().removeAllHighlights();
        rightEditorPane.getHighlighter().removeAllHighlights();
        List<Highlight> leftHighlights = new ArrayList<>();
        List<Highlight> rightHighlights = new ArrayList<>();
        List<Highlight> leftOverlayHighlights = new ArrayList<>();
        List<Highlight> rightOverlayHighlights = new ArrayList<>();

        // apologies for the state of this code
        ProgramState.leftDiffHunkPositions.clear();
        ProgramState.leftDiffFilePositions.clear();
        ProgramState.rightDiffHunkPositions.clear();
        ProgramState.rightDiffFilePositions.clear();
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        UnifiedDiff diff = ProgramState.categories.get(ProgramState.currentCategory);
        int currentFile = 0;
        for (UnifiedDiffFile file : diff.getFiles()) {
            System.out.println("Loading file " + currentFile + " / " + diff.getFiles().size());
            ProgramState.leftDiffFilePositions.add(left.length());
            ProgramState.rightDiffFilePositions.add(right.length());
            if (file.getDiffCommand() != null && file.getDiffCommand().startsWith("Only in")) {
                int begin = left.length();
                left.append(file.getDiffCommand()).append("\n");
                leftHighlights.add(new Highlight(begin, left.length(), Color.YELLOW.brighter()));
                begin = right.length();
                right.append(file.getDiffCommand()).append("\n");
                rightHighlights.add(new Highlight(begin, right.length(), Color.YELLOW.brighter()));
                currentFile++;
                continue;
            }
            int begin = left.length();
            if (file.getDiffCommand() != null)
                left.append(file.getDiffCommand()).append("\n");
            left.append("--- ").append(file.getFromFile()).append("\n");
            leftHighlights.add(new Highlight(begin, left.length(), Color.LIGHT_GRAY));
            begin = right.length();
            if (file.getDiffCommand() != null)
                right.append(file.getDiffCommand()).append("\n");
            right.append("+++ ").append(file.getToFile()).append("\n");
            rightHighlights.add(new Highlight(begin, right.length(), Color.LIGHT_GRAY));
            for (AbstractDelta<String> delta : file.getPatch().getDeltas()) {
                int leftHunkStart = left.length();
                int rightHunkStart = right.length();
                begin = left.length();
                left.append("@@ ").append(delta.getSource().getPosition()).append(",").append(delta.getSource().size()).append(" @@\n");
                leftHighlights.add(new Highlight(begin, left.length(), Color.LIGHT_GRAY));
                begin = right.length();
                right.append("@@ ").append(delta.getTarget().getPosition()).append(",").append(delta.getTarget().size()).append(" @@\n");
                rightHighlights.add(new Highlight(begin, right.length(), Color.LIGHT_GRAY));
                try {
                    int leftInlineBegin = -1;
                    int rightInlineBegin = -1;
                    for (DiffRow line : ProgramState.DIFF_ROW_GENERATOR.generateDiffRows(delta.getSource().getLines(), delta.getTarget().getLines())) {
                        leftInlineBegin = addDiffLine(left, leftHighlights, leftOverlayHighlights,
                                line.getOldLine(), line.getTag(), leftInlineBegin,
                                ProgramState.BEGINOLD, ProgramState.ENDOLD, new Color(255, 130, 141), DiffRow.Tag.INSERT);
                        rightInlineBegin = addDiffLine(right, rightHighlights, rightOverlayHighlights,
                                line.getNewLine(), line.getTag(), rightInlineBegin,
                                ProgramState.BEGINNEW, ProgramState.ENDNEW, new Color(110, 255, 118), DiffRow.Tag.DELETE);
                    }
                } catch (DiffException e) {
                    left.append("Exception generating diff\n");
                    right.append("\n");
                    e.printStackTrace();
                }
                ProgramState.leftDiffHunkPositions.add(new ProgramState.HunkPos(leftHunkStart, left.length(), currentFile));
                ProgramState.rightDiffHunkPositions.add(new ProgramState.HunkPos(rightHunkStart, right.length(), currentFile));
            }
            currentFile++;
        }
        System.out.println("Setting text");
        leftEditorPane.setText(left.toString());
        rightEditorPane.setText(right.toString());
        System.out.println("Highlighter");
        class OverNewlineHighlighter extends DefaultHighlighter.DefaultHighlightPainter {
            public OverNewlineHighlighter(Color color) {
                super(color);
            }

            @Override
            public void paint(Graphics graphics, int i, int i1, Shape shape, JTextComponent jTextComponent) {
                paintLayer(graphics, i, i1, shape, jTextComponent, null);
            }

            @Override
            public Shape paintLayer(Graphics graphics, int i, int i1, Shape shape, JTextComponent jTextComponent, View view) {
                graphics.setColor(getColor());
                try {
                    Rectangle left = jTextComponent.modelToView(i);
                    Rectangle rect = new Rectangle(shape.getBounds().x, left.y, jTextComponent.getWidth(), left.height);
                    graphics.fillRect(rect.x, rect.y, rect.width, rect.height);
                    return rect;
                } catch (BadLocationException ignore) {
                    return null;
                }
            }
        }
        try {
            for (Highlight highlight : leftOverlayHighlights)
                leftEditorPane.getHighlighter().addHighlight(highlight.begin, highlight.end, new DefaultHighlighter.DefaultHighlightPainter(highlight.color));
            for (Highlight highlight : rightOverlayHighlights)
                rightEditorPane.getHighlighter().addHighlight(highlight.begin, highlight.end, new DefaultHighlighter.DefaultHighlightPainter(highlight.color));
            for (Highlight highlight : leftHighlights)
                leftEditorPane.getHighlighter().addHighlight(highlight.begin, highlight.end, new OverNewlineHighlighter(highlight.color));
            for (Highlight highlight : rightHighlights)
                rightEditorPane.getHighlighter().addHighlight(highlight.begin, highlight.end, new OverNewlineHighlighter(highlight.color));
        } catch (BadLocationException e) {
            e.printStackTrace();
        }

        System.out.println("Regression model");
        // Regression model
        ProgramState.createModel();

        System.out.println("Updating selections");
        updateSelections(frame);
        System.out.println("Imported");
    }

    private static int addDiffLine(StringBuilder output, List<Highlight> highlights, List<Highlight> overlayHighlights, // outputs
                                   String line, DiffRow.Tag tag, int inlineBegin, // inputs
                                   String beginInline, String endInline, Color color, DiffRow.Tag emptyTag) { // parameters
        line = line.replace("&lt;", "<").replace("&gt;", ">");
        int begin = output.length();
        if (inlineBegin != -1 && line.contains(endInline)) {
            overlayHighlights.add(new Highlight(inlineBegin, begin + line.indexOf(endInline), color));
            line = line.replaceFirst(endInline, "");
            inlineBegin = -1;
        }
        while (line.contains(beginInline)) {
            inlineBegin = begin + line.indexOf(beginInline);
            line = line.replaceFirst(beginInline, "");
            if (line.contains(endInline)) {
                overlayHighlights.add(new Highlight(inlineBegin, begin + line.indexOf(endInline), color));
                line = line.replaceFirst(endInline, "");
                inlineBegin = -1;
            }
        }
        output.append(line).append("\n");
        if (tag != DiffRow.Tag.EQUAL)
            highlights.add(new Highlight(begin, output.length(), tag == emptyTag ? new Color(230, 230, 230) : color.brighter()));

        return inlineBegin;
    }

    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        panel1 = new JPanel();
        panel1.setLayout(new BorderLayout(0, 0));
        panel1.setOpaque(true);
        panel1.setPreferredSize(new Dimension(1280, 720));
        final JSplitPane splitPane1 = new JSplitPane();
        splitPane1.setResizeWeight(0.5);
        panel1.add(splitPane1, BorderLayout.CENTER);
        splitPane1.setLeftComponent(leftScrollBar);
        leftScrollBar.setViewportView(leftEditorPane);
        splitPane1.setRightComponent(rightScrollBar);
        rightScrollBar.setViewportView(rightEditorPane);
        categoryPanel = new JPanel();
        categoryPanel.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 5));
        panel1.add(categoryPanel, BorderLayout.NORTH);
        final JLabel label1 = new JLabel();
        label1.setText("No category selected");
        categoryPanel.add(label1);
        addToPanel = new JPanel();
        addToPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 5, 5));
        panel1.add(addToPanel, BorderLayout.SOUTH);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return panel1;
    }

    private void createUIComponents() {
        DefaultSyntaxKit.initKit();
        leftEditorPane = createSyntaxPane(true);
        rightEditorPane = createSyntaxPane(false);
        leftScrollBar = new JScrollPane();
        rightScrollBar = new JScrollPane();
        rightScrollBar.getVerticalScrollBar().setModel(leftScrollBar.getVerticalScrollBar().getModel());
    }

    private Object leftHighlightRef;
    private Object rightHighlightRef;

    private JEditorPane createSyntaxPane(boolean left) {
        JEditorPane syntaxPane = new JEditorPane();
        syntaxPane.setEditorKit(new JavaSyntaxKit());
        SyntaxDocument doc = (SyntaxDocument) syntaxPane.getDocument();
        doc.setContainer(syntaxPane);
        doc.setLarge(true);
        syntaxPane.setEditable(false);
        syntaxPane.setContentType("text/java");
        syntaxPane.setFont(syntaxPane.getFont().deriveFont(16f));
        syntaxPane.setCaret(new DefaultCaret() {
            {
                setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
            }

            @Override
            public int getMark() {
                return getDot(); // disable selection
            }

            @Override
            public boolean isSelectionVisible() {
                return false;
            }

            @Override
            public boolean isVisible() {
                return true;
            }
        });
        syntaxPane.setSelectionColor(syntaxPane.getBackground());

        List<ProgramState.HunkPos> hunkPositions = left ? ProgramState.leftDiffHunkPositions : ProgramState.rightDiffHunkPositions;
        List<Integer> filePositions = left ? ProgramState.leftDiffFilePositions : ProgramState.rightDiffFilePositions;
        syntaxPane.addCaretListener(e -> {
            int hunk = -1;
            for (int i = 0; i < hunkPositions.size(); i++) {
                ProgramState.HunkPos pos = hunkPositions.get(i);
                if (e.getDot() >= pos.start && e.getDot() < pos.end) {
                    hunk = i;
                    break;
                }
            }
            if (hunk == -1) {
                int file;
                for (file = filePositions.size() - 1; file >= 0; file--) {
                    if (filePositions.get(file) <= e.getDot())
                        break;
                }
                ProgramState.selectedFile = file;
                ProgramState.selectedHunk = -1;
            } else {
                ProgramState.selectedFile = hunkPositions.get(hunk).file;
                ProgramState.selectedHunk = hunk;
            }
            updateSelections(ProgramState.frame);
        });

        return syntaxPane;
    }

    private void updateSelections(JFrame frame) {
        leftHighlightRef = updateSelection0(leftHighlightRef, leftEditorPane, ProgramState.leftDiffFilePositions, ProgramState.leftDiffHunkPositions);
        rightHighlightRef = updateSelection0(rightHighlightRef, rightEditorPane, ProgramState.rightDiffFilePositions, ProgramState.rightDiffHunkPositions);

        addToPanel.removeAll();

        if (ProgramState.selectedFile >= ProgramState.leftDiffFilePositions.size() || ProgramState.selectedHunk >= ProgramState.leftDiffHunkPositions.size())
            return;

        Map<String, Integer> words = new HashMap<>();
        ProgramState.extractWords(ProgramState.currentCategory, ProgramState.selectedFile, ProgramState.selectedHunk, words);
        Map<String, Float> probabilities = new HashMap<>();
        for (String category : ProgramState.categories.keySet()) {
            if (!category.equalsIgnoreCase(ProgramState.currentCategory))
                probabilities.put(category, ProgramState.fitsInCategory(words, category));
        }

        List<String> categories = ProgramState.categories.keySet().stream()
                .filter(ctgy -> !ctgy.equalsIgnoreCase(ProgramState.currentCategory))
                .sorted(Comparator.<String, Float>comparing(probabilities::get).reversed())
                .collect(Collectors.toList());

        for (String category : categories) {
            JButton button = new JButton(String.format("%s (%.2f%%)", category, Float.isFinite(probabilities.get(category)) ? probabilities.get(category) * 100 : 0));
            button.addActionListener(e -> {
                UnifiedDiff thisCategory = ProgramState.categories.get(ProgramState.currentCategory);
                UnifiedDiff newCategory = ProgramState.categories.get(category);
                if (ProgramState.selectedHunk == -1) {
                    UnifiedDiffFile file = thisCategory.getFiles().remove(ProgramState.selectedFile);
                    UnifiedDiffFile newFile = null;
                    for (UnifiedDiffFile f : newCategory.getFiles()) {
                        if (Objects.equals(f.getDiffCommand(), file.getDiffCommand()) && Objects.equals(f.getFromFile(), file.getFromFile()) && Objects.equals(f.getToFile(), file.getToFile())) {
                            newFile = f;
                            break;
                        }
                    }
                    if (newFile == null)
                        newCategory.getFiles().add(file);
                    else {
                        for (AbstractDelta<String> delta : file.getPatch().getDeltas())
                            newFile.getPatch().addDelta(delta);
                    }
                } else {
                    int hunksSoFar = 0;
                    for (int i = 0; i < ProgramState.selectedFile; i++)
                        hunksSoFar += thisCategory.getFiles().get(i).getPatch().getDeltas().size();
                    UnifiedDiffFile file = thisCategory.getFiles().get(ProgramState.selectedFile);
                    AbstractDelta<String> delta = file.getPatch().getDeltas().remove(ProgramState.selectedHunk - hunksSoFar);
                    if (file.getPatch().getDeltas().isEmpty())
                        thisCategory.getFiles().remove(ProgramState.selectedFile);
                    UnifiedDiffFile newFile = null;
                    for (UnifiedDiffFile f : newCategory.getFiles()) {
                        if (Objects.equals(f.getDiffCommand(), file.getDiffCommand()) && Objects.equals(f.getFromFile(), file.getFromFile()) && Objects.equals(f.getToFile(), file.getToFile())) {
                            newFile = f;
                            break;
                        }
                    }
                    if (newFile == null) {
                        newFile = new UnifiedDiffFile();
                        newFile.setDiffCommand(file.getDiffCommand());
                        newFile.setFromFile(file.getFromFile());
                        newFile.setToFile(file.getToFile());
                        newFile.setIndex(file.getIndex());
                        newCategory.getFiles().add(newFile);
                    }
                    newFile.getPatch().addDelta(delta);
                }

                refresh(frame);
            });
            addToPanel.add(button);
        }

        addToPanel.revalidate();
    }

    private Object updateSelection0(Object highlightRef, JEditorPane editorPane,
                                    List<Integer> filePositions, List<ProgramState.HunkPos> hunkPositions) {
        if (highlightRef != null)
            editorPane.getHighlighter().removeHighlight(highlightRef);
        int highlightStart, highlightEnd;
        if (ProgramState.selectedHunk != -1 && ProgramState.selectedHunk < hunkPositions.size()) {
            ProgramState.HunkPos pos = hunkPositions.get(ProgramState.selectedHunk);
            highlightStart = pos.start;
            highlightEnd = pos.end;
        } else if (ProgramState.selectedFile < filePositions.size()) {
            highlightStart = filePositions.get(ProgramState.selectedFile);
            highlightEnd = ProgramState.selectedFile == filePositions.size() - 1 ? editorPane.getText().length() : filePositions.get(ProgramState.selectedFile + 1);
        } else {
            highlightStart = highlightEnd = 0;
        }
        try {
            return editorPane.getHighlighter().addHighlight(highlightStart, highlightEnd, new DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW));
        } catch (BadLocationException e) {
            e.printStackTrace();
            return null;
        }
    }
}
