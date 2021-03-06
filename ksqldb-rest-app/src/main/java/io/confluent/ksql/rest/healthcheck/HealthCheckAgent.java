/*
 * Copyright 2019 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.rest.healthcheck;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.confluent.ksql.exception.KsqlTopicAuthorizationException;
import io.confluent.ksql.rest.client.RestResponse;
import io.confluent.ksql.rest.entity.HealthCheckResponse;
import io.confluent.ksql.rest.entity.HealthCheckResponseDetail;
import io.confluent.ksql.rest.entity.KsqlEntityList;
import io.confluent.ksql.rest.server.KsqlRestConfig;
import io.confluent.ksql.rest.server.ServerUtil;
import io.confluent.ksql.services.ServiceContext;
import io.confluent.ksql.services.SimpleKsqlClient;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.ReservedInternalTopics;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;

public class HealthCheckAgent {

  public static final String METASTORE_CHECK_NAME = "metastore";
  public static final String KAFKA_CHECK_NAME = "kafka";

  private static final List<Check> DEFAULT_CHECKS = ImmutableList.of(
      new ExecuteStatementCheck(METASTORE_CHECK_NAME, "list streams; list tables; list queries;"),
      new KafkaBrokerCheck(KAFKA_CHECK_NAME)
  );

  private final SimpleKsqlClient ksqlClient;
  private final URI serverEndpoint;
  private final ServiceContext serviceContext;
  private final KsqlConfig ksqlConfig;

  public HealthCheckAgent(
      final SimpleKsqlClient ksqlClient,
      final KsqlRestConfig restConfig,
      final ServiceContext serviceContext,
      final KsqlConfig ksqlConfig
  ) {
    this.ksqlClient = Objects.requireNonNull(ksqlClient, "ksqlClient");
    this.serverEndpoint = ServerUtil.getServerAddress(restConfig);
    this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    this.ksqlConfig = Objects.requireNonNull(ksqlConfig, "ksqlConfig");
  }

  public HealthCheckResponse checkHealth() {
    final Map<String, HealthCheckResponseDetail> results = DEFAULT_CHECKS.stream()
        .collect(Collectors.toMap(
            Check::getName,
            check -> check.check(this)
        ));
    final boolean allHealthy = results.values().stream()
        .allMatch(HealthCheckResponseDetail::getIsHealthy);
    return new HealthCheckResponse(allHealthy, results);
  }

  private interface Check {
    String getName();

    HealthCheckResponseDetail check(HealthCheckAgent healthCheckAgent);
  }

  private static class ExecuteStatementCheck implements Check {
    private final String name;
    private final String ksqlStatement;

    ExecuteStatementCheck(final String name, final String ksqlStatement) {
      this.name = Objects.requireNonNull(name, "name");
      this.ksqlStatement = Objects.requireNonNull(ksqlStatement, "ksqlStatement");
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public HealthCheckResponseDetail check(final HealthCheckAgent healthCheckAgent) {
      final RestResponse<KsqlEntityList> response =
          healthCheckAgent.ksqlClient
              .makeKsqlRequest(healthCheckAgent.serverEndpoint, ksqlStatement, ImmutableMap.of());
      return new HealthCheckResponseDetail(response.isSuccessful());
    }
  }

  private static class KafkaBrokerCheck implements Check {
    private static final int DESCRIBE_TOPICS_TIMEOUT_MS = 30000;

    private final String name;

    KafkaBrokerCheck(final String name) {
      this.name = Objects.requireNonNull(name, "name");
    }

    @Override
    public String getName() {
      return name;
    }

    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT")
    @Override
    public HealthCheckResponseDetail check(final HealthCheckAgent healthCheckAgent) {
      final String commandTopic = ReservedInternalTopics.commandTopic(healthCheckAgent.ksqlConfig);
      boolean isHealthy;

      try {
        healthCheckAgent.serviceContext
            .getAdminClient()
            .describeTopics(Collections.singletonList(commandTopic),
                new DescribeTopicsOptions().timeoutMs(DESCRIBE_TOPICS_TIMEOUT_MS))
            .all()
            .get();

        isHealthy = true;
      } catch (final KsqlTopicAuthorizationException e) {
        isHealthy = true;
      } catch (final Exception e) {
        isHealthy = false;
      }

      return new HealthCheckResponseDetail(isHealthy);
    }
  }
}