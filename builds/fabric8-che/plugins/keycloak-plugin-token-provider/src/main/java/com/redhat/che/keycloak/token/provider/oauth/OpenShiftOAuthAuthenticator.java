package com.redhat.che.keycloak.token.provider.oauth;

import static com.google.common.base.Strings.isNullOrEmpty;

import java.io.IOException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.eclipse.che.api.auth.shared.dto.OAuthToken;
import org.eclipse.che.commons.annotation.Nullable;
import org.eclipse.che.security.oauth.OAuthAuthenticationException;
import org.eclipse.che.security.oauth.OAuthAuthenticator;
import org.eclipse.che.security.oauth.shared.User;

import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.util.store.MemoryDataStoreFactory;

@Singleton
public class OpenShiftOAuthAuthenticator extends OAuthAuthenticator {

    @Inject
    public OpenShiftOAuthAuthenticator(@Nullable @Named("che.oauth.github.clientid") String clientId, //
                                       @Nullable @Named("che.oauth.github.clientsecret") String clientSecret, //
                                       @Nullable @Named("che.oauth.github.redirecturis") String[] redirectUris, //
                                       @Nullable @Named("che.oauth.github.authuri") String authUri, //
                                       @Nullable @Named("che.oauth.github.tokenuri") String tokenUri, //
                                       @Named("che.oauth.github.forceactivation") boolean forceActivation) throws IOException {
        if (forceActivation &&
            !isNullOrEmpty(authUri) &&
            !isNullOrEmpty(tokenUri) &&
            redirectUris != null && 
            redirectUris.length != 0) {

            configure("NULL", "NULL", redirectUris, authUri, tokenUri, new MemoryDataStoreFactory());
        }
    }

    @Override
    public User getUser(OAuthToken accessToken) throws OAuthAuthenticationException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public final String getOAuthProvider() {
        return "openshift";
    }

    public void setToken(String userId, OAuthToken token) throws IOException {
        flow.createAndStoreCredential(
                new TokenResponse().setAccessToken(token.getToken()).setScope(token.getScope()),
                userId);
    }

}
