package com.devmanchego.javaguard.analysis.api;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The public and protected API of one Java source file and the comparison of two versions of it (specification
 * &sect;11.5). A member is part of the API when it is {@code public} or {@code protected} (everything non-private in
 * an interface) and so are all the types enclosing it. Each member has an <em>identity</em> (kind, enclosing type,
 * name, parameter types) and a <em>full signature</em> (identity plus modifiers, return or field type, supertypes,
 * thrown types): a baseline identity that is gone is a removal, one whose full signature differs is a change, a new
 * one is not reported (adding API breaks nobody).
 */
public final class ApiSignatures {

    /** One API element. */
    public record Signature(String identity, String full, String display, int line) {
    }

    /** What happened to an element between two versions. */
    public record Difference(Kind kind, Signature before, Optional<Signature> after) {
        public enum Kind { REMOVED, CHANGED }
    }

    /** The package of the file and its API, in declaration order. */
    public record Api(String packageName, Map<String, Signature> signatures) {
    }

    private ApiSignatures() {
    }

    /** The API of {@code source}; empty when it is blank or does not parse. */
    public static Api extract(String source) {
        if (source.isBlank()) {
            return new Api("", Map.of());
        }
        CompilationUnit unit;
        try {
            unit = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                    .setAttributeComments(false)).parse(source).getResult().orElse(null);
        } catch (RuntimeException e) {
            unit = null;
        }
        if (unit == null) {
            return new Api("", Map.of());
        }
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Map<String, Signature> signatures = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.getTypes()) {
            collect(type, "", false, signatures);
        }
        return new Api(pkg, signatures);
    }

    /** Removals and changes going from {@code before} to {@code after}. */
    public static List<Difference> compare(Api before, Api after) {
        List<Difference> differences = new ArrayList<>();
        before.signatures().forEach((identity, was) -> {
            Signature now = after.signatures().get(identity);
            if (now == null) {
                differences.add(new Difference(Difference.Kind.REMOVED, was, Optional.empty()));
            } else if (!now.full().equals(was.full())) {
                differences.add(new Difference(Difference.Kind.CHANGED, was, Optional.of(now)));
            }
        });
        return differences;
    }

    // --- extraction ---------------------------------------------------------------------------------

    private static void collect(TypeDeclaration<?> type, String outer, boolean outerIsInterface, Map<String, Signature> out) {
        boolean inInterface = outerIsInterface;
        if (!isExposed(type, inInterface, outer.isEmpty())) {
            return;
        }
        String path = outer.isEmpty() ? type.getNameAsString() : outer + "." + type.getNameAsString();
        boolean isInterface = type instanceof ClassOrInterfaceDeclaration c && c.isInterface()
                || type instanceof AnnotationDeclaration;
        add(out, "type:" + path, kindOf(type) + modifiers(type) + " " + path + supertypes(type), path, line(type));

        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                collect(nested, path, isInterface, out);
            } else if (member instanceof FieldDeclaration field && exposed(field, isInterface)) {
                for (var variable : field.getVariables()) {
                    add(out, "field:" + path + "#" + variable.getNameAsString(),
                            modifiers(field) + " " + variable.getTypeAsString() + " " + variable.getNameAsString(),
                            variable.getNameAsString() + " in " + path, line(field));
                }
            } else if (member instanceof MethodDeclaration method && exposed(method, isInterface)) {
                String params = parameterTypes(method);
                add(out, "method:" + path + "#" + method.getNameAsString() + "(" + params + ")",
                        modifiers(method) + (isInterface && method.isDefault() ? " default" : "") + " "
                                + method.getTypeAsString() + " " + method.getNameAsString() + "(" + fullParameters(method) + ")"
                                + thrown(method), method.getNameAsString() + "(" + params + ") in " + path, line(method));
            } else if (member instanceof ConstructorDeclaration constructor && exposed(constructor, false)) {
                String params = parameterTypes(constructor);
                add(out, "ctor:" + path + "#(" + params + ")", modifiers(constructor) + " (" + fullParameters(constructor) + ")"
                        + thrown(constructor), path + "(" + params + ")", line(constructor));
            }
        }
        if (type instanceof EnumDeclaration enumeration) {
            for (EnumConstantDeclaration constant : enumeration.getEntries()) {
                add(out, "enumconstant:" + path + "#" + constant.getNameAsString(), "enum constant",
                        constant.getNameAsString() + " in " + path, line(constant));
            }
        }
        if (type instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                add(out, "recordcomponent:" + path + "#" + component.getNameAsString(), component.getTypeAsString(),
                        component.getNameAsString() + " in " + path, line(component));
            }
        }
    }

    private static boolean isExposed(TypeDeclaration<?> type, boolean inInterface, boolean topLevel) {
        if (topLevel) {
            return type.isPublic();
        }
        return inInterface || type.isPublic() || type.isProtected();
    }

    private static boolean exposed(NodeWithModifiers<?> member, boolean inInterface) {
        if (member.hasModifier(Modifier.Keyword.PRIVATE)) {
            return false;
        }
        return inInterface || member.hasModifier(Modifier.Keyword.PUBLIC) || member.hasModifier(Modifier.Keyword.PROTECTED);
    }

    private static void add(Map<String, Signature> out, String identity, String full, String display, int line) {
        out.putIfAbsent(identity, new Signature(identity, full, display, line));
    }

    private static String kindOf(TypeDeclaration<?> type) {
        if (type instanceof ClassOrInterfaceDeclaration c) {
            return c.isInterface() ? "interface" : "class";
        }
        if (type instanceof EnumDeclaration) {
            return "enum";
        }
        if (type instanceof RecordDeclaration) {
            return "record";
        }
        return type instanceof AnnotationDeclaration ? "@interface" : "type";
    }

    private static String supertypes(TypeDeclaration<?> type) {
        StringBuilder sb = new StringBuilder();
        if (type instanceof ClassOrInterfaceDeclaration c) {
            if (!c.getExtendedTypes().isEmpty()) {
                sb.append(" extends ").append(c.getExtendedTypes().stream().map(Object::toString).collect(Collectors.joining(",")));
            }
            if (!c.getImplementedTypes().isEmpty()) {
                sb.append(" implements ").append(c.getImplementedTypes().stream().map(Object::toString).collect(Collectors.joining(",")));
            }
        }
        return sb.toString();
    }

    /** Visibility and the modifiers that change how callers may use the member. */
    private static String modifiers(NodeWithModifiers<?> node) {
        List<String> kept = new ArrayList<>();
        for (Modifier.Keyword keyword : List.of(Modifier.Keyword.PUBLIC, Modifier.Keyword.PROTECTED, Modifier.Keyword.ABSTRACT,
                Modifier.Keyword.STATIC, Modifier.Keyword.FINAL, Modifier.Keyword.SEALED)) {
            if (node.hasModifier(keyword)) {
                kept.add(keyword.asString());
            }
        }
        return String.join(" ", kept);
    }

    private static String parameterTypes(CallableDeclaration<?> callable) {
        return callable.getParameters().stream().map(p -> erase(p.getTypeAsString()) + (p.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(","));
    }

    private static String fullParameters(CallableDeclaration<?> callable) {
        return callable.getParameters().stream().map(p -> p.getTypeAsString() + (p.isVarArgs() ? "..." : ""))
                .collect(Collectors.joining(","));
    }

    private static String thrown(CallableDeclaration<?> callable) {
        if (callable.getThrownExceptions().isEmpty()) {
            return "";
        }
        return " throws " + callable.getThrownExceptions().stream().map(Object::toString).sorted().collect(Collectors.joining(","));
    }

    /** {@code Map<String, List<X>>} to {@code Map}: generics do not take part in a method's identity. */
    private static String erase(String type) {
        int lt = type.indexOf('<');
        return lt < 0 ? type : type.substring(0, lt) + type.substring(type.lastIndexOf('>') + 1);
    }

    private static int line(com.github.javaparser.ast.Node node) {
        return node.getBegin().map(p -> p.line).orElse(1);
    }
}
