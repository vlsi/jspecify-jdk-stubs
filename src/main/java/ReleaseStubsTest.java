import static java.lang.classfile.TypeAnnotation.TargetType.CLASS_TYPE_PARAMETER_BOUND;
import static java.lang.classfile.TypeAnnotation.TargetType.METHOD_RETURN;
import static java.lang.classfile.TypeAnnotation.TypePathComponent.Kind.TYPE_ARGUMENT;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.nio.file.Files.mismatch;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.TypeAnnotation.FormalParameterTarget;
import java.lang.classfile.TypeAnnotation.SupertypeTarget;
import java.lang.classfile.TypeAnnotation.TargetInfo;
import java.lang.classfile.TypeAnnotation.TypePathComponent;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.SortedMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the jars that {@code ReleaseStubs.java} writes against the {@code ct.sym} file of the
 * running JDK, which has to be the JDK that wrote them, and against the license files of
 * jspecify/jdk. Reports every failed check, not only the first. With {@code --early-access}, it
 * checks the early-access jar that {@code ReleaseStubs.java --early-access} writes instead of the
 * jars of the releases in GA.
 */
public final class ReleaseStubsTest {
  private static final String NULLABLE = "Lorg/jspecify/annotations/Nullable;";
  private static final String NULL_MARKED = "Lorg/jspecify/annotations/NullMarked;";
  private static final String NULL_UNMARKED = "Lorg/jspecify/annotations/NullUnmarked;";

  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    boolean earlyAccess = args.length == 5 && args[4].equals("--early-access");
    if (args.length != 4 && !earlyAccess) {
      throw new IllegalArgumentException(
          "Usage: java ReleaseStubsTest.java <stubs-dir> <second-run-stubs-dir> <yyyy-mm-dd>"
              + " <jspecify-jdk-dir> [--early-access]");
    }
    var stubsDir = Path.of(args[0]);
    var rerunDir = Path.of(args[1]);
    // The date that ReleaseStubs got, so a run past midnight expects the same releases.
    var today = LocalDate.parse(args[2]);
    var legalDir = Path.of(args[3]);

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

    earlyAccessRelease(
        "JDK 28 while JDK 27 is the newest release", "2026-11-13", 28, OptionalInt.of(28));
    earlyAccessRelease("JDK 27 after its GA month", "2026-11-13", 27, OptionalInt.empty());
    earlyAccessRelease(
        "JDK 28 on the last day of its GA month", "2027-03-31", 28, OptionalInt.of(28));
    earlyAccessRelease(
        "JDK 28 on the first day after its GA month", "2027-04-01", 28, OptionalInt.empty());
    earlyAccessRelease(
        "JDK 29 on the first day after the GA month of JDK 28",
        "2027-04-01",
        29,
        OptionalInt.of(29));

    sameTypes("Ljava/util/List<*>;", "Ljava/util/List<+Ljava/lang/Object;>;", true);
    sameTypes("Ljava/util/List<*>;", "Ljava/util/List<+Ljava/lang/String;>;", false);
    sameTypes("Ljava/util/List<*>;", "Ljava/util/List<-Ljava/lang/Object;>;", false);
    sameTypes("Ljava/util/List<+Ljava/lang/Object;>;", "Ljava/util/List<*>;", true);
    stubDeclaresTheWildcardSpellingOfTheHeader();

