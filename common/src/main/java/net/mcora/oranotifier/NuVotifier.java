// Builds NuVotifier's Vote object by reflection, so OraNotifier works with or without NuVotifier installed and
// needs no compile-time dependency on it. The event class differs per platform (see each module).
package net.mcora.oranotifier;

public final class NuVotifier {
    private NuVotifier() {}

    /** A com.vexsoftware.votifier.model.Vote, or null when NuVotifier isn't installed. */
    public static Object vote(String service, String player, long timestamp) {
        try {
            Class<?> cls = Class.forName("com.vexsoftware.votifier.model.Vote");
            return cls.getConstructor(String.class, String.class, String.class, String.class)
                    .newInstance(service, player, "mcora.net", String.valueOf(timestamp / 1000));
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** new <eventClass>(vote), or null when that class doesn't exist here. */
    public static Object event(String eventClass, Object vote, ClassLoader loader) {
        try {
            Class<?> cls = Class.forName(eventClass, true, loader);
            return cls.getConstructor(vote.getClass()).newInstance(vote);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }
}
