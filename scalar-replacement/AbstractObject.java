import soot.Unit;

/**
 * Abstract representation of a heap object for pointer analysis.
 *
 * Three kinds exist:
 *   - AllocObject  — identified by its allocation site (a Jimple Unit).
 *   - ParamObject  — stands for an unknown external object (method parameters,
 *                     return values, static field loads).  Each instance has a
 *                     unique id so different unknowns stay distinguishable.
 *   - NULL         — singleton representing the null reference.
 */
public abstract class AbstractObject {

    /** Singleton representing null.  Identity-based equality. */
    static final AbstractObject NULL = new AbstractObject() {
        @Override public String toString() { return "Null"; }
    };
}

/** Heap object identified by its allocation site. */
class AllocObject extends AbstractObject {
    final Unit site;

    AllocObject(Unit site) { this.site = site; }

    @Override
    public boolean equals(Object o) {
        return o instanceof AllocObject && site.equals(((AllocObject) o).site);
    }

    @Override public int hashCode() { return site.hashCode(); }
    @Override public String toString() { return "Alloc@" + site.getJavaSourceStartLineNumber(); }
}

/**
 * Placeholder for an unknown external object.
 *
 * Used for method parameters at entry, method return values, and values loaded
 * from static fields.  Each instance gets a unique id so distinct unknowns are
 * never accidentally merged.
 */
class ParamObject extends AbstractObject {
    private static int nextId = 0;
    final int id;

    ParamObject() { this.id = nextId++; }

    @Override
    public boolean equals(Object o) {
        return o instanceof ParamObject && id == ((ParamObject) o).id;
    }

    @Override public int hashCode() { return Integer.hashCode(id); }
    @Override public String toString() { return "Param[" + id + "]"; }
}
