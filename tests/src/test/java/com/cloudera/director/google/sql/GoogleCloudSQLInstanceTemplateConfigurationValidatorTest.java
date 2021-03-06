/*
 * Copyright (c) 2015 Google, Inc.
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

package com.cloudera.director.google.sql;

import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationProperty.MASTER_USERNAME;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationProperty.MASTER_USER_PASSWORD;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationProperty.PREFERRED_LOCATION;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationProperty.TIER;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.PREFERRED_LOCATION_NOT_FOUND_IN_REGION_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.PREFERRED_LOCATION_NOT_FOUND_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.PREFIX_MISSING_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.INVALID_NAME_LENGTH_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.INVALID_NAME_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.INVALID_PREFIX_LENGTH_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.INVALID_PREFIX_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLInstanceTemplateConfigurationValidator.INVALID_TIER_MSG;
import static com.cloudera.director.google.sql.GoogleCloudSQLProviderConfigurationProperty.REGION;
import static com.cloudera.director.spi.v1.model.InstanceTemplate.InstanceTemplateConfigurationPropertyToken.INSTANCE_NAME_PREFIX;
import static com.cloudera.director.spi.v1.model.util.SimpleResourceTemplate.SimpleResourceTemplateConfigurationPropertyToken.NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudera.director.google.TestUtils;
import com.cloudera.director.google.compute.util.ComputeUrls;
import com.cloudera.director.google.internal.GoogleCredentials;
import com.cloudera.director.google.shaded.com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.cloudera.director.google.shaded.com.google.api.client.googleapis.testing.json.GoogleJsonResponseExceptionFactoryTesting;
import com.cloudera.director.google.shaded.com.google.api.client.testing.json.MockJsonFactory;
import com.cloudera.director.google.shaded.com.google.api.services.compute.Compute;
import com.cloudera.director.google.shaded.com.google.api.services.compute.model.Zone;
import com.cloudera.director.google.shaded.com.google.api.services.sqladmin.SQLAdmin;
import com.cloudera.director.google.shaded.com.google.api.services.sqladmin.model.Tier;
import com.cloudera.director.google.shaded.com.google.api.services.sqladmin.model.TiersListResponse;
import com.cloudera.director.spi.v1.model.ConfigurationPropertyToken;
import com.cloudera.director.spi.v1.model.Configured;
import com.cloudera.director.spi.v1.model.LocalizationContext;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionCondition;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionConditionAccumulator;
import com.cloudera.director.spi.v1.model.util.DefaultLocalizationContext;
import com.cloudera.director.spi.v1.model.util.SimpleConfiguration;
import com.google.common.base.Optional;
import org.assertj.core.util.Maps;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * Tests {@link GoogleCloudSQLInstanceTemplateConfigurationValidator}.
 */
public class GoogleCloudSQLInstanceTemplateConfigurationValidatorTest {

  private static final String PROJECT_ID = "some-project";
  private static final String REGION_NAME_1 = "us-central";
  private static final String REGION_NAME_2 = "europe-west1";
  private static final String REGION_URL_1 = ComputeUrls.buildRegionalUrl(PROJECT_ID, "us-central1");
  private static final String REGION_URL_2 = ComputeUrls.buildRegionalUrl(PROJECT_ID, REGION_NAME_2);
  private static final String PREFERRED_LOCATION_NAME = "us-central1-f";
  private static final String TIER_NAME = "D4";
  private static final String TIER_NAME_WRONG = "D15";


  private GoogleCloudSQLProvider sqlProvider;
  private GoogleCredentials credentials;
  private SQLAdmin sqlAdmin;
  private Compute compute;
  private GoogleCloudSQLInstanceTemplateConfigurationValidator validator;
  private PluginExceptionConditionAccumulator accumulator;
  private LocalizationContext localizationContext = new DefaultLocalizationContext(Locale.getDefault(), "");

  @Before
  public void setUp() throws IOException {
    sqlProvider = mock(GoogleCloudSQLProvider.class);
    credentials = mock(GoogleCredentials.class);
    sqlAdmin = mock(SQLAdmin.class);
    compute = mock(Compute.class);
    validator = new GoogleCloudSQLInstanceTemplateConfigurationValidator(sqlProvider);
    accumulator = new PluginExceptionConditionAccumulator();

    when(sqlProvider.getCredentials()).thenReturn(credentials);
    when(sqlProvider.getGoogleConfig()).thenReturn(TestUtils.buildGoogleConfig());
    when(sqlProvider.getConfigurationValue(eq(REGION), any(LocalizationContext.class))).thenReturn(REGION_NAME_1);
    when(credentials.getCompute()).thenReturn(compute);
    when(credentials.getSQLAdmin()).thenReturn(sqlAdmin);
    when(credentials.getProjectId()).thenReturn(PROJECT_ID);
  }

