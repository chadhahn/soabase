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
package io.soabase.admin.details;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import com.google.common.net.HttpHeaders;
import io.dropwizard.jetty.setup.ServletEnvironment;
import io.soabase.admin.auth.AuthFields;
import io.soabase.admin.auth.AuthFilter;
import io.soabase.admin.auth.AuthSpec;
import io.soabase.admin.components.ComponentManager;
import io.soabase.admin.components.MetricComponent;
import io.soabase.admin.components.TabComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class IndexServlet extends HttpServlet
{
    public static final String SOA_TAB_PREFIX = "soa-tab-";

    private static final String FORCE = "/force";

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ComponentManager componentManager;
    private final AuthSpec authSpec;
    private final List<IndexMapping> mappings;
    private final AtomicReference<Map<String, Entry>> files = new AtomicReference<Map<String, Entry>>(Maps.<String, Entry>newHashMap());
    private final AtomicLong lastModified = new AtomicLong(0);
    private final AtomicInteger builtFromVersion = new AtomicInteger(-1);
    private final ObjectMapper mapper = new ObjectMapper();

    private static class Entry
    {
        final String content;
        final String eTag;

        public Entry(String content, String eTag)
        {
            this.content = content;
            this.eTag = eTag;
        }
    }

    public IndexServlet(ComponentManager componentManager, List<IndexMapping> mappings, AuthSpec authSpec)
    {
        this.componentManager = componentManager;
        this.authSpec = authSpec;
        this.mappings = ImmutableList.copyOf(mappings);
    }

    public void setServlets(ServletEnvironment servlets)
    {
        AuthFilter authFilter = (authSpec != null) ? new AuthFilter(authSpec) : null;
        for ( IndexMapping mapping : mappings )
        {
            String name = Splitter.on('.').split(mapping.getFile()).iterator().next();
            servlets.addServlet(name, this).addMapping(mapping.getPath());
            servlets.addServlet(name + "-force", this).addMapping(FORCE + mapping.getPath());
            if ( !mapping.isAuthServlet() && (authFilter != null) )
            {
                servlets.addFilter("auth-" + name, authFilter).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, mapping.getPath());
                servlets.addFilter("auth-" + name + "-force", authFilter).addMappingForUrlPatterns(EnumSet.allOf(DispatcherType.class), true, FORCE + mapping.getPath());
            }
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String requestURI = ((request.getRequestURI() != null) && (request.getRequestURI().length() > 0)) ? request.getRequestURI() : "/";
        if ( requestURI.startsWith(FORCE) )
        {
            rebuild();
            requestURI = (requestURI.length() > FORCE.length()) ? requestURI.substring(FORCE.length()) : "/";
        }
        else
        {
            int componentManagerVersion = componentManager.getVersion();
            int localBuiltFromVersion = builtFromVersion.get();
            if ( localBuiltFromVersion != componentManagerVersion )
            {
                if ( builtFromVersion.compareAndSet(localBuiltFromVersion, componentManagerVersion) )
                {
                    rebuild();
                }
            }
        }

        Entry entry = files.get().get(requestURI);
        if ( entry == null )
        {
            response.setStatus(404);
            return;
        }

        response.setStatus(200);
        response.setContentType("text/html");
        response.setContentLength(entry.content.length());
        response.setCharacterEncoding("UTF-8");
        response.setDateHeader(HttpHeaders.LAST_MODIFIED, lastModified.get());
        response.setHeader(HttpHeaders.ETAG, entry.eTag);
        response.getWriter().print(entry.content);
    }

    private synchronized void rebuild()
    {
        Map<String, Entry> newFiles = Maps.newHashMap();
        for ( IndexMapping mapping : mappings )
        {
            try
            {
                String content = Resources.toString(Resources.getResource(mapping.getFile()), Charsets.UTF_8);
                newFiles.put(mapping.getKey(), new Entry(content, "")); // temp entry
            }
            catch ( IOException e )
            {
                log.error("Could not load resource: " + mapping.getFile(), e);
                throw new RuntimeException(e);
            }
        }

        try
        {
            doReplacements(componentManager, newFiles);
        }
        catch ( IOException e )
        {
            throw new RuntimeException(e);
        }

        // zero out the millis since the date we get back from If-Modified-Since will not have them
        lastModified.set((System.currentTimeMillis() / 1000) * 1000);
        files.set(newFiles);
        builtFromVersion.set(componentManager.getVersion());
    }

    private void doReplacements(ComponentManager componentManager, Map<String, Entry> files) throws IOException
    {
        int currentYear = Calendar.getInstance().get(Calendar.YEAR);

        String firstId = "";
        StringBuilder tabsBuilder = new StringBuilder();
        StringBuilder idsBuilder = new StringBuilder();
        StringBuilder cssBuilder = new StringBuilder();
        StringBuilder jsBuilder = new StringBuilder();
        StringBuilder tabContentBuilder = new StringBuilder();
        StringBuilder metricsBuilder = new StringBuilder();
        StringBuilder authFieldsBuilder = new StringBuilder();

        for ( TabComponent tab : componentManager.getTabs() )
        {
            if ( firstId.length() == 0 )
            {
                firstId = tab.getId();
            }

            String id = SOA_TAB_PREFIX + tab.getId();
            tabsBuilder.append("<li id='").append(id).append("-li").append("'><a href=\"#").append(tab.getId()).append("\">").append(tab.getName()).append("</a></li>\n");
            idsBuilder.append("soaTabIds.push('").append(id).append("');\n");

            for ( String cssFile : tab.getCssUris() )
            {
                cssBuilder.append("<link rel=\"stylesheet\" href=\"").append(cssFile).append("\">\n");
            }
            for ( String jssFile : tab.getJavascriptUris() )
            {
                jsBuilder.append("<script src=\"").append(jssFile).append("\"></script>\n");
            }

            String tabContent = Resources.toString(Resources.getResource(tab.getContentResourcePath()), Charsets.UTF_8);
            tabContentBuilder.append("<div class=\"soa-hidden\" id=\"" + SOA_TAB_PREFIX).append(tab.getId()).append("\">").append(tabContent).append("</div>\n");
        }

        for ( MetricComponent metric : componentManager.getMetrics() )
        {
            String obj = mapper.writeValueAsString(metric);
            metricsBuilder.append("vmMetrics.push(").append(obj).append(");\n");
        }

        String signInHeading = "";
        String signInButton = "";
        if ( authSpec != null )
        {
            signInHeading = authSpec.getSignInHeading();
            signInButton = authSpec.getSignInButton();
            for ( AuthFields field : AuthFields.values() )
            {
                if ( authSpec.getFields().contains(field) )
                {
                    authFieldsBuilder.append("$('#").append(field.name().toLowerCase()).append("').removeClass('soa-hidden');\n");
                }
                else
                {
                    authFieldsBuilder.append("$('#").append(field.name().toLowerCase()).append("').remove();\n");
                }
            }
        }

        String tabs = tabsBuilder.toString();
        String metrics = metricsBuilder.toString();
        String tabContent = tabContentBuilder.toString();
        String ids = idsBuilder.toString();
        String css = cssBuilder.toString();
        String js = jsBuilder.toString();
        String authFields = authFieldsBuilder.toString();

        for ( IndexMapping mapping : mappings )
        {
            Entry entry = files.get(mapping.getKey());
            String content = entry.content;

            content = content.replace("$SOA_HAS_AUTH$", (authSpec != null) ? "true" : "false");
            content = content.replace("$SOA_TABS$", tabs);
            content = content.replace("$SOA_TABS_CONTENT$", tabContent);
            content = content.replace("$SOA_METRICS$", metrics);
            content = content.replace("$SOA_DEFAULT_TAB_ID$", firstId);
            content = content.replace("$SOA_TAB_IDS$", ids);
            content = content.replace("$SOA_CSS$", css);
            content = content.replace("$SOA_JS$", js);
            content = content.replace("$SOA_NAME$", componentManager.getAppName());
            content = content.replace("$SOA_COPYRIGHT$", "" + currentYear + " " + componentManager.getCompanyName());
            content = content.replace("$SOA_FOOTER_MESSAGE$", "" + componentManager.getFooterMessage());
            content = content.replace("$SOA_AUTH_FIELDS$", authFields);
            content = content.replace("$SOA_AUTH_HEADING$", signInHeading);
            content = content.replace("$SOA_AUTH_BUTTON$", signInButton);

            String eTag = '"' + Hashing.murmur3_128().hashBytes(content.getBytes()).toString() + '"';
            files.put(mapping.getKey(), new Entry(content, eTag));
        }
    }
}
