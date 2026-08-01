package jp.co.sdcj.workflow.config;

/** Aggregated outcome of an idempotent seed run. */
public final class SeedReport {

    private int created;
    private int existing;
    private int updated;
    private int failed;

    public void created() {
        created++;
    }

    public void existing() {
        existing++;
    }

    public void updated() {
        updated++;
    }

    public void failed() {
        failed++;
    }

    public int createdCount() {
        return created;
    }

    public int existingCount() {
        return existing;
    }

    public int updatedCount() {
        return updated;
    }

    public int failedCount() {
        return failed;
    }
}
