package org.openqa.selenium.tools.javadoc;

import org.openqa.selenium.tools.zip.StableZipEntry;

import javax.tools.DocumentationTool;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class JavadocJarMaker {

  public static void main(String[] args) throws IOException {
    Set<Path> sourceJars = new HashSet<>();
    Path out = null;
    Set<Path> classpath = new HashSet<>();

    for (int i = 0; i < args.length; i++) {
      String flag = args[i];
      String next = args[++i];

      switch (flag) {
        case "--cp":
          classpath.add(Paths.get(next));
          break;

        case "--in":
          sourceJars.add(Paths.get(next));
          break;

        case "--out":
          out = Paths.get(next);
          break;
      }
    }

    if (sourceJars.isEmpty()) {
      throw new IllegalArgumentException("At least one input just must be specified via the --in flag");
    }

    if (out == null) {
      throw new IllegalArgumentException("The output jar location must be specified via the --out flag");
    }

    Set<Path> tempDirs = new HashSet<>();
    Path dir = Files.createTempDirectory("javadocs");
    tempDirs.add(dir);

    try {
      DocumentationTool tool = ToolProvider.getSystemDocumentationTool();
      try (StandardJavaFileManager fileManager = tool.getStandardFileManager(null, Locale.getDefault(), StandardCharsets.UTF_8)) {
        fileManager.setLocation(DocumentationTool.Location.DOCUMENTATION_OUTPUT, List.of(dir.toFile()));

        Set<JavaFileObject> sources = new HashSet<>();
        Set<String> topLevelPackages = new HashSet<>();

        Path unpackTo = Files.createTempDirectory("unpacked-sources");
        tempDirs.add(unpackTo);
        Set<String> fileNames = new HashSet<>();
        readSourceFiles(unpackTo, fileManager, sourceJars, sources, topLevelPackages, fileNames);
        List<String> options = new ArrayList<>(List.of("-html5", "-notimestamp", "-use", "-quiet", "-Xdoclint:-missing", "-encoding", "UTF8"));
        if (!classpath.isEmpty()) {
          options.add("-cp");
          options.add(classpath.stream().map(Path::toAbsolutePath).map(String::valueOf).collect(Collectors.joining(File.pathSeparator)));
        }

        Path outputTo = Files.createTempDirectory("output-dir");
        tempDirs.add(outputTo);

        options.addAll(List.of("-d", outputTo.toAbsolutePath().toString()));

        sources.forEach(obj -> options.add(obj.getName()));

        Writer writer = new StringWriter();
        DocumentationTool.DocumentationTask task = tool.getTask(writer, fileManager, null, null, options, sources);
        Boolean result = task.call();
        if (result == null || !result) {
          System.err.println(writer);
          return;
        }

        try (OutputStream os = Files.newOutputStream(out);
             ZipOutputStream zos = new ZipOutputStream(os)) {
          Files.walk(outputTo)
            .sorted(Comparator.naturalOrder())
            .forEachOrdered(path -> {
              if (path.equals(outputTo)) {
                return;
              }

              try {
                if (Files.isDirectory(path)) {
                  String name = outputTo.relativize(path) + "/";
                  ZipEntry entry = new StableZipEntry(name);
                  zos.putNextEntry(entry);
                  zos.closeEntry();
                } else {
                  String name = outputTo.relativize(path).toString();
                  ZipEntry entry = new StableZipEntry(name);
                  zos.putNextEntry(entry);
                  try (InputStream is = Files.newInputStream(path)) {
                    is.transferTo(zos);
                  }
                  zos.closeEntry();
                }
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
            });
        }
      }
    } finally {
      tempDirs.forEach(d -> {
        try {
          Files.walk(d)
            .sorted(Comparator.reverseOrder())
            .map(Path::toFile)
            .forEach(File::delete);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }
  }

  private static void readSourceFiles(
    Path unpackTo,
    StandardJavaFileManager fileManager,
    Set<Path> sourceJars,
    Set<JavaFileObject> sources,
    Set<String> topLevelPackages,
    Set<String> fileNames) throws IOException {

    for (Path jar : sourceJars) {
      if (!Files.exists(jar)) {
        continue;
      }

      try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(jar))) {
        for (ZipEntry entry = zis.getNextEntry(); entry != null; entry = zis.getNextEntry()) {
          String name = entry.getName();
          if (!name.endsWith(".java")) {
            continue;
          }

          Path target = unpackTo.resolve(name).normalize();
          if (!target.startsWith(unpackTo)) {
            throw new IOException("Attempt to write out of working directory");
          }

          Files.createDirectories(target.getParent());
          try (OutputStream out = Files.newOutputStream(target)) {
            zis.transferTo(out);
          }

          fileManager.getJavaFileObjects(target).forEach(sources::add);

          String[] segments = name.split("/");
          if (segments.length > 0 && !"META-INF".equals(segments[0])) {
            topLevelPackages.add(segments[0]);
          }

          fileNames.add(name);
        }
      }
    }
  }
}
