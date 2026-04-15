import java.util.Objects;
import soot.SootField;

/**
 * Key into the abstract heap: (base object, field).
 *
 * A null {@code field} represents an array element — since we do not track
 * indices, all elements of a given array object share one heap slot.
 */
public class HeapKey {
    final AbstractObject base;
    final SootField field;          // null = array element

    HeapKey(AbstractObject base, SootField field) {
        this.base = base;
        this.field = field;
    }

    /** Convenience factory for array-element keys. */
    static HeapKey arrayOf(AbstractObject base) {
        return new HeapKey(base, null);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HeapKey)) return false;
        HeapKey k = (HeapKey) o;
        return base.equals(k.base) && Objects.equals(field, k.field);
    }

    @Override
    public int hashCode() {
        return 31 * base.hashCode() + Objects.hashCode(field);
    }
}
