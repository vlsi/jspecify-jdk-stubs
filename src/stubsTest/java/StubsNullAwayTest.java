import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.zip.ZipEntry.DEFLATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * NullAway reads the JSpecify annotations of every stubs jar in {@code -DstubsDir} when the jar is
 * put in front of the platform classes the way the README says: {@code --patch-module java.base}
 * from release 9 on, and {@code -bootclasspath} for release 8. A release newer than the JDK that
 * runs the test is skipped, because its javac cannot compile for it.
 */
class StubsNullAwayTest {
  private static final Pattern JAR_NAME = Pattern.compile("jspecify-jdk-stubs-jdk(\\d+)\\.jar");

  @TempDir static Path tempDir;

  static Stream<Arguments> stubsJars() throws IOException {
    var dir = Path.of(Objects.requireNonNull(
        System.getProperty("stubsDir"), "-DstubsDir must name the directory with the stubs jars"));
    var jars = new ArrayList<Arguments>();
    try (var files = Files.list(dir)) {
      for (var jar : files.toList()) {
        var matcher = JAR_NAME.matcher(jar.getFileName().toString());
        if (matcher.matches()) {
          jars.add(arguments(Integer.parseInt(matcher.group(1)), named(jar.getFileName().toString(), jar)));
        }
      }
    }
    if (jars.isEmpty()) {
      throw new IllegalStateException("No jspecify-jdk-stubs-jdk<N>.jar in " + dir);
    }
    jars.sort((a, b) -> Integer.compare((int) a.get()[0], (int) b.get()[0]));
    return jars.stream();
  }

  @ParameterizedTest
  @MethodSource("stubsJars")
  void aNullableReturnIsReportedOnlyWhereTheStubsSayNullable(int release, Path jar) {
    assertEquals(
        List.of("return file.getParent().length(); -> dereferenced expression 'file.getParent()' is @Nullable"),
        nullAway(release, jar, """
            import java.io.File;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              int parent(File file) {
                return file.getParent().length();
              }
              int name(File file) {
                return file.getName().length();
              }
            }
            """));
  }

  @ParameterizedTest
  @MethodSource("stubsJars")
  void nullIsReportedOnlyForAParameterTheStubsLeaveNonNull(int release, Path jar) {
    assertEquals(
        List.of("return s.concat(null); -> passing @Nullable parameter 'null' where @NonNull is required"),
        nullAway(release, jar, """
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              String concat(String s) {
                return s.concat(null);
              }
              String valueOf() {
                return String.valueOf((Object) null);
              }
            }
            """));
  }

  /** Shows that the reports of the other tests come from the stubs and not from NullAway itself. */
  @ParameterizedTest
  @MethodSource("stubsJars")
  void withoutTheStubsNeitherIsReported(int release, Path jar) {
    assertEquals(
        List.of(),
        nullAway(release, null, """
            import java.io.File;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              int parent(File file) {
                return file.getParent().length();
              }
              String concat(String s) {
                return s.concat(null);
              }
            }
            """));
  }

  /**
   * Compiles {@code source} for {@code release} with NullAway in JSpecify mode, with {@code
   * stubsJar} in front of the platform classes unless it is null. Returns each NullAway report as
   * {@code <source line> -> <message>}, and any other error as {@code ERROR -> <message>}.
   */
  private static List<String> nullAway(int release, Path stubsJar, String source) {
    int jdk = Runtime.version().feature();
    assumeTrue(release <= jdk, () -> "javac " + jdk + " cannot compile for release " + release);
    var classPath = System.getProperty("java.class.path");
    var options = new ArrayList<>(List.of(
        "-Xlint:-options",
        "-proc:none",
        "-d", tempDir.resolve("classes").toString(),
        "-classpath", classPath,
        "-processorpath", classPath,
        "-XDcompilePolicy=simple",
        "--should-stop=ifError=FLOW",
        "-XDaddTypeAnnotationsToSymbol=true",
        "-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR"
            + " -XepOpt:NullAway:OnlyNullMarked=true -XepOpt:NullAway:JSpecifyMode=true"));
    if (release == 8) {
      // javac rejects --release together with -bootclasspath, and -bootclasspath replaces the
      // platform classes, so it carries the API of release 8 behind the stubs.
      var bootClassPath = release8Api().toString();
      if (stubsJar != null) {
        bootClassPath = stubsJar + File.pathSeparator + bootClassPath;
      }
      options.addAll(List.of("-source", "8", "-target", "8", "-bootclasspath", bootClassPath));
    } else {
      options.addAll(List.of("--release", Integer.toString(release)));
      if (stubsJar != null) {
        options.addAll(List.of("--patch-module", "java.base=" + stubsJar));
      }
    }

    var javac = ToolProvider.getSystemJavaCompiler();
    var diagnostics = new DiagnosticCollector<JavaFileObject>();
    var file = new SimpleJavaFileObject(URI.create("string:///Test.java"), JavaFileObject.Kind.SOURCE) {
      @Override
      public CharSequence getCharContent(boolean ignoreEncodingErrors) {
        return source;
      }
    };
    try (var fileManager = javac.getStandardFileManager(diagnostics, Locale.ROOT, UTF_8)) {
      javac.getTask(null, fileManager, diagnostics, options, null, List.of(file)).call();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    var lines = source.lines().toList();
    // Warnings other than NullAway's are left out: before JDK 27, javac compiling for release 8
    // warns that the jspecify jar names ElementType.MODULE, which release 8 lacks. Every error is
    // kept, so a compilation that fails for another reason does not pass as one with no reports.
    var nullAwayPrefix = "[NullAway] ";
    return diagnostics.getDiagnostics().stream()
        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR || d.getMessage(Locale.ROOT).startsWith(nullAwayPrefix))
        .map(d -> {
          var message = d.getMessage(Locale.ROOT);
          if (!message.startsWith(nullAwayPrefix)) {
            return d.getKind() + " -> " + message;
          }
          return lines.get((int) d.getLineNumber() - 1).strip()
              + " -> "
              + message.lines().findFirst().orElseThrow().substring(nullAwayPrefix.length());
        })
        .toList();
  }

  /**
   * Returns a jar of the release 8 classes in the {@code lib/ct.sym} of the running JDK, written
   * on the first call.
   */
  private static synchronized Path release8Api() {
    var jar = tempDir.resolve("release8-api.jar");
    if (Files.exists(jar)) {
      return jar;
    }
    // An entry is <releases>/<module>/<path>.sig, where each character of <releases> is a release
    // number in base 36.
    var entryName = Pattern.compile("[0-9A-Z@]*8[0-9A-Z@]*/[^/]+/(.+)\\.sig");
    var ctSym = Path.of(System.getProperty("java.home"), "lib", "ct.sym");
    try (var in = new ZipFile(ctSym.toFile());
        var out = new ZipOutputStream(Files.newOutputStream(jar))) {
      out.setMethod(DEFLATED);
      for (var entries = in.entries(); entries.hasMoreElements(); ) {
        var entry = entries.nextElement();
        var matcher = entryName.matcher(entry.getName());
        if (matcher.matches()) {
          out.putNextEntry(new ZipEntry(matcher.group(1) + ".class"));
          try (var sig = in.getInputStream(entry)) {
            sig.transferTo(out);
          }
          out.closeEntry();
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return jar;
  }
}
