
import java.awt.*;
import java.awt.event.*;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;
import javax.swing.JColorChooser;

import java.util.Collections;

class Line {

    int org;        // text position of first character in this line
    int len;        // length of this line (including CRLF if present)
    int x, y, w, h; // top left corner, width, height
    int base;       // base line y
    Line prev, next;
}

class Position {

    Line line; // line containing this position
    int x, y;  // base line point corresponding to this position
    int tpos;  // text position (relative to start of text)
    int org;   // origin (text position of first character in this line)
    int off;   // text offset from org
}

class Selection {

    Position beg, end;

    Selection(Position a, Position b) {
        beg = a;
        end = b;
    }
}

/**
 * ********************************************************************
 * Viewer
 *********************************************************************
 */
public class Viewer extends Canvas {

    static final int TOP = 5;     // top margin
    static final int BOTTOM = 5;  // bottom margin
    static final int LEFT = 5;    // left margin
    static final int EOF = '\0';
    static final String CRLF = "\r\n";

    Text text;
    Line firstLine = null; // the lines in this viewer
    int firstTpos = 0;     // first text position in this viewer
    int lastTpos;          // last text position in this viewer
    Selection sel = null;  // current selection
    Position caret;        // current caret
    Position lastPos;      // last mouse position: used during mouse dragging
    JScrollBar scrollBar;
    private Text.Style typingStyle;

