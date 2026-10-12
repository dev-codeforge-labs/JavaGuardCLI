package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.devmanchego.javaguard.project.Framework;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ReferenceType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JG-SPR-011 (catalog &sect;1.6): a transactional method that declares a checked exception its {@code @Transactional} does
 * not roll back for. Spring (and JTA) roll back only on unchecked exceptions by default, so when the checked exception
 * leaves the method the work done so far is committed. A checked exception can only leave a method through its
 * {@code throws} clause, so that clause is what is read. Whether a type is checked is decided from a list of JDK and common
 * library types and from the module's own class hierarchy; a type whose ancestry leaves both is unknown and not reported.
 * Read-only transactions are not reported: they have nothing to commit.
 */
final class Spr011CheckedExceptionWithoutRollback extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SPR-011", "Checked exception without rollback", Category.SPRING_JPA, Severity.MAJOR);

    /** Superclass of well-known exception types; RuntimeException and Error end an unchecked chain, Exception a checked one. */
    private static final Map<String, String> KNOWN_PARENTS = Map.ofEntries(
            Map.entry("IOException", "Exception"),
            Map.entry("FileNotFoundException", "IOException"),
            Map.entry("UnknownHostException", "IOException"),
            Map.entry("MalformedURLException", "IOException"),
            Map.entry("SocketException", "IOException"),
            Map.entry("SocketTimeoutException", "IOException"),
            Map.entry("FileSystemException", "IOException"),
            Map.entry("NoSuchFileException", "IOException"),
            Map.entry("JsonProcessingException", "IOException"),
            Map.entry("JsonMappingException", "IOException"),
            Map.entry("SQLException", "Exception"),
            Map.entry("InterruptedException", "Exception"),
            Map.entry("TimeoutException", "Exception"),
            Map.entry("ExecutionException", "Exception"),
            Map.entry("URISyntaxException", "Exception"),
            Map.entry("ParseException", "Exception"),
            Map.entry("ReflectiveOperationException", "Exception"),
            Map.entry("ClassNotFoundException", "Exception"),
            Map.entry("GeneralSecurityException", "Exception"),
            Map.entry("CloneNotSupportedException", "Exception"),
            Map.entry("MessagingException", "Exception"),
            Map.entry("JAXBException", "Exception"),
            Map.entry("IllegalArgumentException", "RuntimeException"),
            Map.entry("IllegalStateException", "RuntimeException"),
            Map.entry("NullPointerException", "RuntimeException"),
            Map.entry("UnsupportedOperationException", "RuntimeException"),
            Map.entry("ArithmeticException", "RuntimeException"),
            Map.entry("ClassCastException", "RuntimeException"),
            Map.entry("IndexOutOfBoundsException", "RuntimeException"),
            Map.entry("NoSuchElementException", "RuntimeException"),
            Map.entry("ConcurrentModificationException", "RuntimeException"),
            Map.entry("UncheckedIOException", "RuntimeException"),
            Map.entry("DateTimeException", "RuntimeException"),
            Map.entry("SecurityException", "RuntimeException"),
            Map.entry("DataAccessException", "RuntimeException"),
            Map.entry("PersistenceException", "RuntimeException"),
            Map.entry("EntityNotFoundException", "RuntimeException"),
            Map.entry("ResponseStatusException", "RuntimeException"),
            Map.entry("ConstraintViolationException", "RuntimeException"),
            Map.entry("ValidationException", "RuntimeException"));

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public Set<Framework> requiresAnyOf() {
        return Frameworks.SPRING;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        ModuleModel model = null;
        for (MethodDeclaration method : ctx.unit().findAll(MethodDeclaration.class)) {
            if (method.getThrownExceptions().isEmpty() || method.isPrivate() || method.isStatic()) {
                continue; // Proxies only see public (and, with class proxies, protected) instance methods.
            }
            Optional<AnnotationExpr> transactional = TransactionalSupport.effective(method);
            if (transactional.isEmpty() || TransactionalSupport.suspends(transactional.get())
                    || TransactionalSupport.readOnly(transactional.get())) {
                continue;
            }
            Set<String> rollback = TransactionalSupport.rollbackTypes(transactional.get());
            if (rollback.contains("?") || rollback.contains("Exception") || rollback.contains("Throwable")) {
                continue;
            }
            if (model == null) {
                model = ctx.model();
            }
            for (ReferenceType thrown : method.getThrownExceptions()) {
                String name = AstSupport.simpleName(thrown.asString());
                Optional<List<String>> ancestry = checkedAncestry(name, model);
                if (ancestry.isPresent() && ancestry.get().stream().noneMatch(rollback::contains)) {
                    out.add(finding(ctx, method, method.getNameAsString() + "() is transactional and throws the checked " + name
                            + ", which does not roll the transaction back: what the method wrote before it is committed.",
                            "Add rollbackFor = " + name + ".class to @Transactional (or rollbackOn for jakarta.transaction), or wrap it in an unchecked exception."));
                }
            }
        }
        return out;
    }

    /** The type and its supertypes when the type is a checked exception; empty when unchecked or unknown. */
    static Optional<List<String>> checkedAncestry(String name, ModuleModel model) {
        Set<String> chain = new LinkedHashSet<>();
        String current = name;
        for (int i = 0; i < 20 && current != null && chain.add(current); i++) {
            if (current.equals("RuntimeException") || current.equals("Error")) {
                return Optional.empty();
            }
            if (current.equals("Exception") || current.equals("Throwable")) {
                return Optional.of(List.copyOf(chain));
            }
            List<String> supers = model.superclasses(current); // The module's own class wins over a JDK name it shadows.
            current = supers.isEmpty() ? KNOWN_PARENTS.get(current) : supers.get(0);
        }
        return Optional.empty();
    }
}
