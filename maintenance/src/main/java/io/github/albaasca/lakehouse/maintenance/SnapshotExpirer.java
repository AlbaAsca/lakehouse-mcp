package io.github.albaasca.lakehouse.maintenance;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;

/** Expires the snapshots of a table and deletes the files that only they referenced. */
final class SnapshotExpirer {

    /** Never goes below this: the current snapshot stays, however old it is. */
    private static final int MIN_SNAPSHOTS = 1;

    private SnapshotExpirer() {}

    /** Removes the snapshots older than {@code cutoff}; one taken exactly at that moment stays. */
    static Result expire(Catalog catalog, TableIdentifier id, Instant cutoff) {
        Table table = catalog.loadTable(id);
        int before = countSnapshots(table);
        checkStoreIsReachable(table);
        TrackedDeletes deletes = new TrackedDeletes(table.io()::deleteFile);
        table.expireSnapshots()
                .expireOlderThan(cutoff.toEpochMilli())
                .retainLast(MIN_SNAPSHOTS)
                .deleteWith(deletes)
                .commit();
        table.refresh();
        return new Result(before, countSnapshots(table), deletes.deleted().size(), deletes.failed());
    }

    /**
     * Iceberg commits first and cleans up afterwards. If the store cannot be read at that point, it only logs warnings:
     * the snapshots are gone, the run looks fine and their files stay forever. So read from it before committing.
     */
    private static void checkStoreIsReachable(Table table) {
        Snapshot current = table.currentSnapshot();
        if (current != null) {
            current.allManifests(table.io());
        }
    }

    private static int countSnapshots(Table table) {
        int count = 0;
        for (Snapshot ignored : table.snapshots()) {
            count++;
        }
        return count;
    }

    /** {@code failedDeletes} are files left in the store with no reference: nothing will ever delete them. */
    record Result(int snapshotsBefore, int snapshotsAfter, int filesDeleted, Set<String> failedDeletes) {

        String summary() {
            return "snapshots: " + snapshotsBefore + " -> " + snapshotsAfter + ", files deleted: " + filesDeleted
                    + ", files that could not be deleted: " + failedDeletes.size();
        }
    }

    /**
     * Without this, Iceberg only logs a delete that fails: the commit has already happened and the run looks fine. It
     * retries each failure, so a file only counts as failed if its last attempt failed.
     */
    static final class TrackedDeletes implements Consumer<String> {

        private final Consumer<String> delete;
        private final Set<String> deleted = ConcurrentHashMap.newKeySet();
        private final Set<String> failed = ConcurrentHashMap.newKeySet();

        TrackedDeletes(Consumer<String> delete) {
            this.delete = delete;
        }

        @Override
        public void accept(String path) {
            try {
                delete.accept(path);
            } catch (RuntimeException e) {
                failed.add(path);
                throw e;
            }
            failed.remove(path);
            deleted.add(path);
        }

        Set<String> deleted() {
            return Collections.unmodifiableSet(deleted);
        }

        Set<String> failed() {
            return Collections.unmodifiableSet(failed);
        }
    }
}
