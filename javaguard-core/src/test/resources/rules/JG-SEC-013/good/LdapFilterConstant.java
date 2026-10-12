import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.NamingEnumeration;
import javax.naming.directory.SearchResult;

class LdapFilterConstant {
    private static final String FILTER = "(objectClass=person)";

    NamingEnumeration<SearchResult> find(DirContext ctx, SearchControls controls) throws Exception {
        return ctx.search("ou=people", FILTER, controls);
    }
}
