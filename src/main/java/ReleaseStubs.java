import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.newOutputStream;
import static java.nio.file.Files.readAllBytes;
import static java.util.zip.Deflater.BEST_COMPRESSION;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.lang.classfile.AccessFlags;
import java.lang.classfile.Annotation;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassFileVersion;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.FieldTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes one stubs jar per Java SE release: the API of that release, taken from the {@code
 * lib/ct.sym} file that backs {@code javac --release}, with the JSpecify annotations of the headers
 * jar that {@code HeaderCompile.java} produces.
 *
 * <p>A class goes into the jar of release N when the headers jar has it and N's API has it. Each
 * stub declares exactly the members of N's API, and its class file version is that of N. It takes
 * the JSpecify annotations of a member from the headers jar only where the member's name,
 * descriptor, and thrown exceptions are the same in both and the stub declares the generic
 * signature of the headers jar, because a type annotation addresses a position in that signature.
 * The stub declares the signature of {@code ct.sym}, or that of the headers jar where the two
 * differ only in writing a wildcard as {@code ?} or as {@code ? extends Object} and the member is
 * not a varargs method (see {@link #headerSpelling}). A method or constructor with no such match,
 * one the JDK removed or changed after N, gets {@code @NullUnmarked}, so a checker treats it as
 * unannotated rather than non-null inside a {@code @NullMarked} class. A field with no match
 * stays unannotated, because {@code @NullUnmarked} does not apply to fields.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * java ReleaseStubs.java \
 *   --headers path/to/jspecify-jdk-headers.jar \
 *   --legal-dir path/to/jspecify-jdk \
 *   --output-dir path/to/out \
 *   [--today yyyy-mm-dd]
 * }</pre>
 *
 * <p>The API comes from the {@code lib/ct.sym} of the running JDK. {@code --today} defaults to the
 * current UTC date, and the releases are the ones {@link #selectReleases} returns for that date.
 * {@code --legal-dir} is the root of the jspecify/jdk checkout, and each jar carries its {@link
 * #LEGAL_FILES} under {@code META-INF/}, because the stubs are derived from the OpenJDK sources.
 */
public final class ReleaseStubs {
  private static final String JSPECIFY_DESCRIPTOR_PREFIX = "Lorg/jspecify/annotations/";
  private static final String EXTENDS_OBJECT = "+Ljava/lang/Object;";
  private static final ClassDesc NULL_UNMARKED =
      ClassDesc.of("org.jspecify.annotations.NullUnmarked");

  /**
   * Same entry timestamp as {@code HeaderCompile.java}, so the jars are byte-for-byte reproducible.
   */
  private static final LocalDateTime FIXED_TIMESTAMP = LocalDateTime.of(2010, 1, 1, 0, 0, 0);

  /** License files at the root of jspecify/jdk, as the OpenJDK repository has them. */
  static final List<String> LEGAL_FILES =
      List.of("ADDITIONAL_LICENSE_INFO", "ASSEMBLY_EXCEPTION", "LICENSE");

  /** Oldest release that {@link #selectReleases} considers. */
  static final int OLDEST_RELEASE = 8;

  /** GA month of JDK 10, the first release of the six-month cadence. */
  private static final YearMonth JDK_10_GA = YearMonth.of(2018, 3);

  /**
   * Entry of {@code ct.sym}: {@code <releases>/<module>/<path>.sig}, where each character of {@code
   * <releases>} is a release number in base 36. A {@code @} marks a preview of the running JDK and
   * is not a release.
   */
  private static final Pattern CT_SYM_ENTRY = Pattern.compile("([0-9A-Z@]+)/[^/]+/(.+)\\.sig");

  public static void main(String[] args) throws IOException {
    Path headersJar = null;
    Path outputDir = null;
    Path legalDir = null;
    var ctSym = Path.of(System.getProperty("java.home"), "lib", "ct.sym");
    var today = LocalDate.now(ZoneOffset.UTC);

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--headers" -> headersJar = Path.of(requireArg(args, ++i, "--headers"));
        case "--legal-dir" -> legalDir = Path.of(requireArg(args, ++i, "--legal-dir"));
        case "--output-dir" -> outputDir = Path.of(requireArg(args, ++i, "--output-dir"));
        case "--today" -> today = LocalDate.parse(requireArg(args, ++i, "--today"));
        default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
      }
    }
    if (headersJar == null || legalDir == null || outputDir == null) {
      throw new IllegalArgumentException(
          "Usage: java ReleaseStubs.java --headers <headers.jar> --legal-dir <jspecify-jdk-dir>"
              + " --output-dir <dir> [--today <yyyy-mm-dd>]");
    }

    var headers = readClasses(headersJar);
    var legalFiles = new TreeMap<String, byte[]>();
    for (var name : LEGAL_FILES) {
      legalFiles.put(name, readAllBytes(legalDir.resolve(name)));
    }
    try (var ctSymZip = new ZipFile(ctSym.toFile())) {
      var api = indexCtSym(ctSymZip);
      var releases = selectReleases(today);
      var missing = releases.stream().filter(r -> !api.containsKey(r)).toList();
      if (!missing.isEmpty()) {
        throw new IllegalStateException(
            "Releases "
                + releases
                + " get a stubs jar on "
                + today
                + ", but "
                + ctSym
                + " has no data for "
                + missing
                + ". Set jdkBuildVersion in gradle.properties to a JDK whose lib/ct.sym has releases "
                + releases.getFirst()
                + " to "
                + releases.getLast()
                + ".");
      }
      createDirectories(outputDir);
      for (int release : releases) {
        writeReleaseJar(release, headers, api.get(release), ctSymZip, legalFiles, outputDir);
      }
    }
  }

  /**
   * Returns, in ascending order, the releases that get a stubs jar on {@code today}: every LTS
   * release, and every other release in the twelve months after its GA month.
   *
   * <p>LTS releases are 8, 11, 17, and every fourth release after 17. No release enters the result
   * before the month after its GA month, because GA falls in the middle of the month and the build
   * needs a JDK that has the release. GA months follow the six-month cadence that started with JDK
   * 10 in March 2018, so the result needs no list of release dates. JDK 26, released in March 2026,
   * is selected from April 1, 2026, to March 31, 2027.
   */
  static List<Integer> selectReleases(LocalDate today) {
    var selected = new ArrayList<Integer>();
    var thisMonth = YearMonth.from(today);
    for (int release = OLDEST_RELEASE;
        release < 10 || gaMonth(release).isBefore(thisMonth);
        release++) {
      if (isLts(release) || (release >= 10 && !thisMonth.isAfter(gaMonth(release).plusYears(1)))) {
        selected.add(release);
      }
    }
    return selected;
  }

  private static boolean isLts(int release) {
    return release == 8 || release == 11 || (release >= 17 && (release - 17) % 4 == 0);
  }

  private static YearMonth gaMonth(int release) {
    return JDK_10_GA.plusMonths(6L * (release - 10));
  }

  /** Maps each release in {@code ct.sym} to its classes, by internal name, and their entries. */
  static SortedMap<Integer, Map<String, ZipEntry>> indexCtSym(ZipFile ctSym) {
    var api = new TreeMap<Integer, Map<String, ZipEntry>>();
    var entries = ctSym.entries();
    while (entries.hasMoreElements()) {
      var entry = entries.nextElement();
      var matcher = CT_SYM_ENTRY.matcher(entry.getName());
      if (!matcher.matches() || matcher.group(2).endsWith("module-info")) {
        continue;
      }
      for (char letter : matcher.group(1).toCharArray()) {
        if (letter != '@') {
          api.computeIfAbsent(Character.digit(letter, 36), r -> new HashMap<>())
              .put(matcher.group(2), entry);
        }
      }
    }
    return api;
  }

  static SortedMap<String, ClassModel> readClasses(Path jarPath) throws IOException {
    var classes = new TreeMap<String, ClassModel>();
    try (var jar = new JarFile(jarPath.toFile())) {
      var entries = jar.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        var name = entry.getName();
        if (name.endsWith(".class")) {
          try (var in = jar.getInputStream(entry)) {
            classes.put(
                name.substring(0, name.length() - ".class".length()),
                ClassFile.of().parse(in.readAllBytes()));
          }
        }
      }
    }
    return classes;
  }

  private static void writeReleaseJar(
      int release,
      SortedMap<String, ClassModel> headers,
      Map<String, ZipEntry> api,
      ZipFile ctSym,
      SortedMap<String, byte[]> legalFiles,
      Path outputDir)
      throws IOException {
    var jarPath = outputDir.resolve(jarName(release));
    var stats = new Stats();
    try (var fileOut = newOutputStream(jarPath);
        var bufferedOut = new BufferedOutputStream(fileOut);
        var zipOut = new ZipOutputStream(bufferedOut)) {
      zipOut.setLevel(BEST_COMPRESSION);
      for (var legalFile : legalFiles.entrySet()) {
        var outEntry = new ZipEntry("META-INF/" + legalFile.getKey());
        outEntry.setTimeLocal(FIXED_TIMESTAMP);
        zipOut.putNextEntry(outEntry);
        zipOut.write(legalFile.getValue());
        zipOut.closeEntry();
      }
      for (var header : headers.entrySet()) {
        var sigEntry = api.get(header.getKey());
        if (sigEntry == null) {
          continue;
        }
        ClassModel sig;
        try (var in = ctSym.getInputStream(sigEntry)) {
          sig = ClassFile.of().parse(in.readAllBytes());
        }
        var outEntry = new ZipEntry(header.getKey() + ".class");
        outEntry.setTimeLocal(FIXED_TIMESTAMP);
        zipOut.putNextEntry(outEntry);
        zipOut.write(stub(sig, header.getValue(), release, stats));
        zipOut.closeEntry();
        stats.classes++;
      }
    }
    System.out.printf(
        "Release %d: wrote %d classes to %s; %d methods without a match marked @NullUnmarked,"
            + " %d fields without a match left unannotated, %d class-level type annotations"
            + " dropped because their type parameter or supertype differs%n",
        release,
        stats.classes,
        jarPath,
        stats.unmatchedMethods,
        stats.unmatchedFields,
        stats.droppedClassTypeAnnotations);
  }

  static String jarName(int release) {
    return "jspecify-jdk-stubs-jdk" + release + ".jar";
  }

  /**
   * Returns {@code sig}, the API of a class in {@code release}, with the JSpecify annotations of
   * {@code header}, the same class compiled from jspecify/jdk.
   */
  static byte[] stub(ClassModel sig, ClassModel header, int release, Stats stats) {
    var headerMethods = byKey(header.methods(), ReleaseStubs::methodKey);
    var headerFields = byKey(header.fields(), ReleaseStubs::fieldKey);
    var classAnnotations = declarationAnnotations(sig, jspecifyAnnotations(header));
    var classTypeAnnotations = typeAnnotations(sig, classTypeAnnotations(sig, header, stats));

    ClassTransform transform =
        (clb, element) -> {
          switch (element) {
            // The class file version of release N is 44 + N.
            case ClassFileVersion _ -> clb.withVersion(44 + release, 0);
            case RuntimeVisibleAnnotationsAttribute _,
                RuntimeVisibleTypeAnnotationsAttribute _ -> {}
            case MethodModel method -> {
              var match = headerMethods.get(methodKey(method));
              var spelling =
                  match == null ? Optional.<String>empty() : headerSpelling(match, method);
              boolean matched = match != null && typeAnnotationsFit(match, method);
              boolean markUnmarked = !matched && !isSynthetic(method.flags());
              if (markUnmarked) {
                stats.unmatchedMethods++;
              }
              var declaration =
                  declarationAnnotations(
                      method,
                      matched
                          ? jspecifyAnnotations(match)
                          : markUnmarked ? List.of(Annotation.of(NULL_UNMARKED)) : List.of());
              var type =
                  typeAnnotations(method, matched ? jspecifyTypeAnnotations(match) : List.of());
              clb.transformMethod(
                  method,
                  MethodTransform.dropping(ReleaseStubs::isVisibleAnnotations)
                      .andThen(
                          MethodTransform.endHandler(
                              mb -> {
                                // The Signature attribute of the headers jar replaces that of
                                // ct.sym: the builder keeps the last of the attributes that may
                                // appear only once.
                                spelling.ifPresent(
                                    s ->
                                        mb.with(
                                            SignatureAttribute.of(mb.constantPool().utf8Entry(s))));
                                declaration.ifPresent(mb::with);
                                type.ifPresent(mb::with);
                              })));
            }
            case FieldModel field -> {
              var match = headerFields.get(fieldKey(field));
              var spelling =
                  match == null ? Optional.<String>empty() : headerSpelling(match, field);
              boolean matched = match != null && typeAnnotationsFit(match, field);
              if (!matched) {
                stats.unmatchedFields++;
              }
              var declaration =
                  declarationAnnotations(field, matched ? jspecifyAnnotations(match) : List.of());
              var type =
                  typeAnnotations(field, matched ? jspecifyTypeAnnotations(match) : List.of());
              clb.transformField(
                  field,
                  FieldTransform.dropping(ReleaseStubs::isVisibleAnnotations)
                      .andThen(
                          FieldTransform.endHandler(
                              fb -> {
                                // The Signature attribute of the headers jar replaces that of
                                // ct.sym: the builder keeps the last of the attributes that may
                                // appear only once.
                                spelling.ifPresent(
                                    s ->
                                        fb.with(
                                            SignatureAttribute.of(fb.constantPool().utf8Entry(s))));
                                declaration.ifPresent(fb::with);
                                type.ifPresent(fb::with);
                              })));
            }
            default -> clb.with(element);
          }
        };
    return ClassFile.of()
        .transformClass(
            sig,
            transform.andThen(
                ClassTransform.endHandler(
                    clb -> {
                      classAnnotations.ifPresent(clb::with);
                      classTypeAnnotations.ifPresent(clb::with);
                    })));
  }

  /**
   * Returns the class-level JSpecify type annotations of {@code header} that address the same type
   * in {@code sig}. An annotation on a type parameter or its bound is kept when both classes
   * declare the same type parameters, and one on a supertype when {@code sig} has the same
   * supertype, with the same type arguments, at the same index.
   */
  private static List<TypeAnnotation> classTypeAnnotations(
      ClassModel sig, ClassModel header, Stats stats) {
    var sigShape = ClassShape.of(sig);
    var headerShape = ClassShape.of(header);
    var kept = new ArrayList<TypeAnnotation>();
    for (var annotation : jspecifyTypeAnnotations(header)) {
      boolean fits =
          switch (annotation.targetInfo()) {
            case TypeAnnotation.SupertypeTarget supertype ->
                sigShape
                    .supertype(supertype.supertypeIndex())
                    .equals(headerShape.supertype(supertype.supertypeIndex()));
            default -> sigShape.typeParameters().equals(headerShape.typeParameters());
          };
      if (fits) {
        kept.add(annotation);
      } else {
        stats.droppedClassTypeAnnotations++;
      }
    }
    return kept;
  }

  /**
   * Type parameters and supertypes of a class, as generic signature strings. The superclass comes
   * first, followed by the interfaces in declaration order.
   */
  record ClassShape(String typeParameters, List<String> supertypes) {
    /** {@code supertype_index} of a type annotation on the superclass. */
    private static final int SUPERCLASS_INDEX = 65535;

    static ClassShape of(ClassModel model) {
      var signature = model.findAttribute(Attributes.signature());
      if (signature.isEmpty()) {
        var supertypes = new ArrayList<String>();
        supertypes.add(model.superclass().map(c -> "L" + c.asInternalName() + ";").orElse(""));
        model.interfaces().forEach(c -> supertypes.add("L" + c.asInternalName() + ";"));
        return new ClassShape("", supertypes);
      }
      var parsed = signature.get().asClassSignature();
      var typeParameters = new StringBuilder();
      for (var parameter : parsed.typeParameters()) {
        typeParameters.append(parameter.identifier()).append(':');
        parameter.classBound().ifPresent(b -> typeParameters.append(b.signatureString()));
        parameter
            .interfaceBounds()
            .forEach(b -> typeParameters.append(':').append(b.signatureString()));
        typeParameters.append(';');
      }
      var supertypes = new ArrayList<String>();
      supertypes.add(parsed.superclassSignature().signatureString());
      parsed.superinterfaceSignatures().forEach(s -> supertypes.add(s.signatureString()));
      return new ClassShape(typeParameters.toString(), supertypes);
    }

    /** Returns the supertype at {@code supertypeIndex}, or an empty string past the last one. */
    String supertype(int supertypeIndex) {
      int position = supertypeIndex == SUPERCLASS_INDEX ? 0 : supertypeIndex + 1;
      return position < supertypes.size() ? supertypes.get(position) : "";
    }
  }

  /** Returns the visible annotations of {@code sig} followed by {@code added}, if there are any. */
  private static Optional<RuntimeVisibleAnnotationsAttribute> declarationAnnotations(
      AttributedElement sig, List<Annotation> added) {
    var annotations = new ArrayList<Annotation>();
    sig.findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(a -> annotations.addAll(a.annotations()));
    annotations.addAll(added);
    return annotations.isEmpty()
        ? Optional.empty()
        : Optional.of(RuntimeVisibleAnnotationsAttribute.of(annotations));
  }

  /**
   * Returns the visible type annotations of {@code sig} followed by {@code added}, if there are
   * any.
   */
  private static Optional<RuntimeVisibleTypeAnnotationsAttribute> typeAnnotations(
      AttributedElement sig, List<TypeAnnotation> added) {
    var annotations = new ArrayList<TypeAnnotation>();
    sig.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .ifPresent(a -> annotations.addAll(a.annotations()));
    annotations.addAll(added);
    return annotations.isEmpty()
        ? Optional.empty()
        : Optional.of(RuntimeVisibleTypeAnnotationsAttribute.of(annotations));
  }

  private static List<Annotation> jspecifyAnnotations(AttributedElement header) {
    return header
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(a -> a.annotations().stream().filter(ReleaseStubs::isJSpecify).toList())
        .orElse(List.of());
  }

  private static List<TypeAnnotation> jspecifyTypeAnnotations(AttributedElement header) {
    return header
        .findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .map(a -> a.annotations().stream().filter(t -> isJSpecify(t.annotation())).toList())
        .orElse(List.of());
  }

  private static boolean isJSpecify(Annotation annotation) {
    return annotation.classSymbol().descriptorString().startsWith(JSPECIFY_DESCRIPTOR_PREFIX);
  }

  private static boolean isVisibleAnnotations(Object element) {
    return element instanceof RuntimeVisibleAnnotationsAttribute
        || element instanceof RuntimeVisibleTypeAnnotationsAttribute;
  }

  private static boolean isSynthetic(AccessFlags flags) {
    return flags.has(AccessFlag.SYNTHETIC) || flags.has(AccessFlag.BRIDGE);
  }

  private static <T> Map<String, T> byKey(List<T> members, Function<T, String> key) {
    return members.stream().collect(Collectors.toMap(key, Function.identity()));
  }

  static String methodKey(MethodModel method) {
    return method.methodName().stringValue() + method.methodType().stringValue();
  }

  static String fieldKey(FieldModel field) {
    return field.fieldName().stringValue() + ":" + field.fieldType().stringValue();
  }

  /**
   * Returns whether every JSpecify type annotation of {@code header} addresses the same type in
   * the stub of {@code sig}: the stub declares the generic signature of {@code header} (see {@link
   * #headerSpelling}), and the thrown exceptions are equal where an annotation is on one of them.
   * Without JSpecify type annotations, nothing has to be equal.
   */
  private static boolean typeAnnotationsFit(AttributedElement header, AttributedElement sig) {
    var annotations = jspecifyTypeAnnotations(header);
    if (annotations.isEmpty()) {
      return true;
    }
    var declared = headerSpelling(header, sig).orElse(signature(sig));
    if (!signature(header).equals(declared)) {
      return false;
    }
    return annotations.stream()
            .noneMatch(a -> a.targetInfo() instanceof TypeAnnotation.ThrowsTarget)
        || exceptions(header).equals(exceptions(sig));
  }

  /**
   * Returns the generic signature of {@code header}, a member of the headers jar, where the stub of
   * {@code sig}, the member in {@code ct.sym}, declares it instead of the signature of {@code sig}.
   * That is where the two differ only in writing a wildcard as {@code ?} or as {@code ? extends
   * Object} (see {@link #sameTypes}). For javac both spellings are the same type, while in a
   * {@code @NullMarked} scope the bound of {@code ?} is that of its type parameter and the {@code
   * Object} of {@code ? extends Object} is non-null, so the stub keeps the spelling jspecify/jdk
   * chose. A varargs method keeps the signature of {@code sig}, because javac 8, 11, and 17 warn
   * about unchecked generic array creation for {@code List<? extends Object>...} and not for
   * {@code List<?>...}.
   */
  static Optional<String> headerSpelling(AttributedElement header, AttributedElement sig) {
    var headerSignature = signature(header);
    var sigSignature = signature(sig);
    boolean varargs = sig instanceof MethodModel m && m.flags().has(AccessFlag.VARARGS);
    if (varargs
        || headerSignature.equals(sigSignature)
        || !sameTypes(headerSignature, sigSignature)) {
      return Optional.empty();
    }
    return Optional.of(headerSignature);
  }

  /**
   * Returns whether the generic signatures {@code a} and {@code b} are the same after writing every
   * {@code ? extends Object} wildcard ({@code +Ljava/lang/Object;}) as {@code ?} ({@code *}).
   */
  static boolean sameTypes(String a, String b) {
    return a.replace(EXTENDS_OBJECT, "*").equals(b.replace(EXTENDS_OBJECT, "*"));
  }

  static List<String> exceptions(AttributedElement method) {
    return method
        .findAttribute(Attributes.exceptions())
        .map(a -> a.exceptions().stream().map(e -> e.asInternalName()).toList())
        .orElse(List.of());
  }

  static String signature(AttributedElement element) {
    return element
        .findAttribute(Attributes.signature())
        .map(s -> s.signature().stringValue())
        .orElse("");
  }

  /** Counts of what a release jar could not carry over, for the summary line. */
  static final class Stats {
    int classes;
    int unmatchedMethods;
    int unmatchedFields;
    int droppedClassTypeAnnotations;
  }

  private static String requireArg(String[] args, int index, String flag) {
    if (index >= args.length) {
      throw new IllegalArgumentException("Missing value for " + flag);
    }
    return args[index];
  }

  private ReleaseStubs() {}
}
