package org.keycloak.login.freemarker;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.MultivaluedMap;

import org.keycloak.login.LoginFormsProvider;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

/**
 * Various util methods, so the logic is not hardcoded in freemarker beans
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class LoginFormsUtil {

    // Display just those identityProviders on login screen, which are already linked to "known" established user
    public static List<IdentityProviderModel> filterIdentityProviders(List<IdentityProviderModel> providers, KeycloakSession session, RealmModel realm,
                                                                      Map<String, Object> attributes, MultivaluedMap<String, String> formData) {

        Boolean usernameEditDisabled = (Boolean) attributes.get(LoginFormsProvider.USERNAME_EDIT_DISABLED);
        if (usernameEditDisabled != null && usernameEditDisabled) {
            String username = formData.getFirst(UserModel.USERNAME);
            if (username == null) {
                throw new IllegalStateException("USERNAME_EDIT_DISABLED but username not known");
            }

            UserModel user = session.users().getUserByUsername(username, realm);
            if (user == null || !user.isEnabled()) {
                throw new IllegalStateException("User " + username + " not found or disabled");
            }

            Set<FederatedIdentityModel> fedLinks = session.users().getFederatedIdentities(user, realm);
            Set<String> federatedIdentities = new HashSet<>();
            for (FederatedIdentityModel fedLink : fedLinks) {
                federatedIdentities.add(fedLink.getIdentityProvider());
            }

            List<IdentityProviderModel> result = new LinkedList<>();
            for (IdentityProviderModel idp : providers) {
                if (federatedIdentities.contains(idp.getAlias())) {
                    result.add(idp);
                }
            }
            return result;
        } else {
            return providers;
        }
    }
}
