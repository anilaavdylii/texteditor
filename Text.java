
import java.awt.Color;
import java.awt.Font;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.UnaryOperator;

interface UpdateEventListener {

    void update(UpdateEvent e);
}

class UpdateEvent {  // [from..to[ was replaced by text

    int from;
    int to;
    String text;

    UpdateEvent(int a, int b, String t) {
        from = a;
        to = b;
        text = t;
    }
}

/**
 * *****************************************************************
 * Piece list / piece table + Style runs
 * *****************************************************************
 */
public class Text {

    // Immutable original file content
    private final String originalBuffer;
    // Append-only buffer for inserted text
    private final StringBuilder addBuffer = new StringBuilder();

    private enum BufferKind {
        ORIGINAL, ADD
    }

    public static final class StyledRun {

        public final int from; // inclusive, relative to fragment start
        public final int to;   // exclusive, relative to fragment start
        public final Style style;

        public StyledRun(int from, int to, Style style) {
            this.from = from;
            this.to = to;
            this.style = style;
        }
    }

    public static final class StyledFragment {

        public final String text;
        public final java.util.List<StyledRun> runs;

        public StyledFragment(String text, java.util.List<StyledRun> runs) {
            this.text = text;
            this.runs = runs;
        }
    }

    private static final class Piece {

        BufferKind buf;
        int start;     // offset into the selected buffer
        int length;    // length in chars
        final long createdAt; // timestamp for debugging

        Piece(BufferKind buf, int start, int length, long createdAt) {
            this.buf = buf;
            this.start = start;
            this.length = length;
            this.createdAt = createdAt;
        }
    }

    private final ArrayList<Piece> pieces = new ArrayList<>();
    private int len = 0;
    private Style nextInsertStyle = null;

    public void setNextInsertStyle(Style s) {
        nextInsertStyle = s;
    }


    /* ============================================================
   * Styling
   * ============================================================ */
    public static final class Style {

        public final String family;
        public final int size;
        public final boolean bold;
        public final boolean italic;
        public final Color color;
        public final boolean underline;
        public final boolean strike;

        public Style(String family, int size,
                boolean bold, boolean italic,
                Color color,
                boolean underline, boolean strike) {
            this.family = family;
            this.size = size;
            this.bold = bold;
            this.italic = italic;
            this.color = color;
            this.underline = underline;
            this.strike = strike;
        }

        public Font toFont() {
            int fs = Font.PLAIN;
            if (bold) {
                fs |= Font.BOLD;
            }
            if (italic) {
                fs |= Font.ITALIC;
            }
            return new Font(family, fs, size);
        }

        public Style withFamily(String f) {
            return new Style(f, size, bold, italic, color, underline, strike);
        }

        public Style withSize(int s) {
            return new Style(family, s, bold, italic, color, underline, strike);
        }

        public Style withBold(boolean b) {
            return new Style(family, size, b, italic, color, underline, strike);
        }

        public Style withItalic(boolean i) {
            return new Style(family, size, bold, i, color, underline, strike);
        }

        public Style withColor(Color c) {
            return new Style(family, size, bold, italic, c, underline, strike);
        }

        public Style withUnderline(boolean u) {
            return new Style(family, size, bold, italic, color, u, strike);
        }

        public Style withStrike(boolean s) {
            return new Style(family, size, bold, italic, color, underline, s);
        }
    }

    private static final class StyleRun {

        int from; // inclusive
        int to;   // exclusive
        Style style;

        StyleRun(int from, int to, Style style) {
            this.from = from;
            this.to = to;
            this.style = style;
        }
    }

    // Default styling: #c0ffee, plain
    public Style defaultStyle = new Style(
            "Monospaced",
            18,
            false,
            false,
            Color.black,
            false,
            false
    );

    private final ArrayList<StyleRun> styles = new ArrayList<>();

