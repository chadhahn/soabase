/**
 * Copyright 2014 Jordan Zimmerman
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
package io.soabase.client;

import com.google.common.base.Preconditions;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.client.HttpClientBuilder;
import io.dropwizard.client.HttpClientConfiguration;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.client.JerseyClientConfiguration;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.soabase.client.apache.WrappedHttpClient;
import io.soabase.client.jersey.JerseyRetryConnectorProvider;
import io.soabase.client.retry.DefaultRetryHandler;
import io.soabase.client.retry.RetryComponents;
import io.soabase.client.retry.RetryExecutor;
import io.soabase.client.retry.RetryHandler;
import io.soabase.core.CheckedConfigurationAccessor;
import io.soabase.core.ConfigurationAccessor;
import io.soabase.core.SoaConfiguration;
import io.soabase.core.SoaFeatures;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.protocol.HttpContext;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import javax.ws.rs.client.Client;
import java.io.IOException;

public class SoaClientBundle<T extends Configuration> implements ConfiguredBundle<T>
{
    public static final String HOST_SUBSTITUTION_TOKEN = "00000.";

    private final String clientName;
    private final ConfigurationAccessor<T, SoaConfiguration> soaAccessor;
    private final ConfigurationAccessor<T, SoaClientConfiguration> clientAccessor;

    public static String hostForService(String serviceName)
    {
        return HOST_SUBSTITUTION_TOKEN + serviceName;
    }

    private volatile RetryHandler retryHandler = new DefaultRetryHandler();

    public SoaClientBundle(ConfigurationAccessor<T, SoaConfiguration> soaAccessor, ConfigurationAccessor<T, SoaClientConfiguration> clientAccessor)
    {
        this(soaAccessor, clientAccessor, SoaFeatures.DEFAULT_NAME);
    }

    public SoaClientBundle(ConfigurationAccessor<T, SoaConfiguration> soaAccessor, ConfigurationAccessor<T, SoaClientConfiguration> clientAccessor, String clientName)
    {
        this.soaAccessor = new CheckedConfigurationAccessor<>(soaAccessor);
        this.clientAccessor = new CheckedConfigurationAccessor<>(clientAccessor);
        this.clientName = Preconditions.checkNotNull(clientName, "clientName cannot be null");
    }

    public RetryHandler getRetryHandler()
    {
        return retryHandler;
    }

    public void setRetryHandler(RetryHandler retryHandler)
    {
        this.retryHandler = retryHandler;
    }

    @Override
    public void initialize(Bootstrap<?> bootstrap)
    {
        // NOP
    }

    @Override
    public void run(T configuration, Environment environment) throws Exception
    {
        SoaConfiguration soaConfiguration = soaAccessor.accessConfiguration(configuration);
        SoaClientConfiguration clientConfiguration = clientAccessor.accessConfiguration(configuration);

        RetryExecutor retryExecutor = new RetryExecutor(environment.lifecycle().executorService("RetryHandler-%d"));
        RetryComponents retryComponents = new RetryComponents(retryHandler, soaConfiguration.getDiscovery(), clientConfiguration.getMaxRetries(), clientConfiguration.isRetry500s(), retryExecutor);

        final HttpClient httpClient = buildHttpClient(configuration, soaConfiguration, clientConfiguration, environment, retryComponents);
        final Client jerseyClient = buildJerseyClient(configuration, soaConfiguration, clientConfiguration, environment, retryComponents);

        AbstractBinder binder = new AbstractBinder()
        {
            @Override
            protected void configure()
            {
                if ( httpClient != null )
                {
                    bind(httpClient).named(clientName).to(HttpClient.class);
                }
                if ( jerseyClient != null )
                {
                    bind(jerseyClient).named(clientName).to(Client.class);
                }
            }
        };
        environment.jersey().register(binder);
    }

    // protected so users can override
    @SuppressWarnings("UnusedParameters")
    protected JerseyClientBuilder updateJerseyClientBuilder(T configuration, Environment environment, JerseyClientBuilder builder)
    {
        return builder;
    }

    // protected so users can override
    @SuppressWarnings("UnusedParameters")
    protected HttpClientBuilder updateHttpClientBuilder(T configuration, Environment environment, HttpClientBuilder httpClientBuilder)
    {
        return httpClientBuilder;
    }

    private Client buildJerseyClient(T configuration, SoaConfiguration soaConfiguration, SoaClientConfiguration clientConfiguration, Environment environment, RetryComponents retryComponents)
    {
        JerseyClientConfiguration jerseyClientConfiguration = clientConfiguration.getJerseyClientConfiguration();
        if ( jerseyClientConfiguration == null )
        {
            return null;
        }

        JerseyClientBuilder builder = new JerseyClientBuilder(environment)
            .using(jerseyClientConfiguration)
            .using(new JerseyRetryConnectorProvider(soaConfiguration.getDiscovery(), retryComponents));
        builder = updateJerseyClientBuilder(configuration, environment, builder);
        Client client = builder.build(clientName);
        soaConfiguration.putNamed(client, Client.class, clientName);

        return client;
    }

    private HttpClient buildHttpClient(T configuration, SoaConfiguration soaConfiguration, SoaClientConfiguration clientConfiguration, Environment environment, RetryComponents retryComponents)
    {
        if ( clientConfiguration.getHttpClientConfiguration() == null )
        {
            return null;
        }

        HttpRequestRetryHandler nullRetry = new HttpRequestRetryHandler()
        {
            @Override
            public boolean retryRequest(IOException exception, int executionCount, HttpContext context)
            {
                return false;
            }
        };

        HttpClientConfiguration httpClientConfiguration = clientConfiguration.getHttpClientConfiguration();
        HttpClientBuilder httpClientBuilder = new HttpClientBuilder(environment)
            .using(httpClientConfiguration)
            .using(nullRetry);  // Apache's retry mechanism does not allow changing hosts. Do retries manually
        httpClientBuilder = updateHttpClientBuilder(configuration, environment, httpClientBuilder);
        HttpClient httpClient = httpClientBuilder.build(clientName);

        HttpClient client = new WrappedHttpClient(httpClient, soaConfiguration.getDiscovery(), retryComponents);
        soaConfiguration.putNamed(client, HttpClient.class, clientName);
        return client;
    }
}
