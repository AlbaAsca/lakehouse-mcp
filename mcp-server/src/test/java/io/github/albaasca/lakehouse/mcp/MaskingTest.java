package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class MaskingTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                role.toLowerCase(), null, AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    @Test
    void onlyTheAdminSeesRealValuesAndNobodyAtAllIsMasked() {
        assertThat(Masking.isAdmin()).isFalse();
        signInAs("ANALYST");
        assertThat(Masking.isAdmin()).isFalse();
        signInAs("ADMIN");
        assertThat(Masking.isAdmin()).isTrue();
    }

    @Test
    void theProjectionReplacesOnlyUserIdWithTheSaltedHash() {
        String projection = new Masking("s'alt").projection();
        assertThat(projection).startsWith("* REPLACE (").endsWith(" AS user_id)").contains("sha256")
                .contains("'s''alt'");
    }

    @Test
    void aBlankSaltBecomesARandomOneNotAGuessableEmptyOne() {
        assertThat(new Masking("").projection()).isNotEqualTo(new Masking("").projection());
        assertThat(new Masking("${MASKING_SALT}").projection()).doesNotContain("MASKING_SALT");
    }

    @Test
    void viewNamesAreSafeIdentifiers() {
        assertThat(Masking.viewName("ecommerce", "events")).isEqualTo("ecommerce__events");
        assertThat(Masking.viewName("a.b", "t-1")).isEqualTo("a_b__t_1");
    }

    @Test
    void aTableWithoutUserIdIsToldApartFromOtherFailures() {
        assertThat(Masking.lacksColumn(new SQLException(
                "Binder Error: Column \"user_id\" in REPLACE list not found in FROM clause"))).isTrue();
        assertThat(Masking.lacksColumn(new SQLException("IO Error: boom"))).isFalse();
        assertThat(Masking.lacksColumn(new SQLException((String) null))).isFalse();
    }
}
