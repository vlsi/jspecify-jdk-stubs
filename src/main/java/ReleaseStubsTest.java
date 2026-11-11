import static java.lang.classfile.TypeAnnotation.TargetType.CLASS_TYPE_PARAMETER_BOUND;
import static java.lang.classfile.TypeAnnotation.TargetType.METHOD_RETURN;
import static java.nio.file.Files.mismatch;

import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.TypeAnnotation.SupertypeTarget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/**
 * Checks the jars that {@code ReleaseStubs.java} writes against the {@code ct.sym} file of the
 * running JDK, which has to be the JDK that wrote them. Reports every failed check, not only the
 * first.
 */
public final class ReleaseStubsTest {
  private static final String NULLABLE = "Lorg/jspecify/annotations/Nullable;";
  private static final String NULL_MARKED = "Lorg/jspecify/annotations/NullMarked;";
  private static final String NULL_UNMARKED = "Lorg/jspecify/annotations/NullUnmarked;";

  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      throw new IllegalArgumentException(
          "Usage: java ReleaseStubsTest.java <stubs-dir> <second-run-stubs-dir> <yyyy-mm-dd>");
    }
    var stubsDir = Path.of(args[0]);
    var rerunDir = Path.of(args[1]);
    // The date that ReleaseStubs got, so a run past midnight expects the same releases.
    var today = LocalDate.parse(args[2]);

    selectReleases(
        "the day JDK 27 is the newest release", "2026-11-11", List.of(8, 11, 17, 21, 25, 26, 27));
    selectReleases(
        "the last day of the GA month of JDK 26", "2026-03-31", List.of(8, 11, 17, 21, 24, 25));
    selectReleases(
        "the first day after the GA month of JDK 26", "2026-04-01", List.of(8, 11, 17, 21, 25, 26));
    selectReleases("the last day JDK 26 is kept", "2027-03-31", List.of(8, 11, 17, 21, 25, 26, 27));
    selectReleases(
        "the first day JDK 26 is dropped", "2027-04-01", List.of(8, 11, 17, 21, 25, 27, 28));
    selectReleases(
        "an LTS release past its first year", "2029-11-11", List.of(8, 11, 17, 21, 25, 29, 32, 33));

    var releases = ReleaseStubs.selectReleases(today);
    var expectedJars = new TreeSet<String>();
    releases.forEach(r -> expectedJars.add(ReleaseStubs.jarName(r)));
    var actualJars = new TreeSet<String>();
    try (var files = Files.list(stubsDir)) {
      files.forEach(f -> actualJars.add(f.getFileName().toString()));
    }
    checkEquals(expectedJars, actualJars, "jars in " + stubsDir);

    for (var jarName : actualJars) {
      long offset = mismatch(stubsDir.resolve(jarName), rerunDir.resolve(jarName));
      check(offset == -1, "Two runs wrote different " + jarName + " at byte offset " + offset);
    }

    var ctSym = Path.of(System.getProperty("java.home"), "lib", "ct.sym");
    try (var ctSymZip = new ZipFile(ctSym.toFile())) {
      var api = ReleaseStubs.indexCtSym(ctSymZip);
      for (int release : releases) {
        var classes = ReleaseStubs.readClasses(stubsDir.resolve(ReleaseStubs.jarName(release)));
        for (var entry : classes.entrySet()) {
          ClassModel sig;
          try (var in = ctSymZip.getInputStream(api.get(release).get(entry.getKey()))) {
            sig = ClassFile.of().parse(in.readAllBytes());
          }
          checkSameApi(sig, entry.getValue(), release, entry.getKey());
        }
        checkAnnotations(release, classes);
      }
    }

    if (!failures.isEmpty()) {
      throw new AssertionError(failures.size() + " checks failed:\n" + String.join("\n", failures));
    }
    System.out.println("PASS");
  }

  private static void selectReleases(String day, String date, List<Integer> expected) {
    checkEquals(
        expected,
        ReleaseStubs.selectReleases(LocalDate.parse(date)),
        "selectReleases(" + date + "), " + day);
  }

  /**
   * Checks that {@code stub} declares exactly the API of {@code sig}, the class in {@code ct.sym},
   * in a class file of {@code release}.
   */
  private static void checkSameApi(ClassModel sig, ClassModel stub, int release, String name) {
    var where = "release " + release + ", " + name;
    checkEquals(44 + release, stub.majorVersion(), where + ", class file major version");
    checkEquals(describeClass(sig), describeClass(stub), where + ", class declaration");
    checkEquals(describeMembers(sig), describeMembers(stub), where + ", members");
    for (var method : stub.methods()) {
      check(
          method.findAttribute(Attributes.code()).isEmpty(),
          where + "#" + method.methodName() + " has a Code attribute");
    }
  }

  private static String describeClass(ClassModel model) {
    return model.flags().flagsMask()
        + " extends "
        + model.superclass().map(c -> c.asInternalName()).orElse("")
        + " implements "
        + model.interfaces().stream().map(c -> c.asInternalName()).toList()
        + " signature "
        + ReleaseStubs.signature(model);
  }

  private static TreeSet<String> describeMembers(ClassModel model) {
    var members = new TreeSet<String>();
    for (var method : model.methods()) {
      members.add(
          ReleaseStubs.methodKey(method)
              + " flags "
              + method.flags().flagsMask()
              + " signature "
              + ReleaseStubs.signature(method)
              + " throws "
              + ReleaseStubs.exceptions(method));
    }
    for (var field : model.fields()) {
      members.add(
          ReleaseStubs.fieldKey(field)
              + " flags "
              + field.flags().flagsMask()
              + " signature "
              + ReleaseStubs.signature(field));
    }
    return members;
  }

  private static void checkAnnotations(int release, SortedMap<String, ClassModel> classes) {
    var where = "release " + release + ", ";

    var getProperty =
        method(classes, "java/lang/System", "getProperty(Ljava/lang/String;)Ljava/lang/String;");
    if (present(getProperty, where + "System.getProperty(String)")) {
      check(
          hasTypeAnnotation(
              getProperty, NULLABLE, t -> t.targetInfo().targetType() == METHOD_RETURN),
          where + "System.getProperty(String) has no @Nullable on its return type");
    }

    // Deque gained SequencedCollection as a supertype in JDK 21, while its type parameter is the
    // same in every release.
    var deque = classes.get("java/util/Deque");
    if (present(deque, where + "Deque")) {
      check(hasDeclarationAnnotation(deque, NULL_MARKED), where + "Deque has no @NullMarked");
      check(
          hasTypeAnnotation(
              deque, NULLABLE, t -> t.targetInfo().targetType() == CLASS_TYPE_PARAMETER_BOUND),
          where + "Deque has no @Nullable on the bound of its type parameter");
    }

    // RecursiveAction extends ForkJoinTask<@Nullable Void>.
    var recursiveAction = classes.get("java/util/concurrent/RecursiveAction");
    if (present(recursiveAction, where + "RecursiveAction")) {
      check(
          hasTypeAnnotation(
              recursiveAction,
              NULLABLE,
              t -> t.targetInfo() instanceof SupertypeTarget s && s.supertypeIndex() == 65535),
          where + "RecursiveAction has no @Nullable on its superclass");
    }

    // Reader.of(CharSequence) was added in JDK 24.
    var readerOf =
        method(classes, "java/io/Reader", "of(Ljava/lang/CharSequence;)Ljava/io/Reader;");
    checkEquals(release >= 24, readerOf != null, where + "Reader declares of(CharSequence)");

    // Release 8 declares the parameter as Supplier<T>, and jspecify/jdk annotates the type
    // argument of Supplier<? extends T>.
    var generate =
        method(
            classes,
            "java/util/stream/Stream",
            "generate(Ljava/util/function/Supplier;)Ljava/util/stream/Stream;");
    if (present(generate, where + "Stream.generate(Supplier)")) {
      checkEquals(
          release == 8,
          hasDeclarationAnnotation(generate, NULL_UNMARKED),
          where + "Stream.generate(Supplier) has @NullUnmarked");
      checkEquals(
          release != 8,
          hasTypeAnnotation(generate, NULLABLE, t -> true),
          where + "Stream.generate(Supplier) has a @Nullable type annotation");
    }

    if (release == 8) {
      // Thread.stop(Throwable) was removed in JDK 11, so jspecify/jdk does not annotate it.
      var stop = method(classes, "java/lang/Thread", "stop(Ljava/lang/Throwable;)V");
      if (present(stop, where + "Thread.stop(Throwable)")) {
        check(
            hasDeclarationAnnotation(stop, NULL_UNMARKED),
            where + "Thread.stop(Throwable) has no @NullUnmarked");
      }
      var getName = method(classes, "java/lang/Thread", "getName()Ljava/lang/String;");
      if (present(getName, where + "Thread.getName()")) {
        check(
            !hasDeclarationAnnotation(getName, NULL_UNMARKED),
            where + "Thread.getName() has @NullUnmarked");
      }
    }
  }

  private static boolean present(Object member, String what) {
    check(member != null, what + " is missing");
    return member != null;
  }

  private static MethodModel method(
      SortedMap<String, ClassModel> classes, String className, String key) {
    var model = classes.get(className);
    if (model == null) {
      return null;
    }
    return model.methods().stream()
        .filter(m -> ReleaseStubs.methodKey(m).equals(key))
        .findFirst()
        .orElse(null);
  }

  private static boolean hasDeclarationAnnotation(AttributedElement element, String descriptor) {
    return element
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(a -> a.annotations().stream().anyMatch(an -> is(an, descriptor)))
        .orElse(false);
  }

  private static boolean hasTypeAnnotation(
      AttributedElement element, String descriptor, Predicate<TypeAnnotation> target) {
    return element
        .findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .map(
            a ->
                a.annotations().stream()
                    .anyMatch(ta -> is(ta.annotation(), descriptor) && target.test(ta)))
        .orElse(false);
  }

  private static boolean is(Annotation annotation, String descriptor) {
    return annotation.classSymbol().descriptorString().equals(descriptor);
  }

  private static void checkEquals(Object expected, Object actual, String what) {
    if (!Objects.equals(expected, actual)) {
      failures.add(what + ": expected " + expected + " but was " + actual);
    }
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      failures.add(message);
    }
  }

  private ReleaseStubsTest() {}
}
