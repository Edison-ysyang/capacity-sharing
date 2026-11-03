package org.matsim.run.ismt;

/** Standard scientific artifacts for one already-executed iteration. No simulation or inference is performed. */
public interface RunArtifactWriter {
    void writeIteration(int iteration);
}
