package seek;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import scrape.ScrapeJava;

public class FileWalker {

    public static void processProjectFiles(String projectRoot) throws IOException {
        System.out.println("FileWalker: scanning " + projectRoot);
        Path startPath = Paths.get(projectRoot);
        File projectDir = startPath.toFile();

        ScrapeJava.initializeTypeSolver(projectDir);
        // optionally can always clean graph here before start...

        Files.walkFileTree(startPath, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {

                File dirObj = dir.toFile();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                try {
                    File f = file.toFile();
                    String fileName = f.getName();
                    String extension = getFileExtension(fileName);

                    if ("java".equalsIgnoreCase(extension)) {
                        ScrapeJava.processJavaFile(f);
                    }
                    System.out.println("Processed " + fileName);
                } catch (Exception e) {
                    System.err.println("Error processing file " + file.getFileName() + ": " + e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                System.err.println("Failed to access: " + file + " => " + exc.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf(".");
        return (lastDotIndex == -1) ? null : fileName.substring(lastDotIndex + 1).toLowerCase();
    }
}
