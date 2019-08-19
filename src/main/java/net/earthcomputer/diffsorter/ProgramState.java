package net.earthcomputer.diffsorter;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.text.DiffRowGenerator;
import com.github.difflib.unifieddiff.UnifiedDiff;
import com.github.difflib.unifieddiff.UnifiedDiffFile;
import com.github.difflib.unifieddiff.UnifiedDiffReader;
import com.github.difflib.unifieddiff.UnifiedDiffWriter;
import org.ejml.data.FMatrixRMaj;
import org.ejml.dense.row.CommonOps_FDRM;

import javax.swing.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProgramState {

    public static final Pattern WORD_PATTERN = Pattern.compile("\\w+");

    public static final String BEGINOLD = "`BEGINOLD`";
    public static final String ENDOLD = "`ENDOLD`";
    public static final String BEGINNEW = "`BEGINNEW`";
    public static final String ENDNEW = "`ENDNEW`";
    public static final DiffRowGenerator DIFF_ROW_GENERATOR = DiffRowGenerator.create()
            .inlineDiffByWord(true)
            .showInlineDiffs(true)
            .oldTag(f -> f ? BEGINOLD : ENDOLD)
            .newTag(f -> f ? BEGINNEW : ENDNEW)
            .build();

    public static File saveDir;
    public static JFrame frame;
    public static DiffSorter ui;
    public static String currentCategory;
    public static List<HunkPos> leftDiffHunkPositions = new ArrayList<>();
    public static List<Integer> leftDiffFilePositions = new ArrayList<>();
    public static List<HunkPos> rightDiffHunkPositions = new ArrayList<>();
    public static List<Integer> rightDiffFilePositions = new ArrayList<>();
    public static int selectedFile;
    public static int selectedHunk = -1;
    public static Map<String, UnifiedDiff> categories = new HashMap<>();

    private static List<List<String>> features = new ArrayList<>();
    private static Map<String, FMatrixRMaj> models = new HashMap<>();

    public static void load(JFrame frame, File directory) throws IOException {
        Map<String, UnifiedDiff> diffs = new HashMap<>();

        File[] subFiles = directory.listFiles();
        if (subFiles != null) {
            for (File file : subFiles) {
                if (file.getName().endsWith(".diff")) {
                    String name = file.getName().substring(0, file.getName().length() - 5);
                    UnifiedDiff diff = UnifiedDiffReader.parseUnifiedDiff(new FileInputStream(file));
                    diffs.put(name, diff);
                }
            }
        }

        if (!diffs.containsKey("unsorted")) {
            JOptionPane.showMessageDialog(frame, "No \"unsorted.diff\" file found", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        ProgramState.categories = diffs;
        ProgramState.currentCategory = "unsorted";

        ui.refresh(frame);
    }

    public static void save(File directory) throws IOException {
        File[] subFiles = directory.listFiles();
        subFiles = subFiles == null ? new File[0] : Arrays.stream(subFiles).filter(file -> file.getName().endsWith(".diff")).toArray(File[]::new);

        List<File> swapFiles = new ArrayList<>();
        for (File file : subFiles) {
            File swapFile = new File(file.getParentFile(), "~" + file.getName() + ".swp");
            try {
                Files.move(file.toPath(), swapFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                swapFiles.add(swapFile);
            } catch (IOException ignore) {
                Files.delete(file.toPath());
            }
        }

        for (Map.Entry<String, UnifiedDiff> category : categories.entrySet()) {
            FileWriter writer = new FileWriter(new File(directory, category.getKey() + ".diff"));
            UnifiedDiffWriter.write(category.getValue(), null, writer, 0);
            writer.flush();
            writer.close();
        }

        for (File swapFile : swapFiles)
            Files.delete(swapFile.toPath());
    }

    public static List<Map<String, Integer>> extractDataExamples(String category) {
        List<Map<String, Integer>> examples = new ArrayList<>();

        UnifiedDiff diff = categories.get(category);
        for (UnifiedDiffFile file : diff.getFiles()) {
            for (AbstractDelta<String> delta : file.getPatch().getDeltas()) {
                Map<String, Integer> example = new HashMap<>();
                for (String line : delta.getSource().getLines())
                    extractWordsFromString(line, example);
                examples.add(example);
            }
        }

        return examples;
    }

    public static void extractWords(String category, int file, int hunk, Map<String, Integer> words) {
        UnifiedDiff diff = categories.get(category);

        if (hunk == -1) {
            UnifiedDiffFile diffFile = diff.getFiles().get(file);
            for (AbstractDelta<String> delta : diffFile.getPatch().getDeltas()) {
                for (String line : delta.getSource().getLines())
                    extractWordsFromString(line, words);
            }
        } else {
            int hunksSoFar = 0;
            for (int i = 0; i < file; i++)
                hunksSoFar += diff.getFiles().get(i).getPatch().getDeltas().size();
            for (String line : diff.getFiles().get(file).getPatch().getDeltas().get(hunk - hunksSoFar).getSource().getLines())
                extractWordsFromString(line, words);
        }
    }

    private static void extractWordsFromString(String line, Map<String, Integer> words) {
        Matcher matcher = WORD_PATTERN.matcher(line);
        while (matcher.find()) {
            String word = matcher.group();
            words.merge(word, 1, Integer::sum);
        }
    }

    public static void createModel() {
        features.clear();
        models.clear();

        Map<String, List<Map<String, Integer>>> allData = new HashMap<>();
        for (String category : categories.keySet()) {
            if (!category.equalsIgnoreCase(currentCategory)) {
                allData.put(category, extractDataExamples(category));
            }
        }
        List<String> categoryList = allData.keySet().stream().sorted().collect(Collectors.toList());
        if (categoryList.isEmpty())
            return;

        Map<String, Integer> wordFrequency = allData.values().stream()
                .flatMap(List::stream)
                .collect(HashMap::new,
                        (mapA, mapB) -> mapB.forEach((word, freq) -> mapA.merge(word, freq, Integer::sum)),
                        (mapA, mapB) -> mapB.forEach((word, freq) -> mapA.merge(word, freq, Integer::sum)));

        List<String> commonWords = wordFrequency.keySet().stream()
                .sorted(Comparator.<String, Integer>comparing(wordFrequency::get).reversed())
                .limit(Math.min(20 * categories.size(), (int) (0.9 * allData.values().stream().mapToInt(List::size).sum())))
                .collect(Collectors.toList());
        // search for linearly dependent pairs
        for (int wordA = 0; wordA < commonWords.size(); wordA++) {
            float firstVal = allData.get(categoryList.get(0)).stream().filter(it -> !it.isEmpty()).findFirst().orElse(Collections.emptyMap()).getOrDefault(commonWords.get(wordA), 0);
            boolean allSameA = true;
            float lengthA = 0;
            for (String ctgy : categoryList) {
                for (Map<String, Integer> example : allData.get(ctgy)) {
                    float val = example.getOrDefault(commonWords.get(wordA), 0);
                    if (Math.abs(val - firstVal) > 0.001)
                        allSameA = false;
                    lengthA += val * val;
                }
            }
            if (allSameA) {
                commonWords.remove(wordA--);
                continue;
            }
            lengthA = (float)Math.sqrt(lengthA);

            int wordB;
            innerWordLoop:
            for (wordB = 0; wordB < wordA; wordB++) {
                float lengthB = 0;
                for (String ctgy : categoryList) {
                    for (Map<String, Integer> example : allData.get(ctgy)) {
                        float val = example.getOrDefault(commonWords.get(wordB), 0);
                        lengthB += val * val;
                    }
                }
                lengthB = (float)Math.sqrt(lengthB);

                for (String ctgy : categoryList) {
                    for (Map<String, Integer> example : allData.get(ctgy)) {
                        if (Math.abs(example.getOrDefault(commonWords.get(wordA), 0) / lengthA - example.getOrDefault(commonWords.get(wordB), 0) / lengthB) > 0.001) {
                            continue innerWordLoop;
                        }
                    }
                }

                break;
            }

            if (wordB == wordA) { // no equivalent found
                ArrayList<String> feature = new ArrayList<>(1);
                feature.add(commonWords.get(wordA));
                features.add(feature);
            } else {
                features.get(wordB).add(commonWords.remove(wordA--));
            }
        }

        if (features.isEmpty())
            return;

        FMatrixRMaj y = new FMatrixRMaj(allData.values().stream().mapToInt(List::size).sum(), 1);
        FMatrixRMaj X = new FMatrixRMaj(y.numRows, features.size() + 1);
        int index = 0, prevIndex;
        for (String category : categoryList) {
            List<Map<String, Integer>> examples = allData.get(category);
            for (int i = 0; i < examples.size(); i++) {
                Map<String, Integer> example = examples.get(i);
                for (int j = 0; j < features.size(); j++) {
                    int count = features.get(j).stream().mapToInt(word -> example.getOrDefault(word, 0)).sum();
                    X.set(index + i, j, count);
                }
                X.set(index + i, features.size(), 1);
            }
            index += examples.size();
        }

        FMatrixRMaj XTXinv = new FMatrixRMaj(features.size() + 1, features.size() + 1);
        CommonOps_FDRM.multTransA(X, X, XTXinv);
        CommonOps_FDRM.invert(XTXinv);
        FMatrixRMaj XTXinvXT = new FMatrixRMaj(features.size() + 1, y.numRows);
        CommonOps_FDRM.multTransB(XTXinv, X, XTXinvXT);
        //noinspection UnusedAssignment
        XTXinv = null;

        index = 0;
        prevIndex = 0;
        for (String category : categoryList) {
            List<Map<String, Integer>> examples = allData.get(category);
            Arrays.fill(y.data, prevIndex, index, 0);
            prevIndex = index;
            index += examples.size();
            Arrays.fill(y.data, prevIndex, index, 1);

            FMatrixRMaj beta = new FMatrixRMaj(features.size() + 1, 1);
            CommonOps_FDRM.mult(XTXinvXT, y, beta);
            models.put(category, beta);
        }
    }

    // Returns the probability that the given set of words fits in the given category
    public static float fitsInCategory(Map<String, Integer> words, String category) {
        FMatrixRMaj model = models.get(category);
        if (model == null)
            return 0.5f;

        FMatrixRMaj x = new FMatrixRMaj(features.size() + 1, 1);
        for (int i = 0; i < features.size(); i++) {
            int count = features.get(i).stream().mapToInt(word -> words.getOrDefault(word, 0)).sum();
            x.set(i, 0, count);
        }
        x.set(features.size(), 0, 1);

        FMatrixRMaj result = new FMatrixRMaj(1, 1);
        CommonOps_FDRM.multTransA(model, x, result);

        float y = result.get(0, 0);
        if (!Float.isFinite(y)) y = 0;
        return (y / (1 + Math.abs(y)) + 1) * 0.5f;
    }

    public static class HunkPos {
        public final int start;
        public final int end;
        public final int file;

        public HunkPos(int start, int end, int file) {
            this.start = start;
            this.end = end;
            this.file = file;
        }
    }

}
