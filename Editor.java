
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import javax.swing.*;

public class Editor {

    public static final class AppClipboard {

        private AppClipboard() {
        }

        private static Text.StyledFragment content = null;
        private static int pasteCount = 0;

        public static void set(Text.StyledFragment frag) {
            content = frag;
        }

        public static Text.StyledFragment get() {
            return content;
        }

        public static boolean hasContent() {
            return content != null && content.text != null && !content.text.isEmpty();
        }

        // Increment on each paste (debugging)
        public static int incPasteCount() {
            return ++pasteCount;
        }

        public static int getPasteCount() {
            return pasteCount;
        }
    }

    // =========================
    // Window wrapper
    // =========================
    private static final class EditorWindow {

        JFrame frame;
        Text model;
        Viewer viewer;
        JScrollBar scrollBar;
        String path; // null => untitled
    }

    public static void main(String[] arg) {
        SwingUtilities.invokeLater(() -> {
            if (arg.length >= 1) {
                String path = arg[0];
                // keep your original validation, but don’t exit the app entirely if invalid
                if (!isReadableFile(path)) {
                    System.out.println("-- file " + path + " not readable/found");
                    createEditorWindow(null); // start with untitled instead
                } else {
                    createEditorWindow(path);
                }
            } else {
                createEditorWindow(null); // untitled
            }
        });
    }

    private static boolean isReadableFile(String path) {
        if (path == null) {
            return false;
        }
        try {
            FileInputStream s = new FileInputStream(path);
            s.close();
            return true;
        } catch (FileNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // =========================
    // Create one full editor window
    // =========================
    private static EditorWindow createEditorWindow(String path) {
        EditorWindow w = new EditorWindow();
        w.path = path;

        w.scrollBar = new JScrollBar(Adjustable.VERTICAL, 0, 0, 0, 0);
        w.model = new Text(path == null ? "" : path);  // "" => empty if untitled
        w.viewer = new Viewer(w.model, w.scrollBar);

        // ---- Main content panel (viewer + scrollbar) ----
        JPanel content = new JPanel(new BorderLayout());
        content.add(w.viewer, BorderLayout.CENTER);
        content.add(w.scrollBar, BorderLayout.EAST);

        // ---- Toolbar (navbar) ----
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        // ---------- Font ----------
        bar.add(new JLabel("Font: "));
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        JComboBox<String> fontBox = new JComboBox<>(fonts);
        fontBox.setMaximumSize(new Dimension(220, 28));
        fontBox.setSelectedItem("Monospaced");
        fontBox.addActionListener(e -> {
            Object sel = fontBox.getSelectedItem();
            if (sel != null) {
                w.viewer.setFontFamily(sel.toString());
            }
            w.viewer.requestFocus();
        });
        bar.add(fontBox);

        bar.addSeparator(new Dimension(12, 0));

        // ---------- Size ----------
        bar.add(new JLabel("Size: "));
        Integer[] sizes = {10, 12, 14, 16, 18, 20, 24, 28, 32, 36, 48, 60, 72};
        JComboBox<Integer> sizeBox = new JComboBox<>(sizes);
        sizeBox.setMaximumSize(new Dimension(80, 28));
        sizeBox.setSelectedItem(18);
        sizeBox.addActionListener(e -> {
            Integer sz = (Integer) sizeBox.getSelectedItem();
            if (sz != null) {
                w.viewer.setFontSize(sz);
            }
            w.viewer.requestFocus();
        });
        bar.add(sizeBox);

        bar.addSeparator(new Dimension(16, 0));

        // ---------- Style ----------
        bar.add(new JLabel("Style: "));

        JToggleButton boldBtn = new JToggleButton("B");
        boldBtn.setFont(boldBtn.getFont().deriveFont(Font.BOLD));
        boldBtn.setFocusable(false);
        boldBtn.addActionListener(e -> {
            w.viewer.toggleBold();
            w.viewer.requestFocus();
        });
        bar.add(boldBtn);

        JToggleButton italicBtn = new JToggleButton("I");
        italicBtn.setFont(italicBtn.getFont().deriveFont(Font.ITALIC));
        italicBtn.setFocusable(false);
        italicBtn.addActionListener(e -> {
            w.viewer.toggleItalic();
            w.viewer.requestFocus();
        });
        bar.add(italicBtn);

        JToggleButton underlineBtn = new JToggleButton("U");
        underlineBtn.setFont(underlineBtn.getFont().deriveFont(Font.PLAIN));
        underlineBtn.setFocusable(false);
        underlineBtn.addActionListener(e -> {
            w.viewer.toggleUnderline();
            w.viewer.requestFocus();
        });
        bar.add(underlineBtn);

        JToggleButton strikeBtn = new JToggleButton("S");
        strikeBtn.setFont(strikeBtn.getFont().deriveFont(Font.PLAIN));
        strikeBtn.setFocusable(false);
        strikeBtn.addActionListener(e -> {
            w.viewer.toggleStrike();
            w.viewer.requestFocus();
        });
        bar.add(strikeBtn);

        bar.addSeparator(new Dimension(16, 0));

        // ---------- Color ----------
        bar.add(new JLabel("Color: "));
        JButton colorBtn = new JButton("Pick…");
        colorBtn.setFocusable(false);
        colorBtn.addActionListener(e -> {
            w.viewer.chooseColor();
            w.viewer.requestFocus();
        });
        bar.add(colorBtn);

        bar.addSeparator(new Dimension(16, 0));

        // ---------- Clipboard toolbar ----------
        JToolBar bar2 = new JToolBar();
        bar2.setFloatable(false);

        bar2.add(new JLabel("Clipboard: "));

        JButton cutBtn = new JButton("Cut");
        cutBtn.setFocusable(false);
        cutBtn.addActionListener(e -> {
            w.viewer.cutSelectionToClipboardFromMenu();
            w.viewer.requestFocus();
        });
        bar2.add(cutBtn);

        JButton copyBtn = new JButton("Copy");
        copyBtn.setFocusable(false);
        copyBtn.addActionListener(e -> {
            w.viewer.copySelectionToClipboardFromMenu();
            w.viewer.requestFocus();
        });
        bar2.add(copyBtn);

        JButton pasteBtn = new JButton("Paste");
        pasteBtn.setFocusable(false);
        pasteBtn.addActionListener(e -> {
            w.viewer.pasteFromClipboardFromMenu();
            w.viewer.requestFocus();
        });
        bar2.add(pasteBtn);

        bar2.addSeparator(new Dimension(16, 0));
        bar2.add(new JLabel("Find: "));

        JTextField findField = new JTextField(18);
        findField.setMaximumSize(new Dimension(220, 28)); // keep toolbar height nice
        bar2.add(findField);

        JButton findBtn = new JButton("Find");
        findBtn.setFocusable(false);
        findBtn.addActionListener(e -> {
            w.viewer.findNext(findField.getText());
            w.viewer.requestFocus();
        });
        bar2.add(findBtn);

        JButton nextBtn = new JButton("Next");
        nextBtn.setFocusable(false);
        nextBtn.addActionListener(e -> {
            w.viewer.findNext(findField.getText());
            w.viewer.requestFocus();
        });
        bar2.add(nextBtn);

// Press Enter in the field = Find Next
        findField.addActionListener(e -> {
            w.viewer.findNext(findField.getText());
            w.viewer.requestFocus();
        });

        // ---- Frame ----
        String title = (w.path == null) ? "Untitled" : w.path;
        w.frame = new JFrame(title);

        // MENU BAR (File -> Open/Save/Save As)
        w.frame.setJMenuBar(buildMenuBar(w));

        // Close behavior: try save if we have a path; otherwise just close.
        w.frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        w.frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (w.path != null) {
                    try {
                        w.model.saveWithMeta(w.path);
                    } catch (Exception ex) {
                        System.out.println("-- failed to save " + w.path + ": " + ex.getMessage());
                    }
                }
            }
        });

