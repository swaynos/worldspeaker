package org.worldspeaker.bootstrap;

/** Identity check for the original {@code CommandOutput} retained by derived command sources. */
public final class CommandSourceOutputProvenance {
    private CommandSourceOutputProvenance() {}

    /**
     * A player command source uses the issuing player as its output. Vanilla derived sources retain that
     * output when substituting their effective entity, so a different entity is not the original issuer.
     */
    public static boolean outputIsCurrentEntity(Object commandOutput, Object currentEntity) {
        return commandOutput != null && commandOutput == currentEntity;
    }
}
