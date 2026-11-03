package org.worldspeaker.compat;

import java.io.IOException;
import java.util.Objects;

/** Safe loader exception. Only its immutable problem crosses the startup mailbox. */
public class PrivateConfigurationException extends IOException {
    private static final long serialVersionUID = 1L;

    private final PrivateConfigurationProblem problem;

    public PrivateConfigurationException(PrivateConfigurationProblem problem) {
        super(Objects.requireNonNull(problem).safeMessage());
        this.problem = problem;
    }

    public final PrivateConfigurationProblem problem() {
        return problem;
    }
}
