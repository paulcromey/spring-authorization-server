/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.stream.Collectors;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer;
import org.springframework.security.config.test.SpringTestRule;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponseType;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.OidcClientRegistration;
import org.springframework.security.oauth2.core.oidc.http.converter.OidcClientRegistrationHttpMessageConverter;
import org.springframework.security.oauth2.jose.TestJwks;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository.RegisteredClientParametersMapper;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.TestRegisteredClients;
import org.springframework.security.oauth2.server.authorization.config.ProviderSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for OpenID Connect Dynamic Client Registration 1.0.
 *
 * @author Ovidiu Popa
 * @author Joe Grandja
 */
public class OidcClientRegistrationTests {
	private static final String DEFAULT_TOKEN_ENDPOINT_URI = "/oauth2/token";
	private static final String DEFAULT_OIDC_CLIENT_REGISTRATION_ENDPOINT_URI = "/connect/register";
	private static final HttpMessageConverter<OAuth2AccessTokenResponse> accessTokenHttpResponseConverter =
			new OAuth2AccessTokenResponseHttpMessageConverter();
	private static final HttpMessageConverter<OidcClientRegistration> clientRegistrationHttpMessageConverter =
			new OidcClientRegistrationHttpMessageConverter();
	private static EmbeddedDatabase db;
	private static JWKSource<SecurityContext> jwkSource;

	@Rule
	public final SpringTestRule spring = new SpringTestRule();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcOperations jdbcOperations;

	@Autowired
	private RegisteredClientRepository registeredClientRepository;

	@BeforeClass
	public static void init() {
		JWKSet jwkSet = new JWKSet(TestJwks.DEFAULT_RSA_JWK);
		jwkSource = (jwkSelector, securityContext) -> jwkSelector.select(jwkSet);
		db = new EmbeddedDatabaseBuilder()
				.generateUniqueName(true)
				.setType(EmbeddedDatabaseType.HSQL)
				.setScriptEncoding("UTF-8")
				.addScript("org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql")
				.addScript("org/springframework/security/oauth2/server/authorization/client/oauth2-registered-client-schema.sql")
				.build();
	}

	@After
	public void tearDown() {
		jdbcOperations.update("truncate table oauth2_authorization");
		jdbcOperations.update("truncate table oauth2_registered_client");
	}

	@AfterClass
	public static void destroy() {
		db.shutdown();
	}

	@Test
	public void requestWhenClientRegistrationRequestAuthorizedThenClientRegistrationResponse() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		// ***** (1) Obtain the "initial" access token used for registering the client

		String clientRegistrationScope = "client.create";
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient2()
				.scope(clientRegistrationScope)
				.build();
		this.registeredClientRepository.save(registeredClient);

