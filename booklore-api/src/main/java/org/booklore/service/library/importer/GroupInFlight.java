package org.booklore.service.library.importer;

import org.booklore.service.fileprocessor.prepare.PreparedAttachment;
import org.booklore.service.fileprocessor.prepare.PreparedBookFile;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A planned group whose files have been handed to the {@link ImportExecutor} and that is not saved yet. */
final class GroupInFlight {

    final GroupPlan plan;
    /** Reads the primary file; null when the processor reads it while saving it. */
    FileTask<PreparedBookFile> primary;
    /** Read the additional files, in the plan's order. */
    final List<FileTask<PreparedAttachment>> attachments = new ArrayList<>();
    /** Set once the primary file ended without being read, so its additional files are not read either. */
    final AtomicBoolean doomed = new AtomicBoolean();

    GroupInFlight(GroupPlan plan) {
        this.plan = plan;
    }

    /** Skips the group's files that have not started reading. Running ones finish or time out by themselves. */
    void cancel() {
        doomed.set(true);
        if (primary != null) {
            primary.cancel();
        }
        attachments.forEach(FileTask::cancel);
    }
}
