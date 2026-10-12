package com.devmanchego.javaguard.analysis;

import com.github.javaparser.StaticJavaParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan Phase 22: the derived-query grammar as a table, written before JG-SPR-015 (TDD). Each row is a method name, the
 * property paths Spring Data's {@code PartTree} reads from it (conditions, then {@code OrderBy} properties, joined with
 * {@code |}), and the number of arguments the conditions consume ({@code -1}: not countable). The second table resolves
 * paths against a small domain the way {@code PropertyPath.from} does.
 */
class SpringDataDerivedQueryTest {

    private static final ModuleModel MODEL = ModuleModel.of(StaticJavaParser.parse("""
            import jakarta.persistence.*;
            @MappedSuperclass abstract class BaseEntity { @Id Long id; java.time.Instant createdAt; }
            @Embeddable class Address { String street; String zipCode; }
            @Entity class Customer extends BaseEntity {
                String firstName; String lastName; String email; boolean active; int age; String URL;
                @Embedded Address address;
                @OneToMany java.util.List<PurchaseOrder> orders;
                @ManyToOne Unmapped partner;
                @Transient String display;
                String addressLine;
            }
            @Entity(name = "Purchase") class PurchaseOrder extends BaseEntity { @ManyToOne Customer customer; String number; }
            @Entity class Product { @Id String sku; boolean loggedIn; }
            """));

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', nullValues = "NONE", value = {
            "findByFirstName;                                   FirstName;                  1",
            "findByFirstNameAndLastName;                        FirstName|LastName;         2",
            "findByFirstNameOrLastName;                         FirstName|LastName;         2",
            "readByEmail;                                       Email;                      1",
            "getByEmail;                                        Email;                      1",
            "queryByEmail;                                      Email;                      1",
            "searchByEmail;                                     Email;                      1",
            "streamByEmail;                                     Email;                      1",
            "countByActive;                                     Active;                     1",
            "existsByEmail;                                     Email;                      1",
            "deleteByEmail;                                     Email;                      1",
            "removeByEmail;                                     Email;                      1",
            "findDistinctByLastName;                            LastName;                   1",
            "findTop10ByOrderByLastNameAsc;                     LastName;                   0",
            "findFirst3ByActiveOrderByCreatedAtDesc;            Active|CreatedAt;           1",
            "findByLastNameOrderByFirstNameAscAgeDesc;          LastName|FirstName|Age;     1",
            "findByAgeBetween;                                  Age;                        2",
            "findByAgeIsBetween;                                Age;                        2",
            "findByAgeLessThan;                                 Age;                        1",
            "findByAgeLessThanEqual;                            Age;                        1",
            "findByAgeGreaterThanEqual;                         Age;                        1",
            "findByCreatedAtBefore;                             CreatedAt;                  1",
            "findByCreatedAtAfter;                              CreatedAt;                  1",
            "findByEmailIsNull;                                 Email;                      0",
            "findByEmailIsNotNull;                              Email;                      0",
            "findByEmailNotNull;                                Email;                      0",
            "findByActiveTrue;                                  Active;                     0",
            "findByActiveIsFalse;                               Active;                     0",
            "findByOrdersIsEmpty;                               Orders;                     0",
            "findByEmailLike;                                   Email;                      1",
            "findByEmailNotLike;                                Email;                      1",
            "findByEmailStartingWith;                           Email;                      1",
            "findByEmailEndsWith;                               Email;                      1",
            "findByEmailContaining;                             Email;                      1",
            "findByEmailNotContaining;                          Email;                      1",
            "findByIdIn;                                        Id;                         1",
            "findByIdNotIn;                                     Id;                         1",
            "findByLastNameNot;                                 LastName;                   1",
            "findByLastNameIs;                                  LastName;                   1",
            "findByLastNameEquals;                              LastName;                   1",
            "findByLastNameIgnoreCase;                          LastName;                   1",
            "findByLastNameIgnoringCase;                        LastName;                   1",
            "findByFirstNameIgnoreCaseContaining;               FirstName;                  1",
            "findByLastNameAndFirstNameAllIgnoreCase;           LastName|FirstName;         2",
            "findByLastNameAllIgnoreCaseOrderByFirstNameAsc;    LastName|FirstName;         1",
            "findByLocationNear;                                Location;                   -1",
            "findByLoggedIn;                                    Logged;                     1",
            "findByAddress_ZipCode;                             Address_ZipCode;            1",
            "findAllByOrderByLastName;                          LastName;                   0",
    })
    void readsThePropertyPathsAndArgumentsOfAMethodName(String method, String paths, int arguments) {
        SpringDataDerivedQuery.Parsed parsed = SpringDataDerivedQuery.parse(method).orElseThrow();

        List<String> read = new ArrayList<>();
        parsed.parts().forEach(p -> read.add(p.property()));
        read.addAll(parsed.orderBy());
        boolean countable = parsed.parts().stream().allMatch(p -> p.arguments() >= 0);
        assertThat(String.join("|", read)).isEqualTo(paths);
        assertThat(countable ? parsed.requiredArguments() : -1).isEqualTo(arguments);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"findById", "existsById", "deleteById", "findAllById", "getReferenceById", "findAll", "save", "findBy",
            "customFinder", "findOne", "processByName"})
    void baseMethodsAndOtherNamesAreNotDerivedQueries(String method) {
        Optional<SpringDataDerivedQuery.Parsed> parsed = SpringDataDerivedQuery.parse(method);

        assertThat(parsed).isEmpty();
    }

    @ParameterizedTest(name = "{1}.{0} -> {2}")
    @CsvSource({
            "FirstName,          Customer,      RESOLVED",
            "Firstname,          Customer,      MISSING",
            "EmailAddress,       Customer,      MISSING",
            "CreatedAt,          Customer,      RESOLVED",
            "Id,                 Customer,      RESOLVED",
            "AddressZipCode,     Customer,      RESOLVED",
            "Address_ZipCode,    Customer,      RESOLVED",
            "AddressZip,         Customer,      MISSING",
            "AddressLine,        Customer,      RESOLVED",
            "AddressStreet,      Customer,      RESOLVED",
            "OrdersNumber,       Customer,      RESOLVED",
            "OrdersCustomerAge,  Customer,      RESOLVED",
            "OrdersAmount,       Customer,      MISSING",
            "URL,                Customer,      RESOLVED",
            "Url,                Customer,      MISSING",
            "Display,            Customer,      MISSING",
            "PartnerName,        Customer,      UNKNOWN",
            "FirstNameLength,    Customer,      MISSING",
            "CustomerFirstName,  PurchaseOrder, RESOLVED",
            "Logged,             Product,       MISSING",
            "Anything,           Invoice,       UNKNOWN",
    })
    void resolvesPathsLikePropertyPath(String path, String type, SpringDataDerivedQuery.Resolution expected) {
        assertThat(SpringDataDerivedQuery.resolve(path, type, MODEL)).isEqualTo(expected);
    }
}
