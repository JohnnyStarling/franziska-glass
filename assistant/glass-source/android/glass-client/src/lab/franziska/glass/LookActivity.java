package lab.franziska.glass;

/** Explicit Glass voice command for a single visual question. */
public final class LookActivity extends GlassActivity {
    @Override protected boolean assistantEntry() { return true; }
    @Override protected boolean photoEntry() { return true; }
}