  private Compute.Zones.Get mockComputeToZone() throws IOException {
    Compute.Zones computeZones = mock(Compute.Zones.class);
    Compute.Zones.Get computeZonesGet = mock(Compute.Zones.Get.class);

    when(compute.zones()).thenReturn(computeZones);
    when(computeZones.get(PROJECT_ID, PREFERRED_LOCATION_NAME)).thenReturn(computeZonesGet);

    return computeZonesGet;
  }

  private TiersListResponse mockSQLAdminToTiersListResponse() throws IOException {
    SQLAdmin.Tiers sqlAdminTiers = mock(SQLAdmin.Tiers.class);
    SQLAdmin.Tiers.List sqlAdminList = mock(SQLAdmin.Tiers.List.class);
    TiersListResponse tiersListResponse = new TiersListResponse();

    when(sqlAdmin.tiers()).thenReturn(sqlAdminTiers);
    when(sqlAdminTiers.list(PROJECT_ID)).thenReturn(sqlAdminList);
    when(sqlAdminList.execute()).thenReturn(tiersListResponse);

    return tiersListResponse;
  }

  @Test
  public void testCheckInstanceName() {
    checkInstanceName("instance-name-7-");
    checkInstanceName("vladimir");
    verifyClean();
  }

  @Test
  public void testCheckInstanceName_TooShort() throws IOException {
    checkInstanceName("");
    verifySingleError(NAME, INVALID_NAME_LENGTH_MSG);
  }

  @Test
  public void testCheckInstanceNameceName_TooLong() throws IOException {
    checkInstanceName("the-length-eqs-twenty-seven");
    verifySingleError(NAME, INVALID_NAME_LENGTH_MSG);
  }

  @Test
  public void testCheckInstanceName_StartsWithUppercaseLetter() throws IOException {
    checkInstanceName("Bad-prefix");
    verifySingleError(NAME, INVALID_NAME_MSG);
  }

  @Test
  public void testCheckInstanceName_StartsWithDash() throws IOException {
    checkInstanceName("-bad-prefix");
    verifySingleError(NAME, INVALID_NAME_MSG);
  }

  @Test
  public void testCheckInstanceName_StartsWithDigit() throws IOException {
    checkInstanceName("1-bad-prefix");
    verifySingleError(NAME, INVALID_NAME_MSG);
  }

  @Test
  public void testCheckInstanceName_ContainsUppercaseLetter() throws IOException {
    checkInstanceName("badPrefix");
    verifySingleError(NAME, INVALID_NAME_MSG);
  }

  @Test
  public void testCheckInstanceName_ContainsUnderscore() throws IOException {
    checkInstanceName("bad_prefix");
    verifySingleError(NAME, INVALID_NAME_MSG);
  }

  @Test
  public void testCheckPassword() throws IOException {
    checkPassword("admin");
    checkPassword("p");
    verifyClean();
  }

  @Test
  public void testCheckUsername() throws IOException {
    checkPassword("admin");
    checkPassword("u");
    verifyClean();
  }

  @Test
  public void testCheckPrefix() throws IOException {
    checkPrefix("director");
    checkPrefix("some-other-prefix");
    checkPrefix("length-is-eq-26-characters");
    checkPrefix("c");
    checkPrefix("c-d");
    checkPrefix("ends-with-digit-1");
    verifyClean();
  }

  @Test
  public void testCheckPreferredLocation() throws IOException {
    Zone zone = new Zone();
    zone.setName(PREFERRED_LOCATION_NAME);
    zone.setRegion(REGION_URL_1);

    Compute.Zones.Get computeZonesGet = mockComputeToZone();
    when(computeZonesGet.execute()).thenReturn(zone);

    checkPreferredLocation(PREFERRED_LOCATION_NAME);
    verify(computeZonesGet).execute();
    verifyClean();
  }

