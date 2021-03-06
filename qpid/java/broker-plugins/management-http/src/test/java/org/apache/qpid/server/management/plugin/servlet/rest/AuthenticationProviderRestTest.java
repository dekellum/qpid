package org.apache.qpid.server.management.plugin.servlet.rest;

import java.util.List;
import java.util.Map;

import org.apache.qpid.server.model.AuthenticationProvider;
import org.apache.qpid.server.model.LifetimePolicy;
import org.apache.qpid.server.model.State;
import org.apache.qpid.server.model.User;

public class AuthenticationProviderRestTest extends QpidRestTestCase
{

    public void testGet() throws Exception
    {
        List<Map<String, Object>> providerDetails = getJsonAsList("/rest/authenticationprovider");
        assertNotNull("Providers details cannot be null", providerDetails);
        assertEquals("Unexpected number of providers", 1, providerDetails.size());
        for (Map<String, Object> provider : providerDetails)
        {
            assertProvider("PrincipalDatabaseAuthenticationManager", provider);
            Map<String, Object> data = getJsonAsSingletonList("/rest/authenticationprovider/"
                    + provider.get(AuthenticationProvider.NAME));
            assertNotNull("Cannot load data for " + provider.get(AuthenticationProvider.NAME), data);
            assertProvider("PrincipalDatabaseAuthenticationManager", data);
        }
    }

    private void assertProvider(String type, Map<String, Object> provider)
    {
        Asserts.assertAttributesPresent(provider, AuthenticationProvider.AVAILABLE_ATTRIBUTES,
                AuthenticationProvider.CREATED, AuthenticationProvider.UPDATED, AuthenticationProvider.DESCRIPTION,
                AuthenticationProvider.TIME_TO_LIVE);
        assertEquals("Unexpected value of provider attribute " + AuthenticationProvider.STATE, State.ACTIVE.name(),
                provider.get(AuthenticationProvider.STATE));
        assertEquals("Unexpected value of provider attribute " + AuthenticationProvider.LIFETIME_POLICY,
                LifetimePolicy.PERMANENT.name(), provider.get(AuthenticationProvider.LIFETIME_POLICY));
        assertEquals("Unexpected value of provider attribute " + AuthenticationProvider.DURABLE, Boolean.TRUE,
                provider.get(AuthenticationProvider.DURABLE));
        assertEquals("Unexpected value of provider attribute " + AuthenticationProvider.TYPE, type,
                provider.get(AuthenticationProvider.TYPE));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> users = (List<Map<String, Object>>) provider.get("users");
        assertNotNull("Users are not found", users);
        assertTrue("Unexpected number of users", users.size() > 1);
        for (Map<String, Object> user : users)
        {
            assertNotNull("Attribute " + User.ID, user.get(User.ID));
            assertNotNull("Attribute " + User.NAME, user.get(User.NAME));
        }
    }
}
