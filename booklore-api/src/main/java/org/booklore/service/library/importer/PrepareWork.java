package org.booklore.service.library.importer;

import org.booklore.service.fileprocessor.prepare.PrepareContext;

/** The work of one import task, run on an import thread with the task's context. */
@FunctionalInterface
public interface PrepareWork<T> {

    T run(PrepareContext ctx) throws Exception;
}
