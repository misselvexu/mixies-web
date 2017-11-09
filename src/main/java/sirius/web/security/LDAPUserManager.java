/*
 * Made with all the love in the world
 * by scireum in Remshalden, Germany
 *
 * Copyright by scireum GmbH
 * http://www.scireum.de - info@scireum.de
 */

package sirius.web.security;

import com.google.common.collect.Sets;
import sirius.kernel.di.std.Register;
import sirius.kernel.health.Exceptions;
import sirius.kernel.settings.Extension;
import sirius.web.http.WebContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.naming.AuthenticationException;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapName;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Set;

/**
 * Uses an LDAP directory to authenticate users.
 */
public class LDAPUserManager extends GenericUserManager {

    private String authPrefix;
    private String authSuffix;
    private String searchPrefix;
    private String searchSuffix;
    private String server;
    private boolean useSSL;
    private String objectClass;
    private String nameAttribute;
    private String[] returnedAtts;
    private String searchBase;
    private final List<String> requiredRoles;

    /**
     * Used to create <tt>ldap</tt> user managers.
     */
    @Register(name = "ldap")
    public static class Factory implements UserManagerFactory {

        @Nonnull
        @Override
        public UserManager createManager(@Nonnull ScopeInfo scope, @Nonnull Extension config) {
            return new LDAPUserManager(scope, config);
        }
    }

    @SuppressWarnings("unchecked")
    protected LDAPUserManager(ScopeInfo scope, Extension config) {
        super(scope, config);
        this.authPrefix = config.get("authPrefix").asString(config.get("prefix").asString());
        this.authSuffix = config.get("authSuffix").asString(config.get("suffix").asString());
        this.searchPrefix = config.get("searchPrefix").asString(config.get("prefix").asString());
        this.searchSuffix = config.get("searchSuffix").asString(config.get("suffix").asString());
        this.server = config.get("server").asString();
        this.useSSL = config.get("ssl").asBoolean(false);
        this.objectClass = config.get("objectClass").asString("user");
        this.nameAttribute = config.get("nameAttribute").asString("userPrincipalName");
        List<String> attrs =
                (List<String>) config.get("returnedAtts").get(List.class, Collections.singletonList("memberOf"));
        this.returnedAtts = attrs.toArray(new String[attrs.size()]);
        this.searchBase = config.get("searchBase").asString();
        this.requiredRoles = (List<String>) config.get("requiredRoles").get(List.class, Collections.emptyList());
        if (SESSION_STORAGE_TYPE_CLIENT.equals(sessionStorage)) {
            UserContext.LOG.WARN(
                    "LDAPUserManager (ldap) for scope %s does not support 'client' as session type! Switching to 'server'.",
                    scope.getScopeType());
            sessionStorage = SESSION_STORAGE_TYPE_SERVER;
        }
    }

    @Override
    public UserInfo findUserByName(WebContext ctx, String user) {
        return null;
    }

    @Override
    public UserInfo findUserByCredentials(@Nullable WebContext wc, String user, String password) {
        try {
            String logonUser = authPrefix + user + authSuffix;
            String searchUser = searchPrefix + user + searchSuffix;
            log("User: %s, logonUser: %s, searchUser: %s", user, logonUser, searchUser);

            DirContext ctx = createInitialContext(password, logonUser);
            try {
                NamingEnumeration<SearchResult> answer = searchInDirectory(searchUser, ctx);
                Set<String> roles = Sets.newTreeSet();
                if (!answer.hasMoreElements()) {
                    return null;
                }

                SearchResult sr = answer.next();
                log("Found user: %s", sr.getName());
                Set<String> permissions = computePermissions(roles, sr, wc);
                if (!permissions.containsAll(requiredRoles)) {
                    return null;
                }

                return UserInfo.Builder.createUser(user).withUsername(user).withPermissions(permissions).build();
            } finally {
                ctx.close();
            }
        } catch (AuthenticationException e) {
            log("Auth-Exception for %s: %s", user, e.getMessage());
            return null;
        } catch (Exception e) {
            throw Exceptions.handle(UserContext.LOG, e);
        }
    }

    private DirContext createInitialContext(String password, String logonUser) throws NamingException {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, server);
        if (useSSL) {
            log("using ssl...");
            env.put(Context.SECURITY_PROTOCOL, "ssl");
        }
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, logonUser);
        env.put(Context.SECURITY_CREDENTIALS, password);
        return new InitialDirContext(env);
    }

    private Set<String> computePermissions(Set<String> roles, SearchResult sr, @Nullable WebContext ctx) {
        Attributes attrs = sr.getAttributes();
        if (attrs != null) {
            extractRoles(roles, attrs);
        }

        Set<String> permissions = transformRoles(roles, ctx != null && ctx.isTrusted());
        permissions.add(UserInfo.PERMISSION_LOGGED_IN);
        return permissions;
    }

    @SuppressWarnings("unchecked")
    private void extractRoles(Set<String> roles, Attributes attrs) {
        try {
            NamingEnumeration<Attribute> ae = (NamingEnumeration<Attribute>) attrs.getAll();
            while (ae.hasMore()) {
                Attribute attr = ae.next();
                NamingEnumeration<Object> e = (NamingEnumeration<Object>) attr.getAll();
                while (e.hasMore()) {
                    String value = String.valueOf(e.next());
                    LdapName name = new LdapName(value);
                    String rdn = String.valueOf(name.getRdn(name.size() - 1).getValue());
                    log("Found group: %s (%s)", value, rdn);
                    roles.add(rdn);
                }
            }
        } catch (NamingException e) {
            Exceptions.handle(UserContext.LOG, e);
        }
    }

    private NamingEnumeration<SearchResult> searchInDirectory(String searchUser, DirContext ctx)
            throws NamingException {
        SearchControls searchCtls = new SearchControls();
        searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        String searchFilter = "(&(objectClass=" + objectClass + ")(" + nameAttribute + "=" + searchUser + "))";
        searchCtls.setReturningAttributes(returnedAtts);
        return ctx.search(searchBase, searchFilter, searchCtls);
    }

    @Override
    protected Object getUserObject(UserInfo u) {
        return null;
    }
}