    public Style styleAt(int pos) {
        if (len <= 0) {
            return defaultStyle;
        }
        if (pos < 0) {
            pos = 0;
        }
        if (pos >= len) {
            pos = len - 1;
        }
        for (StyleRun r : styles) {
            if (pos >= r.from && pos < r.to) {
                return r.style;
            }
        }
        return defaultStyle;
    }

    private boolean sameStyle(Style a, Style b) {
        return a.size == b.size
                && a.bold == b.bold
                && a.italic == b.italic
                && a.family.equals(b.family)
                && a.color.equals(b.color)
                && a.underline == b.underline
                && a.strike == b.strike;
    }

    private void normalizeStyles() {
        styles.sort((a, b) -> Integer.compare(a.from, b.from));

        ArrayList<StyleRun> merged = new ArrayList<>();

        // Merge adjacent same-style and discard invalid runs
        for (StyleRun r : styles) {
            if (r.from >= r.to) {
                continue;
            }

            if (merged.isEmpty()) {
                merged.add(new StyleRun(r.from, r.to, r.style));
            } else {
                StyleRun last = merged.get(merged.size() - 1);
                if (last.to == r.from && sameStyle(last.style, r.style)) {
                    last.to = r.to;
                } else {
                    merged.add(new StyleRun(r.from, r.to, r.style));
                }
            }
        }

        // Fill gaps with defaultStyle so coverage is continuous
        ArrayList<StyleRun> covered = new ArrayList<>();
        int cur = 0;
        for (StyleRun r : merged) {
            if (r.from > cur) {
                covered.add(new StyleRun(cur, r.from, defaultStyle));
            }
            // clamp inside [0..len]
            int a = Math.max(0, Math.min(len, r.from));
            int b = Math.max(0, Math.min(len, r.to));
            if (a < b) {
                covered.add(new StyleRun(a, b, r.style));
            }
            cur = b;
        }
        if (cur < len) {
            covered.add(new StyleRun(cur, len, defaultStyle));
        }

        styles.clear();
        styles.addAll(covered);
    }

    /**
     * Apply a style edit to the region [from, to). Example usage:
     * text.applyStyle(a,b, st -> st.withSize(24));
     */
    public void applyStyle(int from, int to, UnaryOperator<Style> edit) {
        from = adjustPos(from);
        to = adjustPos(to);
        if (from >= to) {
            return;
        }

        normalizeStyles(); // ensure coverage + sorted before editing

        ArrayList<StyleRun> out = new ArrayList<>();

        for (StyleRun r : styles) {
            if (r.to <= from || r.from >= to) {
                out.add(r);
                continue;
            }

            if (r.from < from) {
                out.add(new StyleRun(r.from, from, r.style));
            }

            int midFrom = Math.max(r.from, from);
            int midTo = Math.min(r.to, to);
            out.add(new StyleRun(midFrom, midTo, edit.apply(r.style)));

            if (r.to > to) {
                out.add(new StyleRun(to, r.to, r.style));
            }
        }

        styles.clear();
        styles.addAll(out);
        normalizeStyles();
    }

    private void stylesOnDelete(int from, int to) {
        int k = to - from;
        if (k <= 0) {
            return;
        }

        ArrayList<StyleRun> out = new ArrayList<>();

        for (StyleRun r : styles) {
            if (r.to <= from) {
                out.add(r);
                continue;
            }
            if (r.from >= to) {
                out.add(new StyleRun(r.from - k, r.to - k, r.style));
                continue;
            }
            // overlaps deletion
            if (r.from < from) {
                out.add(new StyleRun(r.from, from, r.style));
            }
            if (r.to > to) {
                out.add(new StyleRun(from, r.to - k, r.style));
            }
        }

        styles.clear();
        styles.addAll(out);

        // Ensure at least one run when text is empty
        if (len - k <= 0) {
            styles.clear();
            styles.add(new StyleRun(0, 0, defaultStyle));
        } else {
            normalizeStyles();
        }
    }