  @Test
  public void testCheckPreferredLocation_WrongRegion() throws IOException {
    Zone zone = new Zone();
    zone.setName(PREFERRED_LOCATION_NAME);
    zone.setRegion(REGION_URL_2);

    Compute.Zones.Get computeZonesGet = mockComputeToZone();
    when(computeZonesGet.execute()).thenReturn(zone);

    checkPreferredLocation(PREFERRED_LOCATION_NAME);
    verify(computeZonesGet).execute();
    verifySingleError(PREFERRED_LOCATION, PREFERRED_LOCATION_NOT_FOUND_IN_REGION_MSG, PREFERRED_LOCATION_NAME, REGION_NAME_1, PROJECT_ID);
  }

  @Test
  public void testCheckPreferredLocation_NotFound() throws IOException {
    Zone zone = new Zone();
    zone.setName(PREFERRED_LOCATION_NAME);
    zone.setRegion(REGION_URL_2);

    Compute.Zones.Get computeZonesGet = mockComputeToZone();

    GoogleJsonResponseException exception =
        GoogleJsonResponseExceptionFactoryTesting.newMock(new MockJsonFactory(), 404, "not found");
    when(computeZonesGet.execute()).thenThrow(exception);

    checkPreferredLocation(PREFERRED_LOCATION_NAME);
    verify(computeZonesGet).execute();
    verifySingleError(PREFERRED_LOCATION, PREFERRED_LOCATION_NOT_FOUND_MSG, PREFERRED_LOCATION_NAME, PROJECT_ID);
  }

  @Test
  public void testCheckPrefix_Missing() throws IOException {
    checkPrefix(null);
    verifySingleError(INSTANCE_NAME_PREFIX, PREFIX_MISSING_MSG);
  }

  @Test
  public void testCheckPrefix_TooShort() throws IOException {
    checkPrefix("");
    verifySingleError(INSTANCE_NAME_PREFIX, INVALID_PREFIX_LENGTH_MSG);
  }

  @Test
  public void testCheckPrefix_TooLong() throws IOException {
    checkPrefix("the-length-eqs-twenty-seven");
    verifySingleError(INSTANCE_NAME_PREFIX, INVALID_PREFIX_LENGTH_MSG);
  }

  @Test
  public void testCheckPrefix_StartsWithUppercaseLetter() throws IOException {
    checkPrefix("Bad-prefix");
    verifySingleError(INSTANCE_NAME_PREFIX, INVALID_PREFIX_MSG);
  }

  @Test
  public void testCheckPrefix_StartsWithDash() throws IOException {
    checkPrefix("-bad-prefix");
    verifySingleError(INSTANCE_NAME_PREFIX, INVALID_PREFIX_MSG);
  }

  @Test
  public void testCheckPrefix_StartsWithDigit() throws IOException {
    checkPrefix("1-bad-prefix");
    verifySingleError(INSTANCE_NAME_PREFIX, INVALID_PREFIX_MSG);
  }

  @Test
  public void testCheckPrefix_ContainsUppercaseLetter() throws IOException {
    checkPrefix("badPrefix");
    verifySingleError(INSTANCE_NAME_PREFIX, INVALID_PREFIX_MSG);
  }

  @Test
  public void testCheckPrefix_ContainsUnderscore() throws IOException {
    checkPrefix("bad_prefix");
    verifySingleError(INSTANCE_NAME_PREFIX, INVALID_PREFIX_MSG);
  }

  @Test
  public void testCheckTier() throws IOException {
    TiersListResponse tiersListResponse = mockSQLAdminToTiersListResponse();

    Tier tier = new Tier();
    tier.setTier(TIER_NAME);
    tiersListResponse.setItems(Arrays.asList(tier));

    checkTier(TIER_NAME);
    verifyClean();
  }

  @Test
  public void testCheckTier_WrongTier() throws IOException {
    TiersListResponse tiersListResponse = mockSQLAdminToTiersListResponse();
    tiersListResponse.setItems(null);

    checkTier(TIER_NAME_WRONG);
    verifySingleError(TIER, INVALID_TIER_MSG, TIER_NAME_WRONG);
  }

  /**
   * Invokes checkInstanceName with the specified configuration.
   *
   * @param instanceName the database instance name.
   */
  protected void checkInstanceName(String instanceName) {
    validator.checkInstanceName(instanceName, accumulator, localizationContext);
  }

  /**
   * Invokes checkPreferredLocation with the specified configuration.
   *
   * @param preferredLocation the preferred location name.
   */
  protected void checkPreferredLocation(String preferredLocation) {
    Map<String, String> configMap = Maps.newHashMap();
    configMap.put(PREFERRED_LOCATION.unwrap().getConfigKey(), preferredLocation);
    Configured configuration = new SimpleConfiguration(configMap);
    validator.checkPreferredLocation(configuration, accumulator, localizationContext);
  }