    var releases =
        earlyAccess
            ? List.of(
                ReleaseStubs.earlyAccessRelease(today, Runtime.version().feature())
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "--early-access needs a JDK newer than every release with a"
                                    + " regular jar on "
                                    + today
                                    + ", but runs on JDK "
                                    + Runtime.version())))
            : ReleaseStubs.selectReleases(today);
    Function<Integer, String> jarNameOf =
        earlyAccess ? ReleaseStubs::earlyAccessJarName : ReleaseStubs::jarName;
    var expectedJars = new TreeSet<String>();
    releases.forEach(r -> expectedJars.add(jarNameOf.apply(r)));
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
        var jarPath = stubsDir.resolve(jarNameOf.apply(release));
        checkLegalFiles(jarPath, legalDir);
        var classes = ReleaseStubs.readClasses(jarPath);
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

  private static void earlyAccessRelease(
      String jdk, String date, int feature, OptionalInt expected) {
    checkEquals(
        expected,
        ReleaseStubs.earlyAccessRelease(LocalDate.parse(date), feature),
        "earlyAccessRelease(" + date + ", " + feature + "), " + jdk);
  }

  private static void sameTypes(String a, String b, boolean expected) {
    checkEquals(expected, ReleaseStubs.sameTypes(a, b), "sameTypes(" + a + ", " + b + ")");
  }

  /**
   * Each member spells its wildcard one way in the headers class and the other way in the {@code
   * ct.sym} class. For {@code m} and {@code v}, the headers class annotates the bound of {@code ?
   * extends Object}, a position that the {@code ?} of {@code ct.sym} lacks.
   */
  private static void stubDeclaresTheWildcardSpellingOfTheHeader() {
    var header =
        syntheticClass(
            "Ljava/util/List<*>;",
            "()Ljava/util/List<+Ljava/lang/Object;>;",
            "([Ljava/util/List<+Ljava/lang/Object;>;)V",
            true);
    var sig =
        syntheticClass(
            "Ljava/util/List<+Ljava/lang/Object;>;",
            "()Ljava/util/List<*>;",
            "([Ljava/util/List<*>;)V",
            false);
    var stub = ClassFile.of().parse(ReleaseStubs.stub(sig, header, 21, new ReleaseStubs.Stats()));

    var field = stub.fields().getFirst();
    checkEquals(List.of("Ljava/util/List<*>;"), signatures(field), "Signature attributes of f");
    check(
        hasTypeAnnotation(field, NULLABLE, t -> describePath(t).equals("[TYPE_ARGUMENT 0]")),
        "f has no @Nullable on its type argument");

    var method = method(stub, "m");
    checkEquals(
        List.of("()Ljava/util/List<+Ljava/lang/Object;>;"),
        signatures(method),
        "Signature attributes of m");
    check(
        hasTypeAnnotation(
            method, NULLABLE, t -> describePath(t).equals("[TYPE_ARGUMENT 0, WILDCARD 0]")),
        "m has no @Nullable on the bound of its type argument");

    var varargs = method(stub, "v");
    checkEquals(
        List.of("([Ljava/util/List<*>;)V"),
        signatures(varargs),
        "Signature attributes of varargs v; javac 8, 11, and 17 warn about unchecked generic array"
            + " creation for List<? extends Object>... and not for List<?>...");
    check(hasDeclarationAnnotation(varargs, NULL_UNMARKED), "v has no @NullUnmarked");
  }

  /**
   * Returns a class with a field {@code f} of type {@code List}, a method {@code m()} returning
   * {@code List}, and a varargs method {@code v(List...)}, with the given generic signatures. The
   * annotated class carries {@code @Nullable} on the {@code List} type argument of {@code f}, and
   * on the bound of that type argument for {@code m} and {@code v}.
   */
  private static ClassModel syntheticClass(
      String fieldSignature, String methodSignature, String varargsSignature, boolean annotated) {
    var list = ClassDesc.of("java.util.List");
    var nullable = Annotation.of(ClassDesc.ofDescriptor(NULLABLE));
    var bytes =
        ClassFile.of()
            .build(
                ClassDesc.of("Synthetic"),
                clb -> {
                  clb.withField(
                      "f",
                      list,
                      fb -> {
                        fb.with(SignatureAttribute.of(fb.constantPool().utf8Entry(fieldSignature)));
                        if (annotated) {
                          fb.with(
                              RuntimeVisibleTypeAnnotationsAttribute.of(
                                  TypeAnnotation.of(
                                      TargetInfo.ofField(),
                                      List.of(TypePathComponent.of(TYPE_ARGUMENT, 0)),
                                      nullable)));
                        }
                      });
                  clb.withMethod(
                      "m",
                      MethodTypeDesc.of(list),
                      ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT,
                      mb -> {
                        mb.with(
                            SignatureAttribute.of(mb.constantPool().utf8Entry(methodSignature)));
                        if (annotated) {
                          mb.with(
                              RuntimeVisibleTypeAnnotationsAttribute.of(
                                  TypeAnnotation.of(
                                      TargetInfo.ofMethodReturn(),
                                      List.of(
                                          TypePathComponent.of(TYPE_ARGUMENT, 0),
                                          TypePathComponent.WILDCARD),
                                      nullable)));
                        }
                      });
                  clb.withMethod(
                      "v",
                      MethodTypeDesc.of(CD_void, list.arrayType()),
                      ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT | ClassFile.ACC_VARARGS,
                      mb -> {
                        mb.with(
                            SignatureAttribute.of(mb.constantPool().utf8Entry(varargsSignature)));
                        if (annotated) {
                          mb.with(
                              RuntimeVisibleTypeAnnotationsAttribute.of(
                                  TypeAnnotation.of(
                                      TargetInfo.ofMethodFormalParameter(0),
                                      List.of(
                                          TypePathComponent.ARRAY,
                                          TypePathComponent.of(TYPE_ARGUMENT, 0),
                                          TypePathComponent.WILDCARD),
                                      nullable)));
                        }
                      });
                });
    return ClassFile.of().parse(bytes);
  }

  private static MethodModel method(ClassModel model, String name) {
    return model.methods().stream()
        .filter(m -> m.methodName().equalsString(name))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> signatures(AttributedElement element) {
    return element.attributes().stream()
        .filter(a -> a instanceof SignatureAttribute)
        .map(a -> ((SignatureAttribute) a).signature().stringValue())
        .toList();
  }

  /**
   * Checks that {@code jarPath} holds the license files of jspecify/jdk, byte for byte, and no
   * other entries besides classes.
   */
  private static void checkLegalFiles(Path jarPath, Path legalDir) throws IOException {
    var expected = new TreeSet<String>();
    ReleaseStubs.LEGAL_FILES.forEach(name -> expected.add("META-INF/" + name));
    var actual = new TreeSet<String>();
    try (var jar = new ZipFile(jarPath.toFile())) {
      jar.stream()
          .map(ZipEntry::getName)
          .filter(name -> !name.endsWith(".class"))
          .forEach(actual::add);
      checkEquals(expected, actual, "Entries other than classes in " + jarPath);
      for (var name : ReleaseStubs.LEGAL_FILES) {
        var entry = jar.getEntry("META-INF/" + name);
        if (entry == null) {
          continue;
        }
        try (var in = jar.getInputStream(entry)) {
          int offset =
              Arrays.mismatch(Files.readAllBytes(legalDir.resolve(name)), in.readAllBytes());
          check(
              offset == -1,
              "META-INF/" + name + " in " + jarPath + " differs from " + legalDir.resolve(name)
                  + " at byte offset " + offset);
        }
      }
    }
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
              + sameTypesForm(ReleaseStubs.signature(method))
              + " throws "
              + ReleaseStubs.exceptions(method));
    }
    for (var field : model.fields()) {
      members.add(
          ReleaseStubs.fieldKey(field)
              + " flags "
              + field.flags().flagsMask()
              + " signature "
              + sameTypesForm(ReleaseStubs.signature(field)));
    }
    return members;
  }

  /**
   * Returns {@code signature} with every {@code ? extends Object} written as {@code ?}, the one
   * difference from {@code ct.sym} that a stub may declare (see {@code
   * ReleaseStubs.headerSpelling}).
   */
  private static String sameTypesForm(String signature) {
    return signature.replace("+Ljava/lang/Object;", "*");
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

    // ct.sym declares the parameter as BiFunction<? super Object, ? super Object, ? extends
    // Object>, and jspecify/jdk as BiFunction<? super Object, ? super @Nullable Object, ?>.
    var compute =
        method(
            classes,
            "java/security/Provider",
            "compute(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;");
    if (present(compute, where + "Provider.compute(Object, BiFunction)")) {
      checkEquals(
          "(Ljava/lang/Object;"
              + "Ljava/util/function/BiFunction<-Ljava/lang/Object;-Ljava/lang/Object;*>;)"
              + "Ljava/lang/Object;",
          ReleaseStubs.signature(compute),
          where + "Provider.compute(Object, BiFunction) signature");
      check(
          !hasDeclarationAnnotation(compute, NULL_UNMARKED),
          where + "Provider.compute(Object, BiFunction) has @NullUnmarked");
      check(
          hasTypeAnnotation(compute, NULLABLE, t -> t.targetInfo().targetType() == METHOD_RETURN),
          where + "Provider.compute(Object, BiFunction) has no @Nullable on its return type");
      check(
          hasTypeAnnotation(
              compute,
              NULLABLE,
              t ->
                  t.targetInfo() instanceof FormalParameterTarget p
                      && p.formalParameterIndex() == 1
                      && describePath(t).equals("[TYPE_ARGUMENT 1, WILDCARD 0]")),
          where
              + "Provider.compute(Object, BiFunction) has no @Nullable on the bound of the"
              + " second type argument of its second parameter");
    }

    // ct.sym declares Enumeration<?>, and jspecify/jdk Enumeration<? extends Object>.
    var propertyNames =
        method(classes, "java/util/Properties", "propertyNames()Ljava/util/Enumeration;");
    if (present(propertyNames, where + "Properties.propertyNames()")) {
      checkEquals(
          "()Ljava/util/Enumeration<+Ljava/lang/Object;>;",
          ReleaseStubs.signature(propertyNames),
          where + "Properties.propertyNames() signature");
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

  private static String describePath(TypeAnnotation annotation) {
    return annotation.targetPath().stream()
        .map(c -> c.typePathKind() + " " + c.typeArgumentIndex())
        .toList()
        .toString();
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
