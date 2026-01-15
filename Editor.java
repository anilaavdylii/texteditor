
import java.awt.*;
import java.awt.event.*;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import javax.swing.*;

public class Editor {

    public static void main(String[] arg) {
        if (arg.length < 1) {
            System.out.println("-- file name missing");
            return;
        }
        String path = arg[0];
        try {
            FileInputStream s = new FileInputStream(path);
            s.close();
        } catch (FileNotFoundException e) {
            System.out.println("-- file " + path + " not found");
            return;
        } catch (Exception e) {
            System.out.println("-- file " + path + " not readable");
            return;
        }

        JScrollBar scrollBar = new JScrollBar(Adjustable.VERTICAL, 0, 0, 0, 0);
        Viewer viewer = new Viewer(new Text(path), scrollBar);

        // ---- Main content panel (viewer + scrollbar) ----
        JPanel content = new JPanel(new BorderLayout());
        content.add(viewer, BorderLayout.CENTER);
        content.add(scrollBar, BorderLayout.EAST);

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
                viewer.setFontFamily(sel.toString());
            }
            viewer.requestFocus();
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
                viewer.setFontSize(sz);
            }
            viewer.requestFocus();
        });
        bar.add(sizeBox);

        bar.addSeparator(new Dimension(16, 0));

        // ---------- Style ----------
        bar.add(new JLabel("Style: "));

        JToggleButton boldBtn = new JToggleButton("B");
        boldBtn.setFont(boldBtn.getFont().deriveFont(Font.BOLD));
        boldBtn.setFocusable(false);
        boldBtn.addActionListener(e -> {
            viewer.toggleBold();
            viewer.requestFocus();
        });
        bar.add(boldBtn);

        JToggleButton italicBtn = new JToggleButton("I");
        italicBtn.setFont(italicBtn.getFont().deriveFont(Font.ITALIC));
        italicBtn.setFocusable(false);
        italicBtn.addActionListener(e -> {
            viewer.toggleItalic();
            viewer.requestFocus();
        });
        bar.add(italicBtn);

        JToggleButton underlineBtn = new JToggleButton("U");
        underlineBtn.setFont(underlineBtn.getFont().deriveFont(Font.PLAIN));
        underlineBtn.setFocusable(false);
        underlineBtn.addActionListener(e -> {
            viewer.toggleUnderline();
            viewer.requestFocus();
        });
        bar.add(underlineBtn);

        JToggleButton strikeBtn = new JToggleButton("S");
        strikeBtn.setFont(strikeBtn.getFont().deriveFont(Font.PLAIN));
        strikeBtn.setFocusable(false);
        strikeBtn.addActionListener(e -> {
            viewer.toggleStrike();
            viewer.requestFocus();
        });
        bar.add(strikeBtn);

        bar.addSeparator(new Dimension(16, 0));

        // ---------- Color ----------
        bar.add(new JLabel("Color: "));
        JButton colorBtn = new JButton("Pick…");
        colorBtn.setFocusable(false);
        colorBtn.addActionListener(e -> {
            viewer.chooseColor();
            viewer.requestFocus();
        });
        bar.add(colorBtn);

        // ---- Frame ----
        JFrame frame = new JFrame(path);
        frame.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });
        frame.setSize(700, 800);
        frame.setResizable(true);

        // Put toolbar on top, content in center
        JPanel root = new JPanel(new BorderLayout());
        root.add(bar, BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setVisible(true);
        viewer.requestFocus();
    }
}
