import java.awt.*;
import java.awt.event.*;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;

import java.util.Collections;

class Line {
	String text;    // text of this line
	int len;        // length of this line (including CRLF)
	int x, y, w, h; // top left corner, width, height
	int base;       // base line
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
	Selection (Position a, Position b) { beg = a; end = b; }
}

/**********************************************************************
*  Viewer
**********************************************************************/

public class Viewer extends Canvas {
	static final int    TOP = 5;    // top margin
	static final int    BOTTOM = 5; // bottom margin
	static final int    LEFT = 5;   // left margin
	static final int    EOF = '\0';
	static final String CRLF = "\r\n";

	Text text;
	Line firstLine = null; // the lines in this viewer
	int firstTpos = 0;     // first text position in this viewer
	int lastTpos;          // last text position in this viewer
	Selection sel = null;	 // current selection
	Position caret;				 // current caret
	Position lastPos;      // last mouse position: used during mouse dragging
	JScrollBar scrollBar;
	Graphics g;

	public Viewer(Text t, JScrollBar sb) {
		scrollBar = sb;
		scrollBar.setMaximum(t.length());
		scrollBar.setUnitIncrement(50);
		scrollBar.setBlockIncrement(500);
		scrollBar.addAdjustmentListener(new AdjustmentListener() {
			public void adjustmentValueChanged(AdjustmentEvent e) { doScroll(e); }
		});
		text = t;
		text.addUpdateEventListener(new UpdateEventListener() {
			public void update(UpdateEvent e) { doUpdate(e); }
		});
		this.addKeyListener(new KeyAdapter() {
			public void keyTyped(KeyEvent e) { doKeyTyped(e); }
			public void keyPressed(KeyEvent e) { doKeyPressed(e); }
		});
		this.addMouseListener(new MouseAdapter() {
			public void mousePressed(MouseEvent e) { doMousePressed(e); }
			public void mouseReleased(MouseEvent e) { doMouseReleased(e); }
		});
		this.addMouseMotionListener(new MouseMotionAdapter() {
			public void mouseDragged(MouseEvent e) { doMouseDragged(e); }
		});
		// disable TAB as a focus traversal key
		setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.<AWTKeyStroke>emptySet());
	}

/*------------------------------------------------------------
*  scrolling
*-----------------------------------------------------------*/

	public void doScroll(AdjustmentEvent e) {
		int pos = e.getValue();
		if (pos > 0) { // find start of line
			char ch;
			do { ch = text.charAt(--pos); } while (pos > 0 && ch != '\n');
			if (pos > 0) pos++;
		}
		if (pos != firstTpos) { // scroll
			Position caret0 = caret;
			Selection sel0 = sel;
			removeSelection();
			removeCaret();
			firstTpos = pos;
			firstLine = fill(TOP, getHeight() - BOTTOM, firstTpos);
			repaint();
			if (caret0 != null) setCaret(caret0.tpos);
			if (sel0 != null) setSelection(sel0.beg.tpos, sel0.end.tpos);
		}
	}


	  private boolean isWordChar(char ch) {
    return Character.isLetterOrDigit(ch) || ch == '_';
  }

  private void selectWordAt(int x, int y) {
    Position p = Pos(x, y);
    int t = p.tpos;

    if (text.length() == 0) return;

    // If click lands at EOF, step back once
    if (t >= text.length()) t = text.length() - 1;

    // If we clicked "just after" a word char, prefer the previous char
    if (!isWordChar(text.charAt(t)) && t > 0 && isWordChar(text.charAt(t - 1))) {
      t = t - 1;
    }

    // If not on a word char at all, clear selection/caret behavior (optional)
    if (!isWordChar(text.charAt(t))) {
      removeSelection();
      setCaret(p);
      return;
    }

    int start = t;
    while (start > 0 && isWordChar(text.charAt(start - 1))) start--;

    int end = t + 1;
    while (end < text.length() && isWordChar(text.charAt(end))) end++;

    setSelection(start, end); // highlights the word
    // If you want a caret too, uncomment:
    // setCaret(end);
  }

