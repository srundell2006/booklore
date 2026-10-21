package org.booklore.service.library.importer;

import org.booklore.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ImportSettingsTest {

    private static final long MIB = 1024 * 1024;
    private static final long TWO_GIB = 2048 * MIB;

    private final AppProperties.Scan scan = new AppProperties.Scan();

    @Test
    void defaults() {
        ImportSettings settings = ImportSettings.from(scan, 8, TWO_GIB);

        assertThat(settings).isEqualTo(new ImportSettings(true, 16, 64, Duration.ofSeconds(180), 16, 4, 512 * MIB));
    }

    @Test
    void pipelineCanBeTurnedOff() {
        scan.setImportPipelineEnabled(false);

        assertThat(ImportSettings.from(scan, 8, TWO_GIB).pipelineEnabled()).isFalse();
    }

    @Test
    void defaultWindowIsFourTimesTheIoParallelism() {
        scan.setImportIoParallelism(32);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB).window()).isEqualTo(128);

        scan.setImportIoParallelism(128);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB).window()).isEqualTo(512);
    }

    @Test
    void defaultRenderParallelismIsHalfTheCoresBetweenOneAndFour() {
        assertThat(ImportSettings.from(scan, 1, TWO_GIB).renderParallelism()).isEqualTo(1);
        assertThat(ImportSettings.from(scan, 4, TWO_GIB).renderParallelism()).isEqualTo(2);
        assertThat(ImportSettings.from(scan, 7, TWO_GIB).renderParallelism()).isEqualTo(3);
        assertThat(ImportSettings.from(scan, 64, TWO_GIB).renderParallelism()).isEqualTo(4);
    }

    @Test
    void defaultHeapBudgetIsAQuarterOfTheHeapBetween128And1024Mib() {
        assertThat(ImportSettings.from(scan, 8, 256 * MIB).heapBudgetBytes()).isEqualTo(128 * MIB);
        assertThat(ImportSettings.from(scan, 8, 3072 * MIB).heapBudgetBytes()).isEqualTo(768 * MIB);
        assertThat(ImportSettings.from(scan, 8, 16384 * MIB).heapBudgetBytes()).isEqualTo(1024 * MIB);
        // A JVM without a heap limit reports Long.MAX_VALUE
        assertThat(ImportSettings.from(scan, 8, Long.MAX_VALUE).heapBudgetBytes()).isEqualTo(1024 * MIB);
    }

    @Test
    void explicitHeapBudgetIsClampedToBetween64MibAndHalfTheHeap() {
        scan.setImportHeapBudgetMb(300);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB).heapBudgetBytes()).isEqualTo(300 * MIB);

        scan.setImportHeapBudgetMb(32);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB).heapBudgetBytes()).isEqualTo(64 * MIB);

        scan.setImportHeapBudgetMb(4096);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB).heapBudgetBytes()).isEqualTo(1024 * MIB);

        // Half of a heap this small is under the minimum, which then wins
        scan.setImportHeapBudgetMb(100);
        assertThat(ImportSettings.from(scan, 8, 100 * MIB).heapBudgetBytes()).isEqualTo(64 * MIB);
    }

    @Test
    void explicitValuesOutOfRangeAreClamped() {
        scan.setImportIoParallelism(500);
        scan.setImportWindow(10_000);
        scan.setImportFileTimeoutSeconds(99_999);
        scan.setImportMaxStuckThreads(500);
        scan.setImportRenderParallelism(99);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB))
                .isEqualTo(new ImportSettings(true, 128, 512, Duration.ofSeconds(3600), 128, 16, 512 * MIB));

        scan.setImportIoParallelism(0);
        scan.setImportWindow(-3);
        scan.setImportFileTimeoutSeconds(1);
        scan.setImportMaxStuckThreads(0);
        scan.setImportRenderParallelism(-1);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB))
                .isEqualTo(new ImportSettings(true, 1, 1, Duration.ofSeconds(10), 1, 1, 512 * MIB));
    }

    @Test
    void explicitWindowIsAtLeastTheIoParallelism() {
        scan.setImportIoParallelism(16);
        scan.setImportWindow(4);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB).window()).isEqualTo(16);

        scan.setImportWindow(40);
        assertThat(ImportSettings.from(scan, 8, TWO_GIB).window()).isEqualTo(40);
    }
}
