package app.freerouting.gui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class RecentFiles {

  static final int MAX_RECENT_FILES = 10;

  private RecentFiles() {
  }

  static boolean rememberRecentFile(List<String> recentFilePaths, File file) {
    if ((recentFilePaths == null) || (file == null)) {
      return false;
    }

    String normalizedPath = normalizePath(file.getPath());
    if (normalizedPath == null) {
      return false;
    }

    removeRecentFile(recentFilePaths, file);
    recentFilePaths.add(0, normalizedPath);
    if (recentFilePaths.size() > MAX_RECENT_FILES) {
      recentFilePaths.subList(MAX_RECENT_FILES, recentFilePaths.size()).clear();
    }

    return true;
  }

  static boolean removeRecentFile(List<String> recentFilePaths, File file) {
    if ((recentFilePaths == null) || (file == null)) {
      return false;
    }

    String normalizedPath = normalizePath(file.getPath());
    if (normalizedPath == null) {
      return false;
    }

    boolean changed = false;
    for (Iterator<String> iterator = recentFilePaths.iterator(); iterator.hasNext();) {
      String candidatePath = normalizePath(iterator.next());
      if (normalizedPath.equals(candidatePath)) {
        iterator.remove();
        changed = true;
      }
    }
    return changed;
  }

  static boolean pruneMissingFiles(List<String> recentFilePaths) {
    if (recentFilePaths == null) {
      return false;
    }

    boolean changed = false;
    for (Iterator<String> iterator = recentFilePaths.iterator(); iterator.hasNext();) {
      String normalizedPath = normalizePath(iterator.next());
      if ((normalizedPath == null) || !Files.exists(Path.of(normalizedPath))) {
        iterator.remove();
        changed = true;
      }
    }
    return changed;
  }

  static List<File> getRecentFiles(List<String> recentFilePaths) {
    List<File> recentFiles = new ArrayList<>();
    if (recentFilePaths == null) {
      return recentFiles;
    }

    for (String recentFilePath : recentFilePaths) {
      String normalizedPath = normalizePath(recentFilePath);
      if ((normalizedPath == null) || !Files.exists(Path.of(normalizedPath))) {
        continue;
      }
      recentFiles.add(Path.of(normalizedPath).toFile());
    }

    return recentFiles;
  }

  private static String normalizePath(String pathText) {
    if ((pathText == null) || pathText.isBlank()) {
      return null;
    }

    try {
      return Path.of(pathText).toAbsolutePath().normalize().toString();
    } catch (RuntimeException _) {
      return null;
    }
  }
}