package dev.richtext.codespace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class App extends Application {

    private static final String[] KEYWORDS = new String[] {
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void", "volatile",
            "while", "record", "sealed", "permits", "var", "yield"
    };

    private static final String KEYWORD_PATTERN = "\\b(" + String.join("|", KEYWORDS) + ")\\b";
    private static final String PAREN_PATTERN = "\\(|\\)";
    private static final String BRACE_PATTERN = "\\{|\\}";
    private static final String BRACKET_PATTERN = "\\[|\\]";
    private static final String SEMICOLON_PATTERN = "\\;";
    private static final String STRING_PATTERN = "\"([^\"\\\\]|\\\\.)*\"";
    private static final String COMMENT_PATTERN = "//[^\\n]*" + "|/\\*(.|\\R)*?\\*/";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<KEYWORD>" + KEYWORD_PATTERN + ")"
                    + "|(?<PAREN>" + PAREN_PATTERN + ")"
                    + "|(?<BRACE>" + BRACE_PATTERN + ")"
                    + "|(?<BRACKET>" + BRACKET_PATTERN + ")"
                    + "|(?<SEMICOLON>" + SEMICOLON_PATTERN + ")"
                    + "|(?<STRING>" + STRING_PATTERN + ")"
                    + "|(?<COMMENT>" + COMMENT_PATTERN + ")"
    );

    private CodeArea codeArea;
    private Label statusLabel;
    private Label fileLabel;
    private Label cursorLabel;
    private Label colorHelperLabel;
    private Region colorSwatch;
    private TextArea terminalOutput;
    private TextField terminalInput;
    private TreeView<Path> projectTree;
    private Stage stage;
    private Path currentFile;
    private Path projectRoot;
    private boolean modified;
    private Subscription syntaxSubscription;

    @Override
    public void start(Stage stage) {
        this.stage = stage;

        codeArea = createEditor();
        terminalOutput = createTerminalOutput();
        terminalInput = createTerminalInput();
        projectTree = createProjectTree();
        statusLabel = new Label("Ready");
        fileLabel = new Label("Untitled");
        cursorLabel = new Label("Ln 1, Col 1");
        colorHelperLabel = new Label("No color under caret");
        colorSwatch = new Region();
        colorSwatch.getStyleClass().add("color-swatch");

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-shell");
        root.setTop(new VBox(createMenuBar(), createToolBar()));
        root.setCenter(createWorkspace());
        root.setBottom(createStatusBar());

        Scene scene = new Scene(root, 1200, 760);
        URL stylesheet = getClass().getResource("/editor.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        }

        stage.setTitle("RichText CodeSpace");
        stage.setScene(scene);
        stage.show();

        stage.setOnCloseRequest(event -> {
            if (!confirmDiscardChanges()) {
                event.consume();
                return;
            }
            if (syntaxSubscription != null) {
                syntaxSubscription.unsubscribe();
            }
        });
    }

    private CodeArea createEditor() {
        CodeArea editor = new CodeArea();
        editor.setParagraphGraphicFactory(LineNumberFactory.get(editor));
        editor.replaceText(sampleCode());
        editor.textProperty().addListener((ignore, oldText, newText) -> {
            modified = true;
            updateTitle();
            updateEditorHelpers();
        });
        editor.caretPositionProperty().addListener((ignore, oldPosition, newPosition) -> updateEditorHelpers());

        syntaxSubscription = editor
                .multiPlainChanges()
                .successionEnds(Duration.ofMillis(40))
                .subscribe(ignore -> editor.setStyleSpans(0, computeHighlighting(editor.getText())));

        editor.setStyleSpans(0, computeHighlighting(editor.getText()));
        Platform.runLater(this::updateEditorHelpers);
        return editor;
    }

    private MenuBar createMenuBar() {
        Menu file = new Menu("File");
        MenuItem openFile = menuItem("Open File...", KeyCode.O, this::openFile);
        MenuItem openFolder = menuItem("Open Folder...", KeyCode.K, this::openFolder);
        MenuItem save = menuItem("Save", KeyCode.S, this::saveFile);
        MenuItem saveAs = new MenuItem("Save As...");
        saveAs.setOnAction(event -> saveFileAs());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(event -> stage.fireEvent(new javafx.stage.WindowEvent(stage, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));
        file.getItems().addAll(openFile, openFolder, new SeparatorMenuItem(), save, saveAs, new SeparatorMenuItem(), exit);

        Menu run = new Menu("Run");
        MenuItem runMaven = new MenuItem("Run Maven JavaFX");
        runMaven.setOnAction(event -> runTerminalCommand("mvn javafx:run"));
        MenuItem compile = new MenuItem("Compile");
        compile.setOnAction(event -> runTerminalCommand("mvn compile"));
        run.getItems().addAll(runMaven, compile);

        Menu view = new Menu("View");
        MenuItem clearTerminal = new MenuItem("Clear Terminal");
        clearTerminal.setOnAction(event -> terminalOutput.clear());
        view.getItems().add(clearTerminal);

        return new MenuBar(file, run, view);
    }

    private MenuItem menuItem(String text, KeyCode key, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setAccelerator(new KeyCodeCombination(key, KeyCombination.CONTROL_DOWN));
        item.setOnAction(event -> action.run());
        return item;
    }

    private ToolBar createToolBar() {
        Button openFile = toolbarButton("Open", this::openFile);
        Button openFolder = toolbarButton("Folder", this::openFolder);
        Button save = toolbarButton("Save", this::saveFile);
        Button compile = toolbarButton("Compile", () -> runTerminalCommand("mvn compile"));
        Button run = toolbarButton("Run", () -> runTerminalCommand("mvn javafx:run"));

        ToolBar toolbar = new ToolBar(openFile, openFolder, save, compile, run);
        toolbar.getStyleClass().add("main-toolbar");
        return toolbar;
    }

    private Button toolbarButton(String text, Runnable action) {
        Button button = new Button(text);
        button.setOnAction(event -> action.run());
        return button;
    }

    private SplitPane createWorkspace() {
        BorderPane explorerPane = new BorderPane(projectTree);
        explorerPane.getStyleClass().add("project-pane");
        Label explorerTitle = new Label("Project");
        explorerTitle.getStyleClass().add("pane-title");
        explorerPane.setTop(explorerTitle);

        BorderPane editorPane = new BorderPane(new VirtualizedScrollPane<>(codeArea));
        editorPane.getStyleClass().add("editor-pane");
        editorPane.setTop(createEditorHeader());

        SplitPane vertical = new SplitPane(editorPane, createBottomPanel());
        vertical.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vertical.setDividerPositions(0.72);

        SplitPane workspace = new SplitPane(explorerPane, vertical);
        workspace.setDividerPositions(0.23);
        return workspace;
    }

    private HBox createEditorHeader() {
        HBox header = new HBox(fileLabel);
        header.getStyleClass().add("editor-header");
        HBox.setHgrow(fileLabel, Priority.ALWAYS);
        return header;
    }

    private TabPane createBottomPanel() {
        Tab terminalTab = new Tab("Terminal");
        terminalTab.setClosable(false);
        terminalTab.setContent(createTerminalPane());

        Tab problemsTab = new Tab("Problems");
        problemsTab.setClosable(false);
        Label emptyProblems = new Label("No diagnostics yet. Compile to surface build output in the terminal.");
        emptyProblems.getStyleClass().add("empty-state");
        problemsTab.setContent(new BorderPane(emptyProblems));

        TabPane tabs = new TabPane(terminalTab, problemsTab);
        tabs.getStyleClass().add("bottom-tabs");
        return tabs;
    }

    private BorderPane createTerminalPane() {
        Button run = new Button("Run");
        run.setOnAction(event -> runTerminalCommand(terminalInput.getText()));

        HBox commandRow = new HBox(8, new Label(">"), terminalInput, run);
        commandRow.setPadding(new Insets(8));
        HBox.setHgrow(terminalInput, Priority.ALWAYS);
        commandRow.getStyleClass().add("terminal-command-row");

        BorderPane pane = new BorderPane(terminalOutput);
        pane.setBottom(commandRow);
        pane.getStyleClass().add("terminal-pane");
        return pane;
    }

    private TextArea createTerminalOutput() {
        TextArea output = new TextArea();
        output.setEditable(false);
        output.setWrapText(true);
        output.setText("Terminal ready. Commands run from the opened folder, or from the app working directory.\n");
        output.getStyleClass().add("terminal-output");
        return output;
    }

    private TextField createTerminalInput() {
        TextField input = new TextField();
        input.setPromptText("mvn compile");
        input.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                runTerminalCommand(input.getText());
            }
        });
        return input;
    }

    private TreeView<Path> createProjectTree() {
        TreeView<Path> tree = new TreeView<>();
        tree.setShowRoot(true);
        tree.setRoot(new TreeItem<>(Path.of("Open a folder")));
        tree.setCellFactory(ignore -> new TreeCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getFileName() == null ? item.toString() : item.getFileName().toString());
            }
        });
        tree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<Path> item = tree.getSelectionModel().getSelectedItem();
                if (item != null && Files.isRegularFile(item.getValue())) {
                    openPath(item.getValue());
                }
            }
        });
        return tree;
    }

    private HBox createStatusBar() {
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox colorHelper = new HBox(8, colorSwatch, colorHelperLabel);
        colorHelper.getStyleClass().add("color-helper");

        HBox statusBar = new HBox(16, statusLabel, spacer, colorHelper, cursorLabel);
        statusBar.getStyleClass().add("status-bar");
        return statusBar;
    }

    private void openFile() {
        if (!confirmDiscardChanges()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open File");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Code Files", "*.java", "*.txt", "*.md", "*.xml", "*.css", "*.js", "*.json"),
                new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        java.io.File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            openPath(selected.toPath());
        }
    }

    private void openFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Project Folder");
        java.io.File selected = chooser.showDialog(stage);
        if (selected == null) {
            return;
        }
        projectRoot = selected.toPath();
        projectTree.setRoot(buildTree(projectRoot, 0));
        projectTree.getRoot().setExpanded(true);
        status("Opened project " + projectRoot);
    }

    private TreeItem<Path> buildTree(Path path, int depth) {
        TreeItem<Path> item = new TreeItem<>(path);
        if (Files.isDirectory(path) && depth < 4) {
            try (Stream<Path> children = Files.list(path)) {
                children
                        .filter(child -> !isHiddenBuildPath(child))
                        .sorted(Comparator
                                .comparing((Path child) -> !Files.isDirectory(child))
                                .thenComparing(child -> child.getFileName().toString().toLowerCase()))
                        .forEach(child -> item.getChildren().add(buildTree(child, depth + 1)));
            } catch (IOException ex) {
                status("Could not read " + path + ": " + ex.getMessage());
            }
        }
        return item;
    }

    private boolean isHiddenBuildPath(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return name.equals(".git") || name.equals("target") || name.equals("bin") || name.equals(".idea");
    }

    private void openPath(Path path) {
        if (!confirmDiscardChanges()) {
            return;
        }
        try {
            codeArea.replaceText(Files.readString(path, StandardCharsets.UTF_8));
            currentFile = path;
            modified = false;
            fileLabel.setText(path.getFileName().toString());
            status("Opened " + path);
            updateTitle();
        } catch (IOException ex) {
            showError("Open failed", ex.getMessage());
        }
    }

    private void saveFile() {
        if (currentFile == null) {
            saveFileAs();
            return;
        }
        writeCurrentFile(currentFile);
    }

    private void saveFileAs() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save File As");
        if (currentFile != null) {
            chooser.setInitialFileName(currentFile.getFileName().toString());
        }
        java.io.File selected = chooser.showSaveDialog(stage);
        if (selected != null) {
            writeCurrentFile(selected.toPath());
        }
    }

    private void writeCurrentFile(Path path) {
        try {
            Files.writeString(path, codeArea.getText(), StandardCharsets.UTF_8);
            currentFile = path;
            modified = false;
            fileLabel.setText(path.getFileName().toString());
            status("Saved " + path);
            updateTitle();
        } catch (IOException ex) {
            showError("Save failed", ex.getMessage());
        }
    }

    private boolean confirmDiscardChanges() {
        if (!modified) {
            return true;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved changes");
        alert.setHeaderText("Discard unsaved changes?");
        alert.setContentText("Save before switching files if you want to keep your edits.");
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void runTerminalCommand(String rawCommand) {
        String command = rawCommand == null ? "" : rawCommand.trim();
        if (command.isEmpty()) {
            return;
        }

        terminalInput.setText(command);
        appendTerminal("\n> " + command + "\n");
        Path workingDirectory = projectRoot != null ? projectRoot : Path.of(System.getProperty("user.dir"));

        Thread worker = new Thread(() -> {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(command));
            builder.directory(workingDirectory.toFile());
            builder.redirectErrorStream(true);

            try {
                Process process = builder.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        appendTerminal(line + "\n");
                    }
                }
                int exitCode = process.waitFor();
                appendTerminal("[exit " + exitCode + "]\n");
                Platform.runLater(() -> status("Command finished with exit code " + exitCode));
            } catch (IOException ex) {
                appendTerminal("Command failed: " + ex.getMessage() + "\n");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                appendTerminal("Command interrupted.\n");
            }
        }, "codespace-terminal");
        worker.setDaemon(true);
        worker.start();
        status("Running command in " + workingDirectory);
    }

    private String[] shellCommand(String command) {
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            return new String[] { "cmd.exe", "/c", command };
        }
        return new String[] { "sh", "-c", command };
    }

    private void appendTerminal(String text) {
        Platform.runLater(() -> terminalOutput.appendText(text));
    }

    private void updateEditorHelpers() {
        if (codeArea == null || cursorLabel == null || colorHelperLabel == null || colorSwatch == null) {
            return;
        }

        int caret = Math.min(codeArea.getCaretPosition(), codeArea.getLength());
        String beforeCaret = codeArea.getText(0, caret);
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < beforeCaret.length(); i++) {
            if (beforeCaret.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        cursorLabel.setText("Ln " + line + ", Col " + (caret - lineStart + 1));

        String token = tokenBeforeCaret(beforeCaret);
        if (token.startsWith("#") && token.length() > 1) {
            updateColorPreview(token);
        } else {
            colorHelperLabel.setText("No color under caret");
            colorSwatch.setStyle("");
        }
    }

    private String tokenBeforeCaret(String beforeCaret) {
        int start = beforeCaret.length();
        while (start > 0) {
            char ch = beforeCaret.charAt(start - 1);
            if (Character.isWhitespace(ch) || ch == ';' || ch == ':' || ch == ',' || ch == ')' || ch == '(' || ch == '"' || ch == '\'') {
                break;
            }
            start--;
        }
        return beforeCaret.substring(start);
    }

    private void updateColorPreview(String token) {
        String color = token.replaceAll("[^#0-9a-fA-F]", "");
        if (color.matches("#([0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")) {
            colorHelperLabel.setText("CSS color " + color);
            colorSwatch.setStyle("-fx-background-color: " + color + ";");
            return;
        }

        colorHelperLabel.setText("Typing color " + token);
        colorSwatch.setStyle("");
    }

    private void status(String text) {
        statusLabel.setText(text);
    }

    private void updateTitle() {
        String name = currentFile == null ? "Untitled" : currentFile.getFileName().toString();
        stage.setTitle((modified ? "* " : "") + name + " - RichText CodeSpace");
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastKeywordEnd = 0;
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

        while (matcher.find()) {
            String styleClass = matcher.group("KEYWORD") != null ? "keyword"
                    : matcher.group("PAREN") != null ? "paren"
                    : matcher.group("BRACE") != null ? "brace"
                    : matcher.group("BRACKET") != null ? "bracket"
                    : matcher.group("SEMICOLON") != null ? "semicolon"
                    : matcher.group("STRING") != null ? "string"
                    : matcher.group("COMMENT") != null ? "comment"
                    : null;

            if (styleClass == null) {
                continue;
            }

            spansBuilder.add(Collections.emptyList(), matcher.start() - lastKeywordEnd);
            spansBuilder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastKeywordEnd = matcher.end();
        }

        spansBuilder.add(Collections.emptyList(), text.length() - lastKeywordEnd);
        return spansBuilder.create();
    }

    private String sampleCode() {
        return "package demo;\n\n"
                + "public class Hello {\n"
                + "    public static void main(String[] args) {\n"
                + "        // Token coloring, project explorer, menu actions, and terminal\n"
                + "        String msg = \"Hello, RichText CodeSpace!\";\n"
                + "        if (msg != null) {\n"
                + "            System.out.println(msg);\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
    }

    public static void main(String[] args) {
        launch(args);
    }
}
