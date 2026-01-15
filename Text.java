import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.IOException;

interface UpdateEventListener {
  void update(UpdateEvent e);
}

class UpdateEvent {  // [from..to[ was replaced by text
  int from;
  int to;
  String text;
  UpdateEvent(int a, int b, String t) { from = a; to = b; text = t; }
}

/*******************************************************************
 *   Piece list / piece table
 *******************************************************************/
public class Text {

  // Immutable original file content
  private final String originalBuffer;
  // Append-only buffer for inserted text
  private final StringBuilder addBuffer = new StringBuilder();

  private enum BufferKind { ORIGINAL, ADD }

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

  public Text(String fn) {
    String fileText;
    try {
      FileInputStream s = new FileInputStream(fn);
      InputStreamReader r = new InputStreamReader(s);
      int available = s.available();
      char[] tmp = new char[Math.max(0, available)];
      int read = r.read(tmp, 0, tmp.length);
      r.close(); s.close();
      if (read < 0) read = 0;
      fileText = new String(tmp, 0, read);
    } catch (IOException e) {
      fileText = "";
    }

    originalBuffer = fileText;
    len = originalBuffer.length();
    if (len > 0) {
      pieces.add(new Piece(BufferKind.ORIGINAL, 0, len, System.currentTimeMillis()));
    }
  }

  // Clamp position into [0..len]
  private int adjustPos(int pos) {
    if (pos < 0) return 0;
    if (pos > len) return len;
    return pos;
  }

  public int length() {
    return len;
  }

  public char charAt(int pos) {
    if (pos < 0 || pos >= len) return '\0';

    int cur = 0;
    for (Piece p : pieces) {
      int next = cur + p.length;
      if (pos < next) {
        int off = pos - cur;
        int idx = p.start + off;
        if (p.buf == BufferKind.ORIGINAL) return originalBuffer.charAt(idx);
        else return addBuffer.charAt(idx);
      }
      cur = next;
    }
    return '\0';
  }

  // Helper describing where a text position lands inside the piece list
  private static final class Location {
    int pieceIndex;   // index into pieces
    int offsetInPiece; // 0..piece.length
    Location(int i, int off) { pieceIndex = i; offsetInPiece = off; }
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
    if (loc.pieceIndex < 0 || loc.pieceIndex >= pieces.size()) return;
    Piece p = pieces.get(loc.pieceIndex);
    int off = loc.offsetInPiece;

    if (off <= 0 || off >= p.length) return; // no split needed

    // left piece keeps original createdAt (fine for debugging)
    Piece left = new Piece(p.buf, p.start, off, p.createdAt);
    // right piece: same buffer, shifted start, remaining length
    Piece right = new Piece(p.buf, p.start + off, p.length - off, p.createdAt);

    pieces.set(loc.pieceIndex, left);
    pieces.add(loc.pieceIndex + 1, right);
  }

  private boolean canMerge(Piece a, Piece b) {
    if (a.buf != b.buf) return false;
    // contiguous in same underlying buffer
    return (a.start + a.length) == b.start;
  }

  private void mergeAround(int idx) {
    // merge idx with idx-1 and idx+1 if possible
    if (idx < 0) return;

    // merge with previous
    if (idx > 0 && idx < pieces.size()) {
      Piece prev = pieces.get(idx - 1);
      Piece cur = pieces.get(idx);
      if (canMerge(prev, cur)) {
        prev.length += cur.length;
        pieces.remove(idx);
        idx--; // current is now at idx
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

  public void insert(int pos, String s) {
    if (s == null || s.isEmpty()) return;
    pos = adjustPos(pos);

    // 1) Append inserted text to addBuffer
    int addStart = addBuffer.length();
    addBuffer.append(s);
    Piece ins = new Piece(BufferKind.ADD, addStart, s.length(), System.currentTimeMillis());

    // 2) Find insertion location and split piece if needed
    Location loc = locate(pos);
    if (loc.pieceIndex < pieces.size()) {
      splitAt(loc);
      // After split, insertion is at loc.pieceIndex + (offset>0 ? 1 : 0)
      // But splitAt only splits when 0<off<length; then insertion point is between left and right,
      // which is at loc.pieceIndex+1.
      if (loc.offsetInPiece > 0 && loc.offsetInPiece < pieces.get(loc.pieceIndex).length) {
        // not reachable because splitAt changed it, but harmless
      }
    }

    // Re-locate after potential split to get correct insertion index
    loc = locate(pos);
    int insertIndex = loc.pieceIndex;

    pieces.add(insertIndex, ins);
    len += s.length();

    // 3) Merge with neighbors if possible
    mergeAround(insertIndex);

	System.out.println("INSERT at " + pos + " text='" + s.replace("\n","\\n").replace("\r","\\r") + "'");
	for (String p : debugPieces()) System.out.println("  " + p);

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
    if (locFrom.pieceIndex < pieces.size()) splitAt(locFrom);

    Location locTo = locate(to);
    if (locTo.pieceIndex < pieces.size()) splitAt(locTo);

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
}
