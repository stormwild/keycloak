/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.services.resources;

import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.NotFoundException;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.common.ClientConnection;
import org.keycloak.common.util.KeycloakUriBuilder;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.ClientModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.protocol.LoginProtocol;
import org.keycloak.protocol.LoginProtocolFactory;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.clientregistration.ClientRegistrationService;
import org.keycloak.services.managers.RealmManager;
import org.keycloak.services.util.CacheControlUtil;
import org.keycloak.services.util.ResolveRelative;
import org.keycloak.wellknown.WellKnownProvider;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.*;
import javax.ws.rs.core.Response.ResponseBuilder;
import java.net.URI;

/**
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
@Path("/realms")
public class RealmsResource {
    protected static ServicesLogger logger = ServicesLogger.ROOT_LOGGER;

    @Context
    protected KeycloakSession session;

    @Context
    protected ClientConnection clientConnection;

    @Context
    private HttpRequest request;

    @Context
    private UriInfo uriInfo;

    public static UriBuilder realmBaseUrl(UriInfo uriInfo) {
        UriBuilder baseUriBuilder = uriInfo.getBaseUriBuilder();
        return realmBaseUrl(baseUriBuilder);
    }

    public static UriBuilder realmBaseUrl(UriBuilder baseUriBuilder) {
        return baseUriBuilder.path(RealmsResource.class).path(RealmsResource.class, "getRealmResource");
    }

    public static UriBuilder accountUrl(UriBuilder base) {
        return base.path(RealmsResource.class).path(RealmsResource.class, "getAccountService");
    }

    public static UriBuilder protocolUrl(UriInfo uriInfo) {
        return uriInfo.getBaseUriBuilder().path(RealmsResource.class).path(RealmsResource.class, "getProtocol");
    }

    public static UriBuilder protocolUrl(UriBuilder builder) {
        return builder.path(RealmsResource.class).path(RealmsResource.class, "getProtocol");
    }

    public static UriBuilder clientRegistrationUrl(UriInfo uriInfo) {
        return uriInfo.getBaseUriBuilder().path(RealmsResource.class).path(RealmsResource.class, "getClientsService");
    }

    public static UriBuilder brokerUrl(UriInfo uriInfo) {
        return uriInfo.getBaseUriBuilder().path(RealmsResource.class).path(RealmsResource.class, "getBrokerService");
    }

    @Path("{realm}/protocol/{protocol}")
    public Object getProtocol(final @PathParam("realm") String name,
                                            final @PathParam("protocol") String protocol) {
        RealmModel realm = init(name);

        EventBuilder event = new EventBuilder(realm, session, clientConnection);

        LoginProtocolFactory factory = (LoginProtocolFactory)session.getKeycloakSessionFactory().getProviderFactory(LoginProtocol.class, protocol);
        Object endpoint = factory.createProtocolEndpoint(realm, event);

        ResteasyProviderFactory.getInstance().injectProperties(endpoint);
        return endpoint;
    }

    /**
     * Returns a temporary redirect to the client url configured for the given {@code clientId} in the given {@code realmName}.
     * <p>
     * This allows a client to refer to other clients just by their client id in URLs, will then redirect users to the actual client url.
     * The client url is derived according to the rules of the base url in the client configuration.
     * </p>
     *
     * @param realmName
     * @param clientId
     * @return
     * @since 2.0
     */
    @GET
    @Path("{realm}/clients/{client_id}/redirect")
    public Response getRedirect(final @PathParam("realm") String realmName, final @PathParam("client_id") String clientId) {

        RealmModel realm = init(realmName);

        if (realm == null) {
            return null;
        }

        ClientModel client = realm.getClientByClientId(clientId);

        if (client == null) {
            return null;
        }

        if (client.getRootUrl() == null && client.getBaseUrl() == null) {
            return null;
        }


        URI targetUri;
        if (client.getRootUrl() != null && (client.getBaseUrl() == null || client.getBaseUrl().isEmpty())) {
            targetUri = KeycloakUriBuilder.fromUri(client.getRootUrl()).build();
        } else {
            targetUri = KeycloakUriBuilder.fromUri(ResolveRelative.resolveRelativeUri(uriInfo.getRequestUri(), client.getRootUrl(), client.getBaseUrl())).build();
        }

        return Response.temporaryRedirect(targetUri).build();
    }

    @Path("{realm}/login-actions")
    public LoginActionsService getLoginActionsService(final @PathParam("realm") String name) {
        RealmModel realm = init(name);
        EventBuilder event = new EventBuilder(realm, session, clientConnection);
        LoginActionsService service = new LoginActionsService(realm, event);
        ResteasyProviderFactory.getInstance().injectProperties(service);
        return service;
    }

    @Path("{realm}/clients-registrations")
    public ClientRegistrationService getClientsService(final @PathParam("realm") String name) {
        RealmModel realm = init(name);
        EventBuilder event = new EventBuilder(realm, session, clientConnection);
        ClientRegistrationService service = new ClientRegistrationService(event);
        ResteasyProviderFactory.getInstance().injectProperties(service);
        return service;
    }

    @Path("{realm}/clients-managements")
    public ClientsManagementService getClientsManagementService(final @PathParam("realm") String name) {
        RealmModel realm = init(name);
        EventBuilder event = new EventBuilder(realm, session, clientConnection);
        ClientsManagementService service = new ClientsManagementService(realm, event);
        ResteasyProviderFactory.getInstance().injectProperties(service);
        return service;
    }

    private RealmModel init(String realmName) {
        RealmManager realmManager = new RealmManager(session);
        RealmModel realm = realmManager.getRealmByName(realmName);
        if (realm == null) {
            throw new NotFoundException("Realm does not exist");
        }
        session.getContext().setRealm(realm);
        return realm;
    }

    @Path("{realm}/account")
    public AccountService getAccountService(final @PathParam("realm") String name) {
        RealmModel realm = init(name);

        ClientModel client = realm.getClientByClientId(Constants.ACCOUNT_MANAGEMENT_CLIENT_ID);
        if (client == null || !client.isEnabled()) {
            logger.debug("account management not enabled");
            throw new NotFoundException("account management not enabled");
        }

        EventBuilder event = new EventBuilder(realm, session, clientConnection);
        AccountService accountService = new AccountService(realm, client, event);
        ResteasyProviderFactory.getInstance().injectProperties(accountService);
        accountService.init();
        return accountService;
    }

    @Path("{realm}")
    public PublicRealmResource getRealmResource(final @PathParam("realm") String name) {
        RealmModel realm = init(name);
        PublicRealmResource realmResource = new PublicRealmResource(realm);
        ResteasyProviderFactory.getInstance().injectProperties(realmResource);
        return realmResource;
    }

    @Path("{realm}/broker")
    public IdentityBrokerService getBrokerService(final @PathParam("realm") String name) {
        RealmModel realm = init(name);

        IdentityBrokerService brokerService = new IdentityBrokerService(realm);
        ResteasyProviderFactory.getInstance().injectProperties(brokerService);

        brokerService.init();

        return brokerService;
    }

    @GET
    @Path("{realm}/.well-known/{provider}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getWellKnown(final @PathParam("realm") String name,
                              final @PathParam("provider") String providerName) {
        init(name);

        WellKnownProvider wellKnown = session.getProvider(WellKnownProvider.class, providerName);

        ResponseBuilder responseBuilder = Response.ok(wellKnown.getConfig()).cacheControl(CacheControlUtil.getDefaultCacheControl());
        return Cors.add(request, responseBuilder).allowedOrigins("*").build();
    }

    /**
     * A JAX-RS sub-resource locator that uses the {@link org.keycloak.services.resource.RealmResourceSPI} to resolve sub-resources instances given an <code>unknownPath</code>.
     *
     * @param unknownPath a path that is unknown to the server
     * @return a JAX-RS sub-resource instance that maps to the given <code>unknownPath</code>. Otherwise null is returned.
     */
    @Path("{realm}/{unknow_path}")
    public Object resolveUnknowPath(@PathParam("realm") String realmName, @PathParam("unknow_path") String unknownPath) {
        List<ProviderFactory> factory = this.session.getKeycloakSessionFactory().getProviderFactories(RealmResourceProvider.class);

        if (factory != null) {
            RealmModel realm = init(realmName);

            for (ProviderFactory providerFactory : factory) {
                RealmResourceProviderFactory realmFactory = (RealmResourceProviderFactory) providerFactory;
                RealmResourceProvider resourceProvider = realmFactory.create(realm, this.session);
                Object resource = resourceProvider.getResource(unknownPath);

                if (resource != null) {
                    return resource;
                }
            }
        }

        return null;
    }
}
