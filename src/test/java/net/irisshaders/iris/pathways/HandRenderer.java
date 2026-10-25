package net.irisshaders.iris.pathways;

/**
 * Headless test-classpath facade for Iris's hand renderer.
 *
 * <p>The real {@code HandRenderer} constructs a
 * {@code FeatureRenderDispatcher} from {@code Minecraft.getInstance()} in its
 * constructor; that instance is null outside a running client, so mapping
 * tests cannot load the real class. Tests only need the neutral "hand not
 * rendering" answer, which this facade always provides.</p>
 */
public class HandRenderer {
    public static final HandRenderer INSTANCE = new HandRenderer();

    public boolean isActive() {
        return false;
    }

    public boolean isRenderingSolid() {
        return false;
    }
}