    /* ============================================================
   * Construction / file loading
   * ============================================================ */
    public Text(String fn) {
        String raw;
        try (FileInputStream s = new FileInputStream(fn); InputStreamReader r = new InputStreamReader(s)) {

            int available = s.available();
            char[] tmp = new char[Math.max(0, available)];
            int read = r.read(tmp, 0, tmp.length);
            if (read < 0) {
                read = 0;
            }
            raw = new String(tmp, 0, read);

        } catch (IOException e) {
            raw = "";
        }

        // Try META/TEXT parsing; if it fails, fall back to plain text behavior.
        Parsed parsed = tryParseMeta(raw);

        this.originalBuffer = parsed.text;
        this.len = originalBuffer.length();

        pieces.clear();
        if (len > 0) {
            pieces.add(new Piece(BufferKind.ORIGINAL, 0, len, System.currentTimeMillis()));
        }

        // Apply default style + runs
        this.defaultStyle = parsed.defaultStyle != null ? parsed.defaultStyle : this.defaultStyle;

        styles.clear();
        if (parsed.runs != null && !parsed.runs.isEmpty()) {
            for (StyleRun rrun : parsed.runs) {
                // clamp to current length to be safe
                int a = Math.max(0, Math.min(len, rrun.from));
                int b = Math.max(0, Math.min(len, rrun.to));
                if (a < b) {
                    styles.add(new StyleRun(a, b, rrun.style));
                }
            }
        } else {
            styles.add(new StyleRun(0, len, defaultStyle));
        }

        normalizeStyles();
    }

    private static final class Parsed {

        final String text;
        final Style defaultStyle;
        final ArrayList<StyleRun> runs;

        Parsed(String text, Style defaultStyle, ArrayList<StyleRun> runs) {
            this.text = text;
            this.defaultStyle = defaultStyle;
            this.runs = runs;
        }
    }

    private static Parsed tryParseMeta(String raw) {
        // Plain ASCII fallback by default:
        Parsed fallback = new Parsed(raw, null, null);

        if (raw == null || raw.isEmpty()) {
            return fallback;
        }

        // Must start with "META" at the very beginning
        if (!raw.startsWith("META")) {
            return fallback;
        }

        // We parse line-by-line until TEXT, then everything after TEXT's newline is the text.
        // If anything is malformed, we fall back to plain.
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.StringReader(raw));

            String line = br.readLine();
            if (line == null || !line.equals("META")) {
                return fallback;
            }

            Style def = null;
            ArrayList<StyleRun> runs = new ArrayList<>();

            while (true) {
                line = br.readLine();
                if (line == null) {
                    // no TEXT marker => fallback
                    return fallback;
                }
                if (line.equals("TEXT")) {
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }

                // DEFAULT|family|size|bold|italic|underline|strike|r|g|b
                // RUN|from|to|family|size|bold|italic|underline|strike|r|g|b
                String[] parts = line.split("\\|");
                if (parts.length < 1) {
                    continue;
                }

                if (parts[0].equals("DEFAULT")) {
                    if (parts.length != 10) {
                        return fallback;
                    }
                    def = parseStyleParts(parts, 1);
                } else if (parts[0].equals("RUN")) {
                    if (parts.length != 12) {
                        return fallback;
                    }
                    int from = Integer.parseInt(parts[1]);
                    int to = Integer.parseInt(parts[2]);
                    Style st = parseStyleParts(parts, 3);
                    runs.add(new StyleRun(from, to, st));
                } else {
                    // unknown line => ignore or fallback; I choose ignore
                }
            }

            // Read remaining lines as text, preserving newlines exactly as in file after TEXT.
            // br.readLine() strips newlines, so we add '\n' back between lines.
            StringBuilder text = new StringBuilder();
            String tline;
            boolean first = true;
            while ((tline = br.readLine()) != null) {
                if (!first) {
                    text.append("\n");
                }
                text.append(tline);
                first = false;
            }

