package net.resheim.timekeeper.server;

import java.io.IOException;

/** A private derived source index advanced by the shared bounded cache worker. */
interface NotesIndexReplay extends AutoCloseable {
    NotesLuceneIndex index();
    boolean caughtUp(NotesSearchSources.State state);
    NotesSearchReplay.Step advance(NotesSearchReplay.Budget budget)throws IOException;
    @Override void close()throws IOException;
}