/*------------------------------------------------------------
*  position handling
*-----------------------------------------------------------*/

	private Position Pos(int tpos) {
		if (tpos < firstTpos) tpos = firstTpos;
		if (tpos > lastTpos) tpos = lastTpos;
		Position pos = new Position();
		setDefaultFont(g);
		Line line = firstLine, last = null;
		pos.org = firstTpos;
		while (line != null && tpos >= pos.org + line.len) {
			pos.org += line.len;
			last = line;
			line = line.next;
		}
		if (line == null) {
			pos.x = last.x + last.w;
			pos.y = last.base;
			pos.line = last;
			pos.org -= last.len;
			pos.off = last.len;
		} else {
			pos.x = line.x;
			pos.y = line.base;
			pos.line = line;
			pos.off = tpos - pos.org;
			FontMetrics m = g.getFontMetrics();
			int i = pos.org;
			while (i < tpos) {
				char ch = text.charAt(i); i++;
				pos.x += charWidth(m, ch);
			}
		}
		pos.tpos = pos.org + pos.off;
		return pos;
	}

	private Position Pos(int x, int y) {
		Position pos = new Position();
		setDefaultFont(g);
		if (y >= getHeight() - BOTTOM) y = getHeight() - BOTTOM - 1;
		Line line = firstLine, last = null;
		pos.org = firstTpos;
		while (line != null && y >= line.y + line.h) {
			pos.org += line.len;
			last = line;
			line = line.next;
		}
		if (line == null) { line = last; pos.org -= last.len; }
		pos.y = line.base;
		pos.line = line;
		if (x >= line.x + line.w) {
			pos.x = line.x + line.w;
			pos.off = line.len;
			if (pos.org + line.len < text.length()) pos.off -= 2;
		} else {
			pos.x = line.x;
			FontMetrics m = g.getFontMetrics();
			int i = pos.org;
			char ch = text.charAt(i);
			int w = charWidth(m, ch);
			while (x >= pos.x + w) {
				pos.x += w;
				i++; ch = text.charAt(i);
				w = charWidth(m, ch);
			}
			pos.off = i - pos.org;
		}
		pos.tpos = pos.org + pos.off;
		return pos;
	}

/*------------------------------------------------------------
*  caret handling
*-----------------------------------------------------------*/

	private void invertCaret() {
		g = getGraphics();
		g.setXORMode(Color.WHITE);
		int x = caret.x;
		int y = caret.y;
		g.drawLine(x, y, x, y); y++;
		g.drawLine(x, y, x + 1, y); y++;
		g.drawLine(x, y, x + 2, y); y++;
		g.drawLine(x, y, x + 3, y); y++;
		g.drawLine(x, y, x + 4, y); y++;
		g.drawLine(x, y, x + 5, y);
		g.setPaintMode();
	}

	private void setCaret(Position pos) {
		removeCaret(); removeSelection();
		caret = pos;
		invertCaret();
	}

	public void setCaret(int tpos) {
		if (tpos >= firstTpos && tpos <= lastTpos) {
			setCaret(Pos(tpos));
		} else caret = null;
	}

	public void setCaret(int x, int y) {
		setCaret(Pos(x, y));
	}

	public void removeCaret() {
		if (caret != null) invertCaret();
		caret = null;
	}

/*------------------------------------------------------------
*  selection handling
*-----------------------------------------------------------*/

	private void invertSelection(Position beg, Position end) {
		g = getGraphics();
		setDefaultFont(g);
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
			x = line.x; y = line.y;
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
		} else sel = null;
	}

	public void removeSelection() {
		if (sel != null) invertSelection(sel.beg, sel.end);
		sel = null;
	}

/*------------------------------------------------------------
*  keyboard handling
*-----------------------------------------------------------*/

	private void doKeyTyped(KeyEvent e) {
		boolean selection = sel != null;
		if (selection) {
			text.delete(sel.beg.tpos, sel.end.tpos);
			// selection is removed; caret is set at sel.beg.tpos
		}
		if (caret != null) {
			char ch = e.getKeyChar();
			if (ch == KeyEvent.VK_BACK_SPACE) {
				if (caret.tpos > 0 && !selection) {
					int d = caret.off == 0 ? 2 : 1;
					text.delete(caret.tpos - d, caret.tpos);
				}
			} else if (ch == KeyEvent.VK_ESCAPE) {
			} else if (ch == KeyEvent.VK_ENTER) {
				text.insert(caret.tpos, CRLF);
			} else {
				text.insert(caret.tpos, String.valueOf(ch));
			}
			scrollBar.setValues(firstTpos, 0, 0, text.length());
		}
	}

	private void doKeyPressed(KeyEvent e) { // for cursor keys
		if (caret != null) {
			int key = e.getKeyCode();
			int pos = caret.tpos;
			char ch;
			if (key == KeyEvent.VK_RIGHT) {
				pos++; ch = text.charAt(pos);
				if (ch == '\n') pos++;
				setCaret(pos);
			} else if (key == KeyEvent.VK_LEFT) {
				pos--; ch = text.charAt(pos);
				if (ch == '\n') pos--;
				setCaret(pos);
			} else if (key == KeyEvent.VK_UP) {
				setCaret(caret.x, caret.y - caret.line.h);
			} else if (key == KeyEvent.VK_DOWN) {
				setCaret(caret.x, caret.y + caret.line.h);
			}
		}
	}

