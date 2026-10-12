import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.NamingEnumeration;
import javax.naming.directory.SearchResult;

class LdapFilterConcatenation {
    NamingEnumeration<SearchResult> find(DirContext ctx, String username, SearchControls controls) throws Exception {
        String filter = "(uid=" + username + ")";
        return ctx.search("ou=people", filter, controls);
    }
}
