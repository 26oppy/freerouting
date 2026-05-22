package app.freerouting.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecentFilesTest {

  @Test
  void rememberRecentFile_movesDuplicatesToFrontAndCapsHistory() throws Exception {
    List<String> recentFiles = new ArrayList<>();
    Path tempDirectory = Files.createTempDirectory("recent-files-test");

    File firstFile = Files.createTempFile(tempDirectory, "first", ".dsn").toFile();
    File secondFile = Files.createTempFile(tempDirectory, "second", ".dsn").toFile();

    assertTrue(RecentFiles.rememberRecentFile(recentFiles, firstFile));
    assertTrue(RecentFiles.rememberRecentFile(recentFiles, secondFile));
    assertTrue(RecentFiles.rememberRecentFile(recentFiles, firstFile));

    assertEquals(firstFile.getAbsolutePath(), recentFiles.get(0));
    assertEquals(secondFile.getAbsolutePath(), recentFiles.get(1));

    for (int i = 0; i < 20; i++) {
      assertTrue(RecentFiles.rememberRecentFile(recentFiles,
          Files.createTempFile(tempDirectory, "extra-" + i, ".dsn").toFile()));
    }

    assertTrue(recentFiles.size() <= RecentFiles.MAX_RECENT_FILES);
  }

  @Test
  void pruneMissingFilesRemovesDeletedEntries() throws Exception {
    Path tempDirectory = Files.createTempDirectory("recent-files-prune-test");
    File existingFile = Files.createTempFile(tempDirectory, "existing", ".dsn").toFile();
    File deletedFile = Files.createTempFile(tempDirectory, "deleted", ".dsn").toFile();

    List<String> recentFiles = new ArrayList<>();
    RecentFiles.rememberRecentFile(recentFiles, existingFile);
    RecentFiles.rememberRecentFile(recentFiles, deletedFile);
    assertTrue(deletedFile.delete());

    assertTrue(RecentFiles.pruneMissingFiles(recentFiles));
    assertEquals(1, recentFiles.size());
    assertEquals(existingFile.getAbsolutePath(), recentFiles.get(0));

    List<File> filteredFiles = RecentFiles.getRecentFiles(recentFiles);
    assertEquals(1, filteredFiles.size());
    assertEquals(existingFile.getAbsolutePath(), filteredFiles.get(0).getAbsolutePath());
    assertTrue(filteredFiles.get(0).exists());
  }
}