/*------------------------------------------------------------
*  mouse handling
*-----------------------------------------------------------*/

	  private void doMousePressed(MouseEvent e) {
		// Double click selects a word
		if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
		removeCaret();
		removeSelection();
		selectWordAt(e.getX(), e.getY());
		lastPos = null;
		return;
		}

		// Single click starts a selection drag as before
		removeCaret(); removeSelection();
		Position pos = Pos(e.getX(), e.getY());
		sel = new Selection(pos, pos);
		lastPos = pos;
	}

	private void doMouseDragged(MouseEvent e) {
		if (sel == null) return;
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
		if (sel.beg.tpos == sel.end.tpos) setCaret(sel.beg);
		lastPos = null;
	}

/*------------------------------------------------------------
*  TAB handling
*-----------------------------------------------------------*/

	private int charWidth(FontMetrics m, char ch) {
		if (ch == '\t') return 4 * m.charWidth(' '); else return m.charWidth(ch);
	}

	private int stringWidth(FontMetrics m, String s) {
		String s1 = s.replaceAll("\t", "    ");
		return m.stringWidth(s1);
	}

	private void drawString(Graphics g, String s, int x, int y) {
		String s1 = s.replaceAll("\t", "    ");
		g.drawString(s1, x, y);
	}

/*------------------------------------------------------------
*  line handling
*-----------------------------------------------------------*/

	private Line fill(int top, int bottom, int pos) {
		g = getGraphics();
		setDefaultFont(g);
		FontMetrics m = g.getFontMetrics();
		Line first = null, line = null;
		int y = top;
		lastTpos = pos;
		char ch = text.charAt(pos);
		while (y < bottom) {
			if (first == null) {
				first = line = new Line();
			} else {
				Line prev = line;
				line.next = new Line(); line = line.next; line.prev = prev;
			}
			StringBuffer buf = new StringBuffer();
			while (ch != '\n' && ch != EOF) {
				buf.append(ch);
				pos++;
				ch = text.charAt(pos);
			}
			boolean eol = ch == '\n';
			if (eol) { buf.append(ch); pos++; ch = text.charAt(pos); }
			line.len = buf.length();
			line.text = buf.toString();
			line.x = LEFT;
			line.y = y;
			line.w = stringWidth(m, line.text);
			line.h = m.getHeight();
			line.base = y + m.getAscent();
			y += line.h;
			lastTpos += line.len;
			if (!eol) break;
		}
		return first;
	}

	private void rebuildFrom(Position pos) {
		Line line = pos.line;
		Line prev = line.prev;
		line = fill(line.y, getHeight() - BOTTOM, pos.org);
		if (prev == null) firstLine = line; else { prev.next = line; line.prev = prev; }
		repaint(LEFT, line.y, getWidth(), getHeight());
	}

/*------------------------------------------------------------
*  text drawing
*-----------------------------------------------------------*/

	public void doUpdate(UpdateEvent e) {
		StringBuffer b;
		g = getGraphics();
		setDefaultFont(g);
		FontMetrics m = g.getFontMetrics();
		Position pos = caret;

		if (e.from == e.to) { // insert
			if (e.from != caret.tpos) pos = Pos(e.from);
			int newCarPos = pos.tpos + e.text.length();
			if (e.text.indexOf(CRLF) >= 0) {
				rebuildFrom(pos);
				if (pos.y + pos.line.h > getHeight() - BOTTOM)
					scrollBar.setValue(firstTpos + firstLine.len);
			} else {
				b = new StringBuffer(pos.line.text);
				b.insert(pos.off, e.text);
				pos.line.text = b.toString();
				pos.line.w += stringWidth(m, e.text);
				pos.line.len += e.text.length();
				lastTpos += e.text.length();
				repaint(pos.line.x, pos.line.y, getWidth(), pos.line.h+1);
			}
			setCaret(newCarPos);

		} else if (e.text == null) { // delete
			if (caret == null || e.to != caret.tpos) pos = Pos(e.to);
			int d = e.to - e.from;
			if (pos.off - d < 0) { // delete across lines
				rebuildFrom(Pos(e.from));
			} else { // delete within a line
				b = new StringBuffer(pos.line.text);
				b.delete(pos.off - d, pos.off);
				pos.line.text = b.toString();
				pos.line.w = stringWidth(m, pos.line.text);
				pos.line.len -= d;
				lastTpos -= d;
				repaint(pos.line.x, pos.line.y, getWidth(), pos.line.h+1);
			}
			setCaret(e.from);
		}
	}

	private void setDefaultFont(Graphics g) {
		g.setFont(g.getFont().deriveFont(18f));
	}

	public void paint(Graphics g) {
		setDefaultFont(g);
		if (firstLine == null) {
			firstLine = fill(TOP, getHeight() - BOTTOM, 0);
			caret = Pos(0);
		}
		Line line = firstLine;
		while (line != null) {
			drawString(g, line.text, line.x, line.base);
			line = line.next;
		}
		if (caret != null) invertCaret();
		if (sel != null) invertSelection(sel.beg, sel.end);
	}
}