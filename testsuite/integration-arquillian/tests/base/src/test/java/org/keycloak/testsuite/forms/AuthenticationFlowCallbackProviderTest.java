/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.testsuite.forms;

import org.jboss.arquillian.graphene.page.Page;
import org.junit.Test;
import org.keycloak.authentication.authenticators.access.AllowAccessAuthenticatorFactory;
import org.keycloak.authentication.authenticators.browser.UsernamePasswordFormFactory;
import org.keycloak.authentication.authenticators.conditional.ConditionalLoaAuthenticator;
import org.keycloak.authentication.authenticators.conditional.ConditionalLoaAuthenticatorFactory;
import org.keycloak.common.Profile;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.AbstractTestRealmKeycloakTest;
import org.keycloak.testsuite.arquillian.annotation.AuthServerContainerExclude;
import org.keycloak.testsuite.authentication.CustomAuthenticationFlowCallback;
import org.keycloak.testsuite.authentication.CustomAuthenticationFlowCallbackFactory;
import org.keycloak.testsuite.pages.ErrorPage;
import org.keycloak.testsuite.pages.LoginPage;
import org.keycloak.testsuite.util.FlowUtil;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeThat;
import static org.keycloak.testsuite.arquillian.annotation.AuthServerContainerExclude.AuthServer.REMOTE;

/**
 * @author <a href="mailto:mabartos@redhat.com">Martin Bartos</a>
 */
@AuthServerContainerExclude(REMOTE)
public class AuthenticationFlowCallbackProviderTest extends AbstractTestRealmKeycloakTest {

    @Page
    protected LoginPage loginPage;

    @Page
    protected ErrorPage errorPage;

    @Override
    public void configureTestRealm(RealmRepresentation testRealm) {
    }

    @Test
    public void loaEssentialNonExisting() {
        setBrowserFlow();
        LevelOfAssuranceFlowTest.openLoginFormWithAcrClaim(oauth, true, "4");

        loginPage.assertCurrent();
        loginPage.login("test-user@localhost", "password");

        errorPage.assertCurrent();
        assertThat(errorPage.getError(), is("Authentication requirements not fulfilled"));
    }

    @Test
    public void errorWithCustomProvider() {
        // Ignore test case for Map Storage - GitHub Issue #10225
        assumeThat("This test case does not work properly with Map Storage", Profile.isFeatureEnabled(Profile.Feature.MAP_STORAGE), is(false));

        setBrowserFlow();
        LevelOfAssuranceFlowTest.openLoginFormWithAcrClaim(oauth, true, "1");

        loginPage.assertCurrent();
        loginPage.login("test-user@localhost", "password");

        errorPage.assertCurrent();
        assertThat(errorPage.getError(), is(CustomAuthenticationFlowCallback.EXPECTED_ERROR_MESSAGE));
    }

    protected void setBrowserFlow() {
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session).copyBrowserFlow("newFlow"));
        testingClient.server("test").run(session -> FlowUtil.inCurrentRealm(session)
                .selectFlow("newFlow")
                .inForms(forms -> forms
                        .clear()
                        .addSubFlowExecution(AuthenticationExecutionModel.Requirement.CONDITIONAL, subflow -> subflow
                                .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, UsernamePasswordFormFactory.PROVIDER_ID)
                                .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, ConditionalLoaAuthenticatorFactory.PROVIDER_ID,
                                        config -> {
                                            config.getConfig().put(ConditionalLoaAuthenticator.LEVEL, "1");
                                            config.getConfig().put(ConditionalLoaAuthenticator.STORE_IN_USER_SESSION, "true");
                                        })
                                .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, AllowAccessAuthenticatorFactory.PROVIDER_ID)
                        )
                        .addAuthenticatorExecution(AuthenticationExecutionModel.Requirement.REQUIRED, CustomAuthenticationFlowCallbackFactory.PROVIDER_ID)
                )
                .defineAsBrowserFlow() // Activate this new flow
        );
    }
}