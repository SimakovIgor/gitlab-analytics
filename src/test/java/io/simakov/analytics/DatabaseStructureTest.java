package io.simakov.analytics;

import io.github.mfvanek.pg.core.checks.common.DatabaseCheckOnHost;
import io.github.mfvanek.pg.core.checks.common.Diagnostic;
import io.github.mfvanek.pg.model.dbobject.DbObject;
import io.github.mfvanek.pg.model.predicates.SkipFlywayTablesPredicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseStructureTest extends BaseIT {

    private static final Set<Diagnostic> IGNORED = EnumSet.of(
        Diagnostic.FUNCTIONS_WITHOUT_DESCRIPTION,
        Diagnostic.TABLES_NOT_LINKED_TO_OTHERS,
        Diagnostic.TABLES_WITHOUT_DESCRIPTION,
        Diagnostic.COLUMNS_WITHOUT_DESCRIPTION,
        Diagnostic.COLUMNS_WITH_FIXED_LENGTH_VARCHAR
    );

    @Autowired
    private List<DatabaseCheckOnHost<? extends DbObject>> checks;

    @Test
    void pgIndexHealthChecksShouldWork() {
        assertThat(checks)
            .hasSameSizeAs(Diagnostic.values());

        checks.stream()
            .filter(DatabaseCheckOnHost::isStatic)
            .filter(c -> !IGNORED.contains(c.getDiagnostic()))
            .filter(c -> c.getDiagnostic() != Diagnostic.PRIMARY_KEYS_WITH_SERIAL_TYPES)
            .forEach(check ->
                assertThat(check.check(SkipFlywayTablesPredicate.ofDefault()))
                    .as(check.getDiagnostic().name())
                    .isEmpty()
            );
    }
}