    public Viewer(Text t, JScrollBar sb) {
        scrollBar = sb;
        scrollBar.setMaximum(t.length());
        scrollBar.setUnitIncrement(50);
        scrollBar.setBlockIncrement(500);
        scrollBar.addAdjustmentListener(new AdjustmentListener() {
            public void adjustmentValueChanged(AdjustmentEvent e) {
                doScroll(e);
            }
        });

        text = t;
        text.addUpdateEventListener(new UpdateEventListener() {
            public void update(UpdateEvent e) {
                doUpdate(e);
            }
        });

        this.addKeyListener(new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
                doKeyTyped(e);
            }

            public void keyPressed(KeyEvent e) {
                doKeyPressed(e);
            }
        });
        this.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                doMousePressed(e);
            }

            public void mouseReleased(MouseEvent e) {
                doMouseReleased(e);
            }
        });
        this.addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                doMouseDragged(e);
            }
        });

        // disable TAB as a focus traversal key
        setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.<AWTKeyStroke>emptySet());
        setFocusable(true);
    }

    /*------------------------------------------------------------
   *  scrolling
   *-----------------------------------------------------------*/
    public void doScroll(AdjustmentEvent e) {
        int pos = e.getValue();
        if (pos > 0) { // find start of line
            char ch;
            do {
                ch = text.charAt(--pos);
            } while (pos > 0 && ch != '\n');
            if (pos > 0) {
                pos++;
            }
        }
        if (pos != firstTpos) { // scroll
            Position caret0 = caret;
            Selection sel0 = sel;
            removeSelection();
            removeCaret();
            firstTpos = pos;
            firstLine = fill(TOP, getHeight() - BOTTOM, firstTpos);
            repaint();
            if (caret0 != null) {
                setCaret(caret0.tpos);
            }
            if (sel0 != null) {
                setSelection(sel0.beg.tpos, sel0.end.tpos);
            }
        }
    }

    private Text.Style currentTypingBaseStyle() {
        if (typingStyle != null) {
            return typingStyle;
        }
        int p = (caret != null) ? caret.tpos : 0;
        return text.styleAt(Math.max(0, p - 1));
    }

    private void setTypingStyle(Text.Style st) {
        typingStyle = st;
    }


    /*------------------------------------------------------------
   *  selection helpers / formatting commands
   *-----------------------------------------------------------*/
    private int selFrom() {
        if (sel != null) {
            return Math.min(sel.beg.tpos, sel.end.tpos);
        }
        if (caret != null) {
            return caret.tpos;
        }
        return 0;
    }

    private int selTo() {
        if (sel != null) {
            return Math.max(sel.beg.tpos, sel.end.tpos);
        }
        if (caret != null) {
            return caret.tpos;
        }
        return 0;
    }

    public void toggleBold() {
        int a = selFrom(), b = selTo();
        if (a == b) {
            Text.Style st = currentTypingBaseStyle();
            setTypingStyle(st.withBold(!st.bold));
            return;
        }
        text.applyStyle(a, b, st -> st.withBold(!st.bold));
        repaintAllKeepingCaretSelection();
    }

    public void toggleUnderline() {
        int a = selFrom(), b = selTo();
        if (a == b) {
            Text.Style st = currentTypingBaseStyle();
            setTypingStyle(st.withUnderline(!st.underline));
            return;
        }
        text.applyStyle(a, b, st -> st.withUnderline(!st.underline));
        repaintAllKeepingCaretSelection();
    }

    public void toggleStrike() {
        int a = selFrom(), b = selTo();
        if (a == b) {
            Text.Style st = currentTypingBaseStyle();
            setTypingStyle(st.withStrike(!st.strike));
            return;
        }
        text.applyStyle(a, b, st -> st.withStrike(!st.strike));
        repaintAllKeepingCaretSelection();
    }

    public void toggleItalic() {
        int a = selFrom(), b = selTo();
        if (a == b) {
            Text.Style st = currentTypingBaseStyle();
            setTypingStyle(st.withItalic(!st.italic));
            return;
        }
        text.applyStyle(a, b, st -> st.withItalic(!st.italic));
        repaintAllKeepingCaretSelection();
    }

    public void chooseFontFamily() {
        int a = selFrom(), b = selTo();
        if (a == b) {
            return;
        }

        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        String current = text.styleAt(a).family;

        String pick = (String) JOptionPane.showInputDialog(
                this,
                "Font:",
                "Font",
                JOptionPane.PLAIN_MESSAGE,
                null,
                fonts,
                current
        );
        if (pick != null) {
            text.applyStyle(a, b, st -> st.withFamily(pick));
            repaintAllKeepingCaretSelection();
        }
    }

    public void setFontFamily(String family) {
        int a = selFrom(), b = selTo();
        if (a == b) {
            Text.Style st = currentTypingBaseStyle();
            setTypingStyle(st.withFamily(family));
            return;
        }
        text.applyStyle(a, b, st -> st.withFamily(family));
        repaintAllKeepingCaretSelection();
    }

    public void setFontSize(int size) {
        int a = selFrom(), b = selTo();
        if (a == b) {
            Text.Style st = currentTypingBaseStyle();
            setTypingStyle(st.withSize(size));
            return;
        }
        text.applyStyle(a, b, st -> st.withSize(size));
        repaintAllKeepingCaretSelection();
    }

    public void chooseFontSize() {
        int a = selFrom(), b = selTo();
        if (a == b) {
            return;
        }

        String s = JOptionPane.showInputDialog(this, "Size (px):", Integer.toString(text.styleAt(a).size));
        if (s == null) {
            return;
        }
        try {
            int sz = Integer.parseInt(s.trim());
            if (sz < 6) {
                sz = 6;
            }
            if (sz > 200) {
                sz = 200;
            }
            final int fsz = sz;
            text.applyStyle(a, b, st -> st.withSize(fsz));
            repaintAllKeepingCaretSelection();
        } catch (NumberFormatException ex) {
            // ignore
        }
    }

    public void chooseColor() {
        int a = selFrom(), b = selTo();

        Color initial = (a == b) ? currentTypingBaseStyle().color : text.styleAt(a).color;
        Color pick = JColorChooser.showDialog(this, "Color", initial);
        if (pick == null) {
            return;
        }

        if (a == b) {
            Text.Style st = currentTypingBaseStyle();
            setTypingStyle(st.withColor(pick));
            return;
        }

        text.applyStyle(a, b, st -> st.withColor(pick));
        repaintAllKeepingCaretSelection();
    }

    public void copySelectionToClipboardFromMenu() { copySelectionToClipboard(); }
    public void cutSelectionToClipboardFromMenu()  { cutSelectionToClipboard(); }
    public void pasteFromClipboardFromMenu()       { pasteFromClipboard(); }


    private void repaintAllKeepingCaretSelection() {
        Position caret0 = caret;
        Selection sel0 = sel;
        removeSelection();
        removeCaret();
        firstLine = fill(TOP, getHeight() - BOTTOM, firstTpos);
        repaint();
        if (caret0 != null) {
            setCaret(caret0.tpos);
        }
        if (sel0 != null) {
            setSelection(sel0.beg.tpos, sel0.end.tpos);
        }
    }

    private boolean isWordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }

    private void selectWordAt(int x, int y) {
        Position p = Pos(x, y);
        int t = p.tpos;

        if (text.length() == 0) {
            return;
        }

        // If click lands at EOF, step back once
        if (t >= text.length()) {
            t = text.length() - 1;
        }

        // If we clicked "just after" a word char, prefer the previous char
        if (!isWordChar(text.charAt(t)) && t > 0 && isWordChar(text.charAt(t - 1))) {
            t = t - 1;
        }

        if (!isWordChar(text.charAt(t))) {
            removeSelection();
            setCaret(p);
            return;
        }

        int start = t;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }

        int end = t + 1;
        while (end < text.length() && isWordChar(text.charAt(end))) {
            end++;
        }

        setSelection(start, end);
    }

    /*------------------------------------------------------------
   *  position handling
   *-----------------------------------------------------------*/
    private int charWidthAt(Graphics g, int tpos, char ch) {
        Text.Style st = text.styleAt(tpos);
        FontMetrics m = g.getFontMetrics(st.toFont());
        if (ch == '\t') {
            return 4 * m.charWidth(' ');
        }
        return m.charWidth(ch);
    }

    // For CRLF at end of line: do not draw; but caret/hit-testing must still treat them as characters.
    private int drawEndFor(Line line) {
        int end = line.org + line.len;
        if (end >= 2 && text.charAt(end - 1) == '\n' && text.charAt(end - 2) == '\r') {
            return end - 2;
        }
        return end;
    }

    private Position Pos(int tpos) {
        if (firstLine == null) {
            firstLine = fill(TOP, getHeight() - BOTTOM, firstTpos);
        }

        if (tpos < firstTpos) {
            tpos = firstTpos;
        }
        if (tpos > lastTpos) {
            tpos = lastTpos;
        }

        Graphics g = getGraphics();
        if (g == null) {
            g = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).getGraphics();
        }

        Position pos = new Position();
        Line line = firstLine, last = null;
        pos.org = firstTpos;

        while (line != null && tpos >= pos.org + line.len) {
            pos.org += line.len;
            last = line;
            line = line.next;
        }

        if (line == null) {
            // after last line
            pos.line = last;
            pos.org -= last.len;
            pos.off = last.len;
            pos.y = last.base;

            // compute x by walking that last line
            int x = last.x;
            int i = last.org;
            int end = last.org + last.len;
            while (i < end) {
                char ch = text.charAt(i);
                x += charWidthAt(g, i, ch);
                i++;
            }
            pos.x = x;
        } else {
            pos.line = line;
            pos.off = tpos - pos.org;
            pos.y = line.base;

            int x = line.x;
            int i = pos.org;
            while (i < tpos) {
                char ch = text.charAt(i);
                x += charWidthAt(g, i, ch);
                i++;
            }
            pos.x = x;
        }

        pos.tpos = pos.org + pos.off;
        return pos;
    }

    private Position Pos(int x, int y) {
        if (firstLine == null) {
            firstLine = fill(TOP, getHeight() - BOTTOM, firstTpos);
        }

        Graphics g = getGraphics();
        if (g == null) {
            g = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).getGraphics();
        }

        Position pos = new Position();
        if (y >= getHeight() - BOTTOM) {
            y = getHeight() - BOTTOM - 1;
        }

        Line line = firstLine, last = null;
        pos.org = firstTpos;
        while (line != null && y >= line.y + line.h) {
            pos.org += line.len;
            last = line;
            line = line.next;
        }
        if (line == null) {
            line = last;
            pos.org -= last.len;
        }

        pos.line = line;
        pos.y = line.base;

        // If beyond end-of-drawn-text, snap to end of line (including CRLF handling like old code)
        int drawEnd = drawEndFor(line);
        int lineX = line.x;
        int lineW = line.w;

        if (x >= lineX + lineW) {
            pos.x = lineX + lineW;
            pos.off = line.len;
            // old behavior: if not last line, don't place caret inside CRLF
            if (pos.org + line.len < text.length()) {
                pos.off -= 2;
            }
            pos.tpos = pos.org + pos.off;
            return pos;
        }

        int curX = lineX;
        int i = pos.org;

        // Walk characters (including tabs width), stop when next char would pass x
        while (i < drawEnd) {
            char ch = text.charAt(i);
            int w = charWidthAt(g, i, ch);
            if (x < curX + w) {
                break;
            }
            curX += w;
            i++;
        }

        pos.x = curX;
        pos.off = i - pos.org;
        pos.tpos = pos.org + pos.off;
        return pos;
    }

    /*------------------------------------------------------------
   *  caret handling
   *-----------------------------------------------------------*/
    private void invertCaret() {
        Graphics g = getGraphics();
        if (g == null || caret == null) {
            return;
        }
        g.setXORMode(Color.WHITE);

        int x = caret.x;
        int y = caret.y;
        g.drawLine(x, y, x, y);
        y++;
        g.drawLine(x, y, x + 1, y);
        y++;
        g.drawLine(x, y, x + 2, y);
        y++;
        g.drawLine(x, y, x + 3, y);
        y++;
        g.drawLine(x, y, x + 4, y);
        y++;
        g.drawLine(x, y, x + 5, y);

        g.setPaintMode();
    }

    private void setCaret(Position pos) {
        removeCaret();
        removeSelection();
        caret = pos;

        int p = caret.tpos;
        if (typingStyle == null) {
            typingStyle = text.styleAt(Math.max(0, p - 1));
        }

        invertCaret();
    }

    public void setCaret(int tpos) {
        if (tpos >= firstTpos && tpos <= lastTpos) {
            setCaret(Pos(tpos)); 
        }else {
            caret = null;
        }
    }

    public void setCaret(int x, int y) {
        setCaret(Pos(x, y));
    }

    public void removeCaret() {
        if (caret != null) {
            invertCaret();
        }
        caret = null;
    }

    /*------------------------------------------------------------
   *  selection handling
   *-----------------------------------------------------------*/
    private void invertSelection(Position beg, Position end) {
        Graphics g = getGraphics();
        if (g == null) {
            return;
        }
        g.setXORMode(Color.WHITE);

        Line line = beg.line;
        int x = beg.x;
        int y = line.y;
        int w;
        int h = line.h;

        while (line != end.line) {
            w = line.w + LEFT - x;
            g.fillRect(x, y, w, h);
            line = line.next;
            x = line.x;
            y = line.y;
            h = line.h;
        }
        w = end.x - x;
        g.fillRect(x, y, w, h);

        g.setPaintMode();
    }

    public void setSelection(int from, int to) {
        if (from < to) {
            removeCaret();
            Position beg = Pos(from);
            Position end = Pos(to);
            sel = new Selection(beg, end);
            invertSelection(beg, end);
        } else {
            sel = null;
        }
    }

    public void removeSelection() {
        if (sel != null) {
            invertSelection(sel.beg, sel.end);
        }
        sel = null;
    }

    /*------------------------------------------------------------
   *  keyboard handling
   *-----------------------------------------------------------*/
    private void doKeyTyped(KeyEvent e) {
        boolean hadSelection = sel != null;
        if (hadSelection) {
            text.delete(sel.beg.tpos, sel.end.tpos);
            // update will rebuild and set caret at sel.beg.tpos
        }

        if (caret != null) {
            char ch = e.getKeyChar();
            if (ch == KeyEvent.VK_BACK_SPACE) {
                if (caret.tpos > 0 && !hadSelection) {
                    int d = (caret.off == 0) ? 2 : 1; // CRLF aware
                    text.delete(caret.tpos - d, caret.tpos);
                }
            } else if (ch == KeyEvent.VK_ESCAPE) {
                // no-op
            } else if (ch == KeyEvent.VK_ENTER) {
                // text.insert(caret.tpos, CRLF);
                Text.Style st = typingStyle;
                if (st == null) {
                    st = text.styleAt(Math.max(0, caret.tpos - 1));
                }
                text.setNextInsertStyle(st);
                text.insert(caret.tpos, CRLF);

            } else {
                // text.insert(caret.tpos, String.valueOf(ch));
                Text.Style st = typingStyle;
                if (st == null) {
                    st = text.styleAt(Math.max(0, caret.tpos - 1));
                }
                text.setNextInsertStyle(st);
                text.insert(caret.tpos, String.valueOf(ch));

            }
            scrollBar.setValues(firstTpos, 0, 0, text.length());
        }
    }

   private void doKeyPressed(KeyEvent e) {

    // Handle clipboard shortcuts first (they should work even if caret is null)
    if (e.isControlDown()) {
        int k = e.getKeyCode();
        if (k == KeyEvent.VK_C) { copySelectionToClipboard(); return; }
        if (k == KeyEvent.VK_X) { cutSelectionToClipboard(); return; }
        if (k == KeyEvent.VK_V) { pasteFromClipboard(); return; }
    }

    // Navigation keys require a caret
    if (caret == null) {
        return;
    }

    int key = e.getKeyCode();
    int pos = caret.tpos;

    if (key == KeyEvent.VK_RIGHT) {
        pos++;
        char ch = text.charAt(pos);
        if (ch == '\n') pos++;     // CRLF-aware (skip \n after \r)
        setCaret(pos);

    } else if (key == KeyEvent.VK_LEFT) {
        pos--;
        char ch = text.charAt(pos);
        if (ch == '\n') pos--;     // CRLF-aware
        setCaret(pos);

    } else if (key == KeyEvent.VK_UP) {
        setCaret(caret.x, caret.y - caret.line.h);

    } else if (key == KeyEvent.VK_DOWN) {
        setCaret(caret.x, caret.y + caret.line.h);
    }
}



    /*------------------------------------------------------------
   *  mouse handling
   *-----------------------------------------------------------*/
    private void doMousePressed(MouseEvent e) {
        requestFocus();

        // Double click selects a word
        if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
            removeCaret();
            removeSelection();
            selectWordAt(e.getX(), e.getY());
            lastPos = null;
            return;
        }

        removeCaret();
        removeSelection();
        Position pos = Pos(e.getX(), e.getY());
        sel = new Selection(pos, pos);
        lastPos = pos;
    }

    private void doMouseDragged(MouseEvent e) {
        if (sel == null) {
            return;
        }

        // FIX: lastPos can be null (e.g., after double-click selection)
        if (lastPos == null) {
            // pick a valid reference point for "last position"
            lastPos = sel.end != null ? sel.end : sel.beg;
            if (lastPos == null) {
                return; // ultra-safety

                    }}

        Position pos = Pos(e.getX(), e.getY());

        if (pos.tpos < sel.beg.tpos) {
            if (lastPos.tpos == sel.end.tpos) {
                invertSelection(sel.beg, lastPos);
                sel.end = sel.beg;
            }
            invertSelection(pos, sel.beg);
            sel.beg = pos;

        } else if (pos.tpos > sel.end.tpos) {
            if (lastPos.tpos == sel.beg.tpos) {
                invertSelection(lastPos, sel.end);
                sel.beg = sel.end;
            }
            invertSelection(sel.end, pos);
            sel.end = pos;

        } else if (pos.tpos < lastPos.tpos) { // beg <= pos <= end; clear pos..end
            invertSelection(pos, sel.end);
            sel.end = pos;

        } else if (lastPos.tpos < pos.tpos) { // beg <= pos <= end; clear beg..pos
            invertSelection(sel.beg, pos);
            sel.beg = pos;
        }

        lastPos = pos;

    }

    private void doMouseReleased(MouseEvent e) {
        if (sel == null) {
            lastPos = null;
            return;
        }
        if (sel.beg.tpos == sel.end.tpos) {
            setCaret(sel.beg);
        }
        lastPos = null;
    }

    /*------------------------------------------------------------
   *  line handling (per-character metrics)
   *-----------------------------------------------------------*/
    private Line fill(int top, int bottom, int pos) {
        Graphics g = getGraphics();
        if (g == null) {
            g = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).getGraphics();
        }

        Line first = null, line = null;
        int y = top;
        lastTpos = pos;

        char ch = text.charAt(pos);
        while (y < bottom) {
            if (first == null) {
                first = line = new Line();
            } else {
                Line prev = line;
                line.next = new Line();
                line = line.next;
                line.prev = prev;
            }

            line.org = pos;
            line.x = LEFT;
            line.y = y;

            int maxAsc = 0, maxDes = 0, maxLead = 0;
            int w = 0;
            int startPos = pos;

            // collect until '\n' or EOF
            while (ch != '\n' && ch != EOF) {
                Text.Style st = text.styleAt(pos);
                FontMetrics m = g.getFontMetrics(st.toFont());
                maxAsc = Math.max(maxAsc, m.getAscent());
                maxDes = Math.max(maxDes, m.getDescent());
                maxLead = Math.max(maxLead, m.getLeading());

                w += (ch == '\t') ? 4 * m.charWidth(' ') : m.charWidth(ch);

                pos++;
                ch = text.charAt(pos);
            }

            boolean eol = (ch == '\n');
            if (eol) {
                // line length includes '\n' (and typically also '\r' right before it)
                pos++;
                ch = text.charAt(pos);
            }

            line.len = pos - startPos;
            line.w = w;
            line.h = Math.max(1, maxAsc + maxDes + maxLead);
            line.base = y + maxAsc;

            y += line.h;
            lastTpos += line.len;

            if (!eol) {
                break;
            }
        }
        return first;
    }

    private void rebuildFrom(Position pos) {
        Line line = pos.line;
        Line prev = line.prev;

        Line rebuilt = fill(line.y, getHeight() - BOTTOM, pos.org);
        if (prev == null) {
            firstLine = rebuilt; 
        }else {
            prev.next = rebuilt;
            rebuilt.prev = prev;
        }

        repaint(LEFT, rebuilt.y, getWidth(), getHeight());
    }


        private void copySelectionToClipboard() {
        if (sel == null) return;
        int a = Math.min(sel.beg.tpos, sel.end.tpos);
        int b = Math.max(sel.beg.tpos, sel.end.tpos);
        Editor.AppClipboard.set(text.copyFragment(a, b));
    }

    private void cutSelectionToClipboard() {
        if (sel == null) return;
        int a = Math.min(sel.beg.tpos, sel.end.tpos);
        int b = Math.max(sel.beg.tpos, sel.end.tpos);
        Editor.AppClipboard.set(text.copyFragment(a, b));
        text.delete(a, b);
        // doUpdate will restore caret
    }

    private void pasteFromClipboard() {
        if (caret == null) return;

        // If there is a selection, replace it (typical editor behavior)
        if (sel != null) {
            int a = Math.min(sel.beg.tpos, sel.end.tpos);
            int b = Math.max(sel.beg.tpos, sel.end.tpos);
            text.delete(a, b);
            // caret will be moved by doUpdate to a
        }

        Text.StyledFragment frag = Editor.AppClipboard.get();
        if (frag == null || frag.text == null || frag.text.isEmpty()) return;

        int count = Editor.AppClipboard.incPasteCount();
        System.out.println("[debug] paste #" + count);

        int p = (caret != null) ? caret.tpos : 0;
        text.insertFragment(p, frag);
        scrollBar.setValues(firstTpos, 0, 0, text.length());
    }


    /*------------------------------------------------------------
   *  updates: simplest correct approach = rebuild from change point
   *-----------------------------------------------------------*/
    public void doUpdate(UpdateEvent e) {
        // Save caret/selection as text positions
        int caretPos = (caret != null) ? caret.tpos : -1;
        int selA = (sel != null) ? sel.beg.tpos : -1;
        int selB = (sel != null) ? sel.end.tpos : -1;

        // Adjust saved positions according to the edit
        if (e.from == e.to && e.text != null) {
            // INSERT at e.from, length = e.text.length()
            int k = e.text.length();

            if (caretPos >= e.from) {
                caretPos += k;
            }
            if (selA >= e.from) {
                selA += k;
            }
            if (selB >= e.from) {
                selB += k;
            }

        } else if (e.text == null) {
            // DELETE [e.from, e.to)
            int k = e.to - e.from;

            if (caretPos > e.from) {
                caretPos = Math.max(e.from, caretPos - k);
            }

            if (selA > e.from) {
                selA = Math.max(e.from, selA - k);
            }
            if (selB > e.from) {
                selB = Math.max(e.from, selB - k);
            }
        }

        // Now rebuild visuals
        removeSelection();
        removeCaret();

        if (firstLine == null) {
            firstLine = fill(TOP, getHeight() - BOTTOM, 0);
            firstTpos = 0;
        } else {
            // rebuild from first affected pos (clamped)
            int rebuildPos = e.from;
            if (rebuildPos < firstTpos) {
                rebuildPos = firstTpos;
            }
            Position anchor = Pos(rebuildPos);
            rebuildFrom(anchor);
        }

        scrollBar.setValues(firstTpos, 0, 0, text.length());

        // Restore caret/selection at adjusted positions
        if (caretPos >= 0) {
            setCaret(caretPos);
        }
        if (selA >= 0 && selB >= 0 && selA != selB) {
            setSelection(Math.min(selA, selB), Math.max(selA, selB));
        }
    }

    /*------------------------------------------------------------
   *  text drawing (per-character font + color)
   *-----------------------------------------------------------*/
    public void paint(Graphics gg) {
        Graphics2D g2 = (Graphics2D) gg;

        if (firstLine == null) {
            firstLine = fill(TOP, getHeight() - BOTTOM, 0);
            caret = Pos(0);
        }

        Line line = firstLine;
        while (line != null) {
            int x = line.x;
            int base = line.base;

            int i = line.org;
            int end = line.org + line.len;

            // Do not draw CRLF if present at end
            int drawEnd = end;
            if (drawEnd >= 2 && text.charAt(drawEnd - 1) == '\n' && text.charAt(drawEnd - 2) == '\r') {
                drawEnd -= 2;
            } else if (drawEnd >= 1 && text.charAt(drawEnd - 1) == '\n') {
                drawEnd -= 1;
            }

            while (i < drawEnd) {
                char ch = text.charAt(i);
                Text.Style st = text.styleAt(i);
                g2.setFont(st.toFont());
                g2.setColor(st.color);

                FontMetrics m = g2.getFontMetrics();
                int cw = (ch == '\t') ? 4 * m.charWidth(' ') : m.charWidth(ch);

                if (ch != '\t') {
                    g2.drawString(String.valueOf(ch), x, base);

                    // underline: a little below baseline
                    if (st.underline) {
                        int uy = base + 1;
                        g2.drawLine(x, uy, x + cw, uy);
                    }

                    // strike: roughly through the middle of the glyph box
                    if (st.strike) {
                        int sy = base - (m.getAscent() / 2);
                        g2.drawLine(x, sy, x + cw, sy);
                    }
                }
                x += cw;
                i++;

            }

            line = line.next;
        }

        if (caret != null) {
            invertCaret();
        }
        if (sel != null) {
            invertSelection(sel.beg, sel.end);
        }
    }
}