		MvcResult mvcResult = this.mvc.perform(post(DEFAULT_TOKEN_ENDPOINT_URI)
				.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
				.param(OAuth2ParameterNames.SCOPE, clientRegistrationScope)
				.header(HttpHeaders.AUTHORIZATION, "Basic " + encodeBasicAuth(
						registeredClient.getClientId(), registeredClient.getClientSecret())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value(clientRegistrationScope))
				.andReturn();

		OAuth2AccessToken accessToken = readAccessTokenResponse(mvcResult.getResponse()).getAccessToken();

		// ***** (2) Register the client

		// @formatter:off
		OidcClientRegistration clientRegistration = OidcClientRegistration.builder()
				.clientName("client-name")
				.redirectUri("https://client.example.com")
				.grantType(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())
				.grantType(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
				.scope("scope1")
				.scope("scope2")
				.build();
		// @formatter:on

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setBearerAuth(accessToken.getTokenValue());

		// Register the client
		mvcResult = this.mvc.perform(post(DEFAULT_OIDC_CLIENT_REGISTRATION_ENDPOINT_URI)
				.headers(httpHeaders)
				.contentType(MediaType.APPLICATION_JSON)
				.content(getClientRegistrationRequestContent(clientRegistration)))
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(header().string(HttpHeaders.PRAGMA, containsString("no-cache")))
				.andReturn();

		OidcClientRegistration clientRegistrationResponse = readClientRegistrationResponse(mvcResult.getResponse());
		assertThat(clientRegistrationResponse.getClientId()).isNotNull();
		assertThat(clientRegistrationResponse.getClientIdIssuedAt()).isNotNull();
		assertThat(clientRegistrationResponse.getClientSecret()).isNotNull();
		assertThat(clientRegistrationResponse.getClientSecretExpiresAt()).isNull();
		assertThat(clientRegistrationResponse.getClientName()).isEqualTo(clientRegistration.getClientName());
		assertThat(clientRegistrationResponse.getRedirectUris())
				.containsExactlyInAnyOrderElementsOf(clientRegistration.getRedirectUris());
		assertThat(clientRegistrationResponse.getGrantTypes())
				.containsExactlyInAnyOrderElementsOf(clientRegistration.getGrantTypes());
		assertThat(clientRegistrationResponse.getResponseTypes())
				.containsExactly(OAuth2AuthorizationResponseType.CODE.getValue());
		assertThat(clientRegistrationResponse.getScopes())
				.containsExactlyInAnyOrderElementsOf(clientRegistration.getScopes());
		assertThat(clientRegistrationResponse.getTokenEndpointAuthenticationMethod())
				.isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
		assertThat(clientRegistrationResponse.getIdTokenSignedResponseAlgorithm())
				.isEqualTo(SignatureAlgorithm.RS256.getName());
		assertThat(clientRegistrationResponse.getRegistrationClientUri())
				.isNotNull();
		assertThat(clientRegistrationResponse.getRegistrationAccessToken()).isNotEmpty();
	}

	@Test
	public void requestWhenClientConfigurationRequestAndRegisteredClientNotEqualToAuthorizationRegisteredClientThenUnauthorized() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		// ***** (1) Obtain the registration access token used for fetching the registered client configuration

		String clientConfigurationRequestScope = "client.read";
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.scope(clientConfigurationRequestScope)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.build();
		this.registeredClientRepository.save(registeredClient);

		RegisteredClient unauthorizedRegisteredClient = TestRegisteredClients.registeredClient()
				.id("registration-2")
				.clientId("client-2")
				.scope(clientConfigurationRequestScope)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.build();
		this.registeredClientRepository.save(unauthorizedRegisteredClient);

		MvcResult mvcResult = this.mvc.perform(post(DEFAULT_TOKEN_ENDPOINT_URI)
						.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
						.param(OAuth2ParameterNames.SCOPE, clientConfigurationRequestScope)
						.header(HttpHeaders.AUTHORIZATION, "Basic " + encodeBasicAuth(
								registeredClient.getClientId(), registeredClient.getClientSecret())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value(clientConfigurationRequestScope))
				.andReturn();

		OAuth2AccessToken accessToken = readAccessTokenResponse(mvcResult.getResponse()).getAccessToken();

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setBearerAuth(accessToken.getTokenValue());

		// ***** (2) Get RegisteredClient Configuration
		this.mvc.perform(get(DEFAULT_OIDC_CLIENT_REGISTRATION_ENDPOINT_URI)
						.headers(httpHeaders)
						.queryParam(OAuth2ParameterNames.CLIENT_ID, unauthorizedRegisteredClient.getClientId()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	public void requestWhenClientConfigurationRequestAuthorizedThenClientRegistrationResponse() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		// ***** (1) Obtain the registration access token used for fetching the registered client configuration

		String clientConfigurationRequestScope = "client.read";
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.scope(clientConfigurationRequestScope)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.build();
		this.registeredClientRepository.save(registeredClient);

		MvcResult mvcResult = this.mvc.perform(post(DEFAULT_TOKEN_ENDPOINT_URI)
						.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
						.param(OAuth2ParameterNames.SCOPE, clientConfigurationRequestScope)
						.header(HttpHeaders.AUTHORIZATION, "Basic " + encodeBasicAuth(
								registeredClient.getClientId(), registeredClient.getClientSecret())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value(clientConfigurationRequestScope))
				.andReturn();

		OAuth2AccessToken accessToken = readAccessTokenResponse(mvcResult.getResponse()).getAccessToken();

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setBearerAuth(accessToken.getTokenValue());

		// ***** (2) Get RegisteredClient Configuration
		mvcResult = this.mvc.perform(get(DEFAULT_OIDC_CLIENT_REGISTRATION_ENDPOINT_URI)
						.headers(httpHeaders)
						.queryParam(OAuth2ParameterNames.CLIENT_ID, registeredClient.getClientId()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(header().string(HttpHeaders.PRAGMA, containsString("no-cache")))
				.andReturn();

		OidcClientRegistration clientConfigurationResponse = readClientRegistrationResponse(mvcResult.getResponse());
		assertThat(clientConfigurationResponse.getClientId()).isNotNull().isEqualTo(registeredClient.getClientId());
		assertThat(clientConfigurationResponse.getClientIdIssuedAt()).isNotNull();
		assertThat(clientConfigurationResponse.getClientSecret()).isNotNull().isEqualTo(registeredClient.getClientSecret());
		assertThat(clientConfigurationResponse.getClientSecretExpiresAt()).isNull();
		assertThat(clientConfigurationResponse.getClientName()).isEqualTo(registeredClient.getClientName());
		assertThat(clientConfigurationResponse.getRedirectUris())
				.containsExactlyInAnyOrderElementsOf(registeredClient.getRedirectUris());
		assertThat(clientConfigurationResponse.getGrantTypes())
				.containsExactlyInAnyOrderElementsOf(registeredClient.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue).collect(Collectors.toList()));
		assertThat(clientConfigurationResponse.getResponseTypes())
				.containsExactly(OAuth2AuthorizationResponseType.CODE.getValue());
		assertThat(clientConfigurationResponse.getScopes())
				.containsExactlyInAnyOrderElementsOf(registeredClient.getScopes());
		assertThat(clientConfigurationResponse.getTokenEndpointAuthenticationMethod())
				.isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
		assertThat(clientConfigurationResponse.getIdTokenSignedResponseAlgorithm())
				.isEqualTo(SignatureAlgorithm.RS256.getName());
		assertThat(clientConfigurationResponse.getRegistrationClientUri())
				.isNotNull();
		assertThat(clientConfigurationResponse.getRegistrationAccessToken()).isNull();
	}

	@Test
	public void requestWhenClientConfigurationRequestTwiceSameAccessTokenAuthorizedThenClientRegistrationResponse() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		// ***** (1) Obtain the registration access token used for fetching the registered client configuration

		String clientConfigurationRequestScope = "client.read";
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient()
				.scope(clientConfigurationRequestScope)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.build();
		this.registeredClientRepository.save(registeredClient);

		MvcResult mvcResult = this.mvc.perform(post(DEFAULT_TOKEN_ENDPOINT_URI)
						.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
						.param(OAuth2ParameterNames.SCOPE, clientConfigurationRequestScope)
						.header(HttpHeaders.AUTHORIZATION, "Basic " + encodeBasicAuth(
								registeredClient.getClientId(), registeredClient.getClientSecret())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value(clientConfigurationRequestScope))
				.andReturn();

		OAuth2AccessToken accessToken = readAccessTokenResponse(mvcResult.getResponse()).getAccessToken();

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setBearerAuth(accessToken.getTokenValue());

		// ***** (2) Get RegisteredClient Configuration
		mvcResult = this.mvc.perform(get(DEFAULT_OIDC_CLIENT_REGISTRATION_ENDPOINT_URI)
						.headers(httpHeaders)
						.queryParam(OAuth2ParameterNames.CLIENT_ID, registeredClient.getClientId()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(header().string(HttpHeaders.PRAGMA, containsString("no-cache")))
				.andReturn();

		assertClientConfigurationResponse(registeredClient, mvcResult);

		// ***** (3) Get RegisteredClient Configuration with the same access token
		mvcResult = this.mvc.perform(get(DEFAULT_OIDC_CLIENT_REGISTRATION_ENDPOINT_URI)
						.headers(httpHeaders)
						.queryParam(OAuth2ParameterNames.CLIENT_ID, registeredClient.getClientId()))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(header().string(HttpHeaders.PRAGMA, containsString("no-cache")))
				.andReturn();
		assertClientConfigurationResponse(registeredClient, mvcResult);
	}

	@Test
	public void requestWhenClientRegistrationRequestAndClientConfigurationRequestAuthorizedThenClientRegistrationResponse() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		// ***** (1) Obtain the "initial" access token used for registering the client

		String clientRegistrationScope = "client.create";
		RegisteredClient registeredClient = TestRegisteredClients.registeredClient2()
				.scope(clientRegistrationScope)
				.build();
		this.registeredClientRepository.save(registeredClient);

		MvcResult mvcResult = this.mvc.perform(post(DEFAULT_TOKEN_ENDPOINT_URI)
						.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
						.param(OAuth2ParameterNames.SCOPE, clientRegistrationScope)
						.header(HttpHeaders.AUTHORIZATION, "Basic " + encodeBasicAuth(
								registeredClient.getClientId(), registeredClient.getClientSecret())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value(clientRegistrationScope))
				.andReturn();

		OAuth2AccessToken accessToken = readAccessTokenResponse(mvcResult.getResponse()).getAccessToken();

		// ***** (2) Register the client

		// @formatter:off
		OidcClientRegistration clientRegistration = OidcClientRegistration.builder()
				.clientName("client-name")
				.redirectUri("https://client.example.com")
				.grantType(AuthorizationGrantType.AUTHORIZATION_CODE.getValue())
				.grantType(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
				.scope("scope1")
				.scope("scope2")
				.build();
		// @formatter:on

		HttpHeaders httpHeaders = new HttpHeaders();
		httpHeaders.setBearerAuth(accessToken.getTokenValue());

		// Register the client
		mvcResult = this.mvc.perform(post(DEFAULT_OIDC_CLIENT_REGISTRATION_ENDPOINT_URI)
						.headers(httpHeaders)
						.contentType(MediaType.APPLICATION_JSON)
						.content(getClientRegistrationRequestContent(clientRegistration)))
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(header().string(HttpHeaders.PRAGMA, containsString("no-cache")))
				.andReturn();

		OidcClientRegistration clientRegistrationResponse = readClientRegistrationResponse(mvcResult.getResponse());


		httpHeaders = new HttpHeaders();
		httpHeaders.setBearerAuth(clientRegistrationResponse.getRegistrationAccessToken());

		// ***** (3) Get RegisteredClient Configuration
		mvcResult = this.mvc.perform(get(clientRegistrationResponse.getRegistrationClientUri().toString())
						.headers(httpHeaders))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
				.andExpect(header().string(HttpHeaders.PRAGMA, containsString("no-cache")))
				.andReturn();

		OidcClientRegistration clientConfigurationResponse = readClientRegistrationResponse(mvcResult.getResponse());
		assertThat(clientConfigurationResponse.getClientId()).isNotNull().isEqualTo(clientRegistrationResponse.getClientId());
		assertThat(clientConfigurationResponse.getClientIdIssuedAt()).isNotNull();
		assertThat(clientConfigurationResponse.getClientSecret()).isNotNull().isEqualTo(clientRegistrationResponse.getClientSecret());
		assertThat(clientConfigurationResponse.getClientSecretExpiresAt()).isNull();
		assertThat(clientConfigurationResponse.getClientName()).isEqualTo(clientRegistrationResponse.getClientName());
		assertThat(clientConfigurationResponse.getRedirectUris())
				.containsExactlyInAnyOrderElementsOf(clientRegistrationResponse.getRedirectUris());
		assertThat(clientConfigurationResponse.getGrantTypes())
				.containsExactlyInAnyOrderElementsOf(clientRegistrationResponse.getGrantTypes());
		assertThat(clientConfigurationResponse.getResponseTypes())
				.containsExactly(OAuth2AuthorizationResponseType.CODE.getValue());
		assertThat(clientConfigurationResponse.getScopes())
				.containsExactlyInAnyOrderElementsOf(clientRegistrationResponse.getScopes());
		assertThat(clientConfigurationResponse.getTokenEndpointAuthenticationMethod())
				.isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
		assertThat(clientConfigurationResponse.getIdTokenSignedResponseAlgorithm())
				.isEqualTo(SignatureAlgorithm.RS256.getName());
		assertThat(clientConfigurationResponse.getRegistrationClientUri())
				.isNotNull();
		assertThat(clientConfigurationResponse.getRegistrationAccessToken()).isNull();
	}

	private static void assertClientConfigurationResponse(RegisteredClient registeredClient, MvcResult mvcResult) throws Exception {
		OidcClientRegistration clientConfigurationResponse;
		clientConfigurationResponse = readClientRegistrationResponse(mvcResult.getResponse());
		assertThat(clientConfigurationResponse.getClientId()).isNotNull().isEqualTo(registeredClient.getClientId());
		assertThat(clientConfigurationResponse.getClientIdIssuedAt()).isNotNull();
		assertThat(clientConfigurationResponse.getClientSecret()).isNotNull().isEqualTo(registeredClient.getClientSecret());
		assertThat(clientConfigurationResponse.getClientSecretExpiresAt()).isNull();
		assertThat(clientConfigurationResponse.getClientName()).isEqualTo(registeredClient.getClientName());
		assertThat(clientConfigurationResponse.getRedirectUris())
				.containsExactlyInAnyOrderElementsOf(registeredClient.getRedirectUris());
		assertThat(clientConfigurationResponse.getGrantTypes())
				.containsExactlyInAnyOrderElementsOf(registeredClient.getAuthorizationGrantTypes().stream().map(AuthorizationGrantType::getValue).collect(Collectors.toList()));
		assertThat(clientConfigurationResponse.getResponseTypes())
				.containsExactly(OAuth2AuthorizationResponseType.CODE.getValue());
		assertThat(clientConfigurationResponse.getScopes())
				.containsExactlyInAnyOrderElementsOf(registeredClient.getScopes());
		assertThat(clientConfigurationResponse.getTokenEndpointAuthenticationMethod())
				.isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue());
		assertThat(clientConfigurationResponse.getIdTokenSignedResponseAlgorithm())
				.isEqualTo(SignatureAlgorithm.RS256.getName());
		assertThat(clientConfigurationResponse.getRegistrationClientUri())
				.isNotNull();
		assertThat(clientConfigurationResponse.getRegistrationAccessToken()).isNull();
	}


	private static String encodeBasicAuth(String clientId, String secret) throws Exception {
		clientId = URLEncoder.encode(clientId, StandardCharsets.UTF_8.name());
		secret = URLEncoder.encode(secret, StandardCharsets.UTF_8.name());
		String credentialsString = clientId + ":" + secret;
		byte[] encodedBytes = Base64.getEncoder().encode(credentialsString.getBytes(StandardCharsets.UTF_8));
		return new String(encodedBytes, StandardCharsets.UTF_8);
	}

	private static OAuth2AccessTokenResponse readAccessTokenResponse(MockHttpServletResponse response) throws Exception {
		MockClientHttpResponse httpResponse = new MockClientHttpResponse(
				response.getContentAsByteArray(), HttpStatus.valueOf(response.getStatus()));
		return accessTokenHttpResponseConverter.read(OAuth2AccessTokenResponse.class, httpResponse);
	}

	private static byte[] getClientRegistrationRequestContent(OidcClientRegistration clientRegistration) throws Exception {
		MockHttpOutputMessage httpRequest = new MockHttpOutputMessage();
		clientRegistrationHttpMessageConverter.write(clientRegistration, null, httpRequest);
		return httpRequest.getBodyAsBytes();
	}

	private static OidcClientRegistration readClientRegistrationResponse(MockHttpServletResponse response) throws Exception {
		MockClientHttpResponse httpResponse = new MockClientHttpResponse(
				response.getContentAsByteArray(), HttpStatus.valueOf(response.getStatus()));
		return clientRegistrationHttpMessageConverter.read(OidcClientRegistration.class, httpResponse);
	}

	@EnableWebSecurity
	static class AuthorizationServerConfiguration {

		// @formatter:off
		@Bean
		public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
			OAuth2AuthorizationServerConfigurer<HttpSecurity> authorizationServerConfigurer =
					new OAuth2AuthorizationServerConfigurer<>();
			authorizationServerConfigurer
					.oidc(oidc ->
							oidc.clientRegistrationEndpoint(Customizer.withDefaults()));
			RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

			http
					.requestMatcher(endpointsMatcher)
					.authorizeRequests(authorizeRequests ->
							authorizeRequests.anyRequest().authenticated()
					)
					.csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
					.oauth2ResourceServer(OAuth2ResourceServerConfigurer::jwt)
					.apply(authorizationServerConfigurer);
			return http.build();
		}
		// @formatter:on

		@Bean
		RegisteredClientRepository registeredClientRepository(JdbcOperations jdbcOperations, PasswordEncoder passwordEncoder) {
			RegisteredClient registeredClient = TestRegisteredClients.registeredClient().build();
			RegisteredClientParametersMapper registeredClientParametersMapper = new RegisteredClientParametersMapper();
			registeredClientParametersMapper.setPasswordEncoder(passwordEncoder);
			JdbcRegisteredClientRepository registeredClientRepository = new JdbcRegisteredClientRepository(jdbcOperations);
			registeredClientRepository.setRegisteredClientParametersMapper(registeredClientParametersMapper);
			registeredClientRepository.save(registeredClient);
			return registeredClientRepository;
		}

		@Bean
		JdbcOperations jdbcOperations() {
			return new JdbcTemplate(db);
		}

		@Bean
		JWKSource<SecurityContext> jwkSource() {
			return jwkSource;
		}

		@Bean
		JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
			return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
		}

		@Bean
		ProviderSettings providerSettings() {
			return ProviderSettings.builder().issuer("http://auth-server:9000")
					.oidcClientRegistrationEndpoint("/connect/register").build();
		}

		@Bean
		PasswordEncoder passwordEncoder() {
			return NoOpPasswordEncoder.getInstance();
		}

	}

}
