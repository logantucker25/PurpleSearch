package scrape;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class SourceRootFinder {

    public static List<File> findAllDirectories(File root) {
        List<File> directories = new ArrayList<>();
        if (root != null && root.exists()) {
            searchDirectories(root, directories);
        }
        return directories;
    }

    private static void searchDirectories(File file, List<File> directories) {
        if (file.isDirectory()) {
            directories.add(file);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    searchDirectories(child, directories);
                }
            }
        }
    }
}