        w.frame.setSize(700, 800);
        w.frame.setResizable(true);

        JPanel root = new JPanel(new BorderLayout());

        JPanel bars = new JPanel();
        bars.setLayout(new BoxLayout(bars, BoxLayout.Y_AXIS));
        bars.add(bar);
        bars.add(bar2);

        root.add(bars, BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);

        w.frame.setContentPane(root);
        w.frame.setVisible(true);
        w.viewer.requestFocus();

        return w;
    }

    private static JMenuBar buildMenuBar(EditorWindow w) {
        JMenuBar mb = new JMenuBar();
        JMenu file = new JMenu("File");

        JMenuItem open = new JMenuItem("Open...");
        open.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK));
        open.addActionListener(e -> doOpenNewWindow(w.frame));
        file.add(open);

        JMenuItem save = new JMenuItem("Save");
        save.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        save.addActionListener(e -> doSave(w));
        file.add(save);

        JMenuItem saveAs = new JMenuItem("Save As...");
        saveAs.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        saveAs.addActionListener(e -> doSaveAs(w));
        file.add(saveAs);

        file.addSeparator();

        JMenuItem close = new JMenuItem("Close Window");
        close.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK));
        close.addActionListener(e -> w.frame.dispatchEvent(
                new WindowEvent(w.frame, WindowEvent.WINDOW_CLOSING)));
        file.add(close);

        JMenuItem exit = new JMenuItem("Exit");
        exit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        exit.addActionListener(e -> System.exit(0));
        file.add(exit);

        mb.add(file);

        return mb;
    }

    // =========================
    // Menu actions
    // =========================
    private static void doOpenNewWindow(JFrame parent) {
        JFileChooser fc = new JFileChooser();
        int r = fc.showOpenDialog(parent);
        if (r != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File f = fc.getSelectedFile();
        if (f == null) {
            return;
        }

        String path = f.getAbsolutePath();
        if (!isReadableFile(path)) {
            JOptionPane.showMessageDialog(parent,
                    "File is not readable:\n" + path,
                    "Open failed",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        // IMPORTANT: open in a NEW viewer / NEW frame
        createEditorWindow(path);
    }

    private static void doSave(EditorWindow w) {
        if (w.path == null) {
            doSaveAs(w);
            return;
        }
        try {
            w.model.saveWithMeta(w.path);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(w.frame,
                    "Failed to save:\n" + ex.getMessage(),
                    "Save failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static void doSaveAs(EditorWindow w) {
        JFileChooser fc = new JFileChooser();
        int r = fc.showSaveDialog(w.frame);
        if (r != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File f = fc.getSelectedFile();
        if (f == null) {
            return;
        }

        String newPath = f.getAbsolutePath();
        try {
            w.model.saveWithMeta(newPath);
            w.path = newPath;
            w.frame.setTitle(newPath);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(w.frame,
                    "Failed to save:\n" + ex.getMessage(),
                    "Save As failed",
                    JOptionPane.ERROR_MESSAGE);
        }
    }
}