            return new Parsed(text.toString(), def, runs);

        } catch (Exception ex) {
            return fallback;
        }
    }

    private static Style parseStyleParts(String[] parts, int idx) {
        // parts[idx+0]=family, idx+1=size, idx+2=bold, idx+3=italic, idx+4=underline, idx+5=strike, idx+6=r, idx+7=g, idx+8=b
        String family = parts[idx];
        int size = Integer.parseInt(parts[idx + 1]);
        boolean bold = Integer.parseInt(parts[idx + 2]) != 0;
        boolean italic = Integer.parseInt(parts[idx + 3]) != 0;
        boolean underline = Integer.parseInt(parts[idx + 4]) != 0;
        boolean strike = Integer.parseInt(parts[idx + 5]) != 0;
        int r = Integer.parseInt(parts[idx + 6]);
        int g = Integer.parseInt(parts[idx + 7]);
        int b = Integer.parseInt(parts[idx + 8]);
        java.awt.Color color = new java.awt.Color(clamp255(r), clamp255(g), clamp255(b));

        return new Style(family, size, bold, italic, color, underline, strike);
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    public String getAllText() {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(charAt(i));
        }
        return sb.toString();
    }

    public void saveWithMeta(String fn) throws java.io.IOException {
        normalizeStyles();

        try (java.io.BufferedWriter w = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(fn), java.nio.charset.StandardCharsets.UTF_8))) {

            w.write("META\n");

            // DEFAULT
            w.write(encodeDefault(defaultStyle));
            w.write("\n");

            // RUNS (skip default-only runs if you want smaller files; optional)
            for (StyleRun r : styles) {
                if (r.from >= r.to) {
                    continue;
                }
                w.write(encodeRun(r));
                w.write("\n");
            }

            w.write("TEXT\n");
            w.write(getAllText());
        }
    }

    private static String encodeDefault(Style s) {
        return "DEFAULT|" + encodeStyle(s);
    }

    private static String encodeRun(StyleRun r) {
        return "RUN|" + r.from + "|" + r.to + "|" + encodeStyle(r.style);
    }

    private static String encodeStyle(Style s) {
        return s.family + "|"
                + s.size + "|"
                + (s.bold ? 1 : 0) + "|"
                + (s.italic ? 1 : 0) + "|"
                + (s.underline ? 1 : 0) + "|"
                + (s.strike ? 1 : 0) + "|"
                + s.color.getRed() + "|"
                + s.color.getGreen() + "|"
                + s.color.getBlue();
    }

    public String substring(int from, int to) {
        from = adjustPos(from);
        to = adjustPos(to);
        if (from >= to) {
            return "";
        }
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            sb.append(charAt(i));
        }
        return sb.toString();
    }

    public StyledFragment copyFragment(int from, int to) {
        from = adjustPos(from);
        to = adjustPos(to);
        if (from >= to) {
            return new StyledFragment("", java.util.Collections.emptyList());
        }

        normalizeStyles(); // ensure coverage & sorted

        String txt = substring(from, to);
        java.util.ArrayList<StyledRun> out = new java.util.ArrayList<>();

        for (StyleRun r : styles) {
            if (r.to <= from) {
                continue;
            }
            if (r.from >= to) {
                break;
            }

            int a = Math.max(from, r.from);
            int b = Math.min(to, r.to);
            if (a < b) {
                out.add(new StyledRun(a - from, b - from, r.style));
            }
        }

        if (out.isEmpty()) {
            out.add(new StyledRun(0, txt.length(), defaultStyle));
        }
        return new StyledFragment(txt, out);
    }

    // Force the style over [from,to) to be exactly "style"
    public void setStyleRange(int from, int to, Style style) {
        applyStyle(from, to, st -> style);
    }

    // Insert styled fragment: insert text, then apply runs to inserted region
    public void insertFragment(int pos, StyledFragment frag) {
        if (frag == null || frag.text == null || frag.text.isEmpty()) {
            return;
        }
        pos = adjustPos(pos);

        // Insert the raw text first
        insert(pos, frag.text);

        // Now overwrite styles for the inserted region according to runs
        int base = pos;
        for (StyledRun r : frag.runs) {
            int a = base + Math.max(0, r.from);
            int b = base + Math.min(frag.text.length(), r.to);
            if (a < b) {
                setStyleRange(a, b, r.style);
            }
        }
    }

    // Clamp position into [0..len]
    private int adjustPos(int pos) {
        if (pos < 0) {
            return 0;
        }
        if (pos > len) {
            return len;
        }
        return pos;
    }

    public int length() {
        return len;
    }

    public char charAt(int pos) {
        if (pos < 0 || pos >= len) {
            return '\0';
        }

        int cur = 0;
        for (Piece p : pieces) {
            int next = cur + p.length;
            if (pos < next) {
                int off = pos - cur;
                int idx = p.start + off;
                if (p.buf == BufferKind.ORIGINAL) {
                    return originalBuffer.charAt(idx);
                } else {
                    return addBuffer.charAt(idx);
                }
            }
            cur = next;
        }
        return '\0';
    }

    // Helper describing where a text position lands inside the piece list
    private static final class Location {

        int pieceIndex;    // index into pieces
        int offsetInPiece; // 0..piece.length

        Location(int i, int off) {
            pieceIndex = i;
            offsetInPiece = off;
        }
    }

    // Find the piece index and offset for a logical position.
    // For pos == len, returns (pieces.size(), 0) meaning "append at end".
    private Location locate(int pos) {
        int cur = 0;
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            int next = cur + p.length;
            if (pos < next) {
                return new Location(i, pos - cur);
            }
            cur = next;
        }
        return new Location(pieces.size(), 0);
    }

    // Split piece at (pieceIndex, offset) where offset is within [0..piece.length].
    // After splitting, the insertion point is between the two pieces.
    private void splitAt(Location loc) {
        if (loc.pieceIndex < 0 || loc.pieceIndex >= pieces.size()) {
            return;
        }
        Piece p = pieces.get(loc.pieceIndex);
        int off = loc.offsetInPiece;

        if (off <= 0 || off >= p.length) {
            return; // no split needed
        }
        Piece left = new Piece(p.buf, p.start, off, p.createdAt);
        Piece right = new Piece(p.buf, p.start + off, p.length - off, p.createdAt);

        pieces.set(loc.pieceIndex, left);
        pieces.add(loc.pieceIndex + 1, right);
    }

    private boolean canMerge(Piece a, Piece b) {
        if (a.buf != b.buf) {
            return false;
        }
        return (a.start + a.length) == b.start;
    }

    private void mergeAround(int idx) {
        if (idx < 0) {
            return;
        }

        // merge with previous
        if (idx > 0 && idx < pieces.size()) {
            Piece prev = pieces.get(idx - 1);
            Piece cur = pieces.get(idx);
            if (canMerge(prev, cur)) {
                prev.length += cur.length;
                pieces.remove(idx);
                idx--;
            }
        }

        // merge with next
        if (idx >= 0 && idx + 1 < pieces.size()) {
            Piece cur = pieces.get(idx);
            Piece next = pieces.get(idx + 1);
            if (canMerge(cur, next)) {
                cur.length += next.length;
                pieces.remove(idx + 1);
            }
        }
    }

    private void stylesOnInsert(int pos, int k) {
        if (k <= 0) {
            return;
        }

        // decide what style the inserted text should get
        Style inherit = nextInsertStyle;
        if (inherit == null) {
            inherit = (len - k <= 0) ? defaultStyle : styleAt(Math.max(0, pos - 1));
        }

        ArrayList<StyleRun> out = new ArrayList<>();

        for (StyleRun r : styles) {
            if (r.to <= pos) {
                out.add(r);
                continue;
            }

            if (r.from >= pos) {
                out.add(new StyleRun(r.from + k, r.to + k, r.style));
                continue;
            }

            out.add(new StyleRun(r.from, pos, r.style));
            out.add(new StyleRun(pos + k, r.to + k, r.style));
        }

        out.add(new StyleRun(pos, pos + k, inherit));

        styles.clear();
        styles.addAll(out);
        normalizeStyles();

        nextInsertStyle = null;
    }

    public void insert(int pos, String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        pos = adjustPos(pos);

        int addStart = addBuffer.length();
        addBuffer.append(s);
        Piece ins = new Piece(BufferKind.ADD, addStart, s.length(), System.currentTimeMillis());

        Location loc = locate(pos);
        if (loc.pieceIndex < pieces.size()) {
            splitAt(loc);
        }
        loc = locate(pos);

        int insertIndex = loc.pieceIndex;
        pieces.add(insertIndex, ins);
        len += s.length();
        mergeAround(insertIndex);

        stylesOnInsert(pos, s.length());

        notify(new UpdateEvent(pos, pos, s));
    }

    public void delete(int from, int to) {
        from = adjustPos(from);
        to = adjustPos(to);
        if (from >= to) {
            notify(new UpdateEvent(from, to, null));
            return;
        }

        // Split at boundaries so deletion aligns with whole pieces
        Location locFrom = locate(from);
        if (locFrom.pieceIndex < pieces.size()) {
            splitAt(locFrom);
        }

        Location locTo = locate(to);
        if (locTo.pieceIndex < pieces.size()) {
            splitAt(locTo);
        }

        // Recompute exact indices after splits
        locFrom = locate(from);
        locTo = locate(to);

        int startIdx = locFrom.pieceIndex;
        int endIdx = locTo.pieceIndex; // delete pieces in [startIdx, endIdx)

        int removed = 0;
        for (int i = startIdx; i < endIdx; i++) {
            removed += pieces.get(i).length;
        }

        if (startIdx < endIdx) {
            pieces.subList(startIdx, endIdx).clear();
        }

        // Update styles before changing len? (either order is fine as long as k is correct)
        stylesOnDelete(from, to);

        len -= removed;

        // merge at the junction
        mergeAround(startIdx);

        notify(new UpdateEvent(from, to, null));
    }

    /*-------------------------------------------------------------------
   *  notification of listeners
   *-----------------------------------------------------------------*/
    ArrayList<UpdateEventListener> listeners = new ArrayList<>();

    public void addUpdateEventListener(UpdateEventListener listener) {
        listeners.add(listener);
    }

    public void removeUpdateEventListener(UpdateEventListener listener) {
        listeners.remove(listener);
    }

    private void notify(UpdateEvent e) {
        Iterator<UpdateEventListener> iter = listeners.iterator();
        while (iter.hasNext()) {
            UpdateEventListener listener = iter.next();
            listener.update(e);
        }
    }

    /* Optional: debugging helper (not required by Viewer/Editor) */
    public List<String> debugPieces() {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            Piece p = pieces.get(i);
            out.add(i + ": " + p.buf + " start=" + p.start + " len=" + p.length + " createdAt=" + p.createdAt);
        }
        return out;
    }

    public List<String> debugStyles() {
        ArrayList<String> out = new ArrayList<>();
        for (int i = 0; i < styles.size(); i++) {
            StyleRun r = styles.get(i);
            Style s = r.style;
            out.add(i + ": (" + r.from + ".." + r.to + ") "
                    + s.family + " " + s.size
                    + " bold=" + s.bold
                    + " italic=" + s.italic
                    + " underline=" + s.underline
                    + " strike=" + s.strike
                    + " color=#" + String.format("%02x%02x%02x", s.color.getRed(), s.color.getGreen(), s.color.getBlue()));
        }
        return out;
    }
}