  /**
   * Invokes checkPassword with the specified configuration.
   *
   * @param password the password
   */
  protected void checkPassword(String password) {
    Map<String, String> configMap = Maps.newHashMap();
    configMap.put(MASTER_USER_PASSWORD.unwrap().getConfigKey(), password);
    Configured configuration = new SimpleConfiguration(configMap);
    validator.checkPassword(configuration, accumulator, localizationContext);
  }

  /**
   * Invokes checkPrefix with the specified configuration.
   *
   * @param prefix the instance name prefix
   */
  protected void checkPrefix(String prefix) {
    Map<String, String> configMap = Maps.newHashMap();
    configMap.put(INSTANCE_NAME_PREFIX.unwrap().getConfigKey(), prefix);
    Configured configuration = new SimpleConfiguration(configMap);
    validator.checkPrefix(configuration, accumulator, localizationContext);
  }

  /**
   * Invokes checkTier with the specified configuration.
   *
   * @param tier the instance tier type
   */
  protected void checkTier(String tier) {
    Map<String, String> configMap = Maps.newHashMap();
    configMap.put(TIER.unwrap().getConfigKey(), tier);
    Configured configuration = new SimpleConfiguration(configMap);
    validator.checkTier(configuration, accumulator, localizationContext);
  }

  /**
   * Invokes checkUsername with the specified configuration.
   *
   * @param username the instance username
   */
  protected void checkUsername(String username) {
    Map<String, String> configMap = Maps.newHashMap();
    configMap.put(MASTER_USERNAME.unwrap().getConfigKey(), username);
    Configured configuration = new SimpleConfiguration(configMap);
    validator.checkUsername(configuration, accumulator, localizationContext);
  }

  /**
   * Verifies that the specified plugin exception condition accumulator contains no errors or
   * warnings.
   */
  private void verifyClean() {
    Map<String, Collection<PluginExceptionCondition>> conditionsByKey = accumulator.getConditionsByKey();
    assertThat(conditionsByKey).isEmpty();
  }

  /**
   * Verifies that the specified plugin exception condition accumulator contains exactly
   * one condition, which must be an error associated with the specified property.
   *
   * @param token the configuration property token for the property which should be in error
   */
  private void verifySingleError(ConfigurationPropertyToken token) {
    verifySingleError(token, Optional.<String>absent());
  }

  /**
   * Verifies that the specified plugin exception condition accumulator contains exactly
   * one condition, which must be an error with the specified message and associated with the
   * specified property.
   *
   * @param token    the configuration property token for the property which should be in error
   * @param errorMsg the expected error message
   * @param args     the error message arguments
   */
  private void verifySingleError(ConfigurationPropertyToken token, String errorMsg, Object... args) {
    verifySingleError(token, Optional.of(errorMsg), args);
  }

  /**
   * Verifies that the specified plugin exception condition accumulator contains exactly
   * one condition, which must be an error with the specified message and associated with the
   * specified property.
   *
   * @param token          the configuration property token for the property which should be in error
   * @param errorMsgFormat the expected error message
   * @param args           the error message arguments
   */
  private void verifySingleError(ConfigurationPropertyToken token, Optional<String> errorMsgFormat, Object... args) {
    Map<String, Collection<PluginExceptionCondition>> conditionsByKey = accumulator.getConditionsByKey();
    assertThat(conditionsByKey).hasSize(1);
    String configKey = token.unwrap().getConfigKey();
    assertThat(conditionsByKey.containsKey(configKey)).isTrue();
    Collection<PluginExceptionCondition> keyConditions = conditionsByKey.get(configKey);
    assertThat(keyConditions).hasSize(1);
    PluginExceptionCondition condition = keyConditions.iterator().next();
    verifySingleErrorCondition(condition, errorMsgFormat, args);
  }

  /**
   * Verifies that the specified plugin exception condition is an error with the specified message.
   *
   * @param condition      the plugin exception condition
   * @param errorMsgFormat the expected error message format
   * @param args           the error message arguments
   */
  private void verifySingleErrorCondition(PluginExceptionCondition condition, Optional<String> errorMsgFormat,
      Object... args) {
    assertThat(condition.isError()).isTrue();
    if (errorMsgFormat.isPresent()) {
      assertThat(condition.getMessage()).isEqualTo(String.format(errorMsgFormat.get(), args));
    }
  }
}